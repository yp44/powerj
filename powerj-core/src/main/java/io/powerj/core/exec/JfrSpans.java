package io.powerj.core.exec;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** The {@link Profiling} spans backed by Flight Recorder events; only loaded when {@code jdk.jfr} is present. */
final class JfrSpans {

    private JfrSpans() {
    }

    @Name("powerj.Command")
    @Label("PowerJ command line")
    @Category("PowerJ")
    @Description("A line executed by the shell: the names of its commands, never their arguments")
    @StackTrace(false)
    public static final class CommandEvent extends Event {
        @Label("Commands")
        public String commands;
        @Label("Statements")
        public int statements;
    }

    @Name("powerj.Stage")
    @Label("PowerJ pipeline stage")
    @Category("PowerJ")
    @Description("A stage of a pipeline, from its start to its end; the stages of a pipeline overlap")
    @StackTrace(false)
    public static final class StageEvent extends Event {
        @Label("Statement")
        @Description("Number of the statement in the line, from 1")
        public int statement;
        @Label("Position")
        @Description("Position of the stage in its pipeline, from 1")
        public int position;
        @Label("Command")
        public String command;
        @Label("Kind")
        @Description("builtin, cmdlet, native or expression")
        public String kind;
        @Label("Objects read")
        @Description("-1 for the first stage or when not counted")
        public long in;
        @Label("Values produced")
        public long out;
    }

    static boolean enabled() {
        return new StageEvent().isEnabled();
    }

    static Profiling.Span command(String commands, int statements) {
        var event = new CommandEvent();
        if (!event.isEnabled()) {
            return (_, _) -> { };
        }
        event.begin();
        return (_, _) -> {
            event.commands = commands;
            event.statements = statements;
            event.commit();
        };
    }

    static Profiling.Span stage(String command, String kind, int statement, int position) {
        var event = new StageEvent();
        if (!event.isEnabled()) {
            return (_, _) -> { };
        }
        event.begin();
        return (in, out) -> {
            event.statement = statement;
            event.position = position;
            event.command = command;
            event.kind = kind;
            event.in = in;
            event.out = out;
            event.commit();
        };
    }
}
