package pl.fradomski.troubleticket.web.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import pl.fradomski.troubleticket.api.model.Error;
import pl.fradomski.troubleticket.web.RequestIdFilter;

/** Builds the spec's {@code Error} body ({@code code, message, requestId}) for every error response. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static Error body(ErrorCode code, String message) {
        return new Error(code.name(), message).requestId(RequestIdFilter.current().orElse(null));
    }

    public static ResponseEntity<Object> response(HttpStatusCode status, ErrorCode code, String message,
            HttpHeaders headers) {
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        // Preset the content type so the body is written as JSON even when the client's Accept
        // header excludes it (e.g. the 406 response itself).
        out.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body(code, message), out, status);
    }
}
