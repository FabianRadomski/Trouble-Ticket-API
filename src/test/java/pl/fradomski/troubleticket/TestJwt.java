package pl.fradomski.troubleticket;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Mints RS256 tokens for tests. The keypair is generated in memory once per JVM; no key is
 * committed (README §1). The application verifies them with its production decoder: {@link Config}
 * points {@code public-key-location} at the test public key.
 */
public final class TestJwt {

    public static final String TENANT_A = "tenant-a";
    public static final String TENANT_B = "tenant-b";
    public static final String READ = "tt:read";
    public static final String WRITE = "tt:write";

    private static final KeyPair KEYS = generateKeyPair();
    private static final KeyPair OTHER_KEYS = generateKeyPair();
    private static final NimbusJwtEncoder ENCODER = encoder(KEYS);
    private static final NimbusJwtEncoder OTHER_ENCODER = encoder(OTHER_KEYS);
    private static final Path PUBLIC_KEY_FILE = writePublicKey();

    private TestJwt() {
    }

    /** Valid token for {@code tenant} with the given scopes. */
    public static String token(String tenant, String... scopes) {
        return mint(ENCODER, c -> c.claim("tenant_id", tenant).claim("scope", String.join(" ", scopes)));
    }

    /** Both scopes for {@link #TENANT_A}. */
    public static String fullAccess() {
        return token(TENANT_A, READ, WRITE);
    }

    public static String withoutTenant(String... scopes) {
        return mint(ENCODER, c -> c.claim("scope", String.join(" ", scopes)));
    }

    public static String withBlankTenant(String... scopes) {
        return mint(ENCODER, c -> c.claim("tenant_id", "  ").claim("scope", String.join(" ", scopes)));
    }

    public static String expired(String tenant, String... scopes) {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        return mint(ENCODER, c -> c.claim("tenant_id", tenant).claim("scope", String.join(" ", scopes))
                .issuedAt(past).expiresAt(past.plus(Duration.ofHours(1))));
    }

    /** Well-formed and unexpired, but signed with a key the application does not trust. */
    public static String signedWithOtherKey(String tenant, String... scopes) {
        return mint(OTHER_ENCODER, c -> c.claim("tenant_id", tenant).claim("scope", String.join(" ", scopes)));
    }

    private static String mint(NimbusJwtEncoder encoder, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject("test-user")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)));
        customizer.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static NimbusJwtEncoder encoder(KeyPair keys) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    private static Path writePublicKey() {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        try {
            Path file = Files.createTempFile("jwt-test-public", ".pem");
            file.toFile().deleteOnExit();
            return Files.writeString(file, pem);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Makes the application trust the test public key. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        DynamicPropertyRegistrar testJwtPublicKey() {
            return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                    () -> PUBLIC_KEY_FILE.toUri().toString());
        }
    }
}
