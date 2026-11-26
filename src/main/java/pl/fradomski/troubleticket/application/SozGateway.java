package pl.fradomski.troubleticket.application;

import pl.fradomski.troubleticket.domain.TroubleTicket;

/**
 * Port to SOZ, the operator's ticket handling system (README §3). Implementations are called
 * outside any database transaction and may be slow or fail; callers bound the wait.
 */
public interface SozGateway {

    /**
     * Hands a newly created ticket over to SOZ. Must be idempotent per ticket {@code id}, since a
     * production outbox would resend after a timeout.
     */
    Registration register(TroubleTicket ticket);

    /** The client closed the ticket. A no-op in v1 (README §3). */
    void notifyClosed(TroubleTicket ticket);

    enum Registration {
        /** SOZ accepted the ticket: it moves to {@code acknowledged}. */
        ACKNOWLEDGED,
        /** SOZ received the ticket but has not accepted it yet: it stays {@code new}. */
        PENDING
    }
}
