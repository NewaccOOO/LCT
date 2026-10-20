package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

final class ExactSteiner {
    private static final float INF = 1e30f;

    static final class Skeleton {
        final List<Integer> junctions = new ArrayList<>();
        final List<Integer> parents = new ArrayList<>();
        final List<Integer> masks = new ArrayList<>();
        int[] leafParent;
        double cost;
    }

    private final float[][] weight;
    private final float[][] transposed;
    private final int k;
    private final int n;
    private final float[] rate;
    private final float[] chamber;
    private float[][] e;
    private float[][] m2;
    private float[] threeAll;
    private final int[] perm;
    private final int[] inv;
    private int[] eLo;
    private int[] eHi;
    private int[] mLo;
    private int[] mHi;
    final java.util.concurrent.atomic.AtomicLong mergeNanos = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong extendNanos = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong liveCount = new java.util.concurrent.atomic.AtomicLong();
    private final float[] rest;
    private static final boolean LOWER_BOUND = Boolean.parseBoolean(System.getProperty("heatnet.exact.lb", "true"));
    private final float[] remaining;
    private final double trial;
    private static final boolean PLACE_BOUND = Boolean.parseBoolean(System.getProperty("heatnet.exact.placeLb", "true"));
    private float[][] detour;
    private int[][] byDetour;
    private float[] rootDist;
    private float minRate;

    ExactSteiner(double[][] weight, int[] leaf, double[] leafCost, double[] flows, DoubleUnaryOperator rate,
            DoubleUnaryOperator chamber) {
        this(weight, leaf, leafCost, flows, rate, chamber, -1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, false);
    }

    ExactSteiner(double[][] weight, int[] leaf, double[] leafCost, double[] flows, DoubleUnaryOperator rate,
            DoubleUnaryOperator chamber, int root, double bound, double trial, boolean prune) {
        this.k = leaf.length;
        this.n = weight.length;
        perm = order(weight, root);
        inv = new int[n];
        for (int p = 0; p < n; p++) {
            inv[perm[p]] = p;
        }
        leaf = Arrays.stream(leaf).map(t -> inv[t]).toArray();
        root = root >= 0 ? inv[root] : root;
        this.weight = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double w = weight[perm[i]][perm[j]];
                this.weight[i][j] = Double.isFinite(w) ? (float) w : INF;
            }
        }
        transposed = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                transposed[j][i] = this.weight[i][j];
            }
        }
        int full = 1 << k;
        double leaves = Arrays.stream(leafCost).sum();
        rest = new float[full];
        for (int d = 1; d < full; d++) {
            double inside = 0;
            for (int t = 0; t < k; t++) {
                if ((d >> t & 1) == 1) {
                    inside += leafCost[t];
                }
            }
            rest[d] = (float) (leaves - inside);
        }
        remaining = new float[full];
        this.trial = prune ? trial : Double.POSITIVE_INFINITY;
        if (prune && LOWER_BOUND && root >= 0 && Double.isFinite(trial)) {
            lowerBounds(leaf, flows, rate, root);
        }
        double[] flow = new double[full];
        this.rate = new float[full];
        this.chamber = new float[full];
        for (int d = 1; d < full; d++) {
            int low = Integer.numberOfTrailingZeros(d);
            flow[d] = flow[d & (d - 1)] + flows[low];
            this.rate[d] = (float) rate.applyAsDouble(flow[d]);
            this.chamber[d] = (float) chamber.applyAsDouble(flow[d]);
        }
        e = new float[full][];
        m2 = new float[full][];
        eLo = new int[full];
        eHi = new int[full];
        mLo = new int[full];
        mHi = new int[full];
        for (int t = 0; t < k; t++) {
            float[] tv = new float[n];
            Arrays.fill(tv, INF);
            tv[leaf[t]] = (float) leafCost[t];
            e[1 << t] = extend(tv, 1 << t, this.rate[1 << t], root, bound - rest[1 << t], this.trial - rest[1 << t], remaining[1 << t]);
            span(e[1 << t], 1 << t, eLo, eHi);
        }
        int rootAt = root;
        List<List<Integer>> bySize = new ArrayList<>();
        for (int s = 0; s <= k; s++) {
            bySize.add(new ArrayList<>());
        }
        for (int d = 1; d < full; d++) {
            bySize.get(Integer.bitCount(d)).add(d);
        }
        for (int size = 2; size <= k; size++) {
            bySize.get(size).parallelStream().forEach(d -> {
                long t0 = System.nanoTime();
                float[][] merged = merge(d);
                mergeNanos.addAndGet(System.nanoTime() - t0);
                m2[d] = merged[0];
                span(m2[d], d, mLo, mHi);
                if (d == full - 1) {
                    threeAll = merged[1];
                }
                float[] tv = new float[n];
                for (int v = 0; v < n; v++) {
                    tv[v] = Math.min(merged[0][v], merged[1][v]) + this.chamber[d];
                }
                long t1 = System.nanoTime();
                e[d] = extend(tv, d, this.rate[d], rootAt, bound - rest[d], this.trial - rest[d], remaining[d]);
                span(e[d], d, eLo, eHi);
                extendNanos.addAndGet(System.nanoTime() - t1);
                liveCount.addAndGet(live(e[d]));
            });
        }
    }

    Skeleton tree(int root, int rootBranches, double extraBranch) {
        root = inv[root];
        int all = (1 << k) - 1;
        double one = e[all][root];
        double two = rootBranches >= 2 && k >= 2 ? m2[all][root] + extraBranch : Double.POSITIVE_INFINITY;
        double three = rootBranches >= 3 && k >= 3 ? threeAll[root] + 2 * extraBranch : Double.POSITIVE_INFINITY;
        Skeleton skeleton = new Skeleton();
        skeleton.leafParent = new int[k];
        skeleton.cost = Math.min(one, Math.min(two, three));
        if (skeleton.cost >= INF / 2) {
            return null;
        }
        if (one <= two && one <= three) {
            edge(all, root, -1, skeleton);
        } else {
            branches(all, root, -1, skeleton, two <= three);
        }
        return skeleton;
    }

    private void edge(int d, int v, int parent, Skeleton skeleton) {
        if (Integer.bitCount(d) == 1) {
            skeleton.leafParent[Integer.numberOfTrailingZeros(d)] = parent;
            return;
        }
        float[][] merged = d == (1 << k) - 1 ? new float[][] {m2[d], threeAll} : merge(d);
        int best = -1;
        float bestValue = INF;
        for (int o = 0; o < n; o++) {
            int u = inv[o];
            float tv = Math.min(merged[0][u], merged[1][u]) + chamber[d];
            float x = tv + rate[d] * weight[u][v];
            if (x < bestValue) {
                bestValue = x;
                best = u;
            }
        }
        skeleton.junctions.add(perm[best]);
        skeleton.parents.add(parent);
        skeleton.masks.add(d);
        int index = skeleton.junctions.size() - 1;
        branches(d, best, index, skeleton, merged[0][best] <= merged[1][best]);
    }

    private void branches(int d, int u, int index, Skeleton skeleton, boolean two) {
        int low = d & -d;
        int rest = d ^ low;
        int bestA = -1;
        int bestB = -1;
        float best = INF;
        for (int s = rest; ; s = (s - 1) & rest) {
            int a = low | s;
            if (a != d) {
                int b = d ^ a;
                float x = two ? e[a][u] + e[b][u] : Integer.bitCount(b) >= 2 ? e[a][u] + m2[b][u] : INF;
                if (x < best) {
                    best = x;
                    bestA = a;
                    bestB = b;
                }
            }
            if (s == 0) {
                break;
            }
        }
        edge(bestA, u, index, skeleton);
        if (two) {
            edge(bestB, u, index, skeleton);
        } else {
            branches(bestB, u, index, skeleton, true);
        }
    }

    private float[][] merge(int d) {
        float[] two = new float[n];
        float[] three = new float[n];
        Arrays.fill(two, INF);
        Arrays.fill(three, INF);
        int low = d & -d;
        int rest = d ^ low;
        for (int s = rest; ; s = (s - 1) & rest) {
            int a = low | s;
            if (a != d) {
                int b = d ^ a;
                float[] ea = e[a];
                float[] eb = e[b];
                if (Integer.bitCount(b) >= 2) {
                    pairs(ea, eb, m2[b], two, three, Math.max(eLo[a], Math.min(eLo[b], mLo[b])),
                            Math.min(eHi[a], Math.max(eHi[b], mHi[b])) + 1);
                } else {
                    pair(ea, eb, two, Math.max(eLo[a], eLo[b]), Math.min(eHi[a], eHi[b]) + 1);
                }
            }
            if (s == 0) {
                break;
            }
        }
        return new float[][] {two, three};
    }

    private void lowerBounds(int[] leaf, double[] flows, DoubleUnaryOperator rate, int root) {
        int m = k + 1;
        int[] at = Arrays.copyOf(leaf, m);
        at[k] = root;
        double[][] closure = new double[m][];
        double[][] all = new double[m][];
        for (int i = 0; i < m; i++) {
            double[] dist = new double[n];
            boolean[] done = new boolean[n];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            dist[at[i]] = 0;
            for (int step = 0; step < n; step++) {
                int u = -1;
                for (int v = 0; v < n; v++) {
                    if (!done[v] && (u < 0 || dist[v] < dist[u])) {
                        u = v;
                    }
                }
                if (u < 0 || dist[u] == Double.POSITIVE_INFINITY) {
                    break;
                }
                done[u] = true;
                float[] row = weight[u];
                for (int v = 0; v < n; v++) {
                    if (row[v] < INF / 2 && dist[u] + row[v] < dist[v]) {
                        dist[v] = dist[u] + row[v];
                    }
                }
            }
            closure[i] = new double[m];
            for (int j = 0; j < m; j++) {
                closure[i][j] = dist[at[j]];
            }
            all[i] = dist;
        }
        double minRate = Double.POSITIVE_INFINITY;
        for (double f : flows) {
            minRate = Math.min(minRate, rate.applyAsDouble(f));
        }
        if (PLACE_BOUND) {
            this.minRate = (float) (minRate * (1 - 1e-4));
            rootDist = new float[n];
            detour = new float[k][n];
            byDetour = new int[n][];
            for (int v = 0; v < n; v++) {
                rootDist[v] = Double.isFinite(all[k][v]) ? (float) (all[k][v] * (1 - 1e-4)) : 0;
                Integer[] order = new Integer[k];
                for (int t = 0; t < k; t++) {
                    double g = (all[t][v] + all[t][at[k]] - all[k][v]) / 2;
                    detour[t][v] = Double.isFinite(g) && g > 0 ? (float) (g * (1 - 1e-4)) : 0;
                    order[t] = t;
                }
                int place = v;
                Arrays.sort(order, (x, y) -> Float.compare(detour[y][place], detour[x][place]));
                byDetour[v] = Arrays.stream(order).mapToInt(Integer::intValue).toArray();
            }
        }
        int full = 1 << k;
        double[] key = new double[m];
        boolean[] in = new boolean[m];
        for (int d = 1; d < full; d++) {
            int count = 0;
            for (int i = 0; i < m; i++) {
                in[i] = i == k || (d >> i & 1) == 0;
                count += in[i] ? 1 : 0;
                key[i] = Double.POSITIVE_INFINITY;
            }
            if (count < 2) {
                continue;
            }
            double mst = 0;
            key[k] = 0;
            for (int step = 0; step < count; step++) {
                int u = -1;
                for (int i = 0; i < m; i++) {
                    if (in[i] && (u < 0 || key[i] < key[u])) {
                        u = i;
                    }
                }
                in[u] = false;
                mst += key[u];
                for (int i = 0; i < m; i++) {
                    if (in[i] && closure[u][i] < key[i]) {
                        key[i] = closure[u][i];
                    }
                }
            }
            double steiner = mst / (2.0 * (1.0 - 1.0 / count));
            remaining[d] = Double.isFinite(steiner) ? (float) (minRate * steiner * (1 - 1e-4)) : 0;
        }
    }

    private static void pairs(float[] ea, float[] eb, float[] mb, float[] two, float[] three, int from, int to) {
        for (int v = from; v < to; v++) {
            float x = ea[v];
            two[v] = Math.min(two[v], x + eb[v]);
            three[v] = Math.min(three[v], x + mb[v]);
        }
    }

    private static void pair(float[] ea, float[] eb, float[] two, int from, int to) {
        for (int v = from; v < to; v++) {
            two[v] = Math.min(two[v], ea[v] + eb[v]);
        }
    }

    private static int[] order(double[][] weight, int root) {
        int n = weight.length;
        Integer[] order = new Integer[n];
        for (int v = 0; v < n; v++) {
            order[v] = v;
        }
        if (root >= 0) {
            double[] dist = new double[n];
            boolean[] done = new boolean[n];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            dist[root] = 0;
            for (int step = 0; step < n; step++) {
                int u = -1;
                for (int v = 0; v < n; v++) {
                    if (!done[v] && (u < 0 || dist[v] < dist[u])) {
                        u = v;
                    }
                }
                if (dist[u] == Double.POSITIVE_INFINITY) {
                    break;
                }
                done[u] = true;
                for (int v = 0; v < n; v++) {
                    if (dist[u] + weight[u][v] < dist[v]) {
                        dist[v] = dist[u] + weight[u][v];
                    }
                }
            }
            Arrays.sort(order, (x, y) -> dist[x] != dist[y] ? Double.compare(dist[x], dist[y]) : Integer.compare(x, y));
        }
        return Arrays.stream(order).mapToInt(Integer::intValue).toArray();
    }

    private void span(float[] values, int d, int[] lo, int[] hi) {
        int first = 0;
        while (first < n && values[first] >= INF) {
            first++;
        }
        int last = n - 1;
        while (last >= first && values[last] >= INF) {
            last--;
        }
        lo[d] = first;
        hi[d] = last;
    }

    private int live(float[] values) {
        int count = 0;
        for (float value : values) {
            count += value < INF / 2 ? 1 : 0;
        }
        return count;
    }

    private float[] extend(float[] tv, int d, float r, int root, double bound, double trial, float lower) {
        float[] toRootRate = new float[n];
        float[] lowerAt = new float[n];
        for (int v = 0; v < n; v++) {
            toRootRate[v] = root >= 0 ? r * weight[v][root] : 0;
            float at = lower;
            if (detour != null) {
                int[] order = byDetour[v];
                int q = 0;
                while (q < k && (d >> order[q] & 1) == 1) {
                    q++;
                }
                at = Math.max(at, r * rootDist[v] + (q < k ? minRate * detour[order[q]][v] : 0));
            }
            lowerAt[v] = at;
        }
        if (root >= 0) {
            for (int u = 0; u < n; u++) {
                if (tv[u] + toRootRate[u] > bound || tv[u] + lowerAt[u] > trial) {
                    tv[u] = INF;
                }
            }
        }
        long[] keys = new long[n];
        int count = 0;
        for (int u = 0; u < n; u++) {
            if (tv[u] < INF / 2) {
                keys[count++] = (long) Float.floatToIntBits(tv[u]) << 32 | u;
            }
        }
        Arrays.sort(keys, 0, count);
        int[] live = new int[count];
        float[] value = new float[count];
        for (int q = 0; q < count; q++) {
            live[q] = (int) keys[q];
            value[q] = tv[live[q]];
        }
        float[] out = new float[n];
        Arrays.fill(out, INF);
        float[] toRoot = root >= 0 ? transposed[root] : null;
        int done = 0;
        int last = n - 1;
        for (int q = 0; q < count; q += 8) {
            float t = value[q];
            while (done <= last && (out[done] <= t || toRoot != null && (t + toRootRate[done] > bound || t + lowerAt[done] > trial))) {
                done++;
            }
            while (last >= done && (out[last] <= t || toRoot != null && (t + toRootRate[last] > bound || t + lowerAt[last] > trial))) {
                last--;
            }
            if (done > last) {
                break;
            }
            if (q + 7 < count) {
                float[] r0 = transposed[live[q]];
                float[] r1 = transposed[live[q + 1]];
                float[] r2 = transposed[live[q + 2]];
                float[] r3 = transposed[live[q + 3]];
                float[] r4 = transposed[live[q + 4]];
                float[] r5 = transposed[live[q + 5]];
                float[] r6 = transposed[live[q + 6]];
                float[] r7 = transposed[live[q + 7]];
                float t1 = value[q + 1];
                float t2 = value[q + 2];
                float t3 = value[q + 3];
                float t4 = value[q + 4];
                float t5 = value[q + 5];
                float t6 = value[q + 6];
                float t7 = value[q + 7];
                for (int v = done; v <= last; v++) {
                    float y = Math.min(Math.min(t + r * r0[v], t1 + r * r1[v]), Math.min(t2 + r * r2[v], t3 + r * r3[v]));
                    float z = Math.min(Math.min(t4 + r * r4[v], t5 + r * r5[v]), Math.min(t6 + r * r6[v], t7 + r * r7[v]));
                    out[v] = Math.min(out[v], Math.min(y, z));
                }
            } else {
                for (int p = q; p < count; p++) {
                    float tp = value[p];
                    float[] row = transposed[live[p]];
                    for (int v = done; v <= last; v++) {
                        out[v] = Math.min(out[v], tp + r * row[v]);
                    }
                }
            }
        }
        if (root >= 0) {
            for (int v = 0; v < n; v++) {
                if (out[v] + toRootRate[v] > bound || out[v] + lowerAt[v] > trial) {
                    out[v] = INF;
                }
            }
        }
        return out;
    }
}
