package io.powerj.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;

/**
 * Informations de construction de PowerJ : sa version et celle de la JVM qui l'exécute.
 *
 * @param version     version de PowerJ (issue du POM Maven)
 * @param javaVersion version du runtime Java en cours d'exécution
 */
public record BuildInfo(String version, Runtime.Version javaVersion) {

    private static final String RESOURCE = "build.properties";

    public BuildInfo {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(javaVersion, "javaVersion");
    }

    /** Informations de la build courante. */
    public static BuildInfo current() {
        return new BuildInfo(readVersion(), Runtime.version());
    }

    /** Bannière affichée au démarrage du shell, ex. {@code PowerJ 0.1.0 (Java 27)}. */
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
