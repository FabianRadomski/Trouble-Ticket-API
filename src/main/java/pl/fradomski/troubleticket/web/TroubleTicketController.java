package pl.fradomski.troubleticket.web;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.fradomski.troubleticket.api.TroubleTicketApi;
import pl.fradomski.troubleticket.api.model.TroubleTicket;
import pl.fradomski.troubleticket.api.model.TroubleTicketCloseStatusRequest;
import pl.fradomski.troubleticket.api.model.TroubleTicketCreateRequest;
import pl.fradomski.troubleticket.api.model.TroubleTicketSummary;
import pl.fradomski.troubleticket.application.CreateTroubleTicket;
import pl.fradomski.troubleticket.application.GetTroubleTicket;
import pl.fradomski.troubleticket.application.ListTroubleTickets;
import pl.fradomski.troubleticket.web.security.CurrentTenant;

/**
 * The generated {@link TroubleTicketApi} fixes paths, methods and DTOs; this class only maps
 * between them and the use cases. The tenant always comes from the token ({@link CurrentTenant}).
 */
@RestController
class TroubleTicketController implements TroubleTicketApi {

    private final CurrentTenant currentTenant;
    private final CreateTroubleTicket createTroubleTicket;
    private final GetTroubleTicket getTroubleTicket;
    private final ListTroubleTickets listTroubleTickets;

    TroubleTicketController(CurrentTenant currentTenant, CreateTroubleTicket createTroubleTicket,
            GetTroubleTicket getTroubleTicket, ListTroubleTickets listTroubleTickets) {
        this.currentTenant = currentTenant;
        this.createTroubleTicket = createTroubleTicket;
        this.getTroubleTicket = getTroubleTicket;
        this.listTroubleTickets = listTroubleTickets;
    }

    @Override
    public ResponseEntity<TroubleTicket> createTroubleTicket(TroubleTicketCreateRequest request) {
        var result = createTroubleTicket.create(currentTenant.id(), TroubleTicketMapper.toCommand(request));
        var ticket = result.ticket();
        // 201 for a new ticket, 200 for an existing one with the same externalId (README §4).
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(location(ticket.id()))
                .body(TroubleTicketMapper.toDto(ticket));
    }

    @Override
    public ResponseEntity<TroubleTicket> getTroubleTicketById(String id) {
        return ResponseEntity.ok(TroubleTicketMapper.toDto(getTroubleTicket.get(currentTenant.id(), id)));
    }

    @Override
    public ResponseEntity<List<TroubleTicketSummary>> listTroubleTickets() {
        return ResponseEntity.ok(listTroubleTickets.list(currentTenant.id()).stream()
                .map(TroubleTicketMapper::toDto)
                .toList());
    }

    @Override
    public ResponseEntity<TroubleTicket> closeTroubleTicket(String id, TroubleTicketCloseStatusRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED); // later slice
    }

    /** Relative, as the spec types {@code Location} as {@code uri-reference} (README §4). */
    static URI location(String ticketId) {
        return URI.create(ApiPathConfig.API_PREFIX + "/troubleTicket/" + ticketId);
    }
}
