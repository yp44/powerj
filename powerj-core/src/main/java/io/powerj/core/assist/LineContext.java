package io.powerj.core.assist;

import io.powerj.core.lang.Lexer;
import io.powerj.core.lang.Scanning;

/**
 * Where the cursor is in the line: the nesting level (command, block, expression) it sits in, and whether it is
 * inside a string.
 *
 * @param inString    the cursor is inside a string that is not closed
 * @param stringStart position of the opening quote of that string, or -1
 */
record LineContext(Frame frame, boolean inString, int stringStart) {

    static LineContext of(String line, int cursor) {
        Frame frame = new Frame(Frame.Kind.COMMAND, 0, null);
        String typed = line.substring(0, cursor);
        int i = 0;
        while (i < cursor) {
            char c = line.charAt(i);
            switch (c) {
                case '"' -> {
                    int end = Scanning.stringEnd(typed, i);
                    if (end < 0) {
                        return new LineContext(frame, true, i);
                    }
                    i = end;
                    continue;
                }
                case '{' -> frame = new Frame(Frame.Kind.BLOCK, i + 1, frame);
                case '(', '[' -> {
                    boolean command = frame.kind == Frame.Kind.COMMAND && c == '('
                            && (i == 0 || !Character.isJavaIdentifierPart(line.charAt(i - 1)));
                    frame = new Frame(command ? Frame.Kind.COMMAND : Frame.Kind.EXPRESSION, i + 1, frame);
                }
                case '}', ')', ']' -> {
                    if (frame.parent != null) {
                        frame = frame.parent;
                    }
                }
                case '|' -> {
                    if (frame.kind == Frame.Kind.COMMAND) {
                        if (i + 1 < cursor && line.charAt(i + 1) == '|') {
                            frame.stageStarts.clear(); // ||: new statement
                            i++;
                        }
                        frame.stageStarts.add(i + 1);
                    }
                }
                case ';' -> {
                    if (frame.kind == Frame.Kind.COMMAND) {
                        frame.stageStarts.clear();
                        frame.stageStarts.add(i + 1);
                    }
                }
                case '&' -> {
                    if (frame.kind == Frame.Kind.COMMAND && i + 1 < cursor && line.charAt(i + 1) == '&') {
                        frame.stageStarts.clear();
                        frame.stageStarts.add(i + 2);
                        i++;
                    } else if (frame.kind == Frame.Kind.COMMAND && isBackground(line, i, cursor)) {
                        frame.stageStarts.clear(); // &: background, new statement (FR-75)
                        frame.stageStarts.add(i + 1);
                    }
                }
                default -> { }
            }
            i++;
        }
        return new LineContext(frame, false, -1);
    }

    /** Lone {@code &} that ends a statement (FR-75): not inside a word such as {@code ?a=1&b=2}. */
    private static boolean isBackground(String line, int at, int cursor) {
        return at + 1 >= cursor || line.charAt(at + 1) == ';' || Lexer.isBlank(line.charAt(at + 1))
                || at > 0 && Lexer.isBlank(line.charAt(at - 1));
    }
}
