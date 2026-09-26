package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
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
 * новой камеры врезки вдоль трубы).
 * Менять константу только при намеренной смене качества.
 */
class OrganizerDatasetRegressionTest {
    private static final double ORGANIZER_RANK1_SCORE = 12.745;

    @Test
    void rank1ScoreMatchesBaseline() throws Exception {
        Path dataset = Path.of("data/real/dataset.geojson");
        assumeTrue(Files.isRegularFile(dataset));
        Rules rules = Rules.load();
        Result result = new VariantEnumerator(VariantEnumerator.read(dataset, rules), rules).run();
        Variant best = result.getVariants().get(0);
        assertEquals(1, best.getSummary().getRank());
        assertEquals(ORGANIZER_RANK1_SCORE, best.getSummary().getScore(), 1e-6);
        // с переносом врезки к стволу другие врезки дают почти ту же трассу, третий вариант — от блоков второго без переноса
        assertEquals(3, result.getVariants().size());
    }
}
