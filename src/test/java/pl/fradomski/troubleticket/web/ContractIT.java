package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static pl.fradomski.troubleticket.TestJwt.READ;
import static pl.fradomski.troubleticket.TestJwt.TENANT_A;
import static pl.fradomski.troubleticket.TestJwt.TENANT_B;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.application.SozGateway;
import pl.fradomski.troubleticket.application.SozGateway.Registration;

/**
 * Validates real HTTP exchanges against {@code devtask/trouble-ticket-api.yaml} (README §11).
 *
 * <p>The generator only checks that controllers match the spec's operations. This test catches
 * what it can't: a response that misses a required field, has an extra one, uses a wrong status
 * value or an undeclared status code or header. Every operation and every response the spec
 * declares is exercised once.
 */
@IntegrationTest
class ContractIT {

    private static final String SPEC = "devtask/trouble-ticket-api.yaml";
    private static final String BASE = "/api/v1";
    private static final long SERVICE_A = 987654321L;

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(SPEC)
            .withBasePathOverride(BASE)
            .build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String externalId = "EXT-" + UUID.randomUUID();

    @LocalServerPort
    int port;

    @MockitoBean
    SozGateway soz;

    @BeforeEach
    void sozAcknowledges() {
        when(soz.register(any())).thenReturn(Registration.ACKNOWLEDGED);
    }

    @Test
    void validatorRejectsAResponseThatBreaksTheContract() {
        // Guards against a validator that silently accepts everything (e.g. unsupported 3.1 schemas).
        var response = SimpleResponse.Builder.ok()
                .withContentType("application/json")
                .withBody("{\"id\":\"TT-2026-000001\",\"externalId\":\"E\",\"serviceId\":1,"
                        + "\"description\":\"d\",\"status\":\"done\",\"notes\":[],\"extra\":1}")
                .build();

        ValidationReport report = VALIDATOR.validateResponse(BASE + "/troubleTicket/TT-2026-000001",
                Request.Method.GET, response);

        assertThat(report.getMessages()).extracting(ValidationReport.Message::getMessage)
                .anyMatch(m -> m.contains("enumeration"))
                .anyMatch(m -> m.contains("extra"));
    }

    @Test
    void createReplayGetListNoteAndCloseMatchTheContract() {
        String token = TestJwt.fullAccess();

        var created = exchange("POST", "/troubleTicket", token, createBody(), 201);
        String location = created.headers().firstValue("Location").orElseThrow();
        String id = location.substring(location.lastIndexOf('/') + 1);

        exchange("POST", "/troubleTicket", token, createBody(), 200);
        exchange("GET", "/troubleTicket", token, null, 200);
        exchange("GET", "/troubleTicket/" + id, token, null, 200);
        exchange("POST", "/troubleTicket/" + id + "/note", token, "{\"text\":\"Still broken\"}", 201);
        exchange("PATCH", "/troubleTicket/" + id, token, "{\"status\":\"closed\"}", 200);
        // The closed ticket, with both notes, still matches the schema.
        exchange("GET", "/troubleTicket/" + id, token, null, 200);
    }

    @Test
    void errorResponsesMatchTheContract() {
        String token = TestJwt.fullAccess();
        String missing = "/troubleTicket/TT-1900-000000";

        exchange("POST", "/troubleTicket", token,
                createBody().replace(String.valueOf(SERVICE_A), "999"), 404);
        exchange("GET", missing, token, null, 404);
        exchange("PATCH", missing, token, "{\"status\":\"closed\"}", 404);
        exchange("POST", missing + "/note", token, "{\"text\":\"x\"}", 404);
        exchange("GET", "/troubleTicket", TestJwt.token(TENANT_B), null, 403);
        exchange("POST", "/troubleTicket", TestJwt.token(TENANT_A, READ), createBody(), 403);

        // The requests below break the contract on purpose, so only the response is checked.
        exchangeCheckingResponseOnly("POST", "/troubleTicket", token,
                createBody().replace("\"new\"", "\"closed\""), 400);
        exchangeCheckingResponseOnly("PATCH", missing, token, "{\"status\":\"resolved\"}", 400);
        exchangeCheckingResponseOnly("POST", missing + "/note", token, "{}", 400);
        exchangeCheckingResponseOnly("GET", "/troubleTicket", null, null, 401);
        exchangeCheckingResponseOnly("GET", "/troubleTicket", "not-a-jwt", null, 401);
    }

    // --- helpers -----------------------------------------------------------------------------

    private String createBody() {
        return """
                {"externalId":"%s","serviceId":%d,"description":"No data transmission",
                 "status":"new","note":"Created by the partner's API account"}"""
                .formatted(externalId, SERVICE_A);
    }

    /** Sends the request and checks both it and the response against the spec. */
    private HttpResponse<String> exchange(String method, String path, String token, String body, int status) {
        var request = new SimpleRequest.Builder(method, BASE + path)
                .withAccept("application/json");
        if (body != null) {
            request.withContentType("application/json").withBody(body);
        }
        if (token != null) {
            request.withAuthorization("Bearer " + token);
        }
        var response = send(method, path, token, body, status);
        assertValid(VALIDATOR.validate(request.build(), toValidatorResponse(response)));
        return response;
    }

    private void exchangeCheckingResponseOnly(String method, String path, String token, String body, int status) {
        var response = send(method, path, token, body, status);
        assertValid(VALIDATOR.validateResponse(BASE + path, Request.Method.valueOf(method),
                toValidatorResponse(response)));
    }

    private HttpResponse<String> send(String method, String path, String token, String body, int status) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE + path))
                .header("Accept", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            var response = http.send(builder.build(), BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
            return response;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.atlassian.oai.validator.model.Response toValidatorResponse(HttpResponse<String> response) {
        var builder = new SimpleResponse.Builder(response.statusCode()).withBody(response.body());
        response.headers().map().forEach(builder::withHeader);
        return builder.build();
    }

    private static void assertValid(ValidationReport report) {
        assertThat(report.getMessages()).as("contract violations").isEmpty();
    }
}
