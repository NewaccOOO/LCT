package ru.lct.heatnet.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!cli")
@Configuration
public class ApiDocs {
    private static final String DESCRIPTION = "Сервис строит трассы новой тепловой сети от существующей сети к новым зданиям "
            + "и считает стоимость каждого варианта.\n\n"
            + "**Порядок работы**\n"
            + "1. `POST /api/v1/jobs` — отправить входной файл. В ответе придёт `id` задачи.\n"
            + "2. `GET /api/v1/jobs/{id}` — запрашивать, пока статус не станет `DONE` или `FAILED`. "
            + "При `DONE` в поле `summary` сводки вариантов, при `FAILED` в поле `error` причина.\n"
            + "3. `GET /api/v1/jobs/{id}/result` — скачать выходной GeoJSON с трассами всех вариантов.\n\n"
            + "Вход и выход — GeoJSON FeatureCollection в WGS84 (EPSG:4326). Варианты упорядочены по показателю "
            + "S = 0,7 × стоимость / 25 млн ₽ + 0,3 × длина новой сети / 100 м: чем меньше S, тем лучше вариант. "
            + "Параметр `depth=true` включает расчёт с учётом глубины.\n\n"
            + "Платформа инженера для просмотра результата в 3D открывается на корне сервиса: [/](/).";

    @Bean
    OpenAPI openApi() {
        String version = Objects.requireNonNullElse(ApiDocs.class.getPackage().getImplementationVersion(), "dev");
        return new OpenAPI()
                .info(new Info().title("Трассировка тепловых сетей").version(version).description(DESCRIPTION))
                .servers(List.of(new Server().url("/").description("Этот сервис")));
    }
}
