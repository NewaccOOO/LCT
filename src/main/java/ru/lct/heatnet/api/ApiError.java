package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Value;
import ru.lct.heatnet.model.Diagnostic;

@Value
@Schema(description = "Ошибка: общее сообщение и список проблем во входных данных")
public class ApiError {
    @Schema(description = "Что случилось", example = "Задача не найдена")
    String message;
    @Schema(description = "Проблемы в фичах входного файла; пустой список, если ошибка не во входных данных")
    List<Diagnostic> errors;
}
