package pl.fradomski.troubleticket.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import pl.fradomski.troubleticket.api.TroubleTicketNoteApi;
import pl.fradomski.troubleticket.api.model.Note;
import pl.fradomski.troubleticket.api.model.NoteCreateRequest;
import pl.fradomski.troubleticket.application.AddTroubleTicketNote;
import pl.fradomski.troubleticket.web.security.CurrentTenant;

/** Notes of a ticket (README §6). The tenant always comes from the token ({@link CurrentTenant}). */
@RestController
class TroubleTicketNoteController implements TroubleTicketNoteApi {

    private final CurrentTenant currentTenant;
    private final AddTroubleTicketNote addTroubleTicketNote;

    TroubleTicketNoteController(CurrentTenant currentTenant, AddTroubleTicketNote addTroubleTicketNote) {
        this.currentTenant = currentTenant;
        this.addTroubleTicketNote = addTroubleTicketNote;
    }

    /** {@code 201} without {@code Location}: the spec declares none and notes have no endpoint of their own. */
    @Override
    public ResponseEntity<Note> addTroubleTicketNote(String id, NoteCreateRequest request) {
        var note = addTroubleTicketNote.add(currentTenant.id(), id, request.getText());
        return ResponseEntity.status(HttpStatus.CREATED).body(TroubleTicketMapper.toDto(note));
    }
}
