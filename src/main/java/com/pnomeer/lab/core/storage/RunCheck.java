package com.pnomeer.lab.core.storage;

import java.util.Objects;

/** Bounded immutable check evidence. Sequence is a check cursor, not a protocol event sequence. */
public record RunCheck(long sequence, String checkId, Status status, String expected, String observed, String description) {
    public enum Status { PASS, FAIL }
    public static final int MAX_CHECKS = 100000;
    public static final int MAX_PAGE = 100;
    public static final int MAX_BYTES = 65536;
    public RunCheck {
        if (sequence < 0 || sequence >= MAX_CHECKS) throw new IllegalArgumentException("Check sequence must be 0..99999");
        if (checkId == null || !checkId.matches("[a-zA-Z0-9._-]{1,100}")) throw new IllegalArgumentException("Invalid check ID");
        Objects.requireNonNull(status);
        for (String field : new String[] {expected, observed, description}) {
            if (field == null || field.length() > 4096) throw new IllegalArgumentException("Check text must be at most 4096 characters");
        }
    }
}
