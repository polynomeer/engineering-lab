package com.portfolio.mcplab.worker;

import java.util.Map;

/** Trusted controller environment only; the public scenario remains variant/workers/blockSize. */
record SequenceDatabaseEndpoint(String password) {
    static SequenceDatabaseEndpoint from(Map<String, String> environment) {
        String mode = environment.get("LAB_SEQUENCE_DB_MODE");
        if (mode == null) {
            if (environment.containsKey("LAB_SEQUENCE_DB_HOST") || environment.containsKey("LAB_SEQUENCE_DB_PASSWORD"))
                throw new IllegalArgumentException("Partial database environment");
            return null;
        }
        if (!mode.equals("EXTERNAL") || !"sequence-db".equals(environment.get("LAB_SEQUENCE_DB_HOST"))
                || environment.get("LAB_SEQUENCE_DB_PASSWORD") == null
                || !environment.get("LAB_SEQUENCE_DB_PASSWORD").matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid controller database environment");
        return new SequenceDatabaseEndpoint(environment.get("LAB_SEQUENCE_DB_PASSWORD"));
    }
    String jdbcUrl() { return "jdbc:mysql://sequence-db:3306/sequence_lab?connectTimeout=5000&socketTimeout=5000&useSSL=false&allowPublicKeyRetrieval=true"; }
    @Override public String toString() { return "SequenceDatabaseEndpoint[controller-owned]"; }
}
