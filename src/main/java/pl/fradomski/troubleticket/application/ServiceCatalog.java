package pl.fradomski.troubleticket.application;

import java.util.Optional;

/**
 * Port to the customer service inventory, an external system (README §2). Create checks the
 * ticket's {@code serviceId} through it.
 */
public interface ServiceCatalog {

    /**
     * The service, if it exists, is active and belongs to {@code tenantId}. Unknown, inactive and
     * other-tenant services all come back empty, so callers cannot tell them apart.
     */
    Optional<CustomerService> findActiveForTenant(String tenantId, long serviceId);

    record CustomerService(long id, String tenantId) {
    }
}
