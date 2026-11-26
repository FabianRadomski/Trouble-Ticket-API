package pl.fradomski.troubleticket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.TestJwt;
import pl.fradomski.troubleticket.application.ServiceCatalog;
import pl.fradomski.troubleticket.application.SozGateway;
import pl.fradomski.troubleticket.application.SozGateway.Registration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Repeated {@code POST /troubleTicket} with the same {@code externalId} (README §4): the first
 * creates ({@code 201}), every later one returns the stored ticket ({@code 200}), also when the
 * requests race each other.
 */
@IntegrationTest
class IdempotentCreateIT {

    private static final long SERVICE_A = 987654321L;
    private static final long OTHER_SERVICE_A = 987654322L;
    private static final long SERVICE_B = 987654331L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SozGateway soz;

    @MockitoSpyBean
    ServiceCatalog catalog;

    private final String externalId = "EXT-" + UUID.randomUUID();

    @BeforeEach
    void sozAcknowledges() {
        when(soz.register(any())).thenReturn(Registration.ACKNOWLEDGED);
    }

    @Test
    void repeatedCreateReturns200WithTheSameTicketAndLocation() throws Exception {
        var first = create(TENANT_A, body(SERVICE_A, "No data transmission", "First note"));
        var second = create(TENANT_A, body(SERVICE_A, "No data transmission", "First note"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(first.body()));
        assertThat(storedRows()).isEqualTo(1);
        verify(soz, times(1)).register(any()); // acknowledged on create, so not registered again
    }

    @Test
    void differentPayloadIsIgnoredAndTheOriginalReturned() throws Exception {
        var first = create(TENANT_A, body(SERVICE_A, "No data transmission", "First note"));

        var replay = create(TENANT_A, body(OTHER_SERVICE_A, "Something else", "Another note"));

        assertThat(replay.statusCode()).isEqualTo(200);
        JsonNode ticket = json.readTree(replay.body());
        assertThat(ticket).isEqualTo(json.readTree(first.body()));
        assertThat(ticket.get("serviceId").asLong()).isEqualTo(SERVICE_A);
        assertThat(ticket.get("notes")).hasSize(1);
    }

    @Test
    void replayIsNotCheckedAgainstTheServiceCatalog() throws Exception {
        assertThat(create(TENANT_A, body(SERVICE_A, "d", "n")).statusCode()).isEqualTo(201);
        // E.g. the service was deactivated since the ticket was created (README §4).
        doAnswer(call -> Optional.empty()).when(catalog).findActiveForTenant(anyString(), anyLong());

        assertThat(create(TENANT_A, body(SERVICE_A, "d", "n")).statusCode()).isEqualTo(200);
    }

    @Test
    void sameExternalIdInAnotherTenantIsANewTicket() throws Exception {
        var inA = create(TENANT_A, body(SERVICE_A, "d", "n"));
        var inB = create(TENANT_B, body(SERVICE_B, "d", "n"));

        assertThat(inA.statusCode()).isEqualTo(201);
        assertThat(inB.statusCode()).isEqualTo(201);
        assertThat(json.readTree(inB.body()).get("id")).isNotEqualTo(json.readTree(inA.body()).get("id"));
    }

    @Test
    void replayOfATicketStillNewRetriesTheSozRegistration() throws Exception {
        when(soz.register(any())).thenReturn(Registration.PENDING);
        var first = create(TENANT_A, body(SERVICE_A, "d", "n"));
        assertThat(json.readTree(first.body()).get("status").asString()).isEqualTo("new");

        when(soz.register(any())).thenReturn(Registration.ACKNOWLEDGED);
        var replay = create(TENANT_A, body(SERVICE_A, "d", "n"));

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).get("status").asString()).isEqualTo("acknowledged");
        verify(soz, times(2)).register(any());
    }

    /**
     * All requests are held after the idempotency lookup (at the service check) until every one has
     * got there, so all of them find nothing and race to insert. The unique constraint lets exactly
     * one insert through; the others re-read the winner.
     */
    @Test
    void concurrentDuplicatesCreateExactlyOneTicket() throws Exception {
        int requests = 10;
        var allPastTheLookup = new CyclicBarrier(requests);
        doAnswer(call -> {
            awaitQuietly(allPastTheLookup);
            return call.callRealMethod();
        }).when(catalog).findActiveForTenant(anyString(), anyLong());

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> create(TENANT_A, body(SERVICE_A, "d", "n")));
            }
            for (Future<HttpResponse<String>> f : pool.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                responses.add(f.get());
            }
        }

        assertThat(allPastTheLookup.isBroken()).as("all requests reached the insert together").isFalse();
        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsOnly(201, 200)
                .filteredOn(status -> status == 201).hasSize(1);
        assertThat(responses).extracting(r -> json.readTree(r.body()).get("id").asString())
                .containsOnly(json.readTree(responses.getFirst().body()).get("id").asString());
        assertThat(storedRows()).isEqualTo(1);
        verify(soz, times(1)).register(any()); // only the winner registers it with SOZ
    }

    private static void awaitQuietly(CyclicBarrier barrier) throws InterruptedException {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            // Checked by the test via isBroken(); carry on so the requests still complete.
        }
    }

    private long storedRows() {
        return jdbc.sql("select count(*) from trouble_ticket where external_id = ?")
                .param(externalId).query(Long.class).single();
    }

    private String body(long serviceId, String description, String note) {
        return """
                {"externalId":"%s","serviceId":%d,"description":"%s","status":"new","note":"%s"}"""
                .formatted(externalId, serviceId, description, note);
    }

    private HttpResponse<String> create(String tenant, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/troubleTicket"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestJwt.token(tenant, READ, WRITE))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
