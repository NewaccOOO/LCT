package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class ExactSteinerTest {
    private static final DoubleUnaryOperator RATE = f -> 0.005 + 0.00001 * f;
    private static final DoubleUnaryOperator CHAMBER = f -> 0.084;

    @Test
    void threeBranchChamberInCentre() {
        double h = 50 * Math.sqrt(3);
        double[][] xy = {{0, 100}, {-h, -50}, {h, -50}, {0, -150}, {0, 0}};
        double[][] w = euclid(xy);
        ExactSteiner.Skeleton skeleton = new ExactSteiner(w, new int[] {0, 1, 2}, new double[3], new double[] {10, 10, 10},
                RATE, CHAMBER).tree(3, 1, 0);
        assertNotNull(skeleton);
        assertEquals(1, skeleton.junctions.size());
        assertEquals(4, skeleton.junctions.get(0).intValue());
        double expected = 0.084 + 3 * RATE.applyAsDouble(10) * 100 + RATE.applyAsDouble(30) * 150;
        assertEquals(expected, skeleton.cost, 1e-4);
    }

    @Test
    void matchesBruteForce() {
        Random random = new Random(7);
        for (int trial = 0; trial < 30; trial++) {
            int k = 3;
            int n = 7;
            double[][] xy = new double[n][2];
            for (double[] p : xy) {
                p[0] = random.nextDouble() * 100;
                p[1] = random.nextDouble() * 100;
            }
            double[][] w = euclid(xy);
            double[] flows = {5, 20, 40};
            int root = k;
            ExactSteiner.Skeleton skeleton = new ExactSteiner(w, new int[] {0, 1, 2}, new double[k], flows, RATE, CHAMBER)
                    .tree(root, 1, 0);
            assertEquals(brute(w, flows, root), skeleton.cost, 1e-3, "набор " + trial);
        }
    }

    @Test
    void lowerBoundKeepsSkeleton() {
        Random random = new Random(11);
        for (int trial = 0; trial < 40; trial++) {
            int k = 6;
            int n = 30;
            double[][] xy = new double[n][2];
            for (double[] p : xy) {
                p[0] = random.nextDouble() * 200;
                p[1] = random.nextDouble() * 200;
            }
            double[][] w = euclid(xy);
            int[] leaf = {0, 1, 2, 3, 4, 5};
            double[] leafCost = new double[k];
            double[] flows = new double[k];
            for (int t = 0; t < k; t++) {
                leafCost[t] = random.nextDouble() * 0.05;
                flows[t] = 5 + random.nextDouble() * 50;
            }
            int root = k;
            double opt = new ExactSteiner(w, leaf, leafCost, flows, RATE, CHAMBER, root, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, false)
                    .tree(root, 3, 0.05).cost;
            for (double factor : new double[] {0.98, 1.0 + 1e-6, 1.01, 1.2}) {
                double bound = opt * factor;
                ExactSteiner.Skeleton full = new ExactSteiner(w, leaf, leafCost, flows, RATE, CHAMBER, root, bound, bound, false)
                        .tree(root, 3, 0.05);
                ExactSteiner.Skeleton pruned = new ExactSteiner(w, leaf, leafCost, flows, RATE, CHAMBER, root, bound, bound, true)
                        .tree(root, 3, 0.05);
                String at = "набор " + trial + " граница ×" + factor;
                if (bound >= opt) {
                    assertNotNull(pruned, at);
                    assertTrue(pruned.cost <= bound, at);
                }
                if (pruned != null && pruned.cost <= bound) {
                    assertNotNull(full, at);
                    assertEquals(full.cost, pruned.cost, 0, at);
                    assertEquals(full.junctions, pruned.junctions, at);
                    assertEquals(full.parents, pruned.parents, at);
                    assertEquals(full.masks, pruned.masks, at);
                    assertEquals(java.util.Arrays.toString(full.leafParent), java.util.Arrays.toString(pruned.leafParent), at);
                }
            }
        }
    }

    @Test
    void trialBoundKeepsSkeletonOnNonMetricWeights() {
        Random random = new Random(5);
        int checked = 0;
        for (int trial = 0; trial < 80; trial++) {
            int k = 6;
            int n = 24;
            double[][] w = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < i; j++) {
                    w[i][j] = w[j][i] = 10 + random.nextDouble() * (random.nextInt(4) == 0 ? 600 : 150);
                }
            }
            int[] leaf = {0, 1, 2, 3, 4, 5};
            double[] leafCost = new double[k];
            double[] flows = new double[k];
            for (int t = 0; t < k; t++) {
                leafCost[t] = random.nextDouble() * 0.05;
                flows[t] = 5 + random.nextDouble() * 50;
            }
            int root = k;
            double loose = new ExactSteiner(w, leaf, leafCost, flows, RATE, CHAMBER, root, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, false).tree(root, 3, 0.05).cost;
            double bound = loose * 1.02;
            ExactSteiner.Skeleton full = new ExactSteiner(w, leaf, leafCost, flows, RATE, CHAMBER, root, bound, bound, false)
                    .tree(root, 3, 0.05);
            if (full == null) {
                continue;
            }
            checked++;
            for (double factor : new double[] {0.97, 0.99, 1.0, 1.005, 1.01}) {
                double guess = full.cost * factor;
                String at = "набор " + trial + " пробная ×" + factor;
                ExactSteiner.Skeleton pruned = new ExactSteiner(w, leaf, leafCost, flows, RATE, CHAMBER, root, bound, guess, true)
                        .tree(root, 3, 0.05);
                if (full.cost <= guess) {
                    assertNotNull(pruned, at);
                    assertTrue(pruned.cost <= guess, at);
                }
                if (pruned != null && pruned.cost <= guess) {
                    assertEquals(full.cost, pruned.cost, 0, at);
                    assertEquals(full.junctions, pruned.junctions, at);
                    assertEquals(full.parents, pruned.parents, at);
                    assertEquals(java.util.Arrays.toString(full.leafParent), java.util.Arrays.toString(pruned.leafParent), at);
                }
            }
        }
        assertTrue(checked >= 25, "наборов с деревом " + checked);
    }

    private static double brute(double[][] w, double[] flows, int root) {
        int n = w.length;
        double best = Double.POSITIVE_INFINITY;
        double all = flows[0] + flows[1] + flows[2];
        for (int j = 0; j < n; j++) {
            double star = CHAMBER.applyAsDouble(all) + RATE.applyAsDouble(all) * w[j][root];
            for (int t = 0; t < 3; t++) {
                star += RATE.applyAsDouble(flows[t]) * w[t][j];
            }
            best = Math.min(best, star);
            for (int pair = 0; pair < 3; pair++) {
                int a1 = (pair + 1) % 3;
                int a2 = (pair + 2) % 3;
                double inner = flows[a1] + flows[a2];
                for (int a = 0; a < n; a++) {
                    double low = CHAMBER.applyAsDouble(inner) + RATE.applyAsDouble(flows[a1]) * w[a1][a]
                            + RATE.applyAsDouble(flows[a2]) * w[a2][a] + RATE.applyAsDouble(inner) * w[a][j];
                    double top = CHAMBER.applyAsDouble(all) + RATE.applyAsDouble(flows[pair]) * w[pair][j]
                            + RATE.applyAsDouble(all) * w[j][root];
                    best = Math.min(best, low + top);
                }
            }
        }
        return best;
    }

    private static double[][] euclid(double[][] xy) {
        double[][] w = new double[xy.length][xy.length];
        for (int i = 0; i < xy.length; i++) {
            for (int j = 0; j < xy.length; j++) {
                w[i][j] = Math.hypot(xy[i][0] - xy[j][0], xy[i][1] - xy[j][1]);
            }
        }
        return w;
    }
}
