package pl.fradomski.troubleticket.application;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pl.fradomski.troubleticket.application.SozGateway.Registration;
import pl.fradomski.troubleticket.domain.DomainValidationException;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/**
 * Creates a ticket and makes one immediate, best-effort attempt to register it with SOZ
 * (README §2, §3):
 *
 * <ol>
 *   <li>The service must exist, be active and belong to the caller's tenant.
 *   <li>The ticket and its first note are saved as {@code new} in their own transaction, which
 *       commits before SOZ is called. No database connection is held during the external call.
 *   <li>SOZ is called with a timeout. If it acknowledges, the ticket moves to
 *       {@code acknowledged} in a second transaction.
 *   <li>If SOZ fails, times out or only accepts the ticket as pending, the ticket stays
 *       {@code new}. The ticket is saved either way, so the client gets {@code 201}.
 * </ol>
 */
@Service
public class CreateTroubleTicket {

    private static final Logger log = LoggerFactory.getLogger(CreateTroubleTicket.class);

    /** The first try plus one retry after a concurrent change (e.g. the client closed the ticket). */
    private static final int ACKNOWLEDGE_ATTEMPTS = 2;

    private final ServiceCatalog serviceCatalog;
    private final SozGateway soz;
    private final TroubleTicketRepository repository;
    private final IdGenerator ids;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration sozTimeout;

    /** Runs the SOZ call so that the request thread can stop waiting after {@link #sozTimeout}. */
    private final ExecutorService sozExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CreateTroubleTicket(ServiceCatalog serviceCatalog, SozGateway soz, TroubleTicketRepository repository,
            IdGenerator ids, TransactionTemplate tx, Clock clock, SozProperties sozProperties) {
        this.serviceCatalog = serviceCatalog;
        this.soz = soz;
        this.repository = repository;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
        this.sozTimeout = sozProperties.registerTimeout();
    }

    /** The fields of a create request, after schema validation. */
    public record Command(String externalId, long serviceId, String description, String note) {
    }

    public TroubleTicket create(String tenantId, Command command) {
        if (serviceCatalog.findActiveForTenant(tenantId, command.serviceId()).isEmpty()) {
            throw new ServiceNotFoundException(command.serviceId());
        }
        TroubleTicket created = Objects.requireNonNull(tx.execute(status -> insert(tenantId, command)));
        if (registerWithSoz(created) == Registration.ACKNOWLEDGED) {
            return acknowledge(created);
        }
        return created;
    }

    private TroubleTicket insert(String tenantId, Command command) {
        Instant now = clock.instant();
        TroubleTicket ticket = new TroubleTicket(ids.nextTicketId(now), tenantId, command.externalId(),
                command.serviceId(), command.description(), now);
        try {
            ticket.addNote(ids.nextNoteId(), command.note(), now);
        } catch (DomainValidationException e) {
            // The domain names the note's own field; in a create request it is called "note".
            throw new DomainValidationException("note", e.problem());
        }
        return repository.save(ticket);
    }

    /** Never throws: any failure leaves the ticket {@code new}. */
    private Registration registerWithSoz(TroubleTicket ticket) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<Registration> call = sozExecutor.submit(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc); // keep the request ID on the adapter's log lines
            }
            try {
                return soz.register(ticket);
            } finally {
                MDC.clear();
            }
        });
        try {
            return Objects.requireNonNull(call.get(sozTimeout.toMillis(), TimeUnit.MILLISECONDS),
                    "SOZ returned no registration result");
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("SOZ did not answer the registration of ticket {} within {}; it stays new",
                    ticket.id(), sozTimeout);
        } catch (ExecutionException e) {
            log.warn("SOZ registration of ticket {} failed; it stays new", ticket.id(), e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Interrupted while registering ticket {} with SOZ; it stays new", ticket.id());
        } catch (RuntimeException e) {
            log.warn("SOZ registration of ticket {} failed; it stays new", ticket.id(), e);
        }
        return Registration.PENDING;
    }

    /**
     * Moves the ticket to {@code acknowledged}, unless it has moved on since it was created; then it
     * is left as it is (README §3). A conflicting concurrent change is retried once. If that fails
     * too, the ticket is returned as created: it is saved, so the client still gets {@code 201}.
     * Any other failure propagates; a retried create returns the saved ticket (README §4).
     */
    private TroubleTicket acknowledge(TroubleTicket created) {
        for (int attempt = 1; ; attempt++) {
            try {
                return Objects.requireNonNull(tx.execute(status -> {
                    TroubleTicket ticket = repository.findByIdAndTenantId(created.id(), created.tenantId())
                            .orElseThrow(() -> new IllegalStateException("Ticket " + created.id() + " vanished"));
                    if (!ticket.acknowledge(clock.instant())) {
                        log.info("Ticket {} is already {}; SOZ acknowledgement not applied", ticket.id(),
                                ticket.status());
                    }
                    return ticket;
                }));
            } catch (OptimisticLockingFailureException e) {
                if (attempt == ACKNOWLEDGE_ATTEMPTS) {
                    log.warn("Could not record the SOZ acknowledgement of ticket {} after {} attempts",
                            created.id(), attempt, e);
                    return created;
                }
                log.debug("Ticket {} changed concurrently; retrying the SOZ acknowledgement", created.id());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        sozExecutor.shutdownNow();
    }
}
