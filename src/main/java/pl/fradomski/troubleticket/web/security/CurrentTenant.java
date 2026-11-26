package pl.fradomski.troubleticket.web.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The caller's tenant, taken from the verified token's {@code tenant_id} claim. This is the
 * <b>only</b> source of tenant identity (README §1): never a header, path or body field.
 *
 * <p>{@link TenantIdValidator} already rejected tokens without the claim, so on an authenticated
 * request it is always present.
 */
@Component
public class CurrentTenant {

    public String id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken().getClaimAsString(TenantIdValidator.CLAIM);
        }
        throw new IllegalStateException("No JWT-authenticated caller on this thread");
    }
}
