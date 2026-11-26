package pl.fradomski.troubleticket.application;

import java.util.List;
import org.springframework.stereotype.Service;
import pl.fradomski.troubleticket.domain.TicketSummary;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/** All tickets of the caller's tenant, oldest first, without notes (README §10). */
@Service
public class ListTroubleTickets {

    private final TroubleTicketRepository repository;

    public ListTroubleTickets(TroubleTicketRepository repository) {
        this.repository = repository;
    }

    public List<TicketSummary> list(String tenantId) {
        return repository.findAllByTenantIdOrderByCreatedAtAscIdAsc(tenantId);
    }
}
