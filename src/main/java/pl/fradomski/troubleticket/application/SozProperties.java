package pl.fradomski.troubleticket.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param registerTimeout how long create waits for SOZ before answering {@code 201} with status
 *     {@code new} (README §3)
 */
@ConfigurationProperties("troubleticket.soz")
public record SozProperties(@DefaultValue("2s") Duration registerTimeout) {
}
