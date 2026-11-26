package pl.fradomski.troubleticket.application;

/**
 * No ticket with this id in the caller's tenant. A ticket of another tenant is reported the same
 * way as a missing one (README §1).
 */
public class TroubleTicketNotFoundException extends RuntimeException {

    public TroubleTicketNotFoundException(String id) {
        super("Trouble ticket " + id + " not found for the caller's tenant");
    }
}
