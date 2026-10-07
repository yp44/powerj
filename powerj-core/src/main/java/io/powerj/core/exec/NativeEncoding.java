package io.powerj.core.exec;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Encodage du texte échangé avec une commande native (spécification FR-40b) :
 * {@code POWERJ_NATIVE_ENCODING_<NOM>}, sinon {@code POWERJ_NATIVE_ENCODING}, sinon automatique
 * (page de code de la console Windows, UTF-8 ailleurs).
 */
public final class NativeEncoding {

    public static final String VARIABLE = "POWERJ_NATIVE_ENCODING";

    private final Supplier<OptionalInt> consoleCodePage;

    public NativeEncoding() {
        this(WindowsConsole::outputCodePage);
    }

    NativeEncoding(Supplier<OptionalInt> consoleCodePage) {
        this.consoleCodePage = consoleCodePage;
    }

    public Charset forProgram(Path executable, Map<String, String> environment) {
        String specific = VARIABLE + "_" + programKey(executable);
        String value = environment.get(specific);
        String source = specific;
        if (value == null || value.isBlank()) {
            value = environment.get(VARIABLE);
            source = VARIABLE;
        }
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("auto")) {
            return automatic();
        }
        try {
            return Charset.forName(value.strip());
        } catch (IllegalArgumentException _) {
            throw new PjException("encodage inconnu '" + value.strip() + "' (variable " + source + ")");
        }
    }

    /** Nom de l'exécutable en majuscules, sans extension : {@code git.exe} → {@code GIT}. */
    static String programKey(Path executable) {
        String name = executable.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private Charset automatic() {
        OptionalInt codePage = consoleCodePage.get();
        if (codePage.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        return codePageCharset(codePage.getAsInt());
    }

    static Charset codePageCharset(int codePage) {
        if (codePage == 65001) {
            return StandardCharsets.UTF_8;
        }
        for (String name : new String[] {"cp" + codePage, "windows-" + codePage, "x-windows-" + codePage}) {
            try {
                return Charset.forName(name);
            } catch (IllegalArgumentException _) {
                // nom suivant
            }
        }
        return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
    }
}
