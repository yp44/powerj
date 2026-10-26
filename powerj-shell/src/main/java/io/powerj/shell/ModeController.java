package io.powerj.shell;

import java.util.EnumMap;
import java.util.Map;

/**
 * The mode of the shell, which is one at a time, as in vi: entering one leaves the other. Each mode says what it does when
 * it is entered and when it is left; none of them knows the others, and the prompt asks this class what the mode is.
 * <p>
 * The preview mode used to switch the perf mode off by being given a callback, and the perf mode switched the preview
 * mode off by holding it: two classes that knew each other to say "not both".
 */
final class ModeController {

    private record Hooks(Runnable enter, Runnable leave) { }

    private final Map<ShellMode, Hooks> hooks = new EnumMap<>(ShellMode.class);
    private volatile ShellMode current = ShellMode.NORMAL;

    /** What happens when {@code mode} is entered and when it is left. */
    void register(ShellMode mode, Runnable enter, Runnable leave) {
        synchronized (this) {
            hooks.put(mode, new Hooks(enter, leave));
        }
    }

    ShellMode current() {
        return current;
    }

    boolean is(ShellMode mode) {
        return current == mode;
    }

    /** Enters {@code mode}, or goes back to {@link ShellMode#NORMAL} if it is the mode the shell is in. */
    void toggle(ShellMode mode) {
        switchTo(current == mode ? ShellMode.NORMAL : mode);
    }

    /** Leaves the mode the shell is in and enters {@code target}; nothing happens if it already is in it. */
    synchronized void switchTo(ShellMode target) {
        ShellMode previous = current;
        if (previous == target) {
            return;
        }
        current = target;
        run(previous, true);
        run(target, false);
    }

    private void run(ShellMode mode, boolean leaving) {
        Hooks registered = hooks.get(mode);
        if (registered != null) {
            (leaving ? registered.leave() : registered.enter()).run();
        }
    }
}
