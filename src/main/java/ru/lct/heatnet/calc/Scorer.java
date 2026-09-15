package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.lct.heatnet.model.VariantSummary;

public final class Scorer {
    private Scorer() {
    }

    /** Индексы сводок по возрастанию score, при равенстве — по возрастанию calculatedCost. */
    public static List<Integer> rank(List<VariantSummary> summaries) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> summaries.get(i).getScore())
                .thenComparingDouble(i -> summaries.get(i).getCalculatedCost()));
        return order;
    }
}
