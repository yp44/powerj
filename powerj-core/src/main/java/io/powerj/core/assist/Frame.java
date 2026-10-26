package io.powerj.core.assist;

import java.util.ArrayList;
import java.util.List;

/** Nesting level at the cursor: command, block { }, or expression parentheses. */
final class Frame {

    enum Kind { COMMAND, BLOCK, EXPRESSION }

    final Kind kind;
    final int start;
    final Frame parent;
    final List<Integer> stageStarts = new ArrayList<>();

    Frame(Kind kind, int start, Frame parent) {
        this.kind = kind;
        this.start = start;
        this.parent = parent;
        stageStarts.add(start);
    }

    int stageStart() {
        return stageStarts.getLast();
    }
}
