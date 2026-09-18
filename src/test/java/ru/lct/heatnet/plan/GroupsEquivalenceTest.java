package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.rules.Rules;

class GroupsEquivalenceTest {
    private static final GeometryFactory FACTORY = new GeometryFactory();

    @Test
    void naiveAndFastMatchOnRandomUtmClusters() {
        for (long seed : new long[] {1, 7, 42, 99, 2026}) {
            for (int n : new int[] {0, 1, 5, 50, 200, 800}) {
                List<ConnectionPoint> points = syntheticUtmClustered(n, seed);
                assertSamePartition(
                        VariantEnumerator.groupsNaive(points),
                        VariantEnumerator.groupsFast(points),
                        "seed=" + seed + " n=" + n);
            }
        }
    }

    @Test
    void naiveAndFastMatchOnOrganizerDataset() throws Exception {
        Path dataset = Path.of("data/real/dataset.geojson");
        assumeTrue(Files.isRegularFile(dataset));
        InputData input = VariantEnumerator.read(dataset, Rules.load());
        List<ConnectionPoint> connections = organizerConnections(input);
        assertSamePartition(
                VariantEnumerator.groupsNaive(connections),
                VariantEnumerator.groupsFast(connections),
                "organizer dataset");
    }

    static void assertSamePartition(
            List<List<ConnectionPoint>> naive, List<List<ConnectionPoint>> fast, String context) {
        assertEquals(partitionSignature(naive), partitionSignature(fast), context);
    }

    private static List<Integer> partitionSignature(List<List<ConnectionPoint>> groups) {
        return groups.stream().map(List::size).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
    }

    /** Кластеры в метрах (EPSG:32637), ~800 м между центрами, шум ~200 м. */
    static List<ConnectionPoint> syntheticUtmClustered(int n, long seed) {
        List<ConnectionPoint> result = new ArrayList<>(n);
        if (n == 0) {
            return result;
        }
        Random random = new Random(seed);
        int clusters = Math.max(1, n / 40);
        double[] cx = new double[clusters];
        double[] cy = new double[clusters];
        for (int c = 0; c < clusters; c++) {
            cx[c] = 415_000 + random.nextDouble() * 8_000;
            cy[c] = 6_198_000 + random.nextDouble() * 6_000;
        }
        for (int i = 0; i < n; i++) {
            int cluster = random.nextInt(clusters);
            double x = cx[cluster] + (random.nextDouble() - 0.5) * 400;
            double y = cy[cluster] + (random.nextDouble() - 0.5) * 400;
            Point geometry = FACTORY.createPoint(new Coordinate(x, y));
            result.add(new ConnectionPoint("cp-" + i, geometry, "oks-" + i));
        }
        return result;
    }

    private static List<ConnectionPoint> organizerConnections(InputData input) {
        java.util.Set<String> futureOks = input.getFutureOks().stream()
                .map(ru.lct.heatnet.model.FutureOks::getId)
                .collect(Collectors.toSet());
        List<ConnectionPoint> connections = new ArrayList<>();
        for (ConnectionPoint connection : input.getConnectionPoints()) {
            if (futureOks.contains(connection.getOksId())) {
                connections.add(connection);
            }
        }
        return connections;
    }
}
