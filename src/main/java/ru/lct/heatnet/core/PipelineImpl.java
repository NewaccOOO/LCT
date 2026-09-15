package ru.lct.heatnet.core;

import org.springframework.stereotype.Component;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;

@Component
public class PipelineImpl implements Pipeline {
    @Override
    public Result run(InputData input) {
        throw new UnsupportedOperationException("Расчёт вариантов ещё не реализован");
    }
}
