package pl.fradomski.troubleticket.web.error;

/** Values of {@code Error.code} (README §8). */
public enum ErrorCode {
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    SERVICE_NOT_FOUND,
    TROUBLE_TICKET_NOT_FOUND,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    UNSUPPORTED_MEDIA_TYPE,
    INTERNAL_ERROR
}
