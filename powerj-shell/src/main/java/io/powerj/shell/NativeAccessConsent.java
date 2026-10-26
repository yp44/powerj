package io.powerj.shell;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.powerj.core.command.NativeAccessPolicy;

/**
 * Consent to load the modules that use native code (FR-77): the answers are the settings
 * {@code modules.nativeAccess.<module>} of {@code config.properties}, one per module. A yes is
 * {@code sha256:<fingerprint>}: it holds for the content of the module and of what is loaded with it, not for its name,
 * so a jar that is replaced asks again (review C-24). A no is {@code false} and holds whatever the content. When a
 * module has no answer that holds, the user is asked (if there is a terminal to ask) and the answer is recorded.
 */
final class NativeAccessConsent implements NativeAccessPolicy {

    private static final Logger LOG = Logger.getLogger(NativeAccessConsent.class.getName());
    private static final Set<String> YES = Set.of("y", "yes", "o", "oui");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Asks the user; empty if they gave no answer (Ctrl+C, end of input). */
    interface Asker {
        Optional<Boolean> ask(String module, List<String> layer);
    }

    private final ConfigFile config;
    private final Asker asker;
    private final Consumer<String> notice;

    /**
     * @param asker  how to ask the user, or {@code null} if there is nobody to ask (script, pipe): a module without
     *               a recorded answer is then not loaded
     * @param notice where to tell the user that their answer was recorded
     */
    NativeAccessConsent(ConfigFile config, Asker asker, Consumer<String> notice) {
        this.config = config;
        this.asker = asker;
        this.notice = notice;
    }

    @Override
    public Optional<Boolean> consent(String module, List<String> layer, String fingerprint) {
        String setting = NativeAccessPolicy.setting(module);
        String allowed = NativeAccessPolicy.allowed(fingerprint.toLowerCase(Locale.ROOT));
        Optional<String> recorded = recorded(setting);
        if (recorded.isPresent()) {
            String value = recorded.get();
            if (value.equals("false")) {
                return Optional.of(false);
            }
            if (value.equals(allowed)) {
                return Optional.of(true);
            }
            if (value.equals("true") || DIGEST.matcher(value).matches()) {
                // A yes that does not hold: given before the answer was tied to the content, or for another version.
                notice.accept(Messages.get("nativeAccess.stale", module));
            }
        }
        if (asker == null) {
            return Optional.empty();
        }
        Optional<Boolean> answer = asker.ask(module, layer);
        answer.ifPresent(yes -> record(setting, yes ? allowed : "false"));
        return answer;
    }

    /** What the file says for {@code setting}, stripped and in lower case. */
    private Optional<String> recorded(String setting) {
        try {
            return config.get(setting).map(value -> value.strip().toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read " + config.path(), e);
            return Optional.empty();
        }
    }

    private void record(String setting, String value) {
        try {
            config.set(setting, value);
            notice.accept(Messages.get("nativeAccess.recorded", setting, value, config.path()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot write " + config.path(), e);
            notice.accept(Messages.get("nativeAccess.recordFailed", config.path(), e.getMessage()));
        }
    }

    /** {@code true} for {@code y}, {@code yes}, {@code o}, {@code oui} (any case); everything else, empty included, is no. */
    static boolean isYes(String answer) {
        return answer != null && YES.contains(answer.strip().toLowerCase(Locale.ROOT));
    }
}
