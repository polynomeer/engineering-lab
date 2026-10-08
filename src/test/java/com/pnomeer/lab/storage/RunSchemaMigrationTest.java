package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class RunSchemaMigrationTest {
    @TempDir Path root;
    private final JsonMapper json = JsonMapper.builder().build();
    private DriverManagerDataSource source(Path directory) {
        return new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("runs") + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }
    private void versionTwoFixture(Path directory) throws Exception {
        Files.createDirectories(directory);
        var source = source(directory);
        new ResourceDatabasePopulator(new ClassPathResource("lab/schema-v1.sql"), new ClassPathResource("lab/schema-v2.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var metadata = new RunMetadata("legacy", Map.of("seed", "42"), RunEnvironment.capture(), new GitProvenance("legacy-commit", false, null));
        jdbc.update("insert into lab_run(run_id,experiment_type,scenario_id,status,created_at,started_at,completed_at,metadata_json,summary_json) values(?,?,?,'SUCCEEDED',1,2,3,?,?)",
                "legacy", "queue", "small", json.writeValueAsString(metadata), json.writeValueAsString(ExperimentSummary.empty()));
        jdbc.update("insert into lab_metric values(?,?,?)", "legacy", 0,
                json.writeValueAsString(new MetricPoint(2, Map.of(), Map.of("count", 3L), Map.of())));
        jdbc.update("insert into lab_dead_letter values(?,?,?)", "legacy", 5,
                json.writeValueAsString(new DeadLetter(5, "validation", "invalid", List.of("한글"))));
        jdbc.update("insert into lab_artifact values(?,?,?,?,?,?)", "artifact", "legacy", "legacy/log.jsonl", "application/x-ndjson", 10, "a".repeat(64));
    }
    private List<List<String>> snapshot(Path directory) throws Exception {
        var snapshot = new ArrayList<List<String>>();
        try (var connection = source(directory).getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("lab_run", "lab_metric", "lab_dead_letter", "lab_artifact")) {
                try (var rows = statement.executeQuery("select * from " + table)) {
                    while (rows.next()) {
                        var values = new ArrayList<String>(); values.add(table);
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) values.add(rows.getString(i));
                        snapshot.add(values);
                    }
                }
            }
        }
        return snapshot;
    }
    private int version(Path directory) { return new JdbcTemplate(source(directory)).queryForObject("select version from lab_schema where id=1", Integer.class); }
    private long tableCount(Path directory, String name) {
        return new JdbcTemplate(source(directory)).queryForObject("select count(*) from information_schema.tables where table_name=?", Long.class, name);
    }

    @Test void upgradesRealV2PreservingRawEvidenceAndProducesRestorableV2Backup() throws Exception {
        versionTwoFixture(root);
        var before = snapshot(root);
        try (var store = new JdbcRunStore(root, 10, 2)) {
            assertEquals(3, version(root));
            assertEquals("legacy", store.find("legacy").orElseThrow().runId());
            assertEquals("legacy-commit", store.find("legacy").orElseThrow().runMetadata().git().commit());
            assertTrue(store.execution("legacy").isEmpty()); // Never invent historical execution evidence.
            assertTrue(store.checks("legacy", -1, 10).isEmpty());
        }
        assertEquals(before, snapshot(root));
        Path backup;
        try (var paths = Files.list(root.resolve("backups"))) {
            var backups = paths.toList(); assertEquals(1, backups.size()); backup = backups.getFirst();
        }
        Path rollback = root.resolve("restored-v2"); Files.createDirectories(rollback);
        try (var archive = new ZipFile(backup.toFile())) {
            var entry = archive.getEntry("runs.mv.db"); assertNotNull(entry);
            try (var input = archive.getInputStream(entry)) { Files.copy(input, rollback.resolve("runs.mv.db")); }
        }
        assertEquals(2, version(rollback));
        assertEquals(0, tableCount(rollback, "LAB_EXECUTION"));
        assertEquals(before, snapshot(rollback));
        try (var store = new JdbcRunStore(root, 10, 2)) { assertEquals(0, store.recoverInterrupted()); }
        try (var paths = Files.list(root.resolve("backups"))) { assertEquals(1, paths.count()); }
    }

    @Test void backupFailureLeavesV2UntouchedAndReleasesOwnershipForRetry() throws Exception {
        versionTwoFixture(root);
        var before = snapshot(root);
        Files.writeString(root.resolve("backups"), "block directory creation");
        assertThrows(IllegalStateException.class, () -> new JdbcRunStore(root, 10, 2));
        assertEquals(2, version(root));
        assertEquals(0, tableCount(root, "LAB_EXECUTION"));
        assertEquals(before, snapshot(root));
        Files.delete(root.resolve("backups"));
        try (var store = new JdbcRunStore(root, 10, 2)) { assertTrue(store.find("legacy").isPresent()); }
        assertEquals(3, version(root));
    }

    @Test void resumesPartialDdlWithoutDroppingLegacyEvidenceAndRejectsFutureVersionsBeforeDdl() throws Exception {
        versionTwoFixture(root);
        var before = snapshot(root);
        var jdbc = new JdbcTemplate(source(root));
        jdbc.execute("create table lab_execution(run_id varchar(100) primary key references lab_run(run_id), definition_json clob not null, worker_json clob, phase varchar(30) not null, evidence_complete boolean not null default false)");
        try (var store = new JdbcRunStore(root, 10, 2)) { assertTrue(store.find("legacy").isPresent()); }
        assertEquals(3, version(root)); assertEquals(1, tableCount(root, "LAB_CHECK"));
        assertEquals(before, snapshot(root));
        jdbc.execute("drop table lab_check"); jdbc.update("update lab_schema set version=99 where id=1");
        assertThrows(IllegalStateException.class, () -> new JdbcRunStore(root, 10, 2));
        assertEquals(99, version(root)); assertEquals(0, tableCount(root, "LAB_CHECK"));
    }
}
