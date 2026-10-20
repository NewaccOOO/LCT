package ru.lct.heatnet.core;

import org.springframework.stereotype.Component;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.plan.VariantEnumerator;
import ru.lct.heatnet.rules.Rules;

@Component
public class PipelineImpl implements Pipeline {
    private static final Rules RULES = Rules.load();

    @Override
    public Result run(InputData input) {
        return new VariantEnumerator(input, RULES).run();
    }

    @Override
    public Result run(InputData input, boolean depth) {
        VariantEnumerator enumerator = new VariantEnumerator(input, RULES);
        Result flat = enumerator.run();
        return depth ? enumerator.depth(flat) : flat;
    }
}
