package ru.lct.heatnet.model;

import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class Result {
    List<Variant> variants;
    /** ОКС, которые не вошли в расчёт: сеть до источника не пропустит их расход даже после реконструкции. */
    Set<String> overCapacityOksIds;

    public Result(List<Variant> variants) {
        this(variants, Set.of());
    }
}
