package io.powerj.core.command;

import java.util.List;
import java.util.Optional;

/**
 * Consent of the user to load a third-party module that uses native code. A module declares it with the manifest
 * attribute {@code Enable-Native-Access: true}; {@link ModuleLoader} then asks this policy, and only loads the module
 * (with native access enabled for the modules of its layer) if the answer is yes.
 * <p>
 * The answer is given for the <em>content</em> of what is loaded, not for a name: the loader hands the policy a
 * fingerprint of every module of the layer, so that a jar replaced by another one does not inherit the consent given
 * to the first (review C-24).
 */
public interface NativeAccessPolicy {

    /**
     * Prefix of the settings of {@code config.properties}: {@code modules.nativeAccess.<module>=sha256:<fingerprint>}
     * (allowed, for that content) or {@code =false} (refused).
     */
    String SETTING_PREFIX = "modules.nativeAccess.";

    /** No answer, ever: modules that ask for native access are not loaded. */
    NativeAccessPolicy NONE = (module, layer, fingerprint) -> Optional.empty();

    /**
     * The answer for a module: the recorded one, otherwise the one of the user if they can be asked (and then recorded).
     *
     * @param module      the module that declares the attribute
     * @param layer       every module that would be loaded with it (the ones that get the access), for the question
     * @param fingerprint the content of those modules, as {@link ContentFingerprint} computes it: what an allowing
     *                    answer must be tied to
     * @return {@code true} to load, {@code false} if refused, empty if there is no answer and nobody to ask
     */
    Optional<Boolean> consent(String module, List<String> layer, String fingerprint);

    /** Name of the setting that records the answer for {@code module}. */
    static String setting(String module) {
        return SETTING_PREFIX + module;
    }

    /** What the setting holds when the user allowed the content whose fingerprint is given. */
    static String allowed(String fingerprint) {
        return "sha256:" + fingerprint;
    }
}
