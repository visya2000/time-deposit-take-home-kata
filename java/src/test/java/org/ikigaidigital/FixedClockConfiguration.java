package org.ikigaidigital;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * A fixed business date for end-to-end balance-update tests: a run crossing midnight cannot make
 * expected markers flaky. The production {@code clock} bean stays; this one is {@code @Primary}, so
 * it is the one injected. Test classes importing the same configuration (and properties) share one
 * cached context and PostgreSQL container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }
}
