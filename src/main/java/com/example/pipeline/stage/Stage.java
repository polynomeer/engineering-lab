package com.example.pipeline.stage;

public interface Stage {
    String name();

    void runOnce() throws InterruptedException;

    default void runLoop() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            runOnce();
        }
    }
}
