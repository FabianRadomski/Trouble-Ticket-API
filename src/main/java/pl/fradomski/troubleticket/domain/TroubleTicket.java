package pl.fradomski.troubleticket.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A customer's trouble ticket and its notes: a JPA entity that also owns its status rules
 * (README §3, §5, §6). Generated API DTOs never reach this class.
 *
 * <p>Times are truncated to microseconds, the precision PostgreSQL stores, so a ticket reads back
 * exactly as it was written.
 */
@Entity
@Table(name = "trouble_ticket")
public class TroubleTicket {

    /** Column limit; the spec sets none, so longer values are rejected with 400 (README §8). */
    public static final int MAX_EXTERNAL_ID_LENGTH = 255;

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private long serviceId;

    @Column(nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Boxed so that Spring Data treats a ticket without a version as new and persists it. */
    @Version
    private Long version;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC, id ASC")
    private List<Note> notes = new ArrayList<>();

    protected TroubleTicket() {
        // for JPA
    }

    /**
     * Opens a ticket in status {@code new}. The caller adds the first note from the create
     * request with {@link #addNote}.
     */
    public TroubleTicket(String id, String tenantId, String externalId, long serviceId, String description,
            Instant now) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.externalId = requireExternalId(externalId);
        if (serviceId < 1) {
            throw new IllegalArgumentException("serviceId must be positive");
        }
        this.serviceId = serviceId;
        this.description = requireNotBlank(description, "description");
        this.status = TicketStatus.NEW;
        this.createdAt = truncate(now);
        this.updatedAt = this.createdAt;
    }

    /**
     * SOZ accepted the ticket. Allowed only from {@code new}: if the ticket has already moved on
     * (e.g. the client closed it meanwhile), nothing changes.
     *
     * @return whether the status changed
     */
    public boolean acknowledge(Instant now) {
        if (status != TicketStatus.NEW) {
            return false;
        }
        changeStatus(TicketStatus.ACKNOWLEDGED, now);
        return true;
    }

    /**
     * The client closes the ticket. Allowed from every status; closing a closed ticket changes
     * nothing, so retries are safe (README §5).
     *
     * @return whether the status changed
     */
    public boolean close(Instant now) {
        if (status == TicketStatus.CLOSED) {
            return false;
        }
        changeStatus(TicketStatus.CLOSED, now);
        return true;
    }

    /**
     * Appends a note. Allowed in every status, including {@code closed}, and never changes the
     * status (README §6). The ticket's {@code updatedAt} tracks the ticket's own fields only;
     * each note carries its own time.
     */
    public Note addNote(String noteId, String text, Instant now) {
        Note note = new Note(requireText(noteId, "noteId"), this, requireNotBlank(text, "text"), truncate(now));
        notes.add(note);
        return note;
    }

    private void changeStatus(TicketStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = truncate(now);
    }

    /**
     * Checks a client's {@code externalId} the way the constructor does. Create calls it before its
     * idempotency lookup, so that a value the database can't take never reaches a query.
     */
    public static String requireExternalId(String externalId) {
        requireNotBlank(externalId, "externalId");
        // Count characters like PostgreSQL's varchar(n) does, not UTF-16 code units.
        if (externalId.codePointCount(0, externalId.length()) > MAX_EXTERNAL_ID_LENGTH) {
            throw new DomainValidationException("externalId",
                    "must be at most " + MAX_EXTERNAL_ID_LENGTH + " characters");
        }
        return externalId;
    }

    /**
     * Client input: whitespace-only is rejected, stricter than the contract (README §8). So is
     * U+0000, which PostgreSQL can't store in a text column.
     */
    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new DomainValidationException(field, "must not contain the NUL character");
        }
        return value;
    }

    /** Values the server supplies itself; a blank one is a bug, not a client error. */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Instant truncate(Instant now) {
        return Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String externalId() {
        return externalId;
    }

    public long serviceId() {
        return serviceId;
    }

    public String description() {
        return description;
    }

    public TicketStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Oldest first. */
    public List<Note> notes() {
        return Collections.unmodifiableList(notes);
    }
}
