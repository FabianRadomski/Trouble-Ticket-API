package pl.fradomski.troubleticket.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pl.fradomski.troubleticket.api.TroubleTicketApi;
import pl.fradomski.troubleticket.api.TroubleTicketNoteApi;

/**
 * The spec's server URL is {@code /api/v{version}}, but the generated interfaces map only
 * {@code /troubleTicket...}. The prefix is added here for the API controllers only, so actuator
 * stays at {@code /actuator} (README §11).
 */
@Configuration(proxyBeanMethods = false)
public class ApiPathConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX,
                HandlerTypePredicate.forAssignableType(TroubleTicketApi.class, TroubleTicketNoteApi.class));
    }
}
