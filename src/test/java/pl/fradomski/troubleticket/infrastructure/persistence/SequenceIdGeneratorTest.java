package pl.fradomski.troubleticket.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SequenceIdGeneratorTest {

    @Test
    void ticketIdHasYearAndSixDigitSequence() {
        assertThat(SequenceIdGenerator.ticketId(Instant.parse("2026-03-01T12:00:00Z"), 1)).isEqualTo("TT-2026-000001");
    }

    @Test
    void ticketYearIsTakenInUtc() {
        assertThat(SequenceIdGenerator.ticketId(Instant.parse("2026-12-31T23:30:00Z"), 42)).isEqualTo("TT-2026-000042");
        assertThat(SequenceIdGenerator.ticketId(Instant.parse("2027-01-01T00:00:00Z"), 43)).isEqualTo("TT-2027-000043");
    }

    @Test
    void sixDigitsIsAMinimumWidth() {
        assertThat(SequenceIdGenerator.ticketId(Instant.parse("2026-03-01T12:00:00Z"), 1_234_567))
                .isEqualTo("TT-2026-1234567");
        assertThat(SequenceIdGenerator.noteId(1_234_567)).isEqualTo("NOTE-1234567");
    }

    @Test
    void noteIdHasSixDigitSequence() {
        assertThat(SequenceIdGenerator.noteId(7)).isEqualTo("NOTE-000007");
    }
}
