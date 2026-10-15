package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.RunStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Admission budget for retained controller workspace/report bytes, not a filesystem hard quota. */
public final class WorkerStorageBudget {
    @ConfigurationProperties("lab.worker-storage")
    public record Properties(long maxBytes, long reservationBytes, int maxEntries) {
        public Properties {
            if (maxBytes < 1 || reservationBytes < 1 || reservationBytes > maxBytes || maxEntries < 1 || maxEntries > 1_000_000)
                throw new IllegalArgumentException("Invalid worker storage budget");
        }
    }
    private final Path root;
    private final Properties properties;
    private long reserved;
    public WorkerStorageBudget(Path root, Properties properties) { this.root = root; this.properties = properties; }
    synchronized long usage() throws IOException {
        long[] bytes = {0}; int[] entries = {0};
        for (String name : new String[]{"worker-workspaces", "worker-reports"}) {
            Path directory = root.resolve(name);
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(directory, java.util.Set.of(), 32, new SimpleFileVisitor<>() {
                private void count(Path path, BasicFileAttributes attributes) throws IOException {
                    if (++entries[0] > properties.maxEntries() || attributes.isSymbolicLink()
                            || (!attributes.isDirectory() && !attributes.isRegularFile())) throw new IOException("Unsafe or excessive worker storage entries");
                    if (attributes.isRegularFile()) {
                        if (attributes.size() > properties.maxBytes() - bytes[0]) throw new IOException("Worker storage budget exceeded");
                        bytes[0] += attributes.size();
                    }
                }
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException { count(path,attributes); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isDirectory()) throw new IOException("Worker storage depth exceeded");
                    count(path,attributes); return FileVisitResult.CONTINUE;
                }
            });
        }
        return bytes[0];
    }
    synchronized void check() throws IOException {
        if (usage() > properties.maxBytes() - reserved - properties.reservationBytes()) throw new IOException("Worker storage budget exhausted");
    }
    synchronized AutoCloseable reserve() throws IOException {
        check(); reserved += properties.reservationBytes();
        return new AutoCloseable() {
            private boolean closed;
            public void close() { synchronized (WorkerStorageBudget.this) { if (!closed) { reserved -= properties.reservationBytes(); closed = true; } } }
        };
    }
    ExperimentExecutor wrap(ExperimentExecutor delegate) {
        return new ExperimentExecutor() {
            public Map<String,String> parameters() { return delegate.parameters(); }
            public void preflight() {
                delegate.preflight();
                try { check(); } catch(IOException ex) { throw new LocalJvmRegistration.WorkerUnavailableException("WORKER_STORAGE_BUDGET"); }
            }
            public <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output) {
                AutoCloseable lease;
                try { lease = reserve(); }
                catch(IOException ex) { return new Outcome(RunStatus.FAILED,ExperimentSummary.empty(),"WORKER_STORAGE_BUDGET",false); }
                try { return delegate.execute(experiment,scenario,output); }
                finally { try { lease.close(); } catch(Exception ex) { throw new IllegalStateException(ex); } }
            }
        };
    }
}
