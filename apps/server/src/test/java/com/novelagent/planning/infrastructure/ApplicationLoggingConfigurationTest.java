package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ApplicationLoggingConfigurationTest {

    @Test
    void keepsLoggingAndDatabaseSettingsInSeparateSections() throws IOException {
        var properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst();

        assertThat(properties.getProperty("logging.file.name"))
                .isEqualTo("${LOG_FILE:logs/novel-agent-server.log}");
        assertThat(properties.getProperty("spring.datasource.url"))
                .asString().contains("jdbc:postgresql:");
        assertThat(properties.getProperty("logging.datasource.url")).isNull();
    }
}
