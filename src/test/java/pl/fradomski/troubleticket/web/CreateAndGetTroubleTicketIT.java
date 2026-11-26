package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.fradomski.troubleticket.TestJwt.READ;
import static pl.fradomski.troubleticket.TestJwt.TENANT_A;
import static pl.fradomski.troubleticket.TestJwt.TENANT_B;
import static pl.fradomski.troubleticket.TestJwt.WRITE;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.application.SozGateway;
import pl.fradomski.troubleticket.application.SozGateway.Registration;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /troubleTicket} and {@code GET /troubleTicket/{id}} over real HTTP with PostgreSQL
 * (README §2, §3). SOZ is a mock so each test decides how it answers.
 *
 * <p>Services come from the catalog seed in {@code application.yml}. Tickets get a random
 * {@code externalId}, so tests do not see each other's data.
 */
@IntegrationTest
@TestPropertySource(properties = "troubleticket.soz.register-timeout=300ms")
class CreateAndGetTroubleTicketIT {

    private static final long SERVICE_A = 987654321L;
    private static final long INACTIVE_SERVICE_A = 987654329L;
    private static final long SERVICE_B = 987654331L;
    private static final long UNKNOWN_SERVICE = 999L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SozGateway soz;

    private final String externalId = "EXT-" + UUID.randomUUID();

    @BeforeEach
    void sozAcknowledges() {
        when(soz.register(any())).thenReturn(Registration.ACKNOWLEDGED);
    }

    // --- create -----------------------------------------------------------------------------

    @Test
    void createReturns201WithTheTicketAndItsLocation() throws Exception {
        Instant before = Instant.now();
        var response = create(TENANT_A, createBody(externalId, SERVICE_A));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        String id = body.get("id").asString();
        assertThat(id).matches("TT-\\d{4}-\\d{6,}");
        assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/troubleTicket/" + id);
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder("id", "externalId", "serviceId", "description", "status", "notes");
        assertThat(body.get("externalId").asString()).isEqualTo(externalId);
        assertThat(body.get("serviceId").asLong()).isEqualTo(SERVICE_A);
        assertThat(body.get("description").asString()).isEqualTo("No data transmission");
        assertThat(body.get("status").asString()).isEqualTo("acknowledged");

        // The create request's note is the first note.
        assertThat(body.get("notes")).hasSize(1);
        JsonNode note = body.get("notes").get(0);
        assertThat(note.propertyNames()).containsExactlyInAnyOrder("id", "text", "date");
        assertThat(note.get("id").asString()).matches("NOTE-\\d{6,}");
        assertThat(note.get("text").asString()).isEqualTo("Created by the partner's API account");
        assertThat(Instant.parse(note.get("date").asString())).isBetween(before.minusSeconds(1), Instant.now());
        assertThat(note.get("date").asString()).endsWith("Z");
    }

    @Test
    void getReturnsTheSameRepresentationAsCreate() throws Exception {
        var created = create(TENANT_A, createBody(externalId, SERVICE_A));

        var fetched = get(created.headers().firstValue("Location").orElseThrow(), TENANT_A);

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(json.readTree(fetched.body())).isEqualTo(json.readTree(created.body()));
    }

    @Test
    void sozIsCalledOnlyAfterTheTicketIsCommitted() throws Exception {
        AtomicLong visibleRows = new AtomicLong(-1);
        when(soz.register(any())).thenAnswer(call -> {
            TroubleTicket ticket = call.getArgument(0);
            // Another connection sees the row only if the insert has committed.
            visibleRows.set(jdbc.sql("select count(*) from trouble_ticket where id = ?")
                    .param(ticket.id()).query(Long.class).single());
            return Registration.ACKNOWLEDGED;
        });

        assertThat(create(TENANT_A, createBody(externalId, SERVICE_A)).statusCode()).isEqualTo(201);
        assertThat(visibleRows).hasValue(1);
    }

    @Test
    void pendingSozRegistrationLeavesTheTicketNew() throws Exception {
        when(soz.register(any())).thenReturn(Registration.PENDING);

        assertCreatedAsNew();
    }

    @Test
    void failingSozLeavesTheTicketNew() throws Exception {
        when(soz.register(any())).thenThrow(new IllegalStateException("SOZ is down"));

        assertCreatedAsNew();
    }

    @Test
    void slowSozIsNotAwaitedBeyondTheTimeout() throws Exception {
        when(soz.register(any())).thenAnswer(call -> {
            Thread.sleep(Duration.ofSeconds(5));
            return Registration.ACKNOWLEDGED;
        });

        long start = System.nanoTime();
        assertCreatedAsNew();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void acknowledgementDoesNotOverrideAStatusSetMeanwhile() throws Exception {
        when(soz.register(any())).thenAnswer(call -> {
            TroubleTicket ticket = call.getArgument(0);
            // E.g. the client closed the ticket before SOZ answered.
            jdbc.sql("update trouble_ticket set status = 'CLOSED', version = version + 1 where id = ?")
                    .param(ticket.id()).update();
            return Registration.ACKNOWLEDGED;
        });

        var response = create(TENANT_A, createBody(externalId, SERVICE_A));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("closed");
        assertThat(storedStatus()).isEqualTo("CLOSED");
    }

    private void assertCreatedAsNew() throws Exception {
        var response = create(TENANT_A, createBody(externalId, SERVICE_A));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("new");
        assertThat(storedStatus()).isEqualTo("NEW");
        assertThat(json.readTree(get("/api/v1/troubleTicket/" + body.get("id").asString(), TENANT_A).body())
                .get("status").asString()).isEqualTo("new");
    }

    // --- create: service check --------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(textBlock = """
            unknown service,         999
            inactive service,        987654329
            other tenant's service,  987654331
            """)
    void serviceNotAvailableToTheCallerIs404(String name, long serviceId) throws Exception {
        var response = create(TENANT_A, createBody(externalId, serviceId));

        assertError(response, 404, "SERVICE_NOT_FOUND",
                "Service does not exist, is not active or is not available to the caller");
        assertNothingStoredOrSent();
    }

    @Test
    void serviceOfTheCallersTenantIsAccepted() throws Exception {
        assertThat(create(TENANT_B, createBody(externalId, SERVICE_B)).statusCode()).isEqualTo(201);
        assertThat(create(TENANT_A, createBody("EXT-" + UUID.randomUUID(), SERVICE_B)).statusCode()).isEqualTo(404);
        assertThat(create(TENANT_A, createBody("EXT-" + UUID.randomUUID(), INACTIVE_SERVICE_A)).statusCode())
                .isEqualTo(404);
        assertThat(create(TENANT_A, createBody("EXT-" + UUID.randomUUID(), UNKNOWN_SERVICE)).statusCode())
                .isEqualTo(404);
    }

    // --- create: validation -----------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            missing field     | {"serviceId":987654321,"description":"d","status":"new","note":"n"}                       | Field 'externalId' is required
            unknown field     | {"externalId":"%s","serviceId":987654321,"description":"d","status":"new","note":"n","x":1} | Unknown field 'x'
            status closed     | {"externalId":"%s","serviceId":987654321,"description":"d","status":"closed","note":"n"}  | Field 'status' has an invalid value
            serviceId zero    | {"externalId":"%s","serviceId":0,"description":"d","status":"new","note":"n"}            | Field 'serviceId' is below the allowed minimum
            empty string      | {"externalId":"%s","serviceId":987654321,"description":"d","status":"new","note":""}      | Field 'note' must not be empty
            wrong type        | {"externalId":"%s","serviceId":true,"description":"d","status":"new","note":"n"}         | Field 'serviceId' has an invalid value
            blank description | {"externalId":"%s","serviceId":987654321,"description":"   ","status":"new","note":"n"}  | Field 'description' must not be blank
            blank note        | {"externalId":"%s","serviceId":987654321,"description":"d","status":"new","note":" "}     | Field 'note' must not be blank
            """)
    void invalidCreateRequestIs400(String name, String body, String message) throws Exception {
        assertError(create(TENANT_A, body.formatted(externalId)), 400, "VALIDATION_ERROR", message);
        assertNothingStoredOrSent();
    }

    @Test
    void overlongExternalIdIs400() throws Exception {
        String tooLong = "E".repeat(TroubleTicket.MAX_EXTERNAL_ID_LENGTH + 1);

        assertError(create(TENANT_A, createBody(tooLong, SERVICE_A)), 400, "VALIDATION_ERROR",
                "Field 'externalId' must be at most 255 characters");
    }

    // --- get --------------------------------------------------------------------------------

    @Test
    void anotherTenantsTicketLooksExactlyLikeAMissingOne() throws Exception {
        var created = create(TENANT_A, createBody(externalId, SERVICE_A));
        String location = created.headers().firstValue("Location").orElseThrow();

        var otherTenant = get(location, TENANT_B);
        var missing = get("/api/v1/troubleTicket/TT-2026-999999", TENANT_B);

        String message = "Trouble ticket does not exist or is not available to the caller";
        assertError(otherTenant, 404, "TROUBLE_TICKET_NOT_FOUND", message);
        assertError(missing, 404, "TROUBLE_TICKET_NOT_FOUND", message);
        assertThat(otherTenant.headers().map().keySet()).isEqualTo(missing.headers().map().keySet());
    }

    @Test
    void getNotesOldestFirst() throws Exception {
        var created = json.readTree(create(TENANT_A, createBody(externalId, SERVICE_A)).body());
        String id = created.get("id").asString();
        jdbc.sql("insert into note (id, ticket_id, text, created_at) values ('NOTE-T-' || ?, ?, 'later', now() + interval '1 minute')")
                .param(UUID.randomUUID().toString().substring(0, 8)).param(id).update();

        JsonNode notes = json.readTree(get("/api/v1/troubleTicket/" + id, TENANT_A).body()).get("notes");

        assertThat(notes.valueStream().map(n -> n.get("text").asString()))
                .containsExactly("Created by the partner's API account", "later");
    }

    // --- helpers ----------------------------------------------------------------------------

    private static String createBody(String externalId, long serviceId) {
        return """
                {"externalId":"%s","serviceId":%d,"description":"No data transmission","status":"new",\
                "note":"Created by the partner's API account"}""".formatted(externalId, serviceId);
    }

    private String storedStatus() {
        return jdbc.sql("select status from trouble_ticket where external_id = ?")
                .param(externalId).query(String.class).single();
    }

    private void assertNothingStoredOrSent() {
        assertThat(jdbc.sql("select count(*) from trouble_ticket where external_id = ?")
                .param(externalId).query(Long.class).single()).isZero();
        verify(soz, never()).register(any());
    }

    private void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isEqualTo(message);
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    }

    private HttpResponse<String> create(String tenant, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri("/api/v1/troubleTicket"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), tenant);
    }

    private HttpResponse<String> get(String path, String tenant) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET(), tenant);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String tenant)
            throws IOException, InterruptedException {
        request.header("Authorization", "Bearer " + TestJwt.token(tenant, READ, WRITE));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
