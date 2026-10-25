package io.powerj.shell;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.powerj.core.command.NativeAccessPolicy;

/**
 * Consent to load the modules that use native code (FR-77): the answers are the settings
 * {@code modules.nativeAccess.<module>=true|false} of {@code config.properties}, one per module. When a module has no
 * answer yet, the user is asked once (if there is a terminal to ask) and the answer is recorded.
 */
final class NativeAccessConsent implements NativeAccessPolicy {

    private static final Logger LOG = Logger.getLogger(NativeAccessConsent.class.getName());
    private static final Set<String> YES = Set.of("y", "yes", "o", "oui");

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
    public Optional<Boolean> consent(String module, List<String> layer) {
        String setting = NativeAccessPolicy.setting(module);
        Optional<Boolean> recorded = recorded(setting);
        if (recorded.isPresent() || asker == null) {
            return recorded;
        }
        Optional<Boolean> answer = asker.ask(module, layer);
        answer.ifPresent(yes -> record(setting, yes));
        return answer;
    }

    private Optional<Boolean> recorded(String setting) {
        try {
            return config.get(setting).map(value -> switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> null; // not an answer: the user is asked again
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read " + config.path(), e);
            return Optional.empty();
        }
    }

    private void record(String setting, boolean yes) {
        try {
            config.set(setting, Boolean.toString(yes));
            notice.accept(Messages.get("nativeAccess.recorded", setting, yes, config.path()));
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
