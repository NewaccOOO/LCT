package ru.lct.heatnet.core;

import org.springframework.stereotype.Component;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.plan.VariantEnumerator;
import ru.lct.heatnet.rules.Rules;

/** Расчёт вариантов без состояния между вызовами: всё состояние живёт в VariantEnumerator одного запуска. */
@Component
public class PipelineImpl implements Pipeline {
    private static final Rules RULES = Rules.load();

    @Override
    public Result run(InputData input) {
        return new VariantEnumerator(input, RULES).run();
    }
}
