package io.powerj.core.exec;

/**
 * Keyboard input of the shell's console, suspended while a native program reads the console directly
 * (vim, ssh, python…): otherwise the line editor's background reading thread (JLine, permanent under
 * Windows) would steal some of the keys typed for the program.
 */
public interface ConsoleInput {

    /** No console (scripts, tests): nothing to suspend. */
    ConsoleInput NONE = new ConsoleInput() {
        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }
    };

    /** Stops reading the console until {@link #resume()}. */
    void pause();

    /** Reads the console again. */
    void resume();
}
