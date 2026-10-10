package com.pnomeer.lab.worker;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProcessTreeTest {
    @Test void overflowDoesNotConfirmCleanup() {
        var children = IntStream.range(0, WorkerProcessTree.MAX_DESCENDANTS + 1)
                .mapToObj(i -> handle(i, false, () -> Optional.of(Instant.EPOCH), new AtomicInteger())).toList();
        assertFalse(new WorkerProcessTree(root(children)).stop(Duration.ofSeconds(1)));
    }
    @Test void missingIdentityDoesNotSignalOrConfirmCleanup() {
        var signals = new AtomicInteger();
        var child = handle(1, true, Optional::empty, signals);
        assertFalse(new WorkerProcessTree(root(List.of(child))).stop(Duration.ofSeconds(1)));
        assertEquals(0, signals.get());
    }
    @Test void reusedPidIsNotSignalled() {
        var reads = new AtomicInteger();
        var signals = new AtomicInteger();
        var child = handle(1, true,
                () -> Optional.of(reads.getAndIncrement() == 0 ? Instant.EPOCH : Instant.EPOCH.plusSeconds(1)), signals);
        assertTrue(new WorkerProcessTree(root(List.of(child))).stop(Duration.ofSeconds(1)));
        assertEquals(0, signals.get());
    }
    private static ProcessHandle handle(long pid, boolean alive, Supplier<Optional<Instant>> start, AtomicInteger signals) {
        var info = (ProcessHandle.Info) Proxy.newProxyInstance(ProcessHandle.Info.class.getClassLoader(),
                new Class<?>[]{ProcessHandle.Info.class}, (proxy, method, args) -> {
                    if (method.getName().equals("startInstant")) return start.get();
                    return Optional.empty();
                });
        return (ProcessHandle) Proxy.newProxyInstance(ProcessHandle.class.getClassLoader(),
                new Class<?>[]{ProcessHandle.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "pid" -> pid;
                    case "info" -> info;
                    case "isAlive" -> alive;
                    case "hashCode" -> Long.hashCode(pid);
                    case "equals" -> proxy == args[0];
                    case "destroy", "destroyForcibly" -> { signals.incrementAndGet(); yield true; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private static Process root(List<ProcessHandle> children) {
        return new Process() {
            public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            public InputStream getInputStream() { return InputStream.nullInputStream(); }
            public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            public int waitFor() { return 0; }
            public int exitValue() { return 0; }
            public void destroy() {}
            public boolean isAlive() { return false; }
            public Stream<ProcessHandle> descendants() { return children.stream(); }
        };
    }
}
