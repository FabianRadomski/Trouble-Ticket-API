package pl.fradomski.troubleticket.domain;

/**
 * Input that the contract's schema accepts but the domain does not, e.g. a whitespace-only
 * description or an over-long {@code externalId} (README §8). Rendered as
 * {@code 400 VALIDATION_ERROR}.
 *
 * <p>{@code field} uses the API's field names, so the message points the client at its input.
 */
public class DomainValidationException extends RuntimeException {

    private final String field;

    public DomainValidationException(String field, String problem) {
        super("Field '" + field + "' " + problem);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
