package pl.fradomski.troubleticket.application;

import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/**
 * The client closes a ticket of its tenant (README §5). Allowed from every status; closing a
 * closed ticket returns it unchanged, so retries are safe.
 *
 * <p>The status change commits in its own transaction. Only then, and only if this call actually
 * closed the ticket, is SOZ told (README §3). A failed notification is logged and does not undo
 * the close.
 */
@Service
public class CloseTroubleTicket {

    private static final Logger log = LoggerFactory.getLogger(CloseTroubleTicket.class);

    /** The first try plus one retry after a concurrent change (e.g. the SOZ acknowledgement). */
    private static final int ATTEMPTS = 2;

    private final TroubleTicketRepository repository;
    private final SozGateway soz;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CloseTroubleTicket(TroubleTicketRepository repository, SozGateway soz, TransactionTemplate tx,
            Clock clock) {
        this.repository = repository;
        this.soz = soz;
        this.tx = tx;
        this.clock = clock;
    }

    public TroubleTicket close(String tenantId, String ticketId) {
        Closed closed = closeWithRetry(tenantId, ticketId);
        if (closed.changed()) {
            notifySoz(closed.ticket());
        }
        return closed.ticket();
    }

    private record Closed(TroubleTicket ticket, boolean changed) {
    }

    /**
     * A concurrent status change (the SOZ acknowledgement, or another close) fails this one on the
     * ticket's version. Closing is allowed from every status, so a re-read and retry always
     * succeeds unless the ticket keeps changing; then the conflict propagates ({@code 500}).
     */
    private Closed closeWithRetry(String tenantId, String ticketId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return Objects.requireNonNull(tx.execute(status -> {
                    TroubleTicket ticket = repository.findByIdAndTenantId(ticketId, tenantId)
                            .orElseThrow(() -> new TroubleTicketNotFoundException(ticketId));
                    return new Closed(ticket, ticket.close(clock.instant()));
                }));
            } catch (OptimisticLockingFailureException e) {
                if (attempt == ATTEMPTS) {
                    throw e;
                }
                log.debug("Ticket {} changed concurrently; retrying the close", ticketId);
            }
        }
    }

    /** Never throws: the ticket is already closed in our database. */
    private void notifySoz(TroubleTicket ticket) {
        try {
            soz.notifyClosed(ticket);
        } catch (RuntimeException e) {
            log.warn("Could not notify SOZ that ticket {} was closed", ticket.id(), e);
        }
    }
}
