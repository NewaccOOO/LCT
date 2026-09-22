package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.calc.ReconstructionCalculator;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

/**
 * Отбор ОКС под пропускную способность сети. Добавленный расход каждой врезки идёт по цепочке участков до источника,
 * и участок не примет больше, чем пропускает наибольший диаметр таблицы: дальше реконструкция не поможет, а сборка
 * варианта упадёт. ОКС берутся по возрастанию пути до источника (по прямой до ближайшей трубы, дальше по сети), пока
 * каждому участку их цепочки хватает запаса. Путь короче — меньше новой сети и реконструкции у источника.
 */
final class NetworkCapacity {
    private static final double EPS_TPH = 1e-6;

    private final InputData input;
    private final Map<String, NetworkSegment> segments = new HashMap<>();
    private final Map<String, Chamber> chambers = new HashMap<>();
    /** Расстояние по сети от источника до нижнего конца объекта. */
    private final Map<String, Double> down = new HashMap<>();
    private final Map<String, Double> remaining = new HashMap<>();
    private final List<String> roots = new ArrayList<>();

    /** Кусок трубы между соседними вершинами; from — расстояние от верхнего конца участка до начала куска. */
    private static final class Piece {
        final NetworkSegment segment;
        final LineSegment line;
        final double from;
        final boolean reversed;

        Piece(NetworkSegment segment, LineSegment line, double from, boolean reversed) {
            this.segment = segment;
            this.line = line;
            this.from = from;
            this.reversed = reversed;
        }

        /** Путь по сети от источника до проекции точки на кусок. */
        double along(Coordinate c, double upstreamEnd) {
            double t = line.segmentFraction(c) * line.getLength();
            return upstreamEnd + from + (reversed ? line.getLength() - t : t);
        }
    }

    NetworkCapacity(InputData input, Rules rules) {
        this.input = input;
        input.getSegments().forEach(segment -> segments.put(segment.getId(), segment));
        input.getChambers().forEach(chamber -> chambers.put(chamber.getId(), chamber));
        List<Diameter> diameters = rules.diameters();
        double largest = diameters.get(diameters.size() - 1).getCapacityTph();
        for (NetworkSegment segment : input.getSegments()) {
            remaining.put(segment.getId(), largest - segment.getFlowTph());
            down(segment.getId());
            if (input.getSource().getId().equals(segment.getUpstreamId())) {
                roots.add(segment.getId());
            }
        }
    }

    /** Точки подключения, которые сеть примет, в порядке входа; flows[i] — расход ОКС точки i. Запаса хватает всем — все. */
    List<ConnectionPoint> select(List<ConnectionPoint> connections, double[] flows) {
        STRtree index = pieces();
        ItemDistance distance = (a, b) -> {
            Piece piece = (Piece) (a.getItem() instanceof Piece ? a.getItem() : b.getItem());
            Coordinate point = (Coordinate) (a.getItem() instanceof Piece ? b.getItem() : a.getItem());
            return piece.line.distance(point);
        };
        Piece[] nearest = new Piece[connections.size()];
        double[] path = new double[connections.size()];
        IntStream.range(0, connections.size()).parallel().forEach(i -> {
            Coordinate c = connections.get(i).getGeometry().getCoordinate();
            Piece piece = (Piece) index.nearestNeighbour(new Envelope(c), c, distance);
            nearest[i] = piece;
            path[i] = piece.line.distance(c) + piece.along(c, down(piece.segment.getUpstreamId()));
        });
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> path[i]));
        double smallest = Double.POSITIVE_INFINITY;
        for (double flow : flows) {
            smallest = Math.min(smallest, flow);
        }
        boolean[] taken = new boolean[connections.size()];
        for (int i : order) {
            if (rootRemaining() < smallest - EPS_TPH) {
                break;
            }
            List<String> chain = chain(nearest[i].segment);
            double flow = flows[i];
            if (chain.stream().allMatch(id -> remaining.get(id) >= flow - EPS_TPH)) {
                chain.forEach(id -> remaining.merge(id, -flow, Double::sum));
                taken[i] = true;
            }
        }
        List<ConnectionPoint> result = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            if (taken[i]) {
                result.add(connections.get(i));
            }
        }
        return result;
    }

    /** Наибольший запас среди участков у источника: меньше расхода любого ОКС — дальше брать некого. */
    private double rootRemaining() {
        double best = 0;
        for (String root : roots) {
            best = Math.max(best, remaining.get(root));
        }
        return best;
    }

    private STRtree pieces() {
        STRtree index = new STRtree();
        for (NetworkSegment segment : input.getSegments()) {
            boolean atStart = ReconstructionCalculator.upstreamAtStart(segment, input, segments, chambers);
            Coordinate[] coords = segment.getGeometry().getCoordinates();
            double length = segment.getGeometry().getLength();
            double fromStart = 0;
            for (int k = 0; k + 1 < coords.length; k++) {
                LineSegment line = new LineSegment(coords[k], coords[k + 1]);
                double from = atStart ? fromStart : length - fromStart - line.getLength();
                Envelope envelope = new Envelope(coords[k], coords[k + 1]);
                index.insert(envelope, new Piece(segment, line, from, !atStart));
                fromStart += line.getLength();
            }
        }
        index.build();
        return index;
    }

    /** Участки от segment до источника. */
    private List<String> chain(NetworkSegment segment) {
        List<String> chain = new ArrayList<>();
        String next = segment.getId();
        while (next != null && !next.equals(input.getSource().getId())) {
            NetworkSegment s = segments.get(next);
            if (s != null) {
                chain.add(next);
                next = s.getUpstreamId();
            } else {
                next = chambers.get(next).getUpstreamId();
            }
        }
        return chain;
    }

    /**
     * Путь по сети от источника до нижнего конца объекта id; цепочки проверены при чтении и доходят до источника.
     * Считается в конструкторе для всех участков, потом только читается, в том числе из параллельного отбора.
     */
    private double down(String id) {
        if (id.equals(input.getSource().getId())) {
            return 0;
        }
        Double known = down.get(id);
        if (known != null) {
            return known;
        }
        NetworkSegment segment = segments.get(id);
        double value = segment != null ? down(segment.getUpstreamId()) + segment.getGeometry().getLength()
                : down(chambers.get(id).getUpstreamId());
        down.put(id, value);
        return value;
    }
}
