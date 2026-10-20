package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.graph.ObstacleIndex;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.io.GeoJsonStreamReader;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.rules.Rules;

class ExitProbeTest {
    private static final Rules RULES = Rules.load();
    private static final InputData INPUT = GeoJsonStreamReader.read(Path.of("data/scenes-v5/SCALE-12/input.geojson"));
    private static final ObstacleIndex INDEX = new ObstacleIndex(INPUT, RULES);

    @Test
    void oldInletPipeAlongTheOnlyExitBlocksTheBuilding() {
        assertEquals(List.of("hn-866"), blockers("cp-ren-w27633750", 125));
    }

    @Test
    void buildingWithFreeSideHasExit() {
        assertNull(blockers("cp-ren-w27728755", 125));
    }

    private List<String> blockers(String id, int dn) {
        ConnectionPoint cp = INPUT.getConnectionPoints().stream().filter(c -> c.getId().equals(id)).findFirst().orElseThrow();
        ExistingOks own = INPUT.getExistingOks().stream()
                .filter(o -> o.getGeometry().distance(cp.getGeometry()) < 0.05).findFirst().orElseThrow();
        Coordinate c = cp.getGeometry().getCoordinate();
        Envelope area = new Envelope(c);
        area.expandBy(80);
        ObstacleSet zones = new ObstacleSet(INDEX, RULES, area, dn, null, null, false);
        Map<String, Geometry> network = new LinkedHashMap<>();
        INPUT.getSegments().stream().filter(s -> s.getGeometry().distance(cp.getGeometry()) < 40)
                .forEach(s -> network.put(s.getId(), s.getGeometry()));
        INPUT.getChambers().stream().filter(s -> s.getGeometry().distance(cp.getGeometry()) < 40)
                .forEach(s -> network.put(s.getId(), s.getGeometry()));
        return ExitProbe.blockers(zones, INDEX, RULES, dn, own.getGeometry(), own.getId(), c, network);
    }
}
