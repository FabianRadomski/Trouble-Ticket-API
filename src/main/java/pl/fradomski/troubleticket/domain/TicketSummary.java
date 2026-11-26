package pl.fradomski.troubleticket.domain;

/**
 * Read model for the ticket list: only the fields the list returns, without notes (README §10).
 * Loaded straight from the ticket table, so listing never touches the notes.
 */
public record TicketSummary(String externalId, long serviceId, String description, TicketStatus status) {
}
