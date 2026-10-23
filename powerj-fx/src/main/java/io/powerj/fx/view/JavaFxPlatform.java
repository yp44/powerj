package io.powerj.fx.view;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import io.powerj.fx.runtime.FxPlatform;

/** The real toolkit. */
final class JavaFxPlatform implements FxPlatform {

    @Override
    public void startup() {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // Started before (by this class, or by another module): nothing to wait for.
            Platform.setImplicitExit(false);
            return;
        }
        Platform.setImplicitExit(false);
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the JavaFX toolkit did not start in 30 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    @Override
    public void runLater(Runnable action) {
        Platform.runLater(action);
    }
}
