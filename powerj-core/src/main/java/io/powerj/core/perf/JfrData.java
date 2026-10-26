package io.powerj.core.perf;

import java.util.List;

import io.powerj.api.perf.AllocStat;
import io.powerj.api.perf.GcStat;
import io.powerj.api.perf.HotMethod;

/** What the profiler recorded during a line, per stage. */
record JfrData(List<HotMethod> hot, List<GcStat> gc, List<AllocStat> alloc) {

    static final JfrData EMPTY = new JfrData(List.of(), List.of(), List.of());
}
