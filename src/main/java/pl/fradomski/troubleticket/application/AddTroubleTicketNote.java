package pl.fradomski.troubleticket.application;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.fradomski.troubleticket.domain.Note;
import pl.fradomski.troubleticket.infrastructure.persistence.TroubleTicketRepository;

/**
 * Appends a note to a ticket of the caller's tenant (README §6). Allowed in every status,
 * including {@code closed}; the ticket's status, {@code updatedAt} and version stay as they are,
 * so a note never conflicts with a concurrent status change.
 */
@Service
public class AddTroubleTicketNote {

    private final TroubleTicketRepository repository;
    private final IdGenerator ids;
    private final Clock clock;

    public AddTroubleTicketNote(TroubleTicketRepository repository, IdGenerator ids, Clock clock) {
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
    }

    /** The ticket is managed in this transaction, so the new note is inserted on commit. */
    @Transactional
    public Note add(String tenantId, String ticketId, String text) {
        var ticket = repository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new TroubleTicketNotFoundException(ticketId));
        return ticket.addNote(ids.nextNoteId(), text, clock.instant());
    }
}
