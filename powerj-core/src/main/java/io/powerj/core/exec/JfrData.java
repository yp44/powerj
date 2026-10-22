package io.powerj.core.exec;

import java.util.List;

import io.powerj.api.AllocStat;
import io.powerj.api.GcStat;
import io.powerj.api.HotMethod;

/** What the profiler recorded during a line, per stage. */
record JfrData(List<HotMethod> hot, List<GcStat> gc, List<AllocStat> alloc) {

    static final JfrData EMPTY = new JfrData(List.of(), List.of(), List.of());
}
