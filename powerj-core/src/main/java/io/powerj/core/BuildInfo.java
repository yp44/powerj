package io.powerj.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;

/**
 * PowerJ build information: its version and that of the JVM running it.
 *
 * @param version     PowerJ version (taken from the Maven POM)
 * @param javaVersion version of the currently running Java runtime
 */
public record BuildInfo(String version, Runtime.Version javaVersion) {

    private static final String RESOURCE = "build.properties";

    public BuildInfo {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(javaVersion, "javaVersion");
    }

    /** Information about the current build. */
    public static BuildInfo current() {
        return new BuildInfo(readVersion(), Runtime.version());
    }

    /** Banner displayed when the shell starts, e.g. {@code PowerJ 0.1.0 (Java 27)}. */
    public String banner() {
        return "PowerJ " + version + " (Java " + javaVersion.feature() + ")";
    }

    private static String readVersion() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return "dev";
            }
            var props = new Properties();
            props.load(in);
            return props.getProperty("version", "dev");
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de " + RESOURCE + " impossible", e);
        }
    }
}
