package com.pnomeer.lab.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;

/** One process / one Run. Synchronous reads and writes propagate pipe backpressure. Not thread-safe. */
public final class WorkerProtocol {
    private WorkerProtocol() {}
    public record Identity(String runId, String moduleId, String experimentId, String definitionSha256, String artifactSha256) {
        public Identity {
            for (String id : List.of(runId, moduleId, experimentId))
                if (!id.matches("[a-zA-Z0-9._-]{1,100}")) throw new IllegalArgumentException("Invalid identity");
            for (String hash : List.of(definitionSha256, artifactSha256))
                if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid hash");
        }
    }
    public record Event(long sequence, String type, ObjectNode payload) {}

    /** Reads exactly one LF-terminated command; leaves stdin open for a future parent-EOF monitor. */
    public static ObjectNode readCommand(InputStream input, ProtocolLimits limits) throws IOException {
        byte[] bytes = new Lines(input, limits).next();
        if (bytes == null) throw new ProtocolException("MISSING_COMMAND");
        ObjectNode command = WireJson.parse(bytes); WireJson.command(command); return command;
    }
    public static void writeCommand(OutputStream output, ObjectNode command, ProtocolLimits limits) throws IOException {
        WireJson.command(command); byte[] bytes = WireJson.encode(command);
        if (bytes.length > limits.recordBytes() || bytes.length + 1L > limits.totalBytes()) throw new ProtocolException("COMMAND_LIMIT");
        output.write(bytes); output.write('\n'); output.flush();
    }

    public static final class Reader {
        private final Lines lines;
        private final State state;
        private boolean eof;
        private boolean failed;
        public Reader(InputStream input, Identity expected, ProtocolLimits limits) {
            lines = new Lines(input, limits); state = new State(expected, limits);
        }
        /** Null means clean EOF after RESULT, not a successful Run or successful evidence persistence. */
        public Event next() throws IOException {
            if (failed) throw new ProtocolException("STREAM_FAILED");
            if (eof) return null;
            try {
                byte[] bytes = lines.next();
                if (bytes == null) {
                    if (!state.result) throw new ProtocolException("MISSING_RESULT");
                    eof = true; return null;
                }
                return state.accept(WireJson.parse(bytes));
            } catch (IOException | RuntimeException e) { failed = true; throw e; }
        }
        /** Process/DB/artifact ownership is intentionally outside the codec. */
        public void verifyExit(int exitCode) throws ProtocolException {
            if (failed || !eof || !state.result) throw new ProtocolException("INCOMPLETE_STREAM");
            if (exitCode != 0) throw new ProtocolException("NONZERO_EXIT");
        }
    }

    public static final class Writer {
        private final OutputStream output;
        private final State state;
        private final ProtocolLimits limits;
        private long bytesWritten;
        private boolean failed;
        public Writer(OutputStream output, Identity identity, ProtocolLimits limits) {
            this.output = output; this.limits = limits; state = new State(identity, limits);
        }
        /** Validates before writing; any failure poisons the session. Never retries a partially written frame. */
        public void emit(String type, ObjectNode payload) throws IOException {
            if (failed) throw new ProtocolException("STREAM_FAILED");
            try {
                ObjectNode event = WireJson.object().put("protocolVersion", 1).put("runId", state.identity.runId())
                        .put("sequence", state.sequence).put("type", type);
                event.set("payload", payload);
                byte[] bytes = WireJson.encode(event);
                if (bytes.length > limits.recordBytes()) throw new ProtocolException("RECORD_LIMIT");
                if (bytesWritten + bytes.length + 1 > limits.totalBytes()) throw new ProtocolException("TOTAL_LIMIT");
                state.accept(event);
                output.write(bytes); output.write('\n'); output.flush(); bytesWritten += bytes.length + 1;
            } catch (IOException | RuntimeException e) { failed = true; throw e; }
        }
        public void result(String status, String reason, ObjectNode summary) throws IOException {
            ObjectNode payload = WireJson.object().put("status", status).put("reason", reason).put("lastSequence", state.sequence);
            payload.set("summary", summary);
            ObjectNode counts = WireJson.object(); state.counts.forEach(counts::put); payload.set("counts", counts);
            emit("RESULT", payload);
        }
    }

    private static final class State {
        private final Identity identity;
        private final ProtocolLimits limits;
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private long sequence;
        private int phase = -1;
        private boolean measured;
        private boolean failedCheck;
        private boolean result;
        State(Identity identity, ProtocolLimits limits) {
            this.identity = Objects.requireNonNull(identity); this.limits = Objects.requireNonNull(limits);
            for (String type : List.of("HELLO", "PHASE", "METRIC", "CHECK", "ARTIFACT")) counts.put(type, 0);
        }
        Event accept(ObjectNode event) throws ProtocolException {
            if (result) throw new ProtocolException("AFTER_RESULT");
            if (sequence >= limits.events()) throw new ProtocolException("EVENT_LIMIT");
            WireJson.fields(event, "protocolVersion", "runId", "sequence", "type", "payload");
            WireJson.integer(event, "protocolVersion", 1, 1);
            if (!identity.runId().equals(WireJson.text(event, "runId", 100, false))) throw new ProtocolException("RUN_MISMATCH");
            if (WireJson.integer(event, "sequence", 0, 99999) != sequence) throw new ProtocolException("SEQUENCE");
            String type = WireJson.text(event, "type", 20, false);
            if (!event.path("payload").isObject()) throw new ProtocolException("PAYLOAD");
            ObjectNode payload = (ObjectNode) event.path("payload"); WireJson.payload(type, payload);
            if (sequence == 0 && !type.equals("HELLO") || sequence > 0 && type.equals("HELLO")) throw new ProtocolException("HELLO_ORDER");
            switch (type) {
                case "HELLO" -> {
                    if (!identity.moduleId().equals(payload.path("moduleId").textValue())
                            || !identity.experimentId().equals(payload.path("experimentId").textValue())
                            || !identity.definitionSha256().equals(payload.path("definitionSha256").textValue())
                            || !identity.artifactSha256().equals(payload.path("artifactSha256").textValue())) throw new ProtocolException("IDENTITY_MISMATCH");
                }
                case "PHASE" -> {
                    int next = List.of("PREPARING", "MEASURING", "FINALIZING").indexOf(payload.path("phase").textValue());
                    if (next <= phase || phase == -1 && next != 0) throw new ProtocolException("PHASE_ORDER");
                    phase = next; if (next == 1) measured = true;
                }
                case "RESULT" -> {
                    if (payload.path("lastSequence").longValue() != sequence) throw new ProtocolException("LAST_SEQUENCE");
                    for (var entry : counts.entrySet())
                        if (payload.path("counts").path(entry.getKey()).longValue() != entry.getValue()) throw new ProtocolException("COUNTS");
                    if (payload.path("status").textValue().equals("SUCCEEDED") && (phase != 2 || !measured || failedCheck))
                        throw new ProtocolException("SUCCESS_EVIDENCE");
                    result = true;
                }
                case "ARTIFACT" -> { if (phase != 2) throw new ProtocolException("ARTIFACT_PHASE"); }
                case "METRIC", "CHECK" -> {
                    if (phase < 1) throw new ProtocolException("EVIDENCE_PHASE");
                    if (type.equals("CHECK") && payload.path("status").textValue().equals("FAIL")) failedCheck = true;
                }
                default -> throw new ProtocolException("EVENT_TYPE");
            }
            if (!type.equals("RESULT")) counts.compute(type, (k, v) -> v + 1);
            return new Event(sequence++, type, payload);
        }
    }

    /** No read-ahead: command parsing must not consume bytes intended for the parent-EOF monitor. */
    private static final class Lines {
        private final InputStream input;
        private final ProtocolLimits limits;
        private long total;
        Lines(InputStream input, ProtocolLimits limits) { this.input = Objects.requireNonNull(input); this.limits = Objects.requireNonNull(limits); }
        byte[] next() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(limits.recordBytes(), 8192));
            while (true) {
                int b = input.read();
                if (b == -1) {
                    if (line.size() == 0) return null;
                    throw new ProtocolException("TRUNCATED_FRAME");
                }
                if (++total > limits.totalBytes()) throw new ProtocolException("TOTAL_LIMIT");
                if (b == '\n') {
                    if (line.size() == 0) throw new ProtocolException("EMPTY_FRAME");
                    return line.toByteArray();
                }
                if (line.size() >= limits.recordBytes()) throw new ProtocolException("RECORD_LIMIT");
                line.write(b);
            }
        }
    }
}
