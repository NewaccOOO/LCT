package ru.lct.heatnet.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.ConnectionPoint;

/**
 * Микробенчмарк groups(): текущий O(n²) vs STRtree. Запуск: {@code mvn -Dtest=CityScaleGroupsBench test}.
 * Печатает t(n) и нормировки для экстраполяции на город.
 */
class CityScaleGroupsBench {
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final int WARMUP = 2;
    private static final int REPEATS = 5;
    private static final Path REPORT = Path.of("target/city-scale-groups-bench.txt");

    @Test
    void benchmarkKMeansOnLargeGroup() throws Exception {
        StringBuilder lines = new StringBuilder("k-means (одна группа), n\tms\n");
        for (int n : new int[] {50, 100, 200, 500}) {
            List<ConnectionPoint> group = syntheticClustered(n, 99);
            long ms = medianNanos(() -> VariantEnumerator.kMeans(group)) / 1_000_000;
            lines.append(String.format("%d\t%d%n", n, ms));
        }
        appendReport(lines.toString());
    }

    @Test
    void benchmarkGroupsScaling() throws Exception {
        int[] sizes = {100, 500, 2000, 5000, 10000};
        StringBuilder lines = new StringBuilder(
                "# GROUP_DISTANCE_M=300, координаты UTM (м), VariantEnumerator.groupsNaive vs groupsFast\n"
                        + "n\tnaive_ms\tstrtree_ms\tratio\tt/n_ms\tt/nlogn_ms\tt/n2_us\n");
        for (int n : sizes) {
            List<ConnectionPoint> points = GroupsEquivalenceTest.syntheticUtmClustered(n, 42);
            long naive = medianNanos(() -> VariantEnumerator.groupsNaive(points));
            long strtree = medianNanos(() -> VariantEnumerator.groupsFast(points));
            GroupsEquivalenceTest.assertSamePartition(
                    VariantEnumerator.groupsNaive(points), VariantEnumerator.groupsFast(points), "n=" + n);
            double naiveMs = naive / 1_000_000.0;
            double strMs = strtree / 1_000_000.0;
            double ratio = naiveMs / Math.max(strMs, 1e-9);
            double perN = naiveMs / n;
            double perNLogN = naiveMs / (n * Math.log(n));
            double perN2Us = naiveMs * 1000 / (n * (long) n);
            lines.append(String.format("%d\t%.2f\t%.2f\t%.1f\t%.4f\t%.6f\t%.4f%n",
                    n, naiveMs, strMs, ratio, perN, perNLogN, perN2Us));
        }
        appendReport(lines.toString());
    }

    private static void appendReport(String text) throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, text, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static long medianNanos(Runnable task) {
        for (int i = 0; i < WARMUP; i++) {
            task.run();
        }
        long[] samples = new long[REPEATS];
        for (int i = 0; i < REPEATS; i++) {
            long start = System.nanoTime();
            task.run();
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        return samples[REPEATS / 2];
    }

    /** Кластеры ~800 м + шум: типичная городская плотность для GROUP_DISTANCE_M=300. */
    static List<ConnectionPoint> syntheticClustered(int n, long seed) {
        Random random = new Random(seed);
        List<ConnectionPoint> result = new ArrayList<>(n);
        int clusters = Math.max(1, n / 40);
        double[] cx = new double[clusters];
        double[] cy = new double[clusters];
        for (int c = 0; c < clusters; c++) {
            cx[c] = 37.60 + random.nextDouble() * 0.08;
            cy[c] = 55.69 + random.nextDouble() * 0.06;
        }
        for (int i = 0; i < n; i++) {
            int cluster = random.nextInt(clusters);
            double x = cx[cluster] + (random.nextDouble() - 0.5) * 0.004;
            double y = cy[cluster] + (random.nextDouble() - 0.5) * 0.003;
            Point geometry = FACTORY.createPoint(new Coordinate(x, y));
            result.add(new ConnectionPoint("cp-" + i, geometry, "oks-" + i));
        }
        return result;
    }

}
