package io.powerj.cmdlets;

/** A process that does nothing for a minute, started by {@link PsKillTest}. */
public final class Sleeper {

    private Sleeper() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(60_000);
    }
}
