package pl.fradomski.troubleticket.application;

/**
 * The service of a create request is unknown, inactive or belongs to another tenant (README §2).
 * The three cases are deliberately indistinguishable.
 */
public class ServiceNotFoundException extends RuntimeException {

    public ServiceNotFoundException(long serviceId) {
        super("Service " + serviceId + " not found for the caller's tenant");
    }
}
