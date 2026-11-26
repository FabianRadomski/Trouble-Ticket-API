package pl.fradomski.troubleticket.domain;

/**
 * Lifecycle of a trouble ticket (README §5). The web layer maps these to the spec's wire values
 * ({@code new}, {@code inProgress}, …); the database stores the constant names.
 */
public enum TicketStatus {
    NEW,
    ACKNOWLEDGED,
    IN_PROGRESS,
    RESOLVED,
    REJECTED,
    CLOSED
}
