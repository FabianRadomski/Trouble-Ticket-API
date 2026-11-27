package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static pl.fradomski.troubleticket.TestJwt.READ;
import static pl.fradomski.troubleticket.TestJwt.WRITE;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.application.IdGenerator;
import pl.fradomski.troubleticket.application.SozGateway;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PATCH /troubleTicket/{id}} over real HTTP with PostgreSQL (README §5).
 *
 * <p>The ticket is saved through the repository and its starting status set with SQL, since
 * statuses SOZ sets have no domain method. Each test uses its own random tenant.
 */
@IntegrationTest
class CloseTroubleTicketIT {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final String CLOSE_BODY = """
            {"status":"closed"}""";

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

    @MockitoBean
    SozGateway soz;

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

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"NEW", "ACKNOWLEDGED", "IN_PROGRESS", "RESOLVED", "REJECTED"})
    void closesFromEveryOpenStatus(String startStatus) throws Exception {
        setStatus(startStatus);
        Instant before = Instant.now();

        var response = close(ticketId, tenant, CLOSE_BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode ticket = json.readTree(response.body());
        assertThat(ticket.get("id").asString()).isEqualTo(ticketId);
        assertThat(ticket.get("status").asString()).isEqualTo("closed");
        assertThat(ticket.get("notes").valueStream().map(n -> n.get("text").asString()))
                .containsExactly("first note");
        assertThat(json.readTree(get(ticketId, tenant).body())).isEqualTo(ticket);
        assertThat(storedStatus()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("select updated_at from trouble_ticket where id = ?").param(ticketId)
                .query(Instant.class).single()).isAfterOrEqualTo(before.minusSeconds(1));

        var notified = ArgumentCaptor.forClass(TroubleTicket.class);
        verify(soz).notifyClosed(notified.capture());
        assertThat(notified.getValue().id()).isEqualTo(ticketId);
    }

    @Test
    void closingTwiceReturnsTheSameTicketAndChangesNothing() throws Exception {
        var first = close(ticketId, tenant, CLOSE_BODY);
        var rowAfterFirst = ticketRow();

        var second = close(ticketId, tenant, CLOSE_BODY);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(first.body()));
        assertThat(ticketRow()).isEqualTo(rowAfterFirst);
        // SOZ hears about the close once, not on every retry.
        verify(soz).notifyClosed(any());
    }

    @Test
    void failedSozNotificationDoesNotUndoTheClose() throws Exception {
        doThrow(new IllegalStateException("SOZ down")).when(soz).notifyClosed(any());

        var response = close(ticketId, tenant, CLOSE_BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("status").asString()).isEqualTo("closed");
        assertThat(storedStatus()).isEqualTo("CLOSED");
    }

    @Test
    void anotherTenantsTicketLooksExactlyLikeAMissingOne() throws Exception {
        String otherTenant = "other-" + tenant;

        var otherTenants = close(ticketId, otherTenant, CLOSE_BODY);
        var missing = close("TT-2026-999999", otherTenant, CLOSE_BODY);

        String message = "Trouble ticket does not exist or is not available to the caller";
        assertError(otherTenants, 404, "TROUBLE_TICKET_NOT_FOUND", message);
        assertError(missing, 404, "TROUBLE_TICKET_NOT_FOUND", message);
        assertThat(otherTenants.headers().map().keySet()).isEqualTo(missing.headers().map().keySet());
        assertStillNew();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            other status   | {"status":"resolved"}              | Field 'status' has an invalid value
            wire-like name | {"status":"CLOSED"}                | Field 'status' has an invalid value
            missing status | {}                                 | Field 'status' is required
            null status    | {"status":null}                    | Field 'status' is required
            unknown field  | {"status":"closed","note":"bye"}   | Unknown field 'note'
            """)
    void invalidBodyIs400(String name, String body, String message) throws Exception {
        assertError(close(ticketId, tenant, body), 400, "VALIDATION_ERROR", message);
        assertStillNew();
    }

    @Test
    void readOnlyTokenIs403() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/troubleTicket/" + ticketId))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestJwt.token(tenant, READ))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(CLOSE_BODY))
                .build();

        assertError(http.send(request, HttpResponse.BodyHandlers.ofString()), 403, "FORBIDDEN",
                "Token lacks the permission for this operation");
        assertStillNew();
    }

    // --- helpers ----------------------------------------------------------------------------

    private void setStatus(String status) {
        jdbc.sql("update trouble_ticket set status = ? where id = ?").params(status, ticketId).update();
    }

    private String storedStatus() {
        return jdbc.sql("select status from trouble_ticket where id = ?").param(ticketId)
                .query(String.class).single();
    }

    private List<Object> ticketRow() {
        return jdbc.sql("select status, updated_at, version from trouble_ticket where id = ?")
                .param(ticketId)
                .query((rs, n) -> List.<Object>of(rs.getString(1), rs.getTimestamp(2), rs.getLong(3)))
                .single();
    }

    private void assertStillNew() {
        assertThat(storedStatus()).isEqualTo("NEW");
        verifyNoInteractions(soz);
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

    private HttpResponse<String> close(String id, String tenantId, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/troubleTicket/" + id))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestJwt.token(tenantId, READ, WRITE))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
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
