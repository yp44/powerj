package io.powerj.shell;

/**
 * Entry point of {@code powerj.exe}: interactive shell without arguments; {@code -c "line"} or a {@code .pj} file for
 * non-interactive mode; {@code --debug} for error details; {@code --no-profile} to start without {@code profile.pj}
 * (FR-63); {@code --version} and {@code --help}. All the work is in {@link ShellBootstrap}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(ShellBootstrap.system().execute(args));
    }
}
