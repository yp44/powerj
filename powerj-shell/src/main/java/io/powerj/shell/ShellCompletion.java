package io.powerj.shell;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import io.powerj.core.assist.Completions;
import io.powerj.core.assist.Highlights;
import io.powerj.core.error.LoggedLine;
import io.powerj.core.runtime.CommandKind;
import io.powerj.core.runtime.Interpreter;

/** Completion (Tab) and input highlighting (specification FR-08, FR-21 to FR-26), plugged into JLine. */
final class ShellCompletion {

    private ShellCompletion() {
    }

    /** Suggestions computed by {@link ShellParser} when splitting the line. */
    static Completer completer() {
        return (reader, line, candidates) -> {
            if (line instanceof ShellParser.CompletionLine completion) {
                for (Completions.Candidate c : completion.result().candidates()) {
                    candidates.add(new Candidate(c.value(), c.display(), null,
                            c.description() == null || c.description().isEmpty() ? null : c.description(),
                            null, null, c.complete()));
                }
            }
        };
    }

    /** Highlighting: cmdlet and built-in command in green, native in cyan, unknown in red… (FR-08). */
    static Highlighter highlighter(Interpreter interpreter) {
        return highlighter(interpreter::commandKind, interpreter.session().java()::isStaticReference);
    }

    /** The same, from what it asks of the interpreter: the kind of a command name, and whether a name is a Java static. */
    static Highlighter highlighter(Function<String, CommandKind> kindOf, Predicate<String> staticName) {
        return new Colors(kindOf, staticName);
    }

    private static final class Colors implements Highlighter {

        private static final long CACHE_MILLIS = 2_000;
        private static final Logger LOG = Logger.getLogger(Colors.class.getName());

        private final Function<String, CommandKind> kindOf;
        private final Predicate<String> staticName;
        /** Kind of each command name, cached briefly (highlighting follows every keystroke). */
        private final Map<String, CommandKind> kinds = new HashMap<>();
        private long cachedAt;

        Colors(Function<String, CommandKind> kindOf, Predicate<String> staticName) {
            this.kindOf = kindOf;
            this.staticName = staticName;
        }

        @Override
        public AttributedString highlight(LineReader reader, String buffer) {
            if (System.currentTimeMillis() - cachedAt > CACHE_MILLIS) {
                kinds.clear();
                cachedAt = System.currentTimeMillis();
            }
            List<Highlights.Span> spans;
            try {
                spans = Highlights.of(buffer, name -> kinds.computeIfAbsent(name, kindOf), staticName);
            } catch (RuntimeException e) {
                // The line is shown plain, and the failure is not lost: like completion, at FINE and without the line (#82).
                LOG.log(Level.FINE, "Highlighting failed on: " + LoggedLine.of(buffer), e);
                return new AttributedString(buffer);
            }
            var builder = new AttributedStringBuilder();
            int at = 0;
            for (Highlights.Span span : spans) {
                if (span.start() < at) {
                    continue;
                }
                builder.append(buffer, at, span.start());
                builder.styled(style(span.kind()), buffer.substring(span.start(), span.end()));
                at = span.end();
            }
            builder.append(buffer.substring(at));
            return builder.toAttributedString();
        }

        private static AttributedStyle style(Highlights.Kind kind) {
            return switch (kind) {
                case BUILTIN, CMDLET -> AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
                case NATIVE -> AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
                case UNKNOWN -> AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
                case OPTION -> AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK);
                case STRING -> AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
                case VARIABLE -> AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
            };
        }

        @Override
        public void setErrorPattern(Pattern errorPattern) {
            // unused
        }

        @Override
        public void setErrorIndex(int errorIndex) {
            // unused
        }
    }
}
