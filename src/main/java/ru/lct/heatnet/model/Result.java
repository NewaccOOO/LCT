package ru.lct.heatnet.model;

import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class Result {
    List<Variant> variants;
    /** ID входа, записанные числом: в выходе они остаются числами (приложение 18.09, п. 7.2). */
    Set<String> numericIds;

    public Result(List<Variant> variants) {
        this(variants, Set.of());
    }
}
