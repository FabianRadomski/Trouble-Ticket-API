package pl.fradomski.troubleticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.domain.TicketStatus;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/**
 * A close races the SOZ acknowledgement (README §3, §5). A real race is hard to time in an
 * integration test, so the conflict is simulated at commit.
 */
class CloseTroubleTicketTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final String TENANT = "tenant-a";
    private static final String ID = "TT-2026-000001";
    private static final ObjectOptimisticLockingFailureException CONFLICT =
            new ObjectOptimisticLockingFailureException(TroubleTicket.class, ID);

    private final PlatformTransactionManager txManager = mock();
    private final TroubleTicketRepository repository = mock();
    private final SozGateway soz = mock();

    private final CloseTroubleTicket closeTroubleTicket = new CloseTroubleTicket(repository, soz,
            new TransactionTemplate(txManager), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        // Each transaction loads its own copy, as JPA would.
        when(repository.findByIdAndTenantId(anyString(), anyString()))
                .thenAnswer(call -> Optional.of(storedTicket()));
    }

    @Test
    void closeIsRetriedOnceAfterAConcurrentChange() {
        doThrow(CONFLICT).doNothing().when(txManager).commit(any());

        TroubleTicket ticket = closeTroubleTicket.close(TENANT, ID);

        assertThat(ticket.status()).isEqualTo(TicketStatus.CLOSED);
        verify(repository, times(2)).findByIdAndTenantId(ID, TENANT);
        verify(soz).notifyClosed(ticket);
    }

    @Test
    void aSecondConflictPropagatesAndSozIsNotTold() {
        doThrow(CONFLICT).when(txManager).commit(any());

        assertThatThrownBy(() -> closeTroubleTicket.close(TENANT, ID)).isSameAs(CONFLICT);
        verifyNoInteractions(soz);
    }

    @Test
    void unknownTicketIsNotFound() {
        when(repository.findByIdAndTenantId(ID, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closeTroubleTicket.close(TENANT, ID))
                .isInstanceOf(TroubleTicketNotFoundException.class);
        verifyNoInteractions(soz);
    }

    @Test
    void sozFailureIsSwallowed() {
        doNothing().when(txManager).commit(any());
        doThrow(new IllegalStateException("SOZ down")).when(soz).notifyClosed(any());

        assertThat(closeTroubleTicket.close(TENANT, ID).status()).isEqualTo(TicketStatus.CLOSED);
    }

    private static TroubleTicket storedTicket() {
        TroubleTicket ticket = new TroubleTicket(ID, TENANT, "EXT-1", 1, "Internet down", NOW.minusSeconds(60));
        ticket.addNote("NOTE-000001", "first note", NOW.minusSeconds(60));
        return ticket;
    }
}
