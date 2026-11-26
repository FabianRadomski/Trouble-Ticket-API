package pl.fradomski.troubleticket.infrastructure.catalog;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seed data of the in-memory service catalog (README §2), from {@code application.yml}. Local
 * runs can add services without a rebuild.
 */
@ConfigurationProperties("troubleticket.service-catalog")
public record ServiceCatalogProperties(@DefaultValue List<Entry> services) {

    public record Entry(long id, String tenantId, @DefaultValue("true") boolean active) {
    }
}
