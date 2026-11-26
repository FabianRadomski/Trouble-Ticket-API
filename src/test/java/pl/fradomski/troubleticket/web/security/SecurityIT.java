package pl.fradomski.troubleticket.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.fradomski.troubleticket.TestJwt.READ;
import static pl.fradomski.troubleticket.TestJwt.TENANT_A;
import static pl.fradomski.troubleticket.TestJwt.TENANT_B;
import static pl.fradomski.troubleticket.TestJwt.WRITE;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.web.RequestIdFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authentication, scopes and tenant extraction (README §1) through real HTTP. A probe controller
 * under {@code /api/v1/troubleTicket/{id}/probe} (a path the API itself does not use) stands in for
 * the ticket endpoints, so the rules are tested against real handlers.
 */
@IntegrationTest
@Import(SecurityIT.Probe.class)
class SecurityIT {

    private static final String PROBE = "/api/v1/troubleTicket/TT-1/probe";
    private static final String CREATE_BODY = """
            {"externalId":"X-1","serviceId":1,"description":"d","status":"new","note":"n"}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    // --- 401 --------------------------------------------------------------------------------

    @Test
    void missingTokenIs401() throws Exception {
        var response = send("GET", PROBE, null);
        assertError(response, 401, "UNAUTHORIZED", "Bearer token is required");
        // RFC 6750 challenge; Spring Security 7 adds an RFC 9728 resource_metadata link.
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(h -> assertThat(h).startsWith("Bearer").doesNotContain("error="));
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                Arguments.of("garbage", "not-a-jwt"),
                Arguments.of("expired", TestJwt.expired(TENANT_A, READ, WRITE)),
                Arguments.of("signed with another key", TestJwt.signedWithOtherKey(TENANT_A, READ, WRITE)),
                Arguments.of("no tenant_id", TestJwt.withoutTenant(READ, WRITE)),
                Arguments.of("blank tenant_id", TestJwt.withBlankTenant(READ, WRITE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void invalidTokenIs401(String name, String token) throws Exception {
        var response = send("GET", PROBE, token);
        assertError(response, 401, "UNAUTHORIZED", "Bearer token is invalid or expired");
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(h -> assertThat(h).startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    void authenticationRunsBeforeBodyValidation() throws Exception {
        assertError(send("POST", "/api/v1/troubleTicket", "{not json", null), 401, "UNAUTHORIZED",
                "Bearer token is required");
    }

    @Test
    void unknownPathWithoutTokenIs401() throws Exception {
        assertThat(send("GET", "/no/such/path", null).statusCode()).isEqualTo(401);
    }

    // --- scopes -----------------------------------------------------------------------------

    @Test
    void readScopeAllowsGet() throws Exception {
        assertThat(send("GET", PROBE, TestJwt.token(TENANT_A, READ)).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void writeScopeAllowsPostAndPatch(String method) throws Exception {
        assertThat(send(method, PROBE, "{}", TestJwt.token(TENANT_A, WRITE)).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void readingNeedsReadScope(String method) throws Exception {
        var response = send(method, PROBE, TestJwt.token(TENANT_A, WRITE));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(h -> assertThat(h).contains("insufficient_scope"));
        if (!method.equals("HEAD")) {
            assertError(response, 403, "FORBIDDEN", "Token lacks the permission for this operation");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void writingNeedsWriteScope(String method) throws Exception {
        assertError(send(method, PROBE, "{}", TestJwt.token(TENANT_A, READ)), 403, "FORBIDDEN",
                "Token lacks the permission for this operation");
    }

    @Test
    void createWithReadOnlyTokenIs403BeforeBodyIsRead() throws Exception {
        assertError(send("POST", "/api/v1/troubleTicket", CREATE_BODY, TestJwt.token(TENANT_A, READ)), 403,
                "FORBIDDEN", "Token lacks the permission for this operation");
    }

    @Test
    void tokenWithoutScopesIsForbiddenOnTicketPaths() throws Exception {
        assertThat(send("GET", PROBE, TestJwt.token(TENANT_A)).statusCode()).isEqualTo(403);
    }

    // --- other paths ------------------------------------------------------------------------

    @Test
    void healthIsPublic() throws Exception {
        assertThat(send("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void otherActuatorEndpointsAreDeniedEvenWithFullToken() throws Exception {
        assertError(send("GET", "/actuator/env", TestJwt.fullAccess()), 403, "FORBIDDEN",
                "Token lacks the permission for this operation");
        assertThat(send("GET", "/actuator/env", null).statusCode()).isEqualTo(401);
    }

    @Test
    void unknownPathWithValidTokenIs404NotForbidden() throws Exception {
        assertThat(send("GET", "/no/such/path", TestJwt.token(TENANT_A)).statusCode()).isEqualTo(404);
    }

    @Test
    void unsupportedMethodWithValidTokenIs405NotForbidden() throws Exception {
        var response = send("DELETE", PROBE, TestJwt.fullAccess());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    // --- tenant -----------------------------------------------------------------------------

    @Test
    void tenantComesFromToken() throws Exception {
        assertThat(json.readTree(send("GET", PROBE, TestJwt.token(TENANT_A, READ)).body())
                .get("tenant").asString()).isEqualTo(TENANT_A);
        assertThat(json.readTree(send("GET", PROBE, TestJwt.token(TENANT_B, READ)).body())
                .get("tenant").asString()).isEqualTo(TENANT_B);
    }

    @Test
    void protectedResourceMetadataIsPublicAndAccurate() throws Exception {
        var response = send("GET", "/.well-known/oauth-protected-resource", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode metadata = json.readTree(response.body());
        assertThat(metadata.get("scopes_supported").valueStream().map(JsonNode::asString))
                .containsExactly(READ, WRITE);
        assertThat(metadata.get("tls_client_certificate_bound_access_tokens").asBoolean()).isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------

    private void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isEqualTo(message);
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    }

    private HttpResponse<String> send(String method, String path, String token)
            throws IOException, InterruptedException {
        return send(method, path, null, token);
    }

    private HttpResponse<String> send(String method, String path, String body, String token)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(Probe.Controller.class)
    static class Probe {

        @RestController
        static class Controller {

            private final CurrentTenant currentTenant;

            Controller(CurrentTenant currentTenant) {
                this.currentTenant = currentTenant;
            }

            @GetMapping(path = PROBE_PATH, produces = "application/json")
            Map<String, String> get() {
                return Map.of("tenant", currentTenant.id());
            }

            @PostMapping(path = PROBE_PATH, produces = "application/json")
            Map<String, String> post() {
                return Map.of("tenant", currentTenant.id());
            }

            @PatchMapping(path = PROBE_PATH, produces = "application/json")
            Map<String, String> patch() {
                return Map.of("tenant", currentTenant.id());
            }
        }

        static final String PROBE_PATH = "/api/v1/troubleTicket/{id}/probe";
    }
}
