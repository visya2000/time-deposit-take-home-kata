package org.ikigaidigital;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

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
}
