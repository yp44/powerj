package io.powerj.core.exec;

import java.time.Instant;

import io.powerj.api.Display;

/**
 * A command launched in the background with {@code &} (specification FR-75), as listed by {@code jobs}.
 *
 * @param id       job number, shown as {@code [1]}
 * @param pid      process number (the last process of a pipeline)
 * @param command  command line, rebuilt from the program names and their arguments
 * @param start    launch time
 * @param running  {@code true} until the process has ended
 * @param exitCode exit code once ended, otherwise {@code null}
 */
@Display(columns = {"id", "pid", "running", "exitCode", "command"})
public record Job(int id, long pid, String command, Instant start, boolean running, Integer exitCode) { }
