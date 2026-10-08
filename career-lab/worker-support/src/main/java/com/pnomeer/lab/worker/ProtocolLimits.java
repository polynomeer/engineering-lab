package com.pnomeer.lab.worker;

/** Limits may be lowered per reader/writer; v1 hard ceilings cannot be raised. */
public record ProtocolLimits(int recordBytes, long totalBytes, int events) {
    public static final ProtocolLimits DEFAULT = new ProtocolLimits(65536, 64L * 1024 * 1024, 100000);
    public ProtocolLimits {
        if (recordBytes < 1 || recordBytes > 65536 || totalBytes < 1 || totalBytes > 64L * 1024 * 1024
                || events < 2 || events > 100000) throw new IllegalArgumentException("Invalid protocol limits");
    }
}
