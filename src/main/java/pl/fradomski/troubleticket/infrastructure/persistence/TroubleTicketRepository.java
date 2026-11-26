package pl.fradomski.troubleticket.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;
import pl.fradomski.troubleticket.domain.TicketSummary;
import pl.fradomski.troubleticket.domain.TroubleTicket;

/**
 * Ticket storage. Every lookup takes the tenant: there is deliberately no {@code findById}, so a
 * ticket of another tenant cannot be loaded by mistake (README §1). Extends the bare
 * {@link Repository} rather than {@code JpaRepository} so that no unscoped query is inherited.
 */
public interface TroubleTicketRepository extends Repository<TroubleTicket, String> {

    TroubleTicket save(TroubleTicket ticket);

    @EntityGraph(attributePaths = "notes")
    Optional<TroubleTicket> findByIdAndTenantId(String id, String tenantId);

    /** Idempotency lookup of create (README §4). */
    @EntityGraph(attributePaths = "notes")
    Optional<TroubleTicket> findByTenantIdAndExternalId(String tenantId, String externalId);

    /**
     * The ticket list (README §10): a DTO projection, so it selects only the summary columns and
     * never loads notes. {@code id} breaks ties between tickets created in the same microsecond.
     */
    List<TicketSummary> findAllByTenantIdOrderByCreatedAtAscIdAsc(String tenantId);
}
