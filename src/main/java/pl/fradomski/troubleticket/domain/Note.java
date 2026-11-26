package pl.fradomski.troubleticket.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** An append-only comment on a ticket (README §6). Created only through {@link TroubleTicket#addNote}. */
@Entity
@Table(name = "note")
public class Note {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private TroubleTicket ticket;

    @Column(nullable = false, updatable = false)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Note() {
        // for JPA
    }

    Note(String id, TroubleTicket ticket, String text, Instant createdAt) {
        this.id = id;
        this.ticket = ticket;
        this.text = text;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
