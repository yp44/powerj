package io.powerj.api;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Supplies cmdlets to the shell. Declared by a module via {@code provides io.powerj.api.CmdletProvider
 * with …} and discovered by {@link java.util.ServiceLoader}.
 */
public interface CmdletProvider {

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
}
