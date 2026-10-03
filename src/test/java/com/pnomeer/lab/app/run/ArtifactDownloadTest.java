package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArtifactDownloadTest {
    @TempDir Path root;
    private StorageProperties storage() { return new StorageProperties(root.toString(),1,1,10,1,1,1000,10,1,1,".",100); }
    private ArtifactDownloadController controller(RunStore store, long bytes) {
        return new ArtifactDownloadController(store,storage(),new ArtifactDownloadProperties(bytes,1));
    }
    private void run(JdbcRunStore store, String id, boolean terminal) {
        store.create("test","test",new RunMetadata(id,Map.of(),RunEnvironment.capture()));
        store.start(id);
        if (terminal) store.complete(id,RunStatus.SUCCEEDED,ExperimentSummary.empty(),null);
    }
    private ArtifactReference artifact(JdbcRunStore store,String runId,byte[] content) throws Exception {
        String id = "log_" + UUID.randomUUID();
        String path = "logs/" + id + ".ndjson";
        Files.createDirectories(root.resolve("logs"));
        Files.write(root.resolve(path),content);
        var ref = new ArtifactReference(id,runId,path,"application/x-ndjson",content.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        store.registerArtifact(ref);
        return ref;
    }
    @Test void downloadsExactVerifiedBytesAfterStoreReopen() throws Exception {
        byte[] bytes = "{\"event\":\"STARTED\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ArtifactReference ref;
        try (var store = new JdbcRunStore(root,10,1)) {
            run(store,"a",true); ref = artifact(store,"a",bytes);
        }
        try (var store = new JdbcRunStore(root,10,1)) {
            var mvc = MockMvcBuilders.standaloneSetup(controller(store,1024)).build();
            mvc.perform(get("/runs/a/artifacts/"+ref.artifactId()+"/download"))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                    .andExpect(header().string("X-Artifact-SHA256",ref.sha256()))
                    .andExpect(header().string("Content-Length",Integer.toString(bytes.length)))
                    .andExpect(header().string("X-Content-Type-Options","nosniff"))
                    .andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get("/runs/missing/artifacts/"+ref.artifactId()+"/download")).andExpect(status().isNotFound());
        }
    }
    @Test void rejectsActiveRunsUnknownReferencesExternalPathsAndOversize() throws Exception {
        try (var store = new JdbcRunStore(root,10,1)) {
            run(store,"a",true); run(store,"b",true); run(store,"active",false);
            var ref = artifact(store,"a",new byte[32]);
            var mvc = MockMvcBuilders.standaloneSetup(controller(store,16)).build();
            mvc.perform(get("/runs/a/artifacts/"+ref.artifactId()+"/download")).andExpect(status().isPayloadTooLarge());
            mvc.perform(get("/runs/b/artifacts/"+ref.artifactId()+"/download")).andExpect(status().isNotFound());
            mvc.perform(get("/runs/active/artifacts/unknown/download")).andExpect(status().isConflict());
            store.registerArtifact(new ArtifactReference("external","a","private/data.txt","text/plain",0,"a".repeat(64)));
            mvc.perform(get("/runs/a/artifacts/external/download")).andExpect(status().isConflict());
            String forged = "log_" + UUID.randomUUID();
            store.registerArtifact(new ArtifactReference(forged,"a","logs/wrong.ndjson","application/x-ndjson",0,"a".repeat(64)));
            mvc.perform(get("/runs/a/artifacts/"+forged+"/download")).andExpect(status().isConflict());
        }
        assertThrows(IllegalArgumentException.class,()->new ArtifactDownloadProperties(0,1));
        assertThrows(IllegalArgumentException.class,()->new ArtifactDownloadProperties(1024,0));
    }
    @Test void rejectsTamperingMissingFilesAndSymlinkPathsBeforeSendingBytes() throws Exception {
        try (var store = new JdbcRunStore(root,10,1)) {
            run(store,"a",true);
            var ref = artifact(store,"a",new byte[]{1,2,3});
            Path path = root.resolve(ref.relativePath());
            var mvc = MockMvcBuilders.standaloneSetup(controller(store,1024)).build();
            String url = "/runs/a/artifacts/"+ref.artifactId()+"/download";
            Files.write(path,new byte[]{3,2,1});
            mvc.perform(get(url)).andExpect(status().isConflict()).andExpect(content().bytes(new byte[0]));
            Files.write(path,new byte[]{1});
            mvc.perform(get(url)).andExpect(status().isConflict());
            Files.delete(path);
            mvc.perform(get(url)).andExpect(status().isNotFound());
            Path outside = root.resolve("outside"); Files.write(outside,new byte[]{1,2,3});
            Files.createSymbolicLink(path,outside);
            mvc.perform(get(url)).andExpect(status().isConflict());
            Files.delete(path); Files.delete(root.resolve("logs"));
            Path other = root.resolve("other"); Files.createDirectory(other);
            Files.write(other.resolve(path.getFileName()),new byte[]{1,2,3});
            Files.createSymbolicLink(root.resolve("logs"),other);
            mvc.perform(get(url)).andExpect(status().isConflict());
        }
    }
    @Test void servesSnapshotEvenIfOriginalChangesAfterVerification() throws Exception {
        try (var store = new JdbcRunStore(root,10,1)) {
            run(store,"a",true);
            var ref = artifact(store,"a",new byte[]{1,2,3});
            var response = new MockHttpServletResponse() {
                @Override public ServletOutputStream getOutputStream() {
                    try { Files.write(root.resolve(ref.relativePath()),new byte[]{9,9,9}); }
                    catch (IOException ex) { throw new UncheckedIOException(ex); }
                    return super.getOutputStream();
                }
            };
            controller(store,1024).download("a",ref.artifactId(),response);
            assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());
        }
    }
    @Test void boundsConcurrentDownloadsAndReleasesSlotOnClientFailure() throws Exception {
        try (var store = new JdbcRunStore(root,10,1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            run(store,"a",true); var ref = artifact(store,"a",new byte[]{1,2,3});
            var controller = controller(store,1024);
            var writing = new CountDownLatch(1); var release = new CountDownLatch(1);
            var response = new MockHttpServletResponse() {
                @Override public ServletOutputStream getOutputStream() {
                    return new ServletOutputStream() {
                        public boolean isReady() { return true; }
                        public void setWriteListener(WriteListener listener) { }
                        public void write(int value) throws IOException {
                            writing.countDown();
                            try { if (!release.await(5,TimeUnit.SECONDS)) throw new IOException("timed out"); }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                            throw new IOException("client disconnected");
                        }
                    };
                }
            };
            var first = executor.submit(()->assertThrows(IOException.class,()->controller.download("a",ref.artifactId(),response)));
            try {
                assertTrue(writing.await(5,TimeUnit.SECONDS));
                var failure = assertThrows(ResponseStatusException.class,()->controller.download("a",ref.artifactId(),new MockHttpServletResponse()));
                assertEquals(429,failure.getStatusCode().value());
            } finally { release.countDown(); }
            first.get(5,TimeUnit.SECONDS);
            var finalResponse = new MockHttpServletResponse();
            controller.download("a",ref.artifactId(),finalResponse);
            assertArrayEquals(new byte[]{1,2,3},finalResponse.getContentAsByteArray());
        }
    }
}
