package pl.fradomski.troubleticket.web;

import java.time.ZoneOffset;
import pl.fradomski.troubleticket.api.model.Note;
import pl.fradomski.troubleticket.api.model.TroubleTicket;
import pl.fradomski.troubleticket.api.model.TroubleTicketCreateRequest;
import pl.fradomski.troubleticket.api.model.TroubleTicketStatus;
import pl.fradomski.troubleticket.application.CreateTroubleTicket;
import pl.fradomski.troubleticket.domain.TicketStatus;

/** Between the generated DTOs and the domain. DTOs never leave the web layer (README §11). */
final class TroubleTicketMapper {

    private TroubleTicketMapper() {
    }

    static CreateTroubleTicket.Command toCommand(TroubleTicketCreateRequest request) {
        // Bean Validation already guaranteed every field is present.
        return new CreateTroubleTicket.Command(request.getExternalId(), request.getServiceId(),
                request.getDescription(), request.getNote());
    }

    static TroubleTicket toDto(pl.fradomski.troubleticket.domain.TroubleTicket ticket) {
        return new TroubleTicket(ticket.id(), ticket.externalId(), ticket.serviceId(), ticket.description(),
                toDto(ticket.status()), ticket.notes().stream().map(TroubleTicketMapper::toDto).toList());
    }

    static Note toDto(pl.fradomski.troubleticket.domain.Note note) {
        return new Note(note.id(), note.text(), note.createdAt().atOffset(ZoneOffset.UTC));
    }

    /** Exhaustive: a new domain status does not compile until it has a wire value. */
    static TroubleTicketStatus toDto(TicketStatus status) {
        return switch (status) {
            case NEW -> TroubleTicketStatus.NEW;
            case ACKNOWLEDGED -> TroubleTicketStatus.ACKNOWLEDGED;
            case IN_PROGRESS -> TroubleTicketStatus.IN_PROGRESS;
            case RESOLVED -> TroubleTicketStatus.RESOLVED;
            case REJECTED -> TroubleTicketStatus.REJECTED;
            case CLOSED -> TroubleTicketStatus.CLOSED;
        };
    }
}
