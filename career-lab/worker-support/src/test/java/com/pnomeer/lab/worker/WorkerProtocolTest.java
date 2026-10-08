package com.pnomeer.lab.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProtocolTest {
    private static final Path CONTRACTS = Path.of(System.getProperty("worker.contracts"));
    private static final ProtocolLimits LIMITS = ProtocolLimits.DEFAULT;
    private static final WorkerProtocol.Identity IDENTITY = new WorkerProtocol.Identity(
            "fixture-run", "fixture-module", "fixture.sequence", "a".repeat(64), "b".repeat(64));
    private byte[] fixture(String name) throws IOException { return Files.readAllBytes(CONTRACTS.resolve("fixtures/" + name)); }
    private WorkerProtocol.Reader reader(byte[] bytes) { return new WorkerProtocol.Reader(new ByteArrayInputStream(bytes), IDENTITY, LIMITS); }
    private List<ObjectNode> success() throws IOException {
        List<ObjectNode> values = new ArrayList<>();
        for (String line : new String(fixture("success.ndjson"), StandardCharsets.UTF_8).lines().toList()) values.add(WireJson.parse(line.getBytes(StandardCharsets.UTF_8)));
        return values;
    }
    private byte[] frames(List<ObjectNode> events) throws IOException {
        var out = new ByteArrayOutputStream();
        for (var event : events) { out.write(WireJson.encode(event)); out.write('\n'); }
        return out.toByteArray();
    }
    private void reject(byte[] bytes, String reason) {
        var reader = reader(bytes);
        assertEquals(reason, assertThrows(ProtocolException.class, () -> { while (reader.next() != null) {} }).getMessage());
        assertEquals("STREAM_FAILED", assertThrows(ProtocolException.class, reader::next).getMessage());
        assertThrows(ProtocolException.class, () -> reader.verifyExit(0));
    }
    @ParameterizedTest @ValueSource(strings = {"success.ndjson", "failure.ndjson"})
    void acceptsCompleteSuccessAndReportedFailureWithoutConfusingTransportWithOutcome(String name) throws Exception {
        var reader = reader(fixture(name));
        WorkerProtocol.Event event, last = null;
        while ((event = reader.next()) != null) last = event;
        assertNotNull(last); assertEquals("RESULT", last.type());
        assertEquals(name.startsWith("success") ? "SUCCEEDED" : "FAILED", last.payload().path("status").textValue());
        reader.verifyExit(0);
        assertThrows(ProtocolException.class, () -> reader.verifyExit(9));
    }
    @ParameterizedTest @ValueSource(strings = {"sequence-gap", "identity-mismatch", "counts-mismatch", "phase-regression", "unknown-version", "missing-result", "after-result", "malformed"})
    void rejectsSharedFailureFixtures(String name) throws Exception {
        ObjectNode errors = WireJson.parse(fixture("errors.json"));
        reject(fixture(name + ".ndjson"), errors.path(name).textValue());
    }
    @Test void commandRoundTripDoesNotConsumeTheParentControlPipe() throws Exception {
        byte[] source = fixture("command.json");
        var input = new ByteArrayInputStream(concat(source, new byte[]{42}));
        ObjectNode command = WorkerProtocol.readCommand(input, LIMITS);
        assertEquals(42, input.read());
        var out = new ByteArrayOutputStream(); WorkerProtocol.writeCommand(out, command, LIMITS);
        assertEquals(command, WorkerProtocol.readCommand(new ByteArrayInputStream(out.toByteArray()), LIMITS));
        command.put("seed", "42");
        assertThrows(ProtocolException.class, () -> WorkerProtocol.writeCommand(out, command, LIMITS));
    }
    @Test void writerRoundTripsEveryEventAndDoesNotCloseTheOwnedStream() throws Exception {
        var out = new ByteArrayOutputStream(); var writer = new WorkerProtocol.Writer(out, IDENTITY, LIMITS);
        for (ObjectNode event : success()) {
            ObjectNode payload = (ObjectNode) event.path("payload");
            if (event.path("type").textValue().equals("RESULT")) writer.result("SUCCEEDED", "", (ObjectNode) payload.path("summary"));
            else writer.emit(event.path("type").textValue(), payload);
        }
        var reader = reader(out.toByteArray()); int count = 0;
        while (reader.next() != null) count++;
        assertEquals(8, count); reader.verifyExit(0);
        assertThrows(ProtocolException.class, () -> writer.emit("HELLO", (ObjectNode) success().getFirst().path("payload")));
    }
    @Test void boundedFramingRejectsOversizeTotalLimitEventLimitAndTruncation() throws Exception {
        byte[] success = fixture("success.ndjson");
        reject(Arrays.copyOf(success, success.length - 1), "TRUNCATED_FRAME");
        reject(new byte[]{'\n'}, "EMPTY_FRAME");
        var tiny = new WorkerProtocol.Reader(new ByteArrayInputStream(success), IDENTITY, new ProtocolLimits(32, 100000, 100));
        assertEquals("RECORD_LIMIT", assertThrows(ProtocolException.class, tiny::next).getMessage());
        var total = new WorkerProtocol.Reader(new ByteArrayInputStream(success), IDENTITY, new ProtocolLimits(65536, success.length - 1, 100));
        assertEquals("TOTAL_LIMIT", assertThrows(ProtocolException.class, () -> { while (total.next() != null) {} }).getMessage());
        var count = new WorkerProtocol.Reader(new ByteArrayInputStream(success), IDENTITY, new ProtocolLimits(65536, 100000, 7));
        assertEquals("EVENT_LIMIT", assertThrows(ProtocolException.class, () -> { while (count.next() != null) {} }).getMessage());
        var exact = new WorkerProtocol.Reader(new ByteArrayInputStream(success), IDENTITY, new ProtocolLimits(65536, success.length, 8));
        while (exact.next() != null) {} exact.verifyExit(0);
        final int[] reads = {0};
        var endless = new WorkerProtocol.Reader(new InputStream() { public int read() { reads[0]++; return 'x'; } }, IDENTITY, LIMITS);
        assertThrows(ProtocolException.class, endless::next); assertEquals(65537, reads[0]);
    }
    @Test void exactRecordBoundaryIncludesUtf8BytesAndExcludesLf() throws Exception {
        ObjectNode command = WireJson.parse(fixture("command.json")); ((ObjectNode) command.path("parameters")).put("label", "한글");
        byte[] bytes = WireJson.encode(command);
        var input = concat(bytes, new byte[]{'\n'});
        assertEquals(command, WorkerProtocol.readCommand(new ByteArrayInputStream(input), new ProtocolLimits(bytes.length, input.length, 2)));
        assertThrows(ProtocolException.class, () -> WorkerProtocol.readCommand(new ByteArrayInputStream(input), new ProtocolLimits(bytes.length - 1, input.length, 2)));
    }
    @ParameterizedTest @ValueSource(strings = {"{\"a\":1,\"a\":2}", "{} {}", "{\"a\":NaN}", "{\"a\":1e999}"})
    void rejectsAmbiguousOrNonFiniteJson(String json) {
        assertThrows(ProtocolException.class, () -> WireJson.parse(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void rejectsMalformedUtf8ExcessiveNestingAndKeyCounts() throws Exception {
        assertThrows(ProtocolException.class, () -> WireJson.parse(new byte[]{'{','"','x','"',':','"',(byte)0xc3,0x28,'"','}'}));
        String nested = "{\"a\":".repeat(20) + "0" + "}".repeat(20);
        assertThrows(ProtocolException.class, () -> WireJson.parse(nested.getBytes(StandardCharsets.UTF_8)));
        ObjectNode many = WireJson.object(); for (int i=0; i<65; i++) many.put("k"+i, 1);
        assertThrows(ProtocolException.class, () -> WireJson.encode(many));
        ObjectNode large = WireJson.object(); for (int i=0; i<32; i++) large.put("k"+i, "한".repeat(4000));
        assertThrows(IOException.class, () -> WireJson.encode(large));
    }
    @Test void outputFailurePoisonsWriterWithoutRetryingPartialFrames() throws Exception {
        var writer = new WorkerProtocol.Writer(new OutputStream() {
            int written;
            public void write(int b) throws IOException { if (++written == 20) throw new IOException("injected"); }
        }, IDENTITY, LIMITS);
        ObjectNode hello = (ObjectNode) success().getFirst().path("payload");
        assertThrows(IOException.class, () -> writer.emit("HELLO", hello));
        assertEquals("STREAM_FAILED", assertThrows(ProtocolException.class, () -> writer.emit("HELLO", hello)).getMessage());
    }
    @Test void rejectsIdentityRunSequenceAndTerminalEvidenceViolations() throws Exception {
        var values = success(); values.get(0).put("runId", "another-run"); reject(frames(values), "RUN_MISMATCH");
        values = success(); values.get(1).put("sequence", 0); reject(frames(values), "SEQUENCE");
        values = success(); values.get(1).put("sequence", 1.0); reject(frames(values), "INTEGER_FIELD");
        values = success(); ((ObjectNode)values.get(4).path("payload")).put("status", "FAIL"); reject(frames(values), "SUCCESS_EVIDENCE");
        values = success(); ((ObjectNode)values.get(7).path("payload")).put("lastSequence", 6); reject(frames(values), "LAST_SEQUENCE");
        values = success(); values.get(0).put("extra", true); reject(frames(values), "FIELDS");
    }
    @ParameterizedTest @ValueSource(strings = {"../escape", "/tmp/escape", "a/../escape", "a\\escape", "C:/escape", "a//b", "a/./b", "a/"})
    void rejectsUnsafeArtifactReferencesBeforeAnyFilesystemAccess(String path) throws Exception {
        var events = success(); ((ObjectNode)events.get(6).path("payload")).put("path", path); reject(frames(events), "ARTIFACT_PATH");
    }
    @Test void exitZeroWithoutEofOrResultCannotBeAccepted() throws Exception {
        var reader = reader(fixture("success.ndjson"));
        for (int i=0; i<8; i++) assertNotNull(reader.next());
        assertThrows(ProtocolException.class, () -> reader.verifyExit(0));
        assertNull(reader.next()); reader.verifyExit(0);
    }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, result, a.length, b.length); return result;
    }
}
