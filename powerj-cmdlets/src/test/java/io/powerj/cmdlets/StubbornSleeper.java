package io.powerj.cmdlets;

/** A process that a normal stop does not end: its shutdown hook never returns (the JVM cannot ignore SIGTERM). */
public final class StubbornSleeper {

    private StubbornSleeper() {
    }

    public static void main(String[] args) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(120_000);
            } catch (InterruptedException _) {
                // ends only when it is killed
            }
        }));
        System.out.println("ready"); // the parent reads it: the hook is registered
        System.out.flush();
        Thread.sleep(120_000);
    }
}
