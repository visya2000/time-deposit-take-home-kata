package org.ikigaidigital.adapter.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Binding {@code interest.business-zone} from application.yml is covered by DefaultConfigurationIT. */
class ApplicationConfigurationTest {

    @Test
    void clock_usesTheGivenBusinessZone() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");

        assertThat(new ApplicationConfiguration().clock(tokyo).getZone()).isEqualTo(tokyo);
    }
}
