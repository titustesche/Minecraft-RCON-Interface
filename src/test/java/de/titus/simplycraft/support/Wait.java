package de.titus.simplycraft.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class Wait {

    private Wait() {
    }

    public static void until(String what, Duration timeout, BooleanSupplier condition) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }
}
