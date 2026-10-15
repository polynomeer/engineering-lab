package com.pnomeer.lab.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerStorageBudgetTest {
    @TempDir Path root;
    @Test void measuresCombinedRetainedBytesAndReservesConcurrentCapacity() throws Exception {
        Files.createDirectories(root.resolve("worker-workspaces/run"));
        Files.createDirectories(root.resolve("worker-reports"));
        Files.write(root.resolve("worker-workspaces/run/report.json"),new byte[3072]);
        Files.write(root.resolve("worker-reports/report.bin"),new byte[1024]);
        var budget = new WorkerStorageBudget(root,new WorkerStorageBudget.Properties(8192,2048,100));
        assertEquals(4096,budget.usage());
        var first = budget.reserve(); var second = budget.reserve();
        assertThrows(IOException.class,budget::reserve);
        first.close(); first.close();
        var third = budget.reserve();
        assertThrows(IOException.class,budget::reserve);
        second.close(); third.close();
        Files.write(root.resolve("worker-reports/retained.bin"),new byte[2049]);
        assertThrows(IOException.class,budget::reserve);
        assertEquals(6145,budget.usage());
        assertThrows(IOException.class,()->new WorkerStorageBudget(root,new WorkerStorageBudget.Properties(8192,2048,100)).reserve());
    }
    @Test void refusesSymlinksExcessiveEntriesAndOversizeWithoutDeletingEvidence() throws Exception {
        Path directory = Files.createDirectory(root.resolve("worker-workspaces"));
        Path file = Files.write(directory.resolve("data"),new byte[65]);
        var budget = new WorkerStorageBudget(root,new WorkerStorageBudget.Properties(64,16,10));
        assertThrows(IOException.class,budget::usage);
        assertEquals(65,Files.size(file));
        Files.write(file,new byte[1]);
        assertThrows(IOException.class,()->new WorkerStorageBudget(root,new WorkerStorageBudget.Properties(64,16,1)).usage());
        Files.createSymbolicLink(directory.resolve("link"),file);
        assertThrows(IOException.class,budget::usage);
    }
    @Test void wrapperRejectsBeforeTransportAndReleasesAfterFailure() throws Exception {
        var budget = new WorkerStorageBudget(root,new WorkerStorageBudget.Properties(32,32,10));
        var delegate = org.mockito.Mockito.mock(ExperimentExecutor.class);
        var wrapped = budget.wrap(delegate);
        try (var lease = budget.reserve()) {
            assertThrows(LocalJvmRegistration.WorkerUnavailableException.class,wrapped::preflight);
            assertEquals("WORKER_STORAGE_BUDGET",wrapped.execute(null,null,null).failure());
            org.mockito.Mockito.verify(delegate,org.mockito.Mockito.never()).execute(null,null,null);
        }
        org.mockito.Mockito.when(delegate.execute(null,null,null)).thenThrow(new IllegalStateException("fixture"));
        assertThrows(IllegalStateException.class,()->wrapped.execute(null,null,null));
        try(var lease=budget.reserve()) { assertNotNull(lease); }
    }
    @Test void concurrentReservationsNeverExceedBudget() throws Exception {
        var budget = new WorkerStorageBudget(root,new WorkerStorageBudget.Properties(32,32,10));
        var acquired = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(()-> { try(var lease=budget.reserve()) { acquired.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); } return null; });
            try { assertTrue(acquired.await(5,TimeUnit.SECONDS)); assertThrows(IOException.class,budget::reserve); }
            finally { release.countDown(); }
            first.get(5,TimeUnit.SECONDS);
            try(var lease=budget.reserve()) { assertNotNull(lease); }
        }
    }
}
