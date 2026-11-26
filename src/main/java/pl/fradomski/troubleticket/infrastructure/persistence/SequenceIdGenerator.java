package pl.fradomski.troubleticket.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pl.fradomski.troubleticket.application.IdGenerator;

/**
 * Identifiers from the global PostgreSQL sequences (README §7). The sequence is not reset each
 * year, and six digits is a minimum width: past 999999 the number just grows.
 */
@Component
class SequenceIdGenerator implements IdGenerator {

    private final JdbcClient jdbc;

    SequenceIdGenerator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextTicketId(Instant createdAt) {
        return ticketId(createdAt, next("trouble_ticket_seq"));
    }

    @Override
    public String nextNoteId() {
        return noteId(next("note_seq"));
    }

    private long next(String sequence) {
        return jdbc.sql("select nextval('" + sequence + "')").query(Long.class).single();
    }

    static String ticketId(Instant createdAt, long sequence) {
        return "TT-%d-%06d".formatted(createdAt.atZone(ZoneOffset.UTC).getYear(), sequence);
    }

    static String noteId(long sequence) {
        return "NOTE-%06d".formatted(sequence);
    }
}
