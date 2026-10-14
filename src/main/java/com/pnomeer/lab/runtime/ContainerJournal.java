package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.WireJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Bounded, no-follow, append-only controller records. Parent directories are administrator-owned. */
public final class ContainerJournal {
    private ContainerJournal() {}
    public static byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid container record");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Container record limit");
            return bytes;
        }
    }
    public static ObjectNode json(Path path) throws IOException { return WireJson.parse(read(path)); }
    public static void write(Path path, ObjectNode value) throws IOException {
        byte[] bytes = WireJson.encode(value);
        if (bytes.length > 4096) throw new IOException("Container record limit");
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
