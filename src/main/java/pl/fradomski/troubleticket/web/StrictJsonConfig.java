package pl.fradomski.troubleticket.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Request bodies must match the spec's JSON types exactly (README §8). By default Jackson converts
 * between scalar types, so {@code "serviceId": 1.9} would become {@code 1}, {@code "serviceId": "5"}
 * would become {@code 5} and {@code "description": true} would become {@code "true"}. Here every
 * such conversion fails instead, which the error handler turns into a 400.
 */
@Configuration(proxyBeanMethods = false)
class StrictJsonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictScalarTypes() {
        return builder -> builder
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
