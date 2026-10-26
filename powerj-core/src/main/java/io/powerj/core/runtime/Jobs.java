package io.powerj.core.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.powerj.core.i18n.Messages;

/**
 * Background jobs of a session (specification FR-75). A job is tracked from its launch until its end has been
 * reported, by the notification before the prompt or by {@code jobs}. Thread-safe: the end of a process is recorded
 * by a JDK thread.
 */
public final class Jobs {

    private final Map<Integer, Job> jobs = new TreeMap<>();

    /** Records a job, numbered with the smallest free number, and follows the end of its last process. */
    Job add(String command, List<Process> processes) {
        Process last = processes.getLast();
        Job job;
        synchronized (jobs) {
            int id = 1;
            while (jobs.containsKey(id)) {
                id++;
            }
            job = new Job(id, last.pid(), command, Instant.now(), true, null);
            jobs.put(id, job);
        }
        last.onExit().thenAccept(process -> finished(job.id(), process.exitValue()));
        return job;
    }

    private void finished(int id, int exitCode) {
        synchronized (jobs) {
            jobs.computeIfPresent(id, (_, job) ->
                    new Job(job.id(), job.pid(), job.command(), job.start(), false, exitCode));
        }
    }

    /** {@code true} while a job is running. */
    boolean anyRunning() {
        synchronized (jobs) {
            return jobs.values().stream().anyMatch(Job::running);
        }
    }

    /** Jobs tracked, by number; the ended ones are then forgotten, as they have been reported. */
    public List<Job> list() {
        synchronized (jobs) {
            List<Job> listed = new ArrayList<>(jobs.values());
            jobs.values().removeIf(job -> !job.running());
            return listed;
        }
    }

    /** Notifications of the jobs ended since the last call ({@code [1] done xeyes (exit 0)}); forgets them. */
    public List<String> drainFinished() {
        List<String> messages = new ArrayList<>();
        synchronized (jobs) {
            var it = jobs.values().iterator();
            while (it.hasNext()) {
                Job job = it.next();
                if (!job.running()) {
                    messages.add(Messages.get("background.done", job.id(), job.command(), job.exitCode()));
                    it.remove();
                }
            }
        }
        return messages;
    }
}
