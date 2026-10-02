package com.pnomeer.lab.core;

public record GitProvenance(String commit, Boolean dirty, String error) {
    public static GitProvenance unknown(String reason) {
        return new GitProvenance(null, null, reason);
    }
}
