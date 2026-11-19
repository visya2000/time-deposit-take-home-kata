package org.ikigaidigital.adapter.config;

import org.ikigaidigital.TimeDepositCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Wires the plain-Java domain objects; the domain itself has no Spring annotations. */
@Configuration
public class ApplicationConfiguration {

    /**
     * Business dates ("today") are computed in one configured zone, never the server's default
     * zone, so instances in different time zones agree on the date.
     */
    @Bean
    public Clock clock(@Value("${interest.business-zone}") ZoneId businessZone) {
        return Clock.system(businessZone);
    }

    /** Uses the default interest plans (InterestStrategies.defaults()). */
    @Bean
    public TimeDepositCalculator timeDepositCalculator() {
        return new TimeDepositCalculator();
    }
}
