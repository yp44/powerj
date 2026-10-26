package io.powerj.shell;

/** Runs one command typed by the user or read from the profile. */
@FunctionalInterface
interface LineRunner {

    void run(String line) throws Exception;
}
