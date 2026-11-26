package pl.fradomski.troubleticket.web.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A token without a usable {@code tenant_id} is invalid (401), not merely unauthorized: without a
 * tenant the request has no scope to work in (README §1).
 */
class TenantIdValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "tenant_id";

    private static final OAuth2Error MISSING_TENANT = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "The " + CLAIM + " claim must be a non-blank string", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return jwt.getClaims().get(CLAIM) instanceof String tenant && !tenant.isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(MISSING_TENANT);
    }
}
