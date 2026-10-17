package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

final class ExactSteiner {
    private static final float INF = 1e30f;
    private static final boolean VECTOR_MIN = Runtime.version().feature() >= 21;

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
    final java.util.concurrent.atomic.AtomicLong mergeNanos = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong extendNanos = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong liveCount = new java.util.concurrent.atomic.AtomicLong();
    private final float[] rest;
    // ponytail: нижняя оценка остатка дерева (MST/2 по метрическому замыканию на непокрытых точках и врезке);
    // отсекает только состояния, которые дороже bound при любом достраивании, поэтому argmin не меняется
    private static final boolean LOWER_BOUND = Boolean.parseBoolean(System.getProperty("heatnet.exact.lb", "true"));
    private final float[] remaining;

    ExactSteiner(double[][] weight, int[] leaf, double[] leafCost, double[] flows, DoubleUnaryOperator rate,
            DoubleUnaryOperator chamber) {
        this(weight, leaf, leafCost, flows, rate, chamber, -1, Double.POSITIVE_INFINITY);
    }

    ExactSteiner(double[][] weight, int[] leaf, double[] leafCost, double[] flows, DoubleUnaryOperator rate,
            DoubleUnaryOperator chamber, int root, double bound) {
        this.k = leaf.length;
        this.n = weight.length;
        this.weight = new float[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double w = weight[i][j];
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
        if (LOWER_BOUND && root >= 0 && Double.isFinite(bound)) {
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
        for (int t = 0; t < k; t++) {
            float[] tv = new float[n];
            Arrays.fill(tv, INF);
            tv[leaf[t]] = (float) leafCost[t];
            e[1 << t] = extend(tv, this.rate[1 << t], root, bound - rest[1 << t], remaining[1 << t]);
        }
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
                float[] tv = new float[n];
                for (int v = 0; v < n; v++) {
                    tv[v] = Math.min(merged[0][v], merged[1][v]) + this.chamber[d];
                }
                long t1 = System.nanoTime();
                e[d] = extend(tv, this.rate[d], root, bound - rest[d], remaining[d]);
                extendNanos.addAndGet(System.nanoTime() - t1);
                liveCount.addAndGet(live(e[d]));
            });
        }
    }

    Skeleton tree(int root, int rootBranches, double extraBranch) {
        int all = (1 << k) - 1;
        double one = e[all][root];
        double two = rootBranches >= 2 && k >= 2 ? m2[all][root] + extraBranch : Double.POSITIVE_INFINITY;
        double three = rootBranches >= 3 && k >= 3 ? merge(all)[1][root] + 2 * extraBranch : Double.POSITIVE_INFINITY;
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
        float[][] merged = merge(d);
        int best = -1;
        float bestValue = INF;
        for (int u = 0; u < n; u++) {
            float tv = Math.min(merged[0][u], merged[1][u]) + chamber[d];
            float x = tv + rate[d] * weight[u][v];
            if (x < bestValue) {
                bestValue = x;
                best = u;
            }
        }
        skeleton.junctions.add(best);
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
                    float[] mb = m2[b];
                    if (VECTOR_MIN) {
                        for (int v = 0; v < n; v++) {
                            float x = ea[v];
                            two[v] = Math.min(two[v], x + eb[v]);
                            three[v] = Math.min(three[v], x + mb[v]);
                        }
                    } else {
                        for (int v = 0; v < n; v++) {
                            float x = ea[v];
                            float y = x + eb[v];
                            float z = x + mb[v];
                            two[v] = y < two[v] ? y : two[v];
                            three[v] = z < three[v] ? z : three[v];
                        }
                    }
                } else if (VECTOR_MIN) {
                    for (int v = 0; v < n; v++) {
                        two[v] = Math.min(two[v], ea[v] + eb[v]);
                    }
                } else {
                    for (int v = 0; v < n; v++) {
                        float y = ea[v] + eb[v];
                        two[v] = y < two[v] ? y : two[v];
                    }
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
        }
        double minRate = Double.POSITIVE_INFINITY;
        for (double f : flows) {
            minRate = Math.min(minRate, rate.applyAsDouble(f));
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

    private int live(float[] values) {
        int count = 0;
        for (float value : values) {
            count += value < INF / 2 ? 1 : 0;
        }
        return count;
    }

    private float[] extend(float[] tv, float r, int root, double bound, float lower) {
        if (root >= 0) {
            for (int u = 0; u < n; u++) {
                if (tv[u] + Math.max(r * weight[u][root], lower) > bound) {
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
        float worst = INF;
        for (int q = 0; q < count && value[q] < worst; q++) {
            float t = value[q];
            float[] row = transposed[live[q]];
            if (VECTOR_MIN) {
                for (int v = 0; v < n; v++) {
                    out[v] = Math.min(out[v], t + r * row[v]);
                }
            } else {
                for (int v = 0; v < n; v++) {
                    float y = t + r * row[v];
                    out[v] = y < out[v] ? y : out[v];
                }
            }
            if ((q & 7) == 7) {
                worst = 0;
                for (int v = 0; v < n; v++) {
                    worst = Math.max(worst, out[v]);
                }
            }
        }
        if (root >= 0) {
            for (int v = 0; v < n; v++) {
                if (out[v] + Math.max(r * weight[v][root], lower) > bound) {
                    out[v] = INF;
                }
            }
        }
        return out;
    }
}
