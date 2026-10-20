package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class OrganizerDatasetRegressionTest {
    private static final double ORGANIZER_RANK1_SCORE = 12.6962;
    private static final int TURNS_BEFORE_AXES = 26;

    @Test
    void rank1ScoreMatchesBaseline() throws Exception {
        Path dataset = Path.of("data/real/dataset.geojson");
        assumeTrue(Files.isRegularFile(dataset));
        Rules rules = Rules.load();
        Result result = new VariantEnumerator(VariantEnumerator.read(dataset, rules), rules).run();
        Variant best = result.getVariants().get(0);
        assertEquals(1, best.getSummary().getRank());
        assertEquals(ORGANIZER_RANK1_SCORE, best.getSummary().getScore(), 1e-6);
        assertEquals(3, result.getVariants().size());
        assertEquals(12.9102, result.getVariants().get(1).getSummary().getScore(), 1e-6);
        assertTrue(turns(best) <= TURNS_BEFORE_AXES, "поворотов " + turns(best));
    }

    @Test
    void correctedDatasetVariantsHaveNoKneesAtChambers() throws Exception {
        Path dataset = Path.of("data/samples/small-1.geojson");
        assumeTrue(Files.isRegularFile(dataset));
        Rules rules = Rules.load();
        Result result = new VariantEnumerator(VariantEnumerator.read(dataset, rules), rules).run();
        for (Variant variant : result.getVariants().subList(1, result.getVariants().size())) {
            Set<String> chambers = variant.getChambers().stream().map(NewChamber::getId).collect(Collectors.toSet());
            for (NewSegment in : variant.getSegments()) {
                for (NewSegment out : variant.getSegments()) {
                    if (chambers.contains(in.getEndNodeId()) && in.getEndNodeId().equals(out.getStartNodeId())) {
                        double turn = TreeBuilder.pathTurnDeg(in.getGeometry().getCoordinates(),
                                out.getGeometry().getCoordinates());
                        assertTrue(turn <= Router.MAX_TURN_DEG, in.getId() + " → " + out.getId() + " " + turn + "°");
                    }
                }
            }
        }
    }

    private static int turns(Variant variant) {
        int count = 0;
        for (NewSegment segment : variant.getSegments()) {
            Coordinate[] c = segment.getGeometry().getCoordinates();
            for (int v = 1; v + 1 < c.length; v++) {
                if (Router.deflectionDeg(c[v - 1], c[v], c[v + 1]) >= TreeBuilder.MIN_TURN_DEG) {
                    count++;
                }
            }
        }
        return count;
    }
}
