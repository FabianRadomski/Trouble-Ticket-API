package pl.fradomski.troubleticket.infrastructure.catalog;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import pl.fradomski.troubleticket.application.ServiceCatalog;
import pl.fradomski.troubleticket.infrastructure.catalog.ServiceCatalogProperties.Entry;

/**
 * Stand-in for the real service inventory (README §2): a fixed list of services from
 * configuration. Service ids are global, as in an inventory, and each belongs to one tenant.
 */
@Component
class InMemoryServiceCatalog implements ServiceCatalog {

    private final Map<Long, Entry> services;

    InMemoryServiceCatalog(ServiceCatalogProperties properties) {
        this.services = properties.services().stream()
                .collect(Collectors.toUnmodifiableMap(Entry::id, Function.identity()));
    }

    @Override
    public Optional<CustomerService> findActiveForTenant(String tenantId, long serviceId) {
        return Optional.ofNullable(services.get(serviceId))
                .filter(Entry::active)
                .filter(service -> service.tenantId().equals(tenantId))
                .map(service -> new CustomerService(service.id(), service.tenantId()));
    }
}
