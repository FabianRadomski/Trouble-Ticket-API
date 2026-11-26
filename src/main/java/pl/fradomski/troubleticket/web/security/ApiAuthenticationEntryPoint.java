package pl.fradomski.troubleticket.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import pl.fradomski.troubleticket.web.error.ApiErrors;
import pl.fradomski.troubleticket.web.error.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 with the spec's {@code Error} body. The standard bearer entry point still sets the status and
 * the RFC 6750 {@code WWW-Authenticate} header; only the body is added.
 */
@Component
class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper jsonMapper;

    ApiAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        bearer.commence(request, response, ex);
        String message = ex instanceof OAuth2AuthenticationException
                ? "Bearer token is invalid or expired"
                : "Bearer token is required";
        ApiErrors.write(response, jsonMapper, ErrorCode.UNAUTHORIZED, message);
    }
}
