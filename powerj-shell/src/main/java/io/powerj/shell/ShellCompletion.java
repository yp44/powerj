package io.powerj.shell;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import io.powerj.core.exec.Completions;
import io.powerj.core.exec.Highlights;
import io.powerj.core.exec.Interpreter;

/** Complétion (Tab) et coloration de la saisie (spécification FR-08, FR-21 à FR-26), branchées sur JLine. */
final class ShellCompletion {

    private ShellCompletion() {
    }

    /** Propositions calculées par {@link ShellParser} au moment de découper la ligne. */
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

    /** Coloration : cmdlet et commande interne en vert, natif en cyan, inconnu en rouge… (FR-08). */
    static Highlighter highlighter(Interpreter interpreter) {
        return new Colors(interpreter);
    }

    private static final class Colors implements Highlighter {

        private static final long CACHE_MILLIS = 2_000;

        private final Interpreter interpreter;
        /** Nature des noms de commande, mise en cache brièvement (la coloration suit chaque touche). */
        private final Map<String, Interpreter.CommandKind> kinds = new HashMap<>();
        private long cachedAt;

        Colors(Interpreter interpreter) {
            this.interpreter = interpreter;
        }

        @Override
        public AttributedString highlight(LineReader reader, String buffer) {
            if (System.currentTimeMillis() - cachedAt > CACHE_MILLIS) {
                kinds.clear();
                cachedAt = System.currentTimeMillis();
            }
            List<Highlights.Span> spans;
            try {
                spans = Highlights.of(buffer, name -> kinds.computeIfAbsent(name, interpreter::commandKind),
                        interpreter.session().java()::isStaticReference);
            } catch (RuntimeException e) {
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
            // non utilisé
        }

        @Override
        public void setErrorIndex(int errorIndex) {
            // non utilisé
        }
    }
}
