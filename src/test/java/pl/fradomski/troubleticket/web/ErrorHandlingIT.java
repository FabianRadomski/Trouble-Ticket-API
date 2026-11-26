package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.api.model.TroubleTicketCreateRequest;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every error response has the spec's {@code Error} shape with the request ID (README §8, §9).
 * Test-only controllers trigger each exception type through real HTTP. Requests carry a valid
 * token, so security lets them through to MVC (security errors: {@code SecurityIT}).
 */
@IntegrationTest
@Import(ErrorHandlingIT.TestControllers.class)
class ErrorHandlingIT {

    private static final String VALID_BODY = """
            {"externalId":"OK-1","serviceId":1,"description":"d","status":"new","note":"n"}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void validBodyPassesThrough() throws Exception {
        assertThat(post("/test/body", VALID_BODY).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            malformed JSON     | {"externalId":                                                                   | Malformed JSON request body
            empty body         | ''                                                                               | Request body is missing or unreadable
            unknown field      | {"externalId":"OK-1","serviceId":1,"description":"d","status":"new","note":"n","foo":1} | Unknown field 'foo'
            disallowed status  | {"externalId":"OK-1","serviceId":1,"description":"d","status":"closed","note":"n"} | Field 'status' has an invalid value
            wrong type         | {"externalId":"OK-1","serviceId":"abc","description":"d","status":"new","note":"n"} | Field 'serviceId' has an invalid value
            missing field      | {"serviceId":1,"description":"d","status":"new","note":"n"}                       | Field 'externalId' is required
            empty string       | {"externalId":"OK-1","serviceId":1,"description":"","status":"new","note":"n"}    | Field 'description' must not be empty
            below minimum      | {"externalId":"OK-1","serviceId":0,"description":"d","status":"new","note":"n"}   | Field 'serviceId' is below the allowed minimum
            """)
    void invalidBodyIs400(String name, String body, String message) throws Exception {
        assertError(post("/test/body", body), 400, "VALIDATION_ERROR", message);
    }

    @Test
    void parameterConstraintViaHandlerMethodValidationIs400() throws Exception {
        assertError(get("/test/param/ab"), 400, "VALIDATION_ERROR", "Parameter 'id' must not be empty");
    }

    @Test
    void parameterConstraintViaValidatedProxyIs400() throws Exception {
        assertError(get("/test/validated/ab"), 400, "VALIDATION_ERROR", "Parameter 'id' must not be empty");
    }

    @Test
    void unsupportedMediaTypeIs415() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/test/body"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(VALID_BODY)));
        assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Content type must be application/json");
    }

    @Test
    void notAcceptableIs406StillWithJsonBody() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/test/param/abc")).header("Accept", "text/plain").GET());
        assertError(response, 406, "NOT_ACCEPTABLE", "Only application/json responses are available");
    }

    @Test
    void wrongMethodIs405WithAllowHeader() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/test/body"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(VALID_BODY)));
        assertError(response, 405, "METHOD_NOT_ALLOWED", "Method PUT is not supported for this path");
        assertThat(response.headers().firstValue("Allow")).hasValue("POST");
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertError(get("/no/such/path"), 404, "NOT_FOUND", "No resource at this path");
    }

    @Test
    void apiPathsNeedThePrefix() throws Exception {
        assertError(get("/troubleTicket"), 404, "NOT_FOUND", "No resource at this path");
    }

    @Test
    void domainValidationErrorIs400() throws Exception {
        assertError(get("/test/blank-description"), 400, "VALIDATION_ERROR", "Field 'description' must not be blank");
    }

    @Test
    void unexpectedExceptionIs500WithoutInternals() throws Exception {
        var response = get("/test/boom");
        assertError(response, 500, "INTERNAL_ERROR", "Unexpected error. Quote the requestId when reporting it.");
        assertThat(response.body()).doesNotContain("secret");
    }

    @Test
    void requestIdIsFreshUuidAndIgnoresIncomingHeader() throws Exception {
        var first = send(HttpRequest.newBuilder(uri("/no/such/path")).header("X-Request-Id", "from-client").GET());
        var second = get("/no/such/path");

        String firstId = first.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(UUID.fromString(firstId).toString()).isEqualTo(firstId);
        assertThat(firstId).isNotEqualTo("from-client")
                .isNotEqualTo(second.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    }

    @Test
    void successfulResponsesAlsoCarryRequestId() throws Exception {
        assertThat(get("/actuator/health").headers().firstValue(RequestIdFilter.HEADER)).isPresent();
    }

    private void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(MediaType.parseMediaType(ct).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isEqualTo(message);
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        request.header("Authorization", "Bearer " + TestJwt.fullAccess());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({TestControllers.Plain.class, TestControllers.ValidatedController.class})
    static class TestControllers {

        @RestController
        static class Plain {

            @PostMapping(path = "/test/body", consumes = "application/json", produces = "application/json")
            Map<String, String> body(@Valid @RequestBody TroubleTicketCreateRequest request) {
                return Map.of("ok", "true");
            }

            @GetMapping(path = "/test/param/{id}", produces = "application/json")
            Map<String, String> param(@Size(min = 3) @PathVariable("id") String id) {
                return Map.of("id", id);
            }

            @GetMapping(path = "/test/boom", produces = "application/json")
            Map<String, String> boom() {
                throw new IllegalStateException("secret internals");
            }

            /** Whitespace-only passes the schema; the domain rejects it (README §8). */
            @GetMapping(path = "/test/blank-description", produces = "application/json")
            Map<String, String> blankDescription() {
                new TroubleTicket("TT-2026-000001", "tenant-a", "EXT-1", 1, "   ", Instant.now());
                return Map.of();
            }
        }

        /** Like the generated interfaces: class-level {@code @Validated}. */
        @RestController
        @Validated
        static class ValidatedController {

            @GetMapping(path = "/test/validated/{id}", produces = "application/json")
            Map<String, String> param(@Size(min = 3) @PathVariable("id") String id) {
                return Map.of("id", id);
            }
        }
    }
}
