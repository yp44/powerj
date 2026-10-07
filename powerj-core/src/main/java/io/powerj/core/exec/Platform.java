package io.powerj.core.exec;

import java.util.Locale;

/** Particularités de la plateforme d'exécution. */
public final class Platform {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private Platform() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }
}
