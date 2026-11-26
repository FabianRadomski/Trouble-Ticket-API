package pl.fradomski.troubleticket.web.security;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import pl.fradomski.troubleticket.web.ApiPathConfig;

/**
 * OAuth2 resource server with locally verified RS256 JWTs (README §1).
 *
 * <p>The {@code JwtDecoder} comes from Spring Boot ({@code spring.security.oauth2.resourceserver.jwt.*}).
 * Boot adds every {@link OAuth2TokenValidator} bean to its default validators (signature, expiry),
 * which is how {@link TenantIdValidator} turns a token without {@code tenant_id} into a 401.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String SCOPE_READ = "SCOPE_tt:read";
    public static final String SCOPE_WRITE = "SCOPE_tt:write";

    private static final String TICKETS = ApiPathConfig.API_PREFIX + "/troubleTicket/**";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // Stateless bearer-token API: no session, no cookies, so no CSRF exposure.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Container error forwards must not be turned into 401/403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Endpoints exposed later must be opened here on purpose, not by accident.
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(GET, TICKETS).hasAuthority(SCOPE_READ)
                        .requestMatchers(HEAD, TICKETS).hasAuthority(SCOPE_READ)
                        .requestMatchers(POST, TICKETS).hasAuthority(SCOPE_WRITE)
                        .requestMatchers(PATCH, TICKETS).hasAuthority(SCOPE_WRITE)
                        // Any other path/method only needs a valid token, so MVC can answer
                        // 404 / 405 instead of a misleading 403.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        // Spring Security serves RFC 9728 metadata at /.well-known/oauth-protected-resource
                        // and links it from WWW-Authenticate. Make it describe this API accurately.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(m -> m
                                .scope("tt:read")
                                .scope("tt:write")
                                .tlsClientCertificateBoundAccessTokens(false)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    OAuth2TokenValidator<Jwt> tenantIdValidator() {
        return new TenantIdValidator();
    }
}
