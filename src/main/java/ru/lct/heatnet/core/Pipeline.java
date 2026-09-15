package ru.lct.heatnet.core;

import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;

public interface Pipeline {
    Result run(InputData input);
}
