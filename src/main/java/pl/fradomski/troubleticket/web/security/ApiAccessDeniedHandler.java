package pl.fradomski.troubleticket.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import pl.fradomski.troubleticket.web.error.ApiErrors;
import pl.fradomski.troubleticket.web.error.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 403 with the spec's {@code Error} body. The standard bearer handler still sets the status and the
 * {@code WWW-Authenticate: Bearer error="insufficient_scope"} header; only the body is added.
 */
@Component
class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
    private final JsonMapper jsonMapper;

    ApiAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        bearer.handle(request, response, ex);
        ApiErrors.write(response, jsonMapper, ErrorCode.FORBIDDEN, "Token lacks the permission for this operation");
    }
}
