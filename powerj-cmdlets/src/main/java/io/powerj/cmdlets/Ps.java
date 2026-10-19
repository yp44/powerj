package io.powerj.cmdlets;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Locale;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.fs.Globs;

/**
 * {@code ps}: the processes of the machine, sorted by pid (specification FR-74). Patterns keep the processes whose
 * name matches (wildcards, case ignored, {@code .exe} optional); {@code --user} those of a user.
 */
@CmdletInfo(name = "ps", preview = true, category = "System", summary = "Lists the processes",
        examples = {"ps", "ps java", "ps java | sort cpu --desc | first 3", "ps -u alice | count",
                "ps notepad | kill"})
public final class Ps implements Cmdlet<Ps.Params, Object, ProcessEntry> {

    /** Parameters of {@code ps}. */
    public record Params(
            @Option(position = 0, description = "Names of the processes to keep (wildcards * and ? accepted, case "
                    + "ignored, .exe optional); default: all")
            List<String> names,
            @Option(shortName = 'u', description = "Keeps the processes of this user")
            String user) {
    }

    @Override
    public void begin(Params p, CmdletContext<ProcessEntry> context) {
        List<PathMatcher> matchers = p.names() == null ? List.of()
                : p.names().stream().map(Globs::matcher).toList();
        long[] pids = ProcessHandle.allProcesses().mapToLong(ProcessHandle::pid).sorted().toArray();
        for (long pid : pids) {
            var process = ProcessHandle.of(pid);
            if (process.isEmpty()) {
                continue; // ended meanwhile
            }
            ProcessEntry entry = ProcessEntry.of(process.get());
            if (matches(entry.name(), matchers) && ofUser(entry.user(), p.user())) {
                context.emit(entry);
            }
        }
    }

    /** {@code true} without pattern, or if the name (with or without {@code .exe}) matches one of them. */
    static boolean matches(String name, List<PathMatcher> matchers) {
        if (matchers.isEmpty()) {
            return true;
        }
        if (name == null || name.isEmpty()) {
            return false;
        }
        String bare = name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
        try {
            Path path = Path.of(name);
            Path barePath = Path.of(bare);
            return matchers.stream().anyMatch(m -> m.matches(path) || m.matches(barePath));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** {@code true} without user, or if the owner is this user ({@code DOMAIN\\user} matches {@code user}). */
    static boolean ofUser(String owner, String user) {
        if (user == null) {
            return true;
        }
        if (owner == null) {
            return false;
        }
        return owner.equalsIgnoreCase(user) || owner.substring(owner.lastIndexOf('\\') + 1).equalsIgnoreCase(user);
    }
}
