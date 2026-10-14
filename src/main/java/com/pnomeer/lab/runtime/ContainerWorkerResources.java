package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;

/** Server-owned module resources; scenario parameters never select network or credentials. */
@FunctionalInterface
public interface ContainerWorkerResources {
    /** Allocate only local bookkeeping here; all external mutations belong in prepare. */
    Session open(ObjectNode command, Path workspace, String artifactSha256) throws Exception;
    default String profile() { return "unrecoverable"; }
    default Recovery recovery(Path workspace, String runId, String artifactSha256) throws Exception {
        throw new java.io.IOException("Resource recovery is not configured");
    }
    interface Recovery {
        String networkId();
        void finish() throws Exception;
    }
    interface Session {
        void prepare() throws Exception;
        String networkId();
        Map<String, String> environment();
        /** Reclaim only after the worker boundary is confirmed absent (or was never attempted). */
        void finish(boolean workerRemoved) throws Exception;
    }
    ContainerWorkerResources NONE = new ContainerWorkerResources() {
        public String profile() { return "none-v1"; }
        public Recovery recovery(Path workspace, String runId, String artifactSha256) {
            return new Recovery() {
                public String networkId() { return "none"; }
                public void finish() { }
            };
        }
        public Session open(ObjectNode command, Path workspace, String artifactSha256) {
            return new Session() {
                public void prepare() { }
                public String networkId() { return "none"; }
                public Map<String, String> environment() { return Map.of(); }
                public void finish(boolean workerRemoved) { }
            };
        }
    };
}
