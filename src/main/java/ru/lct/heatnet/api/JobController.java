package ru.lct.heatnet.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile("!cli")
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Задачи", description = "Загрузка входного GeoJSON, статус расчёта и выгрузка результата")
public class JobController {
    private static final String GEO_JSON = "application/geo+json";
    private static final String JOB_ID_EXAMPLE = "3f0c6a52-2d4e-4b8e-9a41-6f1c2b7d9e10";
    private static final String NOT_FOUND_EXAMPLE = "{\"message\": \"Задача не найдена\", \"errors\": []}";

    private final JobRepository jobs;
    private final JobWorker worker;
    private final Path dataDir;

    public JobController(JobRepository jobs, JobWorker worker, @Value("${app.data-dir}") String dataDir) {
        this.jobs = jobs;
        this.worker = worker;
        this.dataDir = Path.of(dataDir).toAbsolutePath();
    }

    @lombok.Value
    @Schema(description = "Принятая задача")
    public static class JobCreated {
        @Schema(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE)
        UUID id;
        @Schema(description = "Статус сразу после приёма", example = "QUEUED")
        JobEntity.Status status;
    }

    @lombok.Value
    @Schema(description = "Задача в общем списке")
    public static class JobListItem {
        @Schema(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE)
        UUID id;
        @Schema(description = "Статус: QUEUED в очереди, RUNNING считается, DONE готово, FAILED ошибка", example = "DONE")
        JobEntity.Status status;
        @Schema(description = "Когда задача принята", example = "2026-09-15T10:00:00Z")
        Instant createdAt;
        @Schema(description = "Когда расчёт закончился; null, пока задача не завершена", example = "2026-09-15T10:00:42Z")
        Instant finishedAt;
    }

    @lombok.Value
    @Schema(description = "Подробности задачи")
    public static class JobDetails {
        @Schema(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE)
        UUID id;
        @Schema(description = "Статус: QUEUED в очереди, RUNNING считается, DONE готово, FAILED ошибка", example = "DONE")
        JobEntity.Status status;
        @Schema(description = "Когда задача принята", example = "2026-09-15T10:00:00Z")
        Instant createdAt;
        @Schema(description = "Когда начался расчёт; null, пока задача в очереди", example = "2026-09-15T10:00:01Z")
        Instant startedAt;
        @Schema(description = "Когда расчёт закончился; null, пока задача не завершена", example = "2026-09-15T10:00:42Z")
        Instant finishedAt;
        @Schema(description = "Ошибка при статусе FAILED, иначе null", implementation = ApiError.class, nullable = true)
        JsonNode error;
        @ArraySchema(arraySchema = @Schema(
                description = "Сводки всех вариантов при статусе DONE, лучший первым; иначе null. "
                        + "Это объекты variant_summary из выходного файла и поле criteria, которого в файле нет",
                nullable = true), schema = @Schema(implementation = VariantSummaryDoc.class))
        JsonNode summary;
    }

    @lombok.Value
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "VariantSummary", description = "Сводка варианта: стоимость, длина и показатель S. Деньги в рублях, длины в метрах")
    public static class VariantSummaryDoc {
        @Schema(description = "Идентификатор сводки", example = "summary_1")
        String id;
        @Schema(description = "Тип объекта в выходном файле, всегда variant_summary", example = "variant_summary")
        String objectType;
        @Schema(description = "Номер варианта. Участки и камеры варианта в выходном файле несут тот же variant_id", example = "1")
        String variantId;
        @Schema(description = "Место варианта по S: 1 — лучший. При равном S выше вариант дешевле", example = "1")
        int rank;
        @Schema(description = "Стоимость строительства: трубы с учётом способа прокладки, новые камеры и врезки в существующие камеры",
                example = "20914190.0")
        double constructionCost;
        @Schema(description = "Стоимость новых камер, входит в construction_cost", example = "0.0")
        double chamberConstructionCost;
        @Schema(description = "Сколько врезок в существующие камеры", example = "1")
        int existingChamberTieInCount;
        @Schema(description = "Стоимость врезок в существующие камеры, входит в construction_cost", example = "5000000.0")
        double existingChamberTieInCost;
        @Schema(description = "Штраф за неподключённые здания", example = "0.0")
        double unconnectedPenalty;
        @Schema(description = "Полная стоимость: construction_cost и штраф. По ней считается S", example = "20914190.0")
        double calculatedCost;
        @Schema(description = "Длина новой сети", example = "163.6")
        double newNetworkLength;
        @Schema(description = "Показатель S = 0,7 × calculated_cost / 25 млн ₽ + 0,3 × new_network_length / 100 м. Чем меньше, тем лучше",
                example = "1.0764")
        double score;
        @ArraySchema(arraySchema = @Schema(description = "id зданий, которые вариант не подключил"))
        List<String> unconnectedOksIds;
        @Schema(description = "Дополнительные критерии варианта. Их отдаёт только API, в выходном файле их нет")
        VariantCriteriaDoc criteria;
    }

    @lombok.Value
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "VariantCriteria", description = "Дополнительные критерии варианта")
    public static class VariantCriteriaDoc {
        @Schema(description = "Сколько зданий подключено", example = "1")
        int connectedOks;
        @Schema(description = "Суммарный расход подключённых зданий, т/ч", example = "25.0")
        double connectedFlowTph;
        @ArraySchema(arraySchema = @Schema(description = "Почему здания не подключены, не больше 1000 записей"))
        List<UnconnectedReasonDoc> unconnectedReasons;
        @Schema(description = "Сколько зданий дальше от существующей сети, чем допустимая длина трубы наибольшего диаметра. "
                + "Причин для них нет в unconnected_reasons. Поле есть, только если такие здания нашлись", example = "0")
        Integer beyondReachOks;
        @Schema(description = "Сколько неподключённых зданий не вошло в unconnected_reasons сверх 1000 записей. "
                + "Поле есть, только если лимит превышен", example = "0")
        Integer unconnectedWithoutReason;
        @Schema(description = "Сколько врезок в существующие камеры", example = "1")
        int existingChamberTieIns;
        @Schema(description = "Сколько новых камер", example = "0")
        int newChambers;
        @Schema(description = "Сколько технических узлов в новой сети", example = "0")
        int technicalNodes;
        @Schema(description = "Сколько участков проложено спецпереходом", example = "0")
        int specialSegments;
        @Schema(description = "Длина спецпереходов, м", example = "0.0")
        double specialLengthM;
        @Schema(description = "Сколько объектов каждого типа пересекают спецпереходы: тип ограничения или heat_network и число объектов")
        java.util.Map<String, Integer> crossedObjects;
        @Schema(description = "Сколько поворотов в новой сети: изломы больше 3° внутри участков и на стыках в технических узлах. "
                + "В камерах и точках подключения повороты не считаются", example = "0")
        int turns;
        @Schema(description = "Самый крутой поворот, градусы", example = "0.0")
        double maxTurnDeg;
        @Schema(description = "На сколько стоимость участков отличается от цены тех же труб по базовой ставке за метр, ₽: "
                + "коэффициенты спецпереходов и, в режиме depth, глубины. В режиме depth бывает отрицательной", example = "0.0")
        double surchargeCost;
        @Schema(description = "Стоимость без штрафа на одно подключённое здание, ₽; null, если подключённых нет",
                example = "20914190.0", nullable = true)
        Double costPerOks;
        @Schema(description = "Стоимость без штрафа на 1 т/ч подключённого расхода, ₽; null, если расход нулевой",
                example = "836567.6", nullable = true)
        Double costPerTph;
    }

    @lombok.Value
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "UnconnectedReason", description = "Почему здание не подключено")
    public static class UnconnectedReasonDoc {
        @Schema(description = "id здания", example = "oks-7")
        String oksId;
        @Schema(description = "Код причины: coincident_point — точка подключения совпадает с точкой уже подключённого здания; "
                + "no_connection_point — у здания нет точки подключения; flow_exceeds_capacity — расход больше пропускной "
                + "способности наибольшего диаметра; inside_forbidden_zone — точка внутри запретной зоны с учётом отступа; "
                + "enclosed — точка окружена запретными зонами; no_route — допустимая трасса не найдена",
                allowableValues = {"coincident_point", "no_connection_point", "flow_exceeds_capacity", "inside_forbidden_zone",
                        "enclosed", "no_route"},
                example = "inside_forbidden_zone")
        String reason;
        @Schema(description = "Причина словами", example = "точка подключения внутри запретной зоны с учётом отступа: финальный прямой участок из своего здания упирается в чужую зону")
        String message;
        @ArraySchema(arraySchema = @Schema(description = "id объектов, из-за которых здание не подключено: ограничений или подключённых зданий с той же точкой"))
        List<String> objectIds;
    }

    @Operation(
            summary = "Загрузить входной GeoJSON и поставить задачу в очередь",
            description = "Тело запроса — сам файл GeoJSON FeatureCollection, не multipart. В Swagger UI выберите тип "
                    + "application/octet-stream, чтобы загрузить файл с диска. Файл пишется на диск потоком, поэтому его размер "
                    + "не ограничен памятью сервиса. Атрибуты объектов проверяются уже в задаче: ошибки входных данных придут "
                    + "в поле error при статусе FAILED.\n\n"
                    + "Тип объекта задаёт атрибут object_type в properties: source, heat_chamber и heat_network с diameter — "
                    + "существующая сеть; oks_connection_point с расходом flow_tph — точки подключения зданий; "
                    + "oks_future, oks_existing и restriction с restriction_type — здания и ограничения.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Входной GeoJSON FeatureCollection в EPSG:4326",
                    content = {
                            // первым, чтобы Swagger UI по умолчанию показывал кнопку выбора файла
                            @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = GEO_JSON, schema = @Schema(type = "object"), examples = @ExampleObject(
                                    name = "Одно здание",
                                    summary = "Источник, камера на существующей сети и точка подключения здания",
                                    value = "{\"type\": \"FeatureCollection\", \"features\": [\n"
                                            + "{\"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": [37.64103, 55.702354]}, "
                                            + "\"properties\": {\"id\": \"src-1\", \"object_type\": \"source\"}},\n"
                                            + "{\"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": [37.64003, 55.699945]}, "
                                            + "\"properties\": {\"id\": \"ch-1\", \"object_type\": \"heat_chamber\"}},\n"
                                            + "{\"type\": \"Feature\", \"geometry\": {\"type\": \"LineString\", "
                                            + "\"coordinates\": [[37.64103, 55.702354], [37.64003, 55.699945]]}, "
                                            + "\"properties\": {\"id\": \"hn-1\", \"object_type\": \"heat_network\", \"diameter\": 500}},\n"
                                            + "{\"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": [37.6375, 55.6996]}, "
                                            + "\"properties\": {\"id\": \"cp-1\", \"object_type\": \"oks_connection_point\", \"flow_tph\": 25}}\n]}")),
                            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"))
                    }),
            responses = {
                    @ApiResponse(responseCode = "202", description = "Задача принята и стоит в очереди",
                            content = @Content(schema = @Schema(implementation = JobCreated.class), examples = @ExampleObject(
                                    value = "{\"id\": \"" + JOB_ID_EXAMPLE + "\", \"status\": \"QUEUED\"}"))),
                    @ApiResponse(responseCode = "400", description = "Тело пустое или не начинается с JSON-объекта",
                            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(
                                    value = "{\"message\": \"Тело запроса должно быть JSON-объектом GeoJSON FeatureCollection\", "
                                            + "\"errors\": []}"))),
                    @ApiResponse(responseCode = "415", description = "Content-Type не application/json, application/geo+json или application/octet-stream",
                            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(
                                    value = "{\"message\": \"Тело запроса принимается только с Content-Type application/json, "
                                            + "application/geo+json или application/octet-stream\", \"errors\": []}")))
            })
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, GEO_JSON, MediaType.APPLICATION_OCTET_STREAM_VALUE}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobCreated> create(
            HttpServletRequest request,
            @Parameter(description = "Расчёт с учётом глубины: сервис подбирает глубину заложения каждого участка, учитывает её "
                    + "в стоимости и обходит коммуникации по высоте. Считается дольше. По умолчанию false — расчёт в плане, "
                    + "без глубины. То же, что ключ --depth в командной строке", example = "false")
            @RequestParam(name = "depth", defaultValue = "false") boolean depth) throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = dataDir.resolve("jobs").resolve(id.toString());
        Path input = dir.resolve("input.geojson");
        JobEntity job = new JobEntity();
        job.setId(id);
        job.setStatus(JobEntity.Status.QUEUED);
        job.setInputPath(input.toString());
        Files.createDirectories(dir);
        try (InputStream body = request.getInputStream()) {
            Files.copy(body, input);
            int first = firstSignificantByte(input);
            if (first == -1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тело запроса пустое, ожидается GeoJSON FeatureCollection");
            }
            if (first != '{') {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тело запроса должно быть JSON-объектом GeoJSON FeatureCollection");
            }
            if (depth) {
                // флаг режима лежит рядом со входом: схема таблицы задач не меняется
                Files.createFile(dir.resolve(JobWorker.DEPTH_FLAG));
            }
            job.setCreatedAt(Instant.now());
            // save() коммитит свою транзакцию до возврата, поэтому воркер получает уже видимую запись.
            jobs.save(job);
        } catch (IOException | RuntimeException e) {
            FileSystemUtils.deleteRecursively(dir);
            throw e;
        }
        worker.submit(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new JobCreated(id, job.getStatus()));
    }

    @Operation(
            summary = "Список задач",
            description = "Все задачи, новые первыми.",
            responses = @ApiResponse(responseCode = "200", description = "Список задач", content = @Content(
                    array = @ArraySchema(schema = @Schema(implementation = JobListItem.class)), examples = @ExampleObject(
                    value = "[{\"id\": \"" + JOB_ID_EXAMPLE + "\", \"status\": \"DONE\", "
                            + "\"createdAt\": \"2026-09-15T10:00:00Z\", \"finishedAt\": \"2026-09-15T10:00:42Z\"}]"))))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<JobListItem> list() {
        List<JobListItem> items = new ArrayList<>();
        for (JobEntity job : jobs.findAllByOrderByCreatedAtDesc()) {
            items.add(new JobListItem(job.getId(), job.getStatus(), job.getCreatedAt(), job.getFinishedAt()));
        }
        return items;
    }

    @Operation(
            summary = "Статус и итоги задачи",
            description = "Пока задача в статусе QUEUED или RUNNING, повторяйте запрос. После DONE в summary лежат сводки "
                    + "всех вариантов, лучший первым. После FAILED в error причина и список проблем во входном файле.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Задача найдена",
                            content = @Content(schema = @Schema(implementation = JobDetails.class), examples = {
                                    @ExampleObject(name = "Готово", summary = "Готово: расчёт примера «Одно здание», показан лучший из двух вариантов",
                                            value = "{\"id\": \"" + JOB_ID_EXAMPLE + "\", \"status\": \"DONE\", "
                                            + "\"createdAt\": \"2026-09-15T10:00:00Z\", \"startedAt\": \"2026-09-15T10:00:01Z\", "
                                            + "\"finishedAt\": \"2026-09-15T10:00:02Z\", \"error\": null, \"summary\": [{"
                                            + "\"id\": \"summary_1\", \"object_type\": \"variant_summary\", \"variant_id\": \"1\", "
                                            + "\"rank\": 1, \"construction_cost\": 20914190.0, \"chamber_construction_cost\": 0.0, "
                                            + "\"existing_chamber_tie_in_count\": 1, \"existing_chamber_tie_in_cost\": 5000000.0, "
                                            + "\"unconnected_penalty\": 0.0, \"calculated_cost\": 20914190.0, "
                                            + "\"new_network_length\": 163.6, \"score\": 1.0764, "
                                            + "\"unconnected_oks_ids\": [], \"criteria\": {\"connected_oks\": 1, "
                                            + "\"connected_flow_tph\": 25.0, \"unconnected_reasons\": [], \"existing_chamber_tie_ins\": 1, "
                                            + "\"new_chambers\": 0, \"technical_nodes\": 0, \"special_segments\": 0, "
                                            + "\"special_length_m\": 0.0, \"crossed_objects\": {}, \"turns\": 0, \"max_turn_deg\": 0.0, "
                                            + "\"surcharge_cost\": 0.0, \"cost_per_oks\": 20914190.0, \"cost_per_tph\": 836567.6}}]}"),
                                    @ExampleObject(name = "Ошибка входа", value = "{\"id\": \"" + JOB_ID_EXAMPLE + "\", "
                                            + "\"status\": \"FAILED\", \"createdAt\": \"2026-09-15T10:00:00Z\", "
                                            + "\"startedAt\": \"2026-09-15T10:00:01Z\", \"finishedAt\": \"2026-09-15T10:00:02Z\", "
                                            + "\"error\": {\"message\": \"Входной файл не прошёл проверку, найдено ошибок: 1\", "
                                            + "\"errors\": [{\"featureId\": \"oks-1\", \"field\": \"flow_tph\", "
                                            + "\"problem\": \"нет обязательного атрибута\"}]}, \"summary\": null}")
                            })),
                    @ApiResponse(responseCode = "404", description = "Задачи с таким id нет",
                            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
            })
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JobDetails get(@Parameter(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE) @PathVariable String id) {
        JobEntity job = find(id);
        return new JobDetails(job.getId(), job.getStatus(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(),
                json(job.getError()), json(job.getSummary()));
    }

    @Operation(
            summary = "Скачать результат",
            description = "Выходной GeoJSON со всеми вариантами в WGS84: участки новой сети и камеры с номером варианта "
                    + "в variant_id и сводка каждого варианта (variant_summary). Доступен только при статусе DONE, отдаётся потоком.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Файл результата",
                            content = @Content(mediaType = GEO_JSON, schema = @Schema(type = "string", format = "binary"))),
                    @ApiResponse(responseCode = "404", description = "Задачи с таким id нет",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
                    @ApiResponse(responseCode = "409", description = "Задача ещё не в статусе DONE",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(
                                    value = "{\"message\": \"Результат недоступен: задача в статусе RUNNING\", \"errors\": []}")))
            })
    @GetMapping(path = "/{id}/result")
    public ResponseEntity<FileSystemResource> result(
            @Parameter(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE) @PathVariable String id) {
        JobEntity job = find(id);
        if (job.getStatus() != JobEntity.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Результат недоступен: задача в статусе " + job.getStatus());
        }
        return download(Path.of(job.getOutputPath()), "result-" + job.getId() + ".geojson");
    }

    @Operation(
            summary = "Скачать входной файл",
            description = "Файл, загруженный при создании задачи, байт в байт. Отдаётся потоком.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Входной файл",
                            content = @Content(mediaType = GEO_JSON, schema = @Schema(type = "string", format = "binary"))),
                    @ApiResponse(responseCode = "404", description = "Задачи с таким id нет",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
            })
    @GetMapping(path = "/{id}/input")
    public ResponseEntity<FileSystemResource> input(
            @Parameter(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE) @PathVariable String id) {
        JobEntity job = find(id);
        return download(Path.of(job.getInputPath()), "input-" + job.getId() + ".geojson");
    }

    private JobEntity find(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не найдена");
        }
        return jobs.findById(uuid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не найдена"));
    }

    private static ResponseEntity<FileSystemResource> download(Path file, String filename) {
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл задачи не найден на диске");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(GEO_JSON))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(new FileSystemResource(file));
    }

    private static JsonNode json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return JobWorker.JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("В базе испорчен JSON задачи", e);
        }
    }

    // Пробелы по RFC 8259: пробел, табуляция, перевод строки, возврат каретки.
    private static int firstSignificantByte(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            int b = in.read();
            while (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                b = in.read();
            }
            return b;
        }
    }
}
