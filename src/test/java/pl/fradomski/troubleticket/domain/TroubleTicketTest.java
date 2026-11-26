package pl.fradomski.troubleticket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class TroubleTicketTest {

    private static final Instant T0 = Instant.parse("2026-11-27T10:00:00.123456789Z");
    private static final Instant T1 = Instant.parse("2026-11-27T11:00:00Z");

    @Test
    void opensAsNewWithoutNotes() {
        TroubleTicket ticket = ticket();

        assertThat(ticket.status()).isEqualTo(TicketStatus.NEW);
        assertThat(ticket.notes()).isEmpty();
        assertThat(ticket.externalId()).isEqualTo("EXT-1");
        assertThat(ticket.serviceId()).isEqualTo(7);
        assertThat(ticket.tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void timesAreTruncatedToMicrosecondsLikePostgres() {
        TroubleTicket ticket = ticket();

        assertThat(ticket.createdAt()).isEqualTo(Instant.parse("2026-11-27T10:00:00.123456Z"));
        assertThat(ticket.updatedAt()).isEqualTo(ticket.createdAt());
    }

    @Test
    void acknowledgeMovesNewToAcknowledged() {
        TroubleTicket ticket = ticket();

        assertThat(ticket.acknowledge(T1)).isTrue();
        assertThat(ticket.status()).isEqualTo(TicketStatus.ACKNOWLEDGED);
        assertThat(ticket.updatedAt()).isEqualTo(T1);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = "NEW", mode = EnumSource.Mode.EXCLUDE)
    void acknowledgeIsIgnoredOnceTheTicketHasMovedOn(TicketStatus status) {
        TroubleTicket ticket = ticketIn(status);
        Instant updatedAt = ticket.updatedAt();

        assertThat(ticket.acknowledge(T1)).isFalse();
        assertThat(ticket.status()).isEqualTo(status);
        assertThat(ticket.updatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = "CLOSED", mode = EnumSource.Mode.EXCLUDE)
    void closeIsAllowedFromEveryOpenStatus(TicketStatus status) {
        TroubleTicket ticket = ticketIn(status);

        assertThat(ticket.close(T1)).isTrue();
        assertThat(ticket.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.updatedAt()).isEqualTo(T1);
    }

    @Test
    void closingAClosedTicketChangesNothing() {
        TroubleTicket ticket = ticket();
        ticket.close(T1);

        assertThat(ticket.close(T1.plusSeconds(60))).isFalse();
        assertThat(ticket.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.updatedAt()).isEqualTo(T1);
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void notesCanBeAddedInEveryStatusAndNeverChangeIt(TicketStatus status) {
        TroubleTicket ticket = ticketIn(status);
        Instant updatedAt = ticket.updatedAt();

        Note note = ticket.addNote("NOTE-000001", "hello", T1);

        assertThat(ticket.status()).isEqualTo(status);
        assertThat(ticket.updatedAt()).isEqualTo(updatedAt);
        assertThat(ticket.notes()).containsExactly(note);
        assertThat(note.id()).isEqualTo("NOTE-000001");
        assertThat(note.text()).isEqualTo("hello");
        assertThat(note.createdAt()).isEqualTo(T1);
    }

    @Test
    void notesKeepTheOrderTheyWereAddedIn() {
        TroubleTicket ticket = ticket();
        ticket.addNote("NOTE-000001", "first", T0);
        ticket.addNote("NOTE-000002", "second", T1);

        assertThat(ticket.notes()).extracting(Note::text).containsExactly("first", "second");
        assertThatThrownBy(() -> ticket.notes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void blankClientTextIsRejected(String blank) {
        assertValidationError(() -> new TroubleTicket("TT-2026-000001", "tenant-a", blank, 7, "d", T0),
                "externalId", "Field 'externalId' must not be blank");
        assertValidationError(() -> new TroubleTicket("TT-2026-000001", "tenant-a", "EXT-1", 7, blank, T0),
                "description", "Field 'description' must not be blank");
        assertValidationError(() -> ticket().addNote("NOTE-000001", blank, T1),
                "text", "Field 'text' must not be blank");
    }

    @Test
    void externalIdMayHaveUpTo255Characters() {
        String max = "x".repeat(TroubleTicket.MAX_EXTERNAL_ID_LENGTH);

        assertThat(new TroubleTicket("TT-2026-000001", "tenant-a", max, 7, "d", T0).externalId()).isEqualTo(max);
        assertValidationError(() -> new TroubleTicket("TT-2026-000001", "tenant-a", max + "x", 7, "d", T0),
                "externalId", "Field 'externalId' must be at most 255 characters");
    }

    @Test
    void externalIdLengthCountsCharactersNotUtf16Units() {
        String emoji = "😀"; // one character, two UTF-16 units
        String max = emoji.repeat(TroubleTicket.MAX_EXTERNAL_ID_LENGTH);

        assertThat(new TroubleTicket("TT-2026-000001", "tenant-a", max, 7, "d", T0).externalId()).isEqualTo(max);
    }

    @Test
    void serverSuppliedValuesAreProgrammingErrors() {
        assertThatThrownBy(() -> new TroubleTicket(" ", "tenant-a", "EXT-1", 7, "d", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TroubleTicket("TT-2026-000001", "", "EXT-1", 7, "d", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TroubleTicket("TT-2026-000001", "tenant-a", "EXT-1", 0, "d", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TroubleTicket ticket() {
        return new TroubleTicket("TT-2026-000001", "tenant-a", "EXT-1", 7, "Internet down", T0);
    }

    /** Statuses SOZ sets (inProgress, resolved, …) have no domain method in v1, so set the field. */
    private static TroubleTicket ticketIn(TicketStatus status) {
        TroubleTicket ticket = ticket();
        ReflectionTestUtils.setField(ticket, "status", status);
        return ticket;
    }

    private static void assertValidationError(Runnable action, String field, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.field()).isEqualTo(field))
                .hasMessage(message);
    }
}
