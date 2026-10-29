package io.powerj.shell;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import io.powerj.core.runtime.PreviewSettings;

/**
 * The two settings of the preview that a user can change from the banner of a preview that timed out (FR-66), and the
 * keys that change them: Alt+◄ and Alt+► choose the setting, Alt+▲ and Alt+▼ change its value by one step. Which key
 * does what lives here; the {@link Previewer} binds the keys, applies the settings and evaluates the line again.
 */
final class PreviewControl {

    /** The Alt+arrow keys that act on the control. */
    enum Key {
        LEFT("\033[1;3D"), RIGHT("\033[1;3C"), UP("\033[1;3A"), DOWN("\033[1;3B");

        private final String sequence;

        Key(String sequence) {
            this.sequence = sequence;
        }

        /** What a terminal sends for Alt and this arrow. */
        String sequence() {
            return sequence;
        }
    }

    /** What the keys act on. */
    enum Field { DEADLINE, LIMIT }

    private static final AttributedStyle SELECTED = AttributedStyle.DEFAULT.inverse();

    private volatile Field selected = Field.DEADLINE;

    Field selected() {
        return selected;
    }

    /**
     * Applies a key: ◄ and ► choose the setting, ▲ and ▼ change the chosen one by a step.
     *
     * @return the settings after the key; {@code current} if the key chose a setting, or if no step is left
     */
    PreviewSettings press(Key key, PreviewSettings current) {
        return switch (key) {
            case LEFT -> {
                selected = Field.DEADLINE;
                yield current;
            }
            case RIGHT -> {
                selected = Field.LIMIT;
                yield current;
            }
            case UP -> step(current, 1);
            case DOWN -> step(current, -1);
        };
    }

    private PreviewSettings step(PreviewSettings current, int direction) {
        return selected == Field.DEADLINE ? current.withDeadlineStep(direction) : current.withLimitStep(direction);
    }

    /**
     * The two settings as the banner shows them, the chosen one between brackets and in reverse video:
     * {@code [duration 400 ms]  max/stage 50}. A setting that cannot go up any more says so.
     *
     * @param brief the form for a narrow terminal: {@code [400 ms]  50/stage}
     */
    AttributedString fields(PreviewSettings settings, boolean brief) {
        var builder = new AttributedStringBuilder();
        field(builder, deadline(settings, brief), selected == Field.DEADLINE);
        builder.append("  ");
        field(builder, limit(settings, brief), selected == Field.LIMIT);
        return builder.toAttributedString();
    }

    private static void field(AttributedStringBuilder builder, String text, boolean chosen) {
        if (chosen) {
            builder.style(SELECTED).append('[').append(text).append(']').style(AttributedStyle.DEFAULT);
        } else {
            builder.append(text);
        }
    }

    private static String deadline(PreviewSettings settings, boolean brief) {
        if (brief) {
            return Messages.get("preview.control.durationBrief", settings.deadlineMillis());
        }
        return Messages.get(settings.deadlineAtMax() ? "preview.control.durationMax" : "preview.control.duration",
                settings.deadlineMillis());
    }

    private static String limit(PreviewSettings settings, boolean brief) {
        if (brief) {
            return Messages.get("preview.control.limitBrief", settings.stageLimit());
        }
        return Messages.get(settings.limitAtMax() ? "preview.control.limitMax" : "preview.control.limit",
                settings.stageLimit());
    }

    /** What the keys do, in words. */
    static String help(boolean brief) {
        return Messages.get(brief ? "preview.control.helpBrief" : "preview.control.help");
    }
}
