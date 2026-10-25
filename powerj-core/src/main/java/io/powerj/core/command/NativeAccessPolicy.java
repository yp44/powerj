package io.powerj.core.command;

import java.util.List;
import java.util.Optional;

/**
 * Consent of the user to load a third-party module that uses native code. A module declares it with the manifest
 * attribute {@code Enable-Native-Access: true}; {@link ModuleLoader} then asks this policy, and only loads the module
 * (with native access enabled for the modules of its layer) if the answer is yes.
 */
public interface NativeAccessPolicy {

    /** Prefix of the settings of {@code config.properties}: {@code modules.nativeAccess.<module>=true|false}. */
    String SETTING_PREFIX = "modules.nativeAccess.";

    /** No answer, ever: modules that ask for native access are not loaded. */
    NativeAccessPolicy NONE = (module, layer) -> Optional.empty();

    /**
     * The answer for a module: the recorded one, otherwise the one of the user if they can be asked (and then recorded).
     *
     * @param module the module that declares the attribute
     * @param layer  every module that would be loaded with it (the ones that get the access), for the question
     * @return {@code true} to load, {@code false} if refused, empty if there is no answer and nobody to ask
     */
    Optional<Boolean> consent(String module, List<String> layer);

    /** Name of the setting that records the answer for {@code module}. */
    static String setting(String module) {
        return SETTING_PREFIX + module;
    }
}
