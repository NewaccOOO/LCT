package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

/**
 * Эталон S rank=1 на {@code data/real/dataset.geojson} по правилам технического приложения от 18.09.2026
 * (до них было 12.596; 13.019 до строгого финального участка в 0.6.0; 13.729 до точных зон отступа, срезки углов
 * и врезки у ствола; 12.608 до выхода из здания по Ду участка: точки 3 и 11 теперь выходят от ближней стены;
 * 12.866 до запрета выхода с дальней стороны при открытой ближней, п. 2.2; 13.230 до выпрямления дуг и зигзагов:
 * обход угла — один или два поворота вместо цепочки мелких; 13.276 до строгой формы по п. 5: лишние вершины
 * убираются, два поворота в одну сторону со звеном короче 10 м заменяются одним, если он проходит по отступам;
 * 13.194 до запаса формы 0,15 м от нормы вместо 0,2 м; 13.193 до звена после выхода из здания, когда маршрут от
 * выхода уходит назад круче 90°; 13.057 до прокладки рёбер выбранных вариантов по графу своего Ду; 13.019 до сдвига
 * камер ветвления вдоль рёбер в выбранных вариантах; 12.943 до переноса камер ветвления перед сдвигом; 12.785 до
 * общей камеры врезки: ветки первой камеры ветвления идут прямо из новой камеры на трубе, а сама камера ветвления не
 * строится; 12.747 до сдвига камеры ветвления, который снимает излом ветки у камеры; 12.746 до такого же сдвига
 * новой камеры врезки вдоль трубы; 12.745 до правки поворота круче 90° в камере ветвления на пути точки к врезке;
 * 12.746 до переноса камеры ветвления в любую сторону, который снимает изломы у неё; 12.744 до замены двух соседних
 * поворотов в одну сторону одной вершиной при звене любой длины; 12.743 до переноса входа финального участка
 * к ближайшей допустимой точке контура, п. 2.2; 12.751 до финального участка без вершины, когда прямое звено входит
 * не дальше 0,1 м от ближайшей точки контура, и до переноса камеры, который снимает мелкий излом пути в ней; 12.749
 * до вывода score с четырьмя знаками, п. 7.3; 12.7488 до переноса камеры, который без роста S снимает излом меньше
 * 3° в ней: v1_ch_6 на прямой v1_ch_5–v1_ch_7; 12.7483 до ортогональной формы по замечанию экспертов 16.10.2026:
 * рёбра по осям застройки, поворот 90° вместо двух по 45°, длина трассы растёт, S тоже; 13.434 до переноса камер
 * ветвления под оси: ствол v1_ch_6–v1_ch_10 одной прямой; 13.5176 до сдвига камер вдоль своих рёбер и оси по отрезку, из
 * которого выходит ребро; 13.6408 до запрета перекладки по осям, которая добавляет вершины или пары поворотов,
 * даёт двойной поворот, заменимый одной вершиной, или удлиняет участок больше чем в 1,3 раза; 13.1574 до точного
 * скелета дерева врезки, который берётся, только если после проходов формы не хуже ни по одному показателю).
 * Менять константу только при намеренной смене качества.
 */
class OrganizerDatasetRegressionTest {
    private static final double ORGANIZER_RANK1_SCORE = 12.7841;
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
        // с общим правилом выхода из здания (B19, 28.09.2026) черновик с точкой 4 отдельной частью проходит проверки и
        // отличается от первого по устройству: второй вариант (S 13,1835 до ортогональной формы); первый прежний
        assertEquals(3, result.getVariants().size());
        assertEquals(13.0943, result.getVariants().get(1).getSummary().getScore(), 1e-6);
        assertTrue(turns(best) <= TURNS_BEFORE_AXES, "поворотов " + turns(best));
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
