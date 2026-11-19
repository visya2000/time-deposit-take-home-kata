package org.ikigaidigital;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Defaults from application.yml, without property overrides. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DefaultConfigurationIT {

    @Autowired
    private Clock clock;

    @Test
    void clock_usesUtcAsTheDefaultBusinessZone() {
        // normalized(): "UTC", "Etc/UTC" and "Z" are all accepted; a zone with DST rules is not.
        assertThat(clock.getZone().normalized()).isEqualTo(ZoneOffset.UTC);
    }

    // The production system clock, not a test's fixed clock (FixedClockConfiguration must not leak
    // into other contexts; it is UTC too, so the zone check alone would not notice).
    @Test
    void clock_isTheSystemClock() {
        assertThat(clock.instant()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }
}
