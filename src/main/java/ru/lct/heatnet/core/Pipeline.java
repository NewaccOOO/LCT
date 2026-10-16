package ru.lct.heatnet.core;

import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;

public interface Pipeline {
    Result run(InputData input);

    /** depth — режим с учётом глубины (§5 приложения 18.09) отдельным набором вариантов. */
    default Result run(InputData input, boolean depth) {
        if (depth) {
            throw new UnsupportedOperationException("Режим с глубиной не поддержан");
        }
        return run(input);
    }
}
