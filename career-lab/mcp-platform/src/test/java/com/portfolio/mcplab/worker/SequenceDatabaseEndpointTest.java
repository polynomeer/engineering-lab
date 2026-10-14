package com.portfolio.mcplab.worker;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SequenceDatabaseEndpointTest {
    @Test void defaultsToLegacyOwnershipAndRejectsPartialOrUntrustedTargets() {
        assertNull(SequenceDatabaseEndpoint.from(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SequenceDatabaseEndpoint.from(Map.of("LAB_SEQUENCE_DB_PASSWORD", "a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> SequenceDatabaseEndpoint.from(Map.of("LAB_SEQUENCE_DB_MODE", "EXTERNAL", "LAB_SEQUENCE_DB_HOST", "production", "LAB_SEQUENCE_DB_PASSWORD", "a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> SequenceDatabaseEndpoint.from(Map.of("LAB_SEQUENCE_DB_MODE", "EXTERNAL", "LAB_SEQUENCE_DB_HOST", "sequence-db", "LAB_SEQUENCE_DB_PASSWORD", "bad")));
    }
    @Test void externalEndpointIsFixedAndDoesNotRenderCredentials() {
        var endpoint = SequenceDatabaseEndpoint.from(Map.of("LAB_SEQUENCE_DB_MODE", "EXTERNAL", "LAB_SEQUENCE_DB_HOST", "sequence-db", "LAB_SEQUENCE_DB_PASSWORD", "a".repeat(64)));
        assertTrue(endpoint.jdbcUrl().startsWith("jdbc:mysql://sequence-db:3306/sequence_lab?"));
        assertFalse(endpoint.toString().contains("a".repeat(64)));
    }
}
