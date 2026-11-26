package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.fradomski.troubleticket.TestJwt.READ;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.application.IdGenerator;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /troubleTicket} over real HTTP with PostgreSQL (README §10).
 *
 * <p>Tickets are saved through the repository with chosen creation times, so the order is under
 * the test's control. Each test lists its own random tenant, so it sees only its own tickets.
 */
@IntegrationTest
class ListTroubleTicketsIT {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

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

    private final String tenant = "tenant-" + UUID.randomUUID();

    @Test
    void emptyTenantGetsAnEmptyArray() throws Exception {
        var response = list(tenant);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void returnsOnlyTheFourSummaryFields() throws Exception {
        TroubleTicket ticket = newTicket(tenant, "EXT-1", T0);
        ticket.addNote(ids.nextNoteId(), "first note", T0);
        ticket.close(T0.plusSeconds(1));
        save(ticket);

        JsonNode body = json.readTree(list(tenant).body());

        assertThat(body).hasSize(1);
        JsonNode summary = body.get(0);
        assertThat(summary.propertyNames())
                .containsExactlyInAnyOrder("externalId", "serviceId", "description", "status");
        assertThat(summary.get("externalId").asString()).isEqualTo("EXT-1");
        assertThat(summary.get("serviceId").asLong()).isEqualTo(987654321L);
        assertThat(summary.get("description").asString()).isEqualTo("Internet down");
        assertThat(summary.get("status").asString()).isEqualTo("closed");
    }

    @Test
    void listsOldestFirstWithTheIdBreakingTies() throws Exception {
        String lowerId = ids.nextTicketId(T0);
        String higherId = ids.nextTicketId(T0);
        // Saved out of order on purpose.
        save(newTicket(tenant, "EXT-LATEST", T0.plusSeconds(60)));
        save(new TroubleTicket(higherId, tenant, "EXT-TIE-HIGHER-ID", 987654321L, "Internet down", T0));
        save(new TroubleTicket(lowerId, tenant, "EXT-TIE-LOWER-ID", 987654321L, "Internet down", T0));
        save(newTicket(tenant, "EXT-EARLIEST", T0.minusSeconds(60)));

        JsonNode body = json.readTree(list(tenant).body());

        assertThat(body.valueStream().map(summary -> summary.get("externalId").asString()))
                .containsExactly("EXT-EARLIEST", "EXT-TIE-LOWER-ID", "EXT-TIE-HIGHER-ID", "EXT-LATEST");
    }

    @Test
    void listsOnlyTheCallersTenant() throws Exception {
        String otherTenant = "other-" + tenant;
        save(newTicket(tenant, "EXT-MINE", T0));
        save(newTicket(otherTenant, "EXT-THEIRS", T0));

        JsonNode mine = json.readTree(list(tenant).body());
        JsonNode theirs = json.readTree(list(otherTenant).body());

        assertThat(mine.valueStream().map(summary -> summary.get("externalId").asString()))
                .containsExactly("EXT-MINE");
        assertThat(theirs.valueStream().map(summary -> summary.get("externalId").asString()))
                .containsExactly("EXT-THEIRS");
    }

    private TroubleTicket newTicket(String tenantId, String externalId, Instant createdAt) {
        return new TroubleTicket(ids.nextTicketId(createdAt), tenantId, externalId, 987654321L, "Internet down",
                createdAt);
    }

    private void save(TroubleTicket ticket) {
        tx.executeWithoutResult(s -> repository.save(ticket));
    }

    private HttpResponse<String> list(String tenantId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/troubleTicket"))
                .header("Authorization", "Bearer " + TestJwt.token(tenantId, READ))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
