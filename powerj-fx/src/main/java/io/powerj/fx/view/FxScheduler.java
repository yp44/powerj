package io.powerj.fx.view;

import java.time.Duration;

import javafx.animation.PauseTransition;

import io.powerj.fx.model.Scheduler;

/** Runs the tasks on the toolkit thread, after a pause. It must be called from that thread. */
final class FxScheduler implements Scheduler {

    @Override
    public Scheduled schedule(Runnable task, Duration delay) {
        PauseTransition pause = new PauseTransition(javafx.util.Duration.millis(delay.toMillis()));
        pause.setOnFinished(_ -> task.run());
        pause.play();
        return pause::stop;
    }
}
