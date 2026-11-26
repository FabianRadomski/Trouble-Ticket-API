package pl.fradomski.troubleticket.application;

import java.time.Instant;

/** Issues ticket and note identifiers in the format of the spec's examples (README §7). */
public interface IdGenerator {

    /** {@code TT-{year}-{000001}}, the year taken from {@code createdAt} in UTC. */
    String nextTicketId(Instant createdAt);

    /** {@code NOTE-{000001}}. */
    String nextNoteId();
}
