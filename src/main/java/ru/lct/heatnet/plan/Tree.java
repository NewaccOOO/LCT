package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.model.ConnectionPoint;

final class Tree {
    enum Kind { TIE, JUNCTION, CONNECTION }

    static final class Node {
        final String key;
        final Coordinate point;
        final Kind kind;
        final ConnectionPoint connection;

        Node(String key, Coordinate point, Kind kind, ConnectionPoint connection) {
            this.key = key;
            this.point = point;
            this.kind = kind;
            this.connection = connection;
        }

        static Node junction(Coordinate point) {
            return new Node(String.format(Locale.ROOT, "junction:%.3f,%.3f", point.x, point.y), point, Kind.JUNCTION, null);
        }

        static Node connection(ConnectionPoint cp) {
            return new Node("cp:" + cp.getId(), cp.getGeometry().getCoordinate(), Kind.CONNECTION, cp);
        }
    }

    static final class Edge {
        final Node from;
        final Node to;
        final LineString line;
        final Map<SpecialObjects.Special, Object> crossings = new ConcurrentHashMap<>();
        volatile List<SpecialObjects.Special> nearSpecials;

        Edge(Node from, Node to, LineString line) {
            this.from = from;
            this.to = to;
            this.line = line;
        }
    }

    private static final GeometryFactory FACTORY = new GeometryFactory();

    final TieCandidate tie;
    final Node root;
    final List<Edge> edges = new ArrayList<>();
    final List<ConnectionPoint> unconnected = new ArrayList<>();
    private volatile Envelope envelope;
    private volatile Geometry geometry;
    boolean narrow;
    boolean linked;
    boolean turnStuck;
    Tree unsquared;

    Tree(TieCandidate tie) {
        this.tie = tie;
        this.root = new Node(tie.nodeKey(), tie.getPoint().getCoordinate(), Kind.TIE, null);
    }

    int degree(Node node) {
        int degree = 0;
        for (Edge edge : edges) {
            if (edge.from == node || edge.to == node) {
                degree++;
            }
        }
        return degree;
    }

    Envelope envelope() {
        Envelope cached = envelope;
        if (cached == null) {
            cached = new Envelope();
            for (Edge edge : edges) {
                cached.expandToInclude(edge.line.getEnvelopeInternal());
            }
            envelope = cached;
        }
        return cached;
    }

    Geometry geometry() {
        Geometry cached = geometry;
        if (cached == null) {
            cached = FACTORY.createMultiLineString(edges.stream().map(edge -> edge.line).toArray(LineString[]::new));
            cached.getEnvelopeInternal();
            geometry = cached;
        }
        return cached;
    }

    List<ConnectionPoint> connected() {
        List<ConnectionPoint> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.to.kind == Kind.CONNECTION) {
                result.add(edge.to.connection);
            }
        }
        return result;
    }

    double length() {
        double length = 0;
        for (Edge edge : edges) {
            length += edge.line.getLength();
        }
        return length;
    }
}
