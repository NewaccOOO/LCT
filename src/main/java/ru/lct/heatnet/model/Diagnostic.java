package ru.lct.heatnet.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;

@Value
@Schema(description = "Проблема в одном объекте входного файла")
public class Diagnostic {
    @Schema(description = "id объекта из properties. Если id нет — #N, порядковый номер объекта в features; #0 — ошибка в файле целиком",
            example = "oks-1")
    String featureId;
    @Schema(description = "Атрибут или часть файла с ошибкой", example = "flow_tph")
    String field;
    @Schema(description = "Что не так", example = "нет обязательного атрибута")
    String problem;
}
