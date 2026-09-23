package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.model.ConnectionPoint;

/** Дерево новой сети одной врезки: рёбра-полилинии между врезкой, камерами ветвления и точками подключения. */
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

    /** Ребро направлено от врезки: координаты line идут от from к to. */
    static final class Edge {
        final Node from;
        final Node to;
        final LineString line;

        Edge(Node from, Node to, LineString line) {
            this.from = from;
            this.to = to;
            this.line = line;
        }
    }

    final TieCandidate tie;
    final Node root;
    final List<Edge> edges = new ArrayList<>();
    final List<ConnectionPoint> unconnected = new ArrayList<>();

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

    /** Рамка всех рёбер; у дерева без рёбер пустая. */
    Envelope envelope() {
        Envelope envelope = new Envelope();
        for (Edge edge : edges) {
            envelope.expandToInclude(edge.line.getEnvelopeInternal());
        }
        return envelope;
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
