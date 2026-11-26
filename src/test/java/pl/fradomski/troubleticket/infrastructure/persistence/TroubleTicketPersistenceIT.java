package pl.fradomski.troubleticket.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.IntegrationTest;
import pl.fradomski.troubleticket.application.IdGenerator;
import pl.fradomski.troubleticket.domain.Note;
import pl.fradomski.troubleticket.domain.TicketStatus;
import pl.fradomski.troubleticket.domain.TroubleTicket;

/**
 * Flyway schema, entity mapping and repository against real PostgreSQL. The context itself only
 * starts if Hibernate's {@code ddl-auto: validate} finds the entities matching the migrations.
 * Each test uses its own tenant, so tests do not see each other's rows.
 */
@IntegrationTest
class TroubleTicketPersistenceIT {

    @Autowired
    TroubleTicketRepository repository;

    @Autowired
    IdGenerator ids;

    @Autowired
    Clock clock;

    @Autowired
    TransactionTemplate tx;

    private final String tenant = "tenant-" + UUID.randomUUID();

    @Test
    void savesAndReloadsATicketWithItsNotesOldestFirst() {
        Instant now = Instant.parse("2026-11-27T10:00:00.123456789Z");
        String id = tx.execute(s -> {
            TroubleTicket ticket = newTicket("EXT-1", now);
            ticket.addNote(ids.nextNoteId(), "first", now);
            ticket.addNote(ids.nextNoteId(), "second", now.plusSeconds(1));
            return repository.save(ticket).id();
        });

        TroubleTicket loaded = repository.findByIdAndTenantId(id, tenant).orElseThrow();

        assertThat(loaded.externalId()).isEqualTo("EXT-1");
        assertThat(loaded.serviceId()).isEqualTo(7);
        assertThat(loaded.description()).isEqualTo("Internet down");
        assertThat(loaded.status()).isEqualTo(TicketStatus.NEW);
        assertThat(loaded.createdAt()).isEqualTo(Instant.parse("2026-11-27T10:00:00.123456Z"));
        // Loaded with the notes (entity graph), so usable outside the transaction.
        assertThat(loaded.notes()).extracting(Note::text).containsExactly("first", "second");
        assertThat(loaded.notes().getFirst().createdAt()).isEqualTo(loaded.createdAt());
    }

    @Test
    void lookupsAreTenantScoped() {
        String id = saveTicket("EXT-1").id();

        assertThat(repository.findByIdAndTenantId(id, tenant)).isPresent();
        assertThat(repository.findByIdAndTenantId(id, "other-" + tenant)).isEmpty();
        assertThat(repository.findByTenantIdAndExternalId(tenant, "EXT-1")).map(TroubleTicket::id).hasValue(id);
        assertThat(repository.findByTenantIdAndExternalId("other-" + tenant, "EXT-1")).isEmpty();
    }

    @Test
    void externalIdIsUniquePerTenant() {
        saveTicket("EXT-1");

        assertThatThrownBy(() -> saveTicket("EXT-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_trouble_ticket_tenant_external");
    }

    @Test
    void sameExternalIdIsAllowedInAnotherTenant() {
        saveTicket("EXT-1");
        String otherTenant = "other-" + tenant;

        String id = tx.execute(s -> repository.save(
                new TroubleTicket(ids.nextTicketId(clock.instant()), otherTenant, "EXT-1", 7, "d", clock.instant()))
                .id());

        assertThat(repository.findByIdAndTenantId(id, otherTenant)).isPresent();
    }

    @Test
    void externalIdOf255CharactersFitsTheColumn() {
        String max = "é".repeat(TroubleTicket.MAX_EXTERNAL_ID_LENGTH); // multi-byte, still 255 characters

        String id = saveTicket(max).id();

        assertThat(repository.findByIdAndTenantId(id, tenant).orElseThrow().externalId()).isEqualTo(max);
    }

    @Test
    void statusChangesArePersisted() {
        String id = saveTicket("EXT-1").id();

        tx.executeWithoutResult(s -> repository.findByIdAndTenantId(id, tenant).orElseThrow().close(clock.instant()));

        assertThat(repository.findByIdAndTenantId(id, tenant).orElseThrow().status()).isEqualTo(TicketStatus.CLOSED);
    }

    @Test
    void concurrentStatusChangesAreDetected() {
        String id = saveTicket("EXT-1").id();
        TroubleTicket stale = repository.findByIdAndTenantId(id, tenant).orElseThrow();

        tx.executeWithoutResult(s -> repository.findByIdAndTenantId(id, tenant).orElseThrow().close(clock.instant()));
        stale.acknowledge(clock.instant());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(repository.findByIdAndTenantId(id, tenant).orElseThrow().status()).isEqualTo(TicketStatus.CLOSED);
    }

    @Test
    void idsComeFromSequencesInTheSpecFormat() {
        Instant now = Instant.parse("2026-05-01T00:00:00Z");

        String first = ids.nextTicketId(now);
        String second = ids.nextTicketId(now);

        assertThat(first).matches("TT-2026-\\d{6,}");
        assertThat(sequence(second)).isGreaterThan(sequence(first));
        assertThat(ids.nextNoteId()).matches("NOTE-\\d{6,}");
    }

    private TroubleTicket saveTicket(String externalId) {
        return tx.execute(s -> repository.save(newTicket(externalId, clock.instant())));
    }

    private TroubleTicket newTicket(String externalId, Instant now) {
        return new TroubleTicket(ids.nextTicketId(now), tenant, externalId, 7, "Internet down", now);
    }

    private static long sequence(String ticketId) {
        return Long.parseLong(ticketId.substring(ticketId.lastIndexOf('-') + 1));
    }
}
