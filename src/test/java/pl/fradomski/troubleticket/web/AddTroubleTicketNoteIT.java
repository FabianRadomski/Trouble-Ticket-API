package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.fradomski.troubleticket.TestJwt.READ;
import static pl.fradomski.troubleticket.TestJwt.WRITE;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.application.IdGenerator;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /troubleTicket/{id}/note} over real HTTP with PostgreSQL (README §6).
 *
 * <p>The ticket is saved through the repository, so the test chooses its status. Each test uses
 * its own random tenant.
 */
@IntegrationTest
class AddTroubleTicketNoteIT {

    /** Before any note the API adds, which takes the real clock. */
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final String NOTE_BODY = """
            {"text":"Customer confirmed the router was restarted"}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    TroubleTicketRepository repository;

    @Autowired
    IdGenerator ids;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    private final String tenant = "tenant-" + UUID.randomUUID();
    private String ticketId;

    @BeforeEach
    void saveTicket() {
        TroubleTicket ticket = new TroubleTicket(ids.nextTicketId(CREATED_AT), tenant, "EXT-1", 987654321L,
                "Internet down", CREATED_AT);
        ticket.addNote(ids.nextNoteId(), "first note", CREATED_AT);
        tx.executeWithoutResult(s -> repository.save(ticket));
        ticketId = ticket.id();
    }

    @Test
    void returns201WithTheNoteAndNoLocation() throws Exception {
        Instant before = Instant.now();
        var response = addNote(ticketId, tenant, NOTE_BODY);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        JsonNode note = json.readTree(response.body());
        assertThat(note.propertyNames()).containsExactlyInAnyOrder("id", "text", "date");
        assertThat(note.get("id").asString()).matches("NOTE-\\d{6,}");
        assertThat(note.get("text").asString()).isEqualTo("Customer confirmed the router was restarted");
        assertThat(Instant.parse(note.get("date").asString())).isBetween(before.minusSeconds(1), Instant.now());
        assertThat(note.get("date").asString()).endsWith("Z");
    }

    @Test
    void theTicketShowsTheNoteLastAndIsOtherwiseUnchanged() throws Exception {
        var ticketBefore = ticketRow();
        JsonNode added = json.readTree(addNote(ticketId, tenant, NOTE_BODY).body());

        JsonNode ticket = json.readTree(get(ticketId, tenant).body());

        assertThat(ticket.get("notes").valueStream().map(n -> n.get("text").asString()))
                .containsExactly("first note", "Customer confirmed the router was restarted");
        assertThat(ticket.get("notes").get(1)).isEqualTo(added);
        assertThat(ticket.get("status").asString()).isEqualTo("new");
        // Status, updatedAt and version untouched: a note never conflicts with a status change.
        assertThat(ticketRow()).isEqualTo(ticketBefore);
    }

    @Test
    void closedTicketAcceptsNotes() throws Exception {
        jdbc.sql("update trouble_ticket set status = 'CLOSED' where id = ?").param(ticketId).update();

        assertThat(addNote(ticketId, tenant, NOTE_BODY).statusCode()).isEqualTo(201);

        JsonNode ticket = json.readTree(get(ticketId, tenant).body());
        assertThat(ticket.get("status").asString()).isEqualTo("closed");
        assertThat(ticket.get("notes")).hasSize(2);
    }

    @Test
    void concurrentNotesAreAllStored() throws Exception {
        int threads = 5;
        var barrier = new CyclicBarrier(threads);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                String body = "{\"text\":\"note " + i + "\"}";
                statuses.add(executor.submit(() -> {
                    barrier.await();
                    return addNote(ticketId, tenant, body).statusCode();
                }));
            }
        }

        for (Future<Integer> status : statuses) {
            assertThat(status.get()).isEqualTo(201);
        }
        assertThat(json.readTree(get(ticketId, tenant).body()).get("notes")).hasSize(1 + threads);
    }

    @Test
    void anotherTenantsTicketLooksExactlyLikeAMissingOne() throws Exception {
        String otherTenant = "other-" + tenant;

        var otherTenants = addNote(ticketId, otherTenant, NOTE_BODY);
        var missing = addNote("TT-2026-999999", otherTenant, NOTE_BODY);

        String message = "Trouble ticket does not exist or is not available to the caller";
        assertError(otherTenants, 404, "TROUBLE_TICKET_NOT_FOUND", message);
        assertError(missing, 404, "TROUBLE_TICKET_NOT_FOUND", message);
        assertThat(otherTenants.headers().map().keySet()).isEqualTo(missing.headers().map().keySet());
        assertOnlyTheFirstNoteStored();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            missing text  | {}                    | Field 'text' is required
            empty text    | {"text":""}           | Field 'text' must not be empty
            blank text    | {"text":"  "}         | Field 'text' must not be blank
            unknown field | {"text":"t","x":1}    | Unknown field 'x'
            wrong type    | {"text":["t"]}        | Field 'text' has an invalid value
            """)
    void invalidNoteIs400(String name, String body, String message) throws Exception {
        assertError(addNote(ticketId, tenant, body), 400, "VALIDATION_ERROR", message);
        assertOnlyTheFirstNoteStored();
    }

    @Test
    void readOnlyTokenIs403() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/troubleTicket/" + ticketId + "/note"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestJwt.token(tenant, READ))
                .POST(HttpRequest.BodyPublishers.ofString(NOTE_BODY))
                .build();

        assertError(http.send(request, HttpResponse.BodyHandlers.ofString()), 403, "FORBIDDEN",
                "Token lacks the permission for this operation");
        assertOnlyTheFirstNoteStored();
    }

    // --- helpers ----------------------------------------------------------------------------

    private List<Object> ticketRow() {
        return jdbc.sql("select status, updated_at, version from trouble_ticket where id = ?")
                .param(ticketId)
                .query((rs, n) -> List.<Object>of(rs.getString(1), rs.getTimestamp(2), rs.getLong(3)))
                .single();
    }

    private void assertOnlyTheFirstNoteStored() {
        assertThat(jdbc.sql("select count(*) from note where ticket_id = ?").param(ticketId)
                .query(Long.class).single()).isEqualTo(1);
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

    private HttpResponse<String> addNote(String id, String tenantId, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/troubleTicket/" + id + "/note"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestJwt.token(tenantId, READ, WRITE))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String id, String tenantId) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/troubleTicket/" + id))
                .header("Authorization", "Bearer " + TestJwt.token(tenantId, READ))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
