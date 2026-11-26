package pl.fradomski.troubleticket.infrastructure.soz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.fradomski.troubleticket.application.SozGateway;
import pl.fradomski.troubleticket.domain.TroubleTicket;

/**
 * In-process stand-in for SOZ (README §3): acknowledges every ticket at once. A real adapter
 * would call SOZ over the network with the ticket {@code id} as idempotency key.
 */
@Component
class FakeSozGateway implements SozGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeSozGateway.class);

    @Override
    public Registration register(TroubleTicket ticket) {
        log.info("Fake SOZ: ticket {} acknowledged", ticket.id());
        return Registration.ACKNOWLEDGED;
    }

    @Override
    public void notifyClosed(TroubleTicket ticket) {
        log.info("Fake SOZ: ticket {} closed", ticket.id());
    }
}
