package ru.lct.heatnet.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.lct.heatnet.core.Pipeline;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class JobApiIT {
    private static final String GEO_JSON = "application/geo+json";
    private static final Path SAMPLE = Path.of("data/samples/small-1.geojson");
    private static final long WAIT_MS = 30_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DATA_DIR = createDataDir();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.data-dir", DATA_DIR::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JobRepository jobs;
    @Autowired
    JobWorker worker;
    @MockBean
    Pipeline pipeline;

    @AfterAll
    static void cleanUp() {
        FileSystemUtils.deleteRecursively(DATA_DIR.toFile());
    }

    @BeforeEach
    void pipelineReturnsOneVariant() {
        when(pipeline.run(any())).thenReturn(oneVariant());
    }

    @Test
    void postStoresBodyOnDiskAndQueuesJob() throws Exception {
        byte[] body = Files.readAllBytes(SAMPLE);
        UUID id = submit(body);

        assertArrayEquals(body, Files.readAllBytes(DATA_DIR.resolve("jobs").resolve(id.toString()).resolve("input.geojson")));
        mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].createdAt").isString());
        await(id, "DONE");
    }

    @Test
    void emptyOrNonObjectBodyIsRejectedWithoutLeavingJob() throws Exception {
        long jobsBefore = jobs.count();
        long dirsBefore = jobDirs();
        for (String body : List.of("", "  \n", "[1]")) {
            mvc.perform(post("/api/v1/jobs").contentType(GEO_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.errors").isEmpty());
        }
        assertEquals(jobsBefore, jobs.count());
        assertEquals(dirsBefore, jobDirs());
    }

    @Test
    void unsupportedContentTypeGets415InErrorFormat() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType("application/x-www-form-urlencoded").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void jobRunsToDoneWithSummaries() throws Exception {
        UUID id = submit(Files.readAllBytes(SAMPLE));
        JsonNode job = await(id, "DONE");

        assertTrue(job.get("createdAt").isTextual());
        assertTrue(job.get("startedAt").isTextual());
        assertTrue(job.get("finishedAt").isTextual());
        assertTrue(job.get("error").isNull());
        JsonNode summary = job.get("summary");
        assertEquals(1, summary.size());
        assertEquals("variant_summary", summary.get(0).get("object_type").asText());
        assertEquals(1, summary.get(0).get("rank").asInt());
        assertEquals("1.946", summary.get(0).get("score").decimalValue().toPlainString());
        assertTrue(summary.get(0).has("unconnected_oks_ids"));
    }

    @Test
    void resultIsGeoJsonAttachment() throws Exception {
        UUID id = submit(Files.readAllBytes(SAMPLE));
        await(id, "DONE");

        byte[] body = mvc.perform(get("/api/v1/jobs/{id}/result", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(GEO_JSON))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"result-" + id + ".geojson\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("FeatureCollection", MAPPER.readTree(body).get("type").asText());
    }

    @Test
    void resultIsConflictUntilJobIsDone() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(pipeline.run(any())).thenAnswer(invocation -> {
            release.await(WAIT_MS, TimeUnit.MILLISECONDS);
            return oneVariant();
        });
        UUID id = submit(Files.readAllBytes(SAMPLE));
        try {
            mvc.perform(get("/api/v1/jobs/{id}/result", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.errors").isEmpty());
        } finally {
            release.countDown();
        }
        await(id, "DONE");
        mvc.perform(get("/api/v1/jobs/{id}/result", id)).andExpect(status().isOk());
    }

    @Test
    void inputIsReturnedByteForByte() throws Exception {
        byte[] body = ("  " + Files.readString(SAMPLE) + "\n").getBytes(StandardCharsets.UTF_8);
        UUID id = submit(body);

        byte[] downloaded = mvc.perform(get("/api/v1/jobs/{id}/input", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(body, downloaded);
        await(id, "DONE");
    }

    @Test
    void brokenInputFailsWithFeatureErrors() throws Exception {
        UUID id = submit(brokenSample());
        JsonNode error = await(id, "FAILED").get("error");

        JsonNode errors = error.get("errors");
        assertTrue(errors.size() >= 4, errors.toString());
        for (JsonNode item : errors) {
            assertTrue(item.get("featureId").isTextual(), item.toString());
            assertTrue(item.get("field").isTextual(), item.toString());
            assertTrue(item.get("problem").isTextual(), item.toString());
        }
        assertTrue(errors.toString().contains("\"field\":\"flow_tph\""), errors.toString());
        mvc.perform(get("/api/v1/jobs/{id}/result", id)).andExpect(status().isConflict());
        assertFalse(Files.exists(DATA_DIR.resolve("jobs").resolve(id.toString()).resolve("result.geojson")));
    }

    @Test
    void storedErrorsAreCappedAndMessageKeepsTotal() throws Exception {
        StringBuilder body = new StringBuilder("{\"type\": \"FeatureCollection\", \"features\": [");
        for (int i = 0; i < 1500; i++) {
            body.append(i == 0 ? "" : ",").append("{\"type\": \"Feature\"}");
        }
        UUID id = submit(body.append("]}").toString().getBytes(StandardCharsets.UTF_8));
        JsonNode error = await(id, "FAILED").get("error");

        assertEquals(JobWorker.MAX_STORED_ERRORS, error.get("errors").size());
        assertTrue(error.get("message").asText().matches(".*: 1\\d{3}, показаны первые 1000"), error.get("message").asText());
    }

    @Test
    void pipelineCrashFailsJobWithMessage() throws Exception {
        when(pipeline.run(any())).thenThrow(new UnsupportedOperationException("Расчёт вариантов ещё не реализован"));
        UUID id = submit(Files.readAllBytes(SAMPLE));
        JsonNode error = await(id, "FAILED").get("error");

        assertTrue(error.get("message").asText().contains("Расчёт вариантов ещё не реализован"));
        assertEquals(0, error.get("errors").size());
    }

    @Test
    void unknownOrMalformedIdGives404() throws Exception {
        for (String path : List.of("/api/v1/jobs/{id}", "/api/v1/jobs/{id}/result", "/api/v1/jobs/{id}/input")) {
            for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
                mvc.perform(get(path, id))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.message", containsString("не найдена")))
                        .andExpect(jsonPath("$.errors").isEmpty());
            }
        }
    }

    @Test
    void unfinishedJobsFailAfterRestart() throws Exception {
        JobEntity running = new JobEntity();
        running.setId(UUID.randomUUID());
        running.setStatus(JobEntity.Status.RUNNING);
        running.setCreatedAt(Instant.now());
        running.setInputPath(SAMPLE.toAbsolutePath().toString());
        jobs.save(running);

        worker.failInterrupted();

        JsonNode job = MAPPER.readTree(mvc.perform(get("/api/v1/jobs/{id}", running.getId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("FAILED", job.get("status").asText());
        assertTrue(job.get("error").get("message").asText().contains("перезапуском"));
    }

    private static long jobDirs() throws Exception {
        Files.createDirectories(DATA_DIR.resolve("jobs"));
        try (Stream<Path> dirs = Files.list(DATA_DIR.resolve("jobs"))) {
            return dirs.count();
        }
    }

    private UUID submit(byte[] body) throws Exception {
        String response = mvc.perform(post("/api/v1/jobs").contentType(GEO_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(MAPPER.readTree(response).get("id").asText());
    }

    private JsonNode await(UUID id, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            String response = mvc.perform(get("/api/v1/jobs/{id}", id))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode job = MAPPER.readTree(response);
            String status = job.get("status").asText();
            if (status.equals(expected)) {
                return job;
            }
            assertTrue(List.of("QUEUED", "RUNNING").contains(status), "задача ушла в " + status + ": " + response);
            assertTrue(System.currentTimeMillis() < deadline, "задача не дошла до " + expected + ": " + response);
            Thread.sleep(100);
        }
    }

    // Нет flow_tph у ОКС, дробный диаметр камеры, повтор id и ссылка на несуществующий ОКС.
    private static byte[] brokenSample() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(SAMPLE.toFile());
        ArrayNode features = (ArrayNode) root.get("features");
        ObjectNode oks = properties(features, "oks_future");
        oks.remove("flow_tph");
        properties(features, "heat_chamber").put("diameter", 12.5);
        properties(features, "oks_connection_point").put("oks_id", "oks-missing");
        features.add(features.get(0).deepCopy());
        return MAPPER.writeValueAsBytes(root);
    }

    private static ObjectNode properties(ArrayNode features, String objectType) {
        for (JsonNode feature : features) {
            if (objectType.equals(feature.get("properties").get("object_type").asText())) {
                return (ObjectNode) feature.get("properties");
            }
        }
        throw new IllegalArgumentException(objectType);
    }

    private static Result oneVariant() {
        VariantSummary summary = new VariantSummary("summary_1", "1", 1, 25_000_000, 3_000_000, 5_000_000, 0, 0, 0,
                33_000_000, 340.5, 0, 340.5, 1.946, List.of());
        return new Result(List.of(new Variant("1", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), summary)));
    }

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("heatnet-api-it");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
