package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Трасса черновика для правила R-11 (VariantEnumerator#sameRoute): линии всех деревьев, их полоса по буферу JTS
 * и отрезки по рамке. Длина одной трассы в полосе другой сначала оценивается без буфера: снизу — по отрезкам ближе
 * ширины полосы без запаса, сверху — с запасом. Буфер JTS и пересечение с ним считаются, только когда оценки не
 * решают: на «густо-200» они занимали четверть времени основной нити.
 */
final class RouteBand {
    /**
     * Буфер JTS отличается от точной полосы на сантиметры: дуги хордами (0,5 см при 8 сегментах на четверть круга),
     * упрощение входа на 1 % ширины, округление на сетку при повторе с меньшей точностью. Оценки берут полосу
     * уже и шире на этот запас.
     */
    private static final double SLACK_M = 0.05;
    /**
     * Наложение линий одной трассы JTS считает один раз, а сумма по отрезкам — дважды. Сборка не пускает касаний
     * дальше 0,15 м от общих узлов и врезок, поэтому оценка снизу теряет по 0,3 м на ребро и дерево.
     */
    private static final double OVERLAP_M = 0.3;
    /** Погрешность длины пересечения JTS на отрезок: узлы наложения и привязка при повторе. */
    private static final double NOISE_M = 1e-4;

    final Geometry lines;
    private final double width;
    /** Рёбра и деревья трассы: на них наложения, см. OVERLAP_M. */
    private final int parts;
    /** Отрезки подряд: x0, y0, x1, y1. */
    private final double[] sides;
    private STRtree index;
    private Geometry buffered;

    RouteBand(List<LineString> edges, int parts, double width, GeometryFactory factory) {
        this.lines = factory.createMultiLineString(edges.toArray(new LineString[0]));
        this.width = width;
        this.parts = parts;
        List<Coordinate[]> pairs = new ArrayList<>();
        for (LineString edge : edges) {
            Coordinate[] coords = edge.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                pairs.add(new Coordinate[] {coords[i], coords[i + 1]});
            }
        }
        sides = new double[4 * pairs.size()];
        for (int k = 0; k < pairs.size(); k++) {
            sides[4 * k] = pairs.get(k)[0].x;
            sides[4 * k + 1] = pairs.get(k)[0].y;
            sides[4 * k + 2] = pairs.get(k)[1].x;
            sides[4 * k + 3] = pairs.get(k)[1].y;
        }
    }

    /**
     * R-11: больше share длины меньшей трассы лежит в полосе другой, и наоборот. Ответ тот же, что у сравнения
     * длин пересечений с буферами JTS: оценки решают, только если буфер заведомо дал бы то же.
     */
    static boolean same(RouteBand a, RouteBand b, double share) {
        double shorter = Math.min(a.lines.getLength(), b.lines.getLength());
        if (shorter == 0) {
            return a.lines.getLength() == b.lines.getLength();
        }
        if (a.lines.getEnvelopeInternal().distance(b.lines.getEnvelopeInternal()) > a.width) {
            return false;
        }
        // полоса отобранного варианта (a) сравнивается со всеми следующими, поэтому она считается первой
        double limit = share * shorter;
        return b.inside(a, limit) && a.inside(b, limit);
    }

    /** Длина этой трассы в полосе other больше limit. */
    private boolean inside(RouteBand other, double limit) {
        double[] bounds = bounds(other);
        if (bounds[0] > limit) {
            return true;
        }
        if (bounds[1] <= limit) {
            return false;
        }
        return lines.intersection(other.buffered()).getLength() > limit;
    }

    /** Полоса JTS, строится один раз. */
    private Geometry buffered() {
        if (buffered == null) {
            buffered = lines.buffer(width);
        }
        return buffered;
    }

    /**
     * Оценки снизу и сверху длины этой трассы в буфере other: сумма по отрезкам длин их частей не дальше
     * width − SLACK_M и width + SLACK_M от отрезков other, снизу — за вычетом наложений и погрешности.
     */
    double[] bounds(RouteBand other) {
        double reach = width + SLACK_M;
        STRtree near = other.index();
        double low = 0;
        double high = 0;
        List<double[]> inner = new ArrayList<>();
        List<double[]> outer = new ArrayList<>();
        for (int k = 0; k < sides.length; k += 4) {
            double x0 = sides[k];
            double y0 = sides[k + 1];
            double dx = sides[k + 2] - x0;
            double dy = sides[k + 3] - y0;
            double length = Math.hypot(dx, dy);
            if (length == 0) {
                continue;
            }
            dx /= length;
            dy /= length;
            Envelope envelope = new Envelope(x0, sides[k + 2], y0, sides[k + 3]);
            envelope.expandBy(reach + SLACK_M);
            inner.clear();
            outer.clear();
            for (Object item : near.query(envelope)) {
                int t = (Integer) item;
                double[] os = other.sides;
                add(inner, span(x0, y0, dx, dy, os[t], os[t + 1], os[t + 2], os[t + 3], width - SLACK_M), length);
                add(outer, span(x0, y0, dx, dy, os[t], os[t + 1], os[t + 2], os[t + 3], reach), length);
            }
            low += covered(inner);
            high += covered(outer);
        }
        double noise = NOISE_M * sides.length / 4;
        return new double[] {low - OVERLAP_M * parts - noise, high + noise};
    }

    private STRtree index() {
        if (index == null) {
            index = new STRtree();
            for (int t = 0; t < sides.length; t += 4) {
                index.insert(new Envelope(sides[t], sides[t + 2], sides[t + 1], sides[t + 3]), t);
            }
            index.build();
        }
        return index;
    }

    /** Интервал в пределах [0, length] в список, пустой не добавляется. */
    private static void add(List<double[]> out, double[] span, double length) {
        if (span != null && span[1] >= 0 && span[0] <= length) {
            out.add(new double[] {Math.max(0, span[0]), Math.min(length, span[1])});
        }
    }

    /** Длина объединения интервалов. */
    private static double covered(List<double[]> spans) {
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        double total = 0;
        double from = Double.NaN;
        double to = Double.NaN;
        for (double[] span : spans) {
            if (!(span[0] <= to)) {
                total += Double.isNaN(from) ? 0 : to - from;
                from = span[0];
                to = span[1];
            } else {
                to = Math.max(to, span[1]);
            }
        }
        return total + (Double.isNaN(from) ? 0 : to - from);
    }

    /**
     * Параметры u на прямой (x0, y0) + u·(dx, dy), (dx, dy) единичный, где точка не дальше r от отрезка
     * (tx0, ty0)–(tx1, ty1), или null. Окрестность отрезка выпукла: круги у концов и полоса вдоль него, поэтому
     * ответ — один интервал, охват их трёх интервалов.
     */
    static double[] span(double x0, double y0, double dx, double dy, double tx0, double ty0, double tx1, double ty1, double r) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int end = 0; end < 2; end++) {
            double px = x0 - (end == 0 ? tx0 : tx1);
            double py = y0 - (end == 0 ? ty0 : ty1);
            double b = dx * px + dy * py;
            double disc = b * b - (px * px + py * py - r * r);
            if (disc >= 0) {
                double s = Math.sqrt(disc);
                lo = Math.min(lo, -b - s);
                hi = Math.max(hi, -b + s);
            }
        }
        double ex = tx1 - tx0;
        double ey = ty1 - ty0;
        double side = Math.hypot(ex, ey);
        if (side > 0) {
            ex /= side;
            ey /= side;
            double px = x0 - tx0;
            double py = y0 - ty0;
            // вдоль отрезка: проекция в [0, side]; поперёк: расстояние до его прямой не больше r
            double[] along = slab(px * ex + py * ey, dx * ex + dy * ey, 0, side);
            double[] across = slab(px * ey - py * ex, dx * ey - dy * ex, -r, r);
            if (along != null && across != null) {
                double from = Math.max(along[0], across[0]);
                double to = Math.min(along[1], across[1]);
                if (from <= to) {
                    lo = Math.min(lo, from);
                    hi = Math.max(hi, to);
                }
            }
        }
        return lo <= hi ? new double[] {lo, hi} : null;
    }

    /** u, при которых min ≤ value + u·rate ≤ max, или null. */
    private static double[] slab(double value, double rate, double min, double max) {
        if (rate == 0) {
            return value >= min && value <= max ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        }
        double a = (min - value) / rate;
        double b = (max - value) / rate;
        return new double[] {Math.min(a, b), Math.max(a, b)};
    }
}
