package com.pnomeer.lab.worker;

import java.io.*;
import java.util.Objects;

/** Worker-side stdin monitor, started after readCommand. Callback must stop the harness and its resources. */
public final class ParentConnection {
    private ParentConnection() {}
    public static Thread watch(InputStream control, Runnable lost) {
        Objects.requireNonNull(control); Objects.requireNonNull(lost);
        Thread thread = new Thread(() -> {
            try { control.read(); } catch (IOException ignored) {}
            // EOF, unexpected additional command data and broken control pipes all revoke ownership.
            lost.run();
        }, "worker-parent-watch");
        thread.setDaemon(true); thread.start(); return thread;
    }
}
