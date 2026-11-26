package pl.fradomski.troubleticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.application.ServiceCatalog.CustomerService;
import pl.fradomski.troubleticket.application.SozGateway.Registration;
import pl.fradomski.troubleticket.domain.TicketStatus;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/**
 * The SOZ acknowledgement races other changes to the ticket (README §3). A real race is hard to
 * time in an integration test, so the conflict is simulated at commit.
 */
class CreateTroubleTicketTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final String TENANT = "tenant-a";
    private static final CreateTroubleTicket.Command COMMAND =
            new CreateTroubleTicket.Command("EXT-1", 1, "Internet down", "first note");
    private static final ObjectOptimisticLockingFailureException CONFLICT =
            new ObjectOptimisticLockingFailureException(TroubleTicket.class, "TT-2026-000001");

    private final PlatformTransactionManager txManager = mock();
    private final TroubleTicketRepository repository = mock();
    private final SozGateway soz = mock();
    private final ServiceCatalog catalog = mock();
    private final IdGenerator ids = mock();

    private final CreateTroubleTicket createTroubleTicket = new CreateTroubleTicket(catalog, soz, repository, ids,
            new TransactionTemplate(txManager), Clock.fixed(NOW, ZoneOffset.UTC),
            new SozProperties(Duration.ofSeconds(1)));

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(catalog.findActiveForTenant(TENANT, 1)).thenReturn(Optional.of(new CustomerService(1, TENANT)));
        when(ids.nextTicketId(any())).thenReturn("TT-2026-000001");
        when(ids.nextNoteId()).thenReturn("NOTE-000001");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        // Each transaction loads its own copy, as JPA would.
        when(repository.findByIdAndTenantId(anyString(), anyString()))
                .thenAnswer(call -> Optional.of(storedTicket()));
        when(soz.register(any())).thenReturn(Registration.ACKNOWLEDGED);
    }

    @Test
    void acknowledgementIsRetriedOnceAfterAConcurrentChange() {
        // insert commits, first acknowledge conflicts, retry commits
        doNothing().doThrow(CONFLICT).doNothing().when(txManager).commit(any());

        TroubleTicket ticket = createTroubleTicket.create(TENANT, COMMAND).ticket();

        assertThat(ticket.status()).isEqualTo(TicketStatus.ACKNOWLEDGED);
        verify(repository, times(2)).findByIdAndTenantId("TT-2026-000001", TENANT);
    }

    @Test
    void ticketIsReturnedAsCreatedWhenTheRetryConflictsToo() {
        doNothing().doThrow(CONFLICT).when(txManager).commit(any());

        TroubleTicket ticket = createTroubleTicket.create(TENANT, COMMAND).ticket();

        assertThat(ticket.status()).isEqualTo(TicketStatus.NEW);
        assertThat(ticket.notes()).hasSize(1);
        verify(repository, times(2)).findByIdAndTenantId("TT-2026-000001", TENANT);
    }

    @Test
    void unavailableServiceStopsBeforeAnythingIsStored() {
        when(catalog.findActiveForTenant(anyString(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createTroubleTicket.create(TENANT, COMMAND))
                .isInstanceOf(ServiceNotFoundException.class);
        verify(repository, never()).save(any());
        verify(soz, never()).register(any());
    }

    // --- idempotency (README §4) -----------------------------------------------------------

    @Test
    void existingTicketIsReturnedWithoutCheckingTheServiceOrStoringAnything() {
        TroubleTicket existing = storedTicket();
        existing.acknowledge(NOW);
        when(repository.findByTenantIdAndExternalId(TENANT, "EXT-1")).thenReturn(Optional.of(existing));
        var differentPayload = new CreateTroubleTicket.Command("EXT-1", 2, "Other", "other note");

        var result = createTroubleTicket.create(TENANT, differentPayload);

        assertThat(result.created()).isFalse();
        assertThat(result.ticket()).isSameAs(existing);
        verify(catalog, never()).findActiveForTenant(anyString(), anyLong());
        verify(repository, never()).save(any());
        verify(soz, never()).register(any());
    }

    @Test
    void repeatedCreateOfATicketStillNewRetriesTheSozRegistration() {
        when(repository.findByTenantIdAndExternalId(TENANT, "EXT-1")).thenReturn(Optional.of(storedTicket()));

        var result = createTroubleTicket.create(TENANT, COMMAND);

        assertThat(result.created()).isFalse();
        assertThat(result.ticket().status()).isEqualTo(TicketStatus.ACKNOWLEDGED);
        verify(soz).register(any());
        verify(repository, never()).save(any());
    }

    @Test
    void losingAConcurrentCreateReturnsTheWinnersTicket() {
        TroubleTicket winner = storedTicket();
        when(repository.findByTenantIdAndExternalId(TENANT, "EXT-1"))
                .thenReturn(Optional.empty())       // before the insert: not there yet
                .thenReturn(Optional.of(winner));   // after the unique violation
        doThrow(new DataIntegrityViolationException("uq_trouble_ticket_tenant_external")).when(txManager)
                .commit(any());

        var result = createTroubleTicket.create(TENANT, COMMAND);

        assertThat(result.created()).isFalse();
        assertThat(result.ticket()).isSameAs(winner);
        // The winning request registers the ticket with SOZ; the loser must not do it again.
        verify(soz, never()).register(any());
    }

    @Test
    void otherIntegrityViolationsPropagate() {
        var violation = new DataIntegrityViolationException("something else");
        doThrow(violation).when(txManager).commit(any());

        assertThatThrownBy(() -> createTroubleTicket.create(TENANT, COMMAND)).isSameAs(violation);
        verify(soz, never()).register(any());
    }

    private static TroubleTicket storedTicket() {
        TroubleTicket ticket = new TroubleTicket("TT-2026-000001", TENANT, "EXT-1", 1, "Internet down", NOW);
        ticket.addNote("NOTE-000001", "first note", NOW);
        return ticket;
    }
}
