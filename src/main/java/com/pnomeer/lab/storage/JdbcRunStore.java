package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** A separate file DB: ingestion table initialization cannot erase experiment evidence. */
public final class JdbcRunStore implements RunStore, AutoCloseable {
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    private final int pageLimit;
    private final int batchLimit;

    public JdbcRunStore(Path root, int pageLimit, int batchLimit) {
        if (pageLimit < 1 || batchLimit < 1) throw new IllegalArgumentException("limits must be positive");
        this.pageLimit = pageLimit;
        this.batchLimit = batchLimit;
        String path = root.toAbsolutePath().normalize().resolve("runs").toString();
        if (path.contains(";") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalArgumentException("Unsupported storage path");
        }
        try { Files.createDirectories(root); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cannot create run storage", ex); }
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=2000");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(3000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            // Check an existing schema before applying any DDL.
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select count(*) > 0 from information_schema.tables where table_name='LAB_SCHEMA'", Boolean.class));
            if (exists) {
                Integer version = jdbc.queryForObject("select version from lab_schema where id=1", Integer.class);
                if (version == null || version != 1) throw new IllegalStateException("Unsupported lab schema version: " + version);
            }
            new ResourceDatabasePopulator(new ClassPathResource("lab/schema-v1.sql")).execute(dataSource);
        } catch (RuntimeException ex) {
            dataSource.close();
            throw ex;
        }
    }

    @Override
    public StoredRun create(String type, String scenario, RunMetadata metadata) {
        jdbc.update("insert into lab_run(run_id,experiment_type,scenario_id,status,created_at,metadata_json,summary_json) values(?,?,?,'QUEUED',?,?,?)",
                metadata.runId(), type, scenario, System.currentTimeMillis(), encode(metadata), encode(ExperimentSummary.empty()));
        return find(metadata.runId()).orElseThrow();
    }

    @Override
    public void start(String id) {
        int updated = jdbc.update("update lab_run set status='RUNNING', started_at=? where run_id=? and status='QUEUED'",
                System.currentTimeMillis(), id);
        requireTransition(updated, id);
    }

    @Override
    public void complete(String id, RunStatus status, ExperimentSummary summary, String failure) {
        if (status != RunStatus.SUCCEEDED && status != RunStatus.FAILED && status != RunStatus.INTERRUPTED) {
            throw new IllegalArgumentException("Completion requires a terminal status");
        }
        int updated = jdbc.update("update lab_run set status=?, completed_at=?, summary_json=?, failure_message=? where run_id=? and status in ('QUEUED','RUNNING')",
                status.name(), System.currentTimeMillis(), encode(summary), failure, id);
        requireTransition(updated, id);
    }

    @Override
    public Optional<StoredRun> find(String id) {
        return jdbc.query("select * from lab_run where run_id=?", this::readRun, id).stream().findFirst();
    }

    @Override
    public List<StoredRun> list(String type, long before, int limit) {
        checkLimit(limit);
        if (type == null) return jdbc.query("select * from lab_run where seq < ? order by seq desc limit ?", this::readRun, before, limit);
        return jdbc.query("select * from lab_run where experiment_type=? and seq < ? order by seq desc limit ?", this::readRun, type, before, limit);
    }

    @Override
    public void appendMetrics(String id, List<StoredMetric> metrics) {
        if (metrics.size() > batchLimit) throw new IllegalArgumentException("Metric batch exceeds limit");
        if (metrics.isEmpty()) return;
        transactions.executeWithoutResult(status -> jdbc.batchUpdate(
                "merge into lab_metric(run_id,seq,point_json) key(run_id,seq) values(?,?,?)", metrics, batchLimit,
                (statement, metric) -> {
                    if (metric.sequence() < 0) throw new IllegalArgumentException("Metric sequence must be nonnegative");
                    statement.setString(1, id);
                    statement.setLong(2, metric.sequence());
                    statement.setString(3, encode(metric.point()));
                }));
    }

    @Override
    public List<StoredMetric> metrics(String id, long after, int limit) {
        checkLimit(limit);
        if (after < -1) throw new IllegalArgumentException("afterSequence must be >= -1");
        return jdbc.query("select seq, point_json from lab_metric where run_id=? and seq>? order by seq limit ?",
                (row, n) -> new StoredMetric(row.getLong(1), json.readValue(row.getString(2), MetricPoint.class)), id, after, limit);
    }

    @Override
    public int recoverInterrupted() {
        return jdbc.update("update lab_run set status='INTERRUPTED', completed_at=?, failure_message='Process stopped before durable completion' where status in ('QUEUED','RUNNING')",
                System.currentTimeMillis());
    }

    private StoredRun readRun(ResultSet row, int index) throws SQLException {
        return new StoredRun(row.getLong("seq"), row.getString("run_id"), row.getString("experiment_type"), row.getString("scenario_id"),
                RunStatus.valueOf(row.getString("status")), row.getLong("created_at"), row.getLong("started_at"), row.getLong("completed_at"),
                json.readValue(row.getString("metadata_json"), RunMetadata.class),
                json.readValue(row.getString("summary_json"), ExperimentSummary.class), row.getString("failure_message"));
    }

    private String encode(Object value) { return json.writeValueAsString(value); }
    private void checkLimit(int limit) {
        if (limit < 1 || limit > pageLimit) throw new IllegalArgumentException("limit must be between 1 and " + pageLimit);
    }
    private static void requireTransition(int count, String id) {
        if (count != 1) throw new IllegalStateException("Invalid run state transition: " + id);
    }
    @Override public void close() { dataSource.close(); }
}
