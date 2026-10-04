package com.pnomeer.lab.experiments.pipeline.parse;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Validate expansion before POI loads shared strings, styles and package metadata. */
final class XlsxLimits {
    static final long MAX_EXPANDED = 256L * 1024 * 1024;
    static final long MAX_METADATA = 8L * 1024 * 1024;
    static final int MAX_ROW_CHARS = 65536;
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("XLSX input interrupted");
    }
    static void copy(InputStream input, Path target) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192]; long count = 0; int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                count += read;
                if (count > com.pnomeer.lab.core.input.InputPayload.MAX_BYTES) throw new IOException("XLSX exceeds 50 MiB");
                output.write(buffer, 0, read);
            }
        }
    }
    static void validate(Path file) throws IOException {
        try (var zip = new ZipFile(file.toFile())) {
            if (zip.size() > 1024) throw new IOException("XLSX has too many ZIP entries");
            long total = 0, metadata = 0; byte[] buffer = new byte[8192];
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                boolean sheet = entry.getName().matches("xl/worksheets/sheet[0-9]+\\.xml");
                try (var input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkInterrupted();
                        total += read;
                        if (!sheet) metadata += read;
                        if (total > MAX_EXPANDED || metadata > MAX_METADATA) throw new IOException("XLSX expanded content exceeds limit");
                    }
                }
            }
        }
    }
}
