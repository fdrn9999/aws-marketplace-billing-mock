package io.github.fdrn9999.marketplace.support;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.store.DemoDataService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 포트로 서버를 띄우고 HTTP로 검증하는 통합 테스트의 공통 기반.
 * 매 테스트 전에 시계와 시드 데이터를 초기화한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class HttpTestSupport {

    @LocalServerPort
    protected int port;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    protected DemoDataService demoData;
    @Autowired
    protected SimulatedClock clock;

    protected RestClient http;

    public record Response(int status, JsonNode body, HttpHeaders headers) {

        public String text(String path) {
            JsonNode node = body.at(path);
            return node.isMissingNode() || node.isNull() ? null : node.asString();
        }
    }

    @BeforeEach
    void setUpHttp() {
        demoData.reset();
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    protected Response post(String path, Object body) {
        return post(path, body, Map.of());
    }

    protected Response post(String path, Object body, Map<String, String> headers) {
        RestClient.RequestBodySpec spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        headers.forEach(spec::header);
        if (body != null) {
            spec.body(body instanceof String s ? s : jsonMapper.writeValueAsString(body));
        }
        return toResponse(spec.retrieve().toEntity(String.class));
    }

    protected Response put(String path, Object body) {
        return toResponse(http.put().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(body)).retrieve().toEntity(String.class));
    }

    protected Response get(String path) {
        return get(path, Map.of());
    }

    protected Response get(String path, Map<String, String> headers) {
        RestClient.RequestHeadersSpec<?> spec = http.get().uri(path);
        headers.forEach(spec::header);
        return toResponse(spec.retrieve().toEntity(String.class));
    }

    protected Response toResponse(ResponseEntity<String> entity) {
        String raw = entity.getBody();
        JsonNode body = raw == null || raw.isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(raw);
        return new Response(entity.getStatusCode().value(), body, entity.getHeaders());
    }

    protected static Map<String, String> customer(String subscriberId) {
        return Map.of("X-Customer-Id", subscriberId);
    }
}
