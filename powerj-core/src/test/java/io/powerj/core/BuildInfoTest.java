package io.powerj.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

    @Test
    void currentReadsTheFilteredMavenVersion() {
        var info = BuildInfo.current();

        assertThat(info.version()).isNotBlank().doesNotContain("${");
        assertThat(info.javaVersion()).isEqualTo(Runtime.version());
    }

    @Test
    void bannerShowsPowerJAndJavaFeatureVersion() {
        var info = new BuildInfo("1.2.3", Runtime.Version.parse("27.0.1"));

        assertThat(info.banner()).isEqualTo("PowerJ 1.2.3 (Java 27)");
    }
}
