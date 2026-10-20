package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

public final class ObstacleIndex {
    private static final String OKS_EXISTING = "oks_existing";
    private static final String HEAT_NETWORK = "heat_network";

    final InputData input;
    private final STRtree existingOks = new STRtree();
    private final STRtree restrictions = new STRtree();
    private final STRtree segments = new STRtree();

    public ObstacleIndex(InputData input, Rules rules) {
        this.input = input;
        double reach = reach(input, rules);
        insert(existingOks, input.getExistingOks(), ExistingOks::getGeometry, reach);
        insert(restrictions, input.getRestrictions(), Restriction::getGeometry, reach);
        insert(segments, input.getSegments(), NetworkSegment::getGeometry, reach);
    }

    public List<ExistingOks> existingOks(Envelope area) {
        return near(existingOks, input.getExistingOks(), area);
    }

    public List<Restriction> restrictions(Envelope area) {
        return near(restrictions, input.getRestrictions(), area);
    }

    public List<NetworkSegment> segments(Envelope area) {
        return near(segments, input.getSegments(), area);
    }

    private static double reach(InputData input, Rules rules) {
        Set<String> types = new HashSet<>(List.of(OKS_EXISTING, HEAT_NETWORK));
        input.getRestrictions().forEach(restriction -> types.add(restriction.getType()));
        double width = 0;
        for (Diameter diameter : rules.diameters()) {
            width = Math.max(width, diameter.getWidthM());
        }
        double clearance = 0;
        double halfWidth = 0;
        for (String type : types) {
            RestrictionRule rule = rules.restriction(type);
            if (rule.getHalfWidthM() != null) {
                halfWidth = Math.max(halfWidth, rule.getHalfWidthM());
            }
            for (Diameter diameter : rules.diameters()) {
                try {
                    clearance = Math.max(clearance, rule.clearanceM(diameter.getDn()));
                } catch (IllegalArgumentException e) {
                }
            }
        }
        return clearance + width + halfWidth + 1;
    }

    private static <T> void insert(STRtree tree, List<T> items, Function<T, Geometry> geometry, double reach) {
        for (int i = 0; i < items.size(); i++) {
            Envelope envelope = new Envelope(geometry.apply(items.get(i)).getEnvelopeInternal());
            envelope.expandBy(reach);
            tree.insert(envelope, i);
        }
        tree.build();
    }

    private static <T> List<T> near(STRtree tree, List<T> items, Envelope area) {
        List<Integer> hits = new ArrayList<>();
        for (Object hit : tree.query(area)) {
            hits.add((Integer) hit);
        }
        Collections.sort(hits);
        List<T> result = new ArrayList<>(hits.size());
        for (int i : hits) {
            result.add(items.get(i));
        }
        return result;
    }
}
