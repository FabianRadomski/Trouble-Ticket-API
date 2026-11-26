package pl.fradomski.troubleticket.web.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import pl.fradomski.troubleticket.domain.DomainValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every exception into the spec's {@code Error} body (README §8).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that all of Spring MVC's own exceptions
 * (405, 406, 415, unknown path, unreadable body, validation) are covered and keep their headers
 * (e.g. {@code Allow}); only the body is replaced. Messages are written here and never echo raw
 * exception text back to the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * The generated API interfaces are annotated {@code @Validated}, so parameter constraints on
     * them are checked by the method-validation AOP proxy, which throws this instead of
     * {@link HandlerMethodValidationException}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> "Parameter '" + lastNode(v) + "' " + constraintMessage(
                        v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()))
                .sorted()
                .collect(Collectors.joining("; "));
        return ApiErrors.response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, null);
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    /** Input the schema accepts but the domain rejects, e.g. a whitespace-only description. */
    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<Object> handleDomainValidation(DomainValidationException ex) {
        return ApiErrors.response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ApiErrors.response(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Unexpected error. Quote the requestId when reporting it.", null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ErrorCode code = codeFor(ex, status);
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Request failed with status {}", status.value(), ex);
        } else {
            log.debug("Request rejected with status {}: {}", status.value(), ex.toString());
        }
        return ApiErrors.response(status, code, messageFor(ex, status), headers);
    }

    private static ErrorCode codeFor(Exception ex, HttpStatusCode status) {
        return switch (ex) {
            case HttpRequestMethodNotSupportedException e -> ErrorCode.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException e -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case HttpMediaTypeNotAcceptableException e -> ErrorCode.NOT_ACCEPTABLE;
            case NoResourceFoundException e -> ErrorCode.NOT_FOUND;
            default -> {
                if (status.is4xxClientError()) {
                    yield status.value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.VALIDATION_ERROR;
                }
                yield ErrorCode.INTERNAL_ERROR;
            }
        };
    }

    private static String messageFor(Exception ex, HttpStatusCode status) {
        return switch (ex) {
            case HttpRequestMethodNotSupportedException e -> "Method " + e.getMethod() + " is not supported for this path";
            case HttpMediaTypeNotSupportedException e -> "Content type must be application/json";
            case HttpMediaTypeNotAcceptableException e -> "Only application/json responses are available";
            case NoResourceFoundException e -> "No resource at this path";
            case HttpMessageNotReadableException e -> unreadableMessage(e);
            case MethodArgumentNotValidException e -> fieldErrorsMessage(e.getFieldErrors());
            case HandlerMethodValidationException e -> parameterErrorsMessage(e);
            default -> status.is4xxClientError() ? "Invalid request" : "Unexpected error. Quote the requestId when reporting it.";
        };
    }

    private static String unreadableMessage(HttpMessageNotReadableException ex) {
        return switch (outermostJacksonCause(ex)) {
            case UnrecognizedPropertyException e -> "Unknown field '" + path(e) + "'";
            case StreamReadException e -> "Malformed JSON request body";
            case JacksonException e when !e.getPath().isEmpty() -> "Field '" + path(e) + "' has an invalid value";
            case null, default -> "Request body is missing or unreadable";
        };
    }

    /**
     * The outermost Jackson exception carries the JSON path. The most specific cause may be an
     * {@code IllegalArgumentException} from a generated enum's {@code fromValue}, which has none.
     */
    private static JacksonException outermostJacksonCause(Throwable ex) {
        for (Throwable t = ex.getCause(); t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof JacksonException je) {
                return je;
            }
        }
        return null;
    }

    /** JSON path like {@code notes[0].text} built from property names only, never from input values. */
    private static String path(JacksonException ex) {
        StringBuilder sb = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static String fieldErrorsMessage(List<FieldError> errors) {
        return errors.stream()
                .map(e -> "Field '" + e.getField() + "' " + constraintMessage(e))
                .sorted()
                .collect(Collectors.joining("; "));
    }

    private static String parameterErrorsMessage(HandlerMethodValidationException ex) {
        return ex.getParameterValidationResults().stream()
                .map(r -> "Parameter '" + r.getMethodParameter().getParameterName() + "' "
                        + r.getResolvableErrors().stream()
                                .map(ApiExceptionHandler::constraintMessage)
                                .collect(Collectors.joining(", ")))
                .sorted()
                .collect(Collectors.joining("; "));
    }

    /** Message by constraint type, so the wording does not depend on Hibernate Validator's defaults. */
    private static String constraintMessage(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return constraintMessage(codes == null || codes.length == 0 ? "" : codes[codes.length - 1]);
    }

    /** The spec only uses {@code minLength: 1} and {@code minimum}, hence the fixed wording. */
    private static String constraintMessage(String constraint) {
        return switch (constraint) {
            case "NotNull" -> "is required";
            case "NotBlank" -> "must not be blank";
            case "Size", "NotEmpty" -> "must not be empty";
            case "Min" -> "is below the allowed minimum";
            default -> "is invalid";
        };
    }
}
