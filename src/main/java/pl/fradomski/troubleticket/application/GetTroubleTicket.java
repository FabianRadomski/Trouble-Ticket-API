package pl.fradomski.troubleticket.application;

import org.springframework.stereotype.Service;
import pl.fradomski.troubleticket.domain.TroubleTicket;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/** A single ticket with its notes, oldest first, within the caller's tenant. */
@Service
public class GetTroubleTicket {

    private final TroubleTicketRepository repository;

    public GetTroubleTicket(TroubleTicketRepository repository) {
        this.repository = repository;
    }

    public TroubleTicket get(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new TroubleTicketNotFoundException(id));
    }
}
