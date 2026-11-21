package io.powerj.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Supplies cmdlets to the shell. Declared by a module via {@code provides io.powerj.api.CmdletProvider
 * with …} and discovered by {@link java.util.ServiceLoader}.
 */
public interface CmdletProvider {

    /**
     * The cmdlets of the module. Called once each time the module is loaded (when the shell starts, or by {@code mod-load}),
     * on the thread that loads it: the shell registers the cmdlets the list holds and does not keep the list, so it may be
     * a new list or an immutable one. The same instance of each cmdlet then runs every command that names it, in several
     * pipelines at once ({@link CmdletContext#state} holds what belongs to one execution). A cmdlet that is not valid (no
     * {@link CmdletInfo}, a {@code Params} record the shell cannot bind, a name reserved for a built-in command) is left out
     * with a warning, and the others are registered.
     * <p>
     * It should not throw: an exception is reported as an error while loading the module, whose cmdlets are then not
     * available.
     *
     * @return the cmdlets, none {@code null}
     */
    List<Cmdlet<?, ?, ?>> cmdlets();

    /**
     * Translations of the texts of the provided cmdlets, for {@code locale} ({@link Language#current()}):
     * keys {@code <cmdlet>.summary}, {@code <cmdlet>.option.<longName>} and {@code category.<Category>}.
     * The annotation texts ({@link CmdletInfo}, {@link Option}) are used when a key is absent. Load the
     * bundle from the module itself: {@code ResourceBundle.getBundle("my.pkg.messages", locale)}.
     *
     * @return the bundle, or {@code null} (default) if the module is not translated
     */
    default ResourceBundle messages(Locale locale) {
        return null;
    }

    /**
     * Called once, when the shell starts (for the modules loaded later, when they are loaded), with the configuration
     * folder ({@code ~/.powerj}, or {@code POWERJ_HOME}), which exists: the module can create its default files there
     * and nothing else is needed from the shell to do so. Not called when the shell runs without such a folder.
     *
     * @throws IOException if the files cannot be written: the user is warned, and the module still loads
     */
    default void initialize(Path configDirectory) throws IOException {
    }
}
