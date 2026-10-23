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
public final class JdbcRunStore implements RunStore, RunSeriesStore, AutoCloseable {
    public static final int SCHEMA_VERSION = 4;
    private final HikariDataSource dataSource;
    private final java.nio.channels.FileChannel ownershipChannel;
    private final java.nio.channels.FileLock ownershipLock;
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
        java.nio.channels.FileChannel channel = null;
        try {
            channel = java.nio.channels.FileChannel.open(root.resolve("owner.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            var lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Run storage is already owned by another application");
            ownershipChannel = channel;
            ownershipLock = lock;
        } catch (Exception ex) {
            if (channel != null) try { channel.close(); } catch (java.io.IOException close) { ex.addSuppressed(close); }
            throw new IllegalStateException("Cannot acquire exclusive run storage ownership", ex);
        }
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=2000");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(3000);
        try { dataSource = new HikariDataSource(config); }
        catch (RuntimeException ex) { releaseOwnership(); throw ex; }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            // Check an existing schema before applying any DDL.
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select count(*) > 0 from information_schema.tables where table_name='LAB_SCHEMA'", Boolean.class));
            Integer version = exists ? jdbc.queryForObject("select version from lab_schema where id=1", Integer.class) : 0;
            if (version == null || version < 0 || version > SCHEMA_VERSION || exists && version == 0) {
                throw new IllegalStateException("Unsupported lab schema version: " + version);
            }
            if (version >= 1 && version < SCHEMA_VERSION) backupBeforeUpgrade(root, version);
            if (version < 2) {
                new ResourceDatabasePopulator(new ClassPathResource("lab/schema-v1.sql"),
                        new ClassPathResource("lab/schema-v2.sql")).execute(dataSource);
            }
            if (version < 3) new ResourceDatabasePopulator(new ClassPathResource("lab/schema-v3.sql")).execute(dataSource);
            if (version < 4) new ResourceDatabasePopulator(new ClassPathResource("lab/schema-v4.sql")).execute(dataSource);
        } catch (RuntimeException ex) {
            dataSource.close();
            releaseOwnership();
            throw ex;
        }
    }

    private void backupBeforeUpgrade(Path root, int version) {
        String target = "v" + SCHEMA_VERSION;
        Path directory = root.toAbsolutePath().normalize().resolve("backups");
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new java.io.IOException("Backup directory must not be a symbolic link");
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot create pre-" + target + " backup directory", ex); }
        Path backup = directory.resolve("schema-v" + version + "-before-" + target + "-" + java.util.UUID.randomUUID() + ".zip");
        Path partial = backup.resolveSibling(backup.getFileName() + ".partial");
        // Publish only a complete H2 backup; failed copies remain visibly partial for inspection.
        jdbc.execute("BACKUP TO '" + partial.toString().replace("'", "''") + "'");
        try { Files.move(partial, backup, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cannot publish pre-" + target + " backup", ex); }
    }

    @Override
    public RunSeries createSeries(String id, String type, String scenario, String sha256, int repetitions, int warmup) {
        long now = System.currentTimeMillis();
        new RunSeries(0, id, type, scenario, sha256, repetitions, warmup, RunStatus.QUEUED, now, 0, null); // validates bounds
        jdbc.update("insert into lab_series(series_id,experiment_type,scenario_id,definition_sha256,repetitions,warmup,status,created_at) values(?,?,?,?,?,?,'QUEUED',?)",
                id, type, scenario, sha256, repetitions, warmup, now);
        return findSeries(id).orElseThrow();
    }

    @Override
    public void startSeries(String id) {
        requireTransition(jdbc.update("update lab_series set status='RUNNING' where series_id=? and status='QUEUED'", id), id);
    }

    @Override
    public void addSeriesRun(String id, RunSeries.Member member) {
        transactions.executeWithoutResult(tx -> {
            var series = jdbc.query("select * from lab_series where series_id=? for update", this::readSeries, id).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown series: " + id));
            if (series.status() != RunStatus.RUNNING) throw new IllegalStateException("Series is not running: " + id);
            if (member.position() >= series.total()) throw new IllegalArgumentException("Series position exceeds repetitions");
            jdbc.update("insert into lab_series_run(series_id,position,run_id,warmup) values(?,?,?,?)",
                    id, member.position(), member.runId(), member.warmup());
        });
    }

    @Override
    public void completeSeries(String id, RunStatus status, String failure) {
        if (status != RunStatus.SUCCEEDED && status != RunStatus.FAILED && status != RunStatus.INTERRUPTED)
            throw new IllegalArgumentException("Series completion needs a terminal status");
        String message = failure == null ? null : failure.length() > 1000 ? failure.substring(0, 1000) : failure;
        requireTransition(jdbc.update("update lab_series set status=?, completed_at=?, failure_message=? where series_id=? and status in ('QUEUED','RUNNING')",
                status.name(), System.currentTimeMillis(), message, id), id);
    }

    @Override
    public Optional<RunSeries> findSeries(String id) {
        return jdbc.query("select * from lab_series where series_id=?", this::readSeries, id).stream().findFirst();
    }

    @Override
    public List<RunSeries.Member> seriesRuns(String id) {
        return jdbc.query("select * from lab_series_run where series_id=? order by position", (row, n) ->
                new RunSeries.Member(row.getInt("position"), row.getString("run_id"), row.getBoolean("warmup")), id);
    }

    @Override
    public List<RunSeries> listSeries(String type, int limit) {
        checkLimit(limit);
        if (type == null) return jdbc.query("select * from lab_series order by seq desc limit ?", this::readSeries, limit);
        return jdbc.query("select * from lab_series where experiment_type=? order by seq desc limit ?", this::readSeries, type, limit);
    }

    @Override
    public void pinBaseline(String id) {
        transactions.executeWithoutResult(tx -> {
            var series = findSeries(id).orElseThrow(() -> new IllegalArgumentException("Unknown series: " + id));
            if (series.status() != RunStatus.SUCCEEDED) throw new IllegalStateException("Only a succeeded series can be a baseline");
            long now = System.currentTimeMillis();
            int updated = jdbc.update("update lab_series_baseline set series_id=?, pinned_at=? where experiment_type=? and scenario_id=?",
                    id, now, series.experimentType(), series.scenarioId());
            if (updated == 0) jdbc.update("insert into lab_series_baseline(experiment_type,scenario_id,series_id,pinned_at) values(?,?,?,?)",
                    series.experimentType(), series.scenarioId(), id, now);
        });
    }

    @Override
    public Optional<RunSeries> baseline(String type, String scenario) {
        return jdbc.query("select s.* from lab_series_baseline b join lab_series s on s.series_id=b.series_id where b.experiment_type=? and b.scenario_id=?",
                this::readSeries, type, scenario).stream().findFirst();
    }

    @Override
    public List<RunSeries> baselines(int limit) {
        checkLimit(limit);
        return jdbc.query("select s.* from lab_series_baseline b join lab_series s on s.series_id=b.series_id order by b.pinned_at desc, s.seq desc limit ?",
                this::readSeries, limit);
    }

    @Override
    public int recoverInterruptedSeries() {
        long now = System.currentTimeMillis();
        return transactions.execute(tx -> jdbc.update("update lab_sweep set status='INTERRUPTED', completed_at=?, failure_message='Process stopped before the sweep finished' where status in ('QUEUED','RUNNING')", now)
                + jdbc.update("update lab_series set status='INTERRUPTED', completed_at=?, failure_message='Process stopped before the series finished' where status in ('QUEUED','RUNNING')", now));
    }

    /**
     * JFR and Run-log artifacts of finished runs completed before {@code cutoff}, oldest first. Runs in a
     * pinned baseline series are never candidates; worker reports and metrics are out of scope.
     */
    public List<ArtifactReference> retentionCandidates(long cutoffEpochMs, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return jdbc.query("select a.* from lab_artifact a join lab_run r on r.run_id=a.run_id"
                + " where r.status in ('SUCCEEDED','FAILED','INTERRUPTED') and r.completed_at > 0 and r.completed_at < ?"
                + " and (a.artifact_id like 'jfr!_%' escape '!' or a.artifact_id like 'log!_%' escape '!')"
                + " and a.run_id not in (select sr.run_id from lab_series_run sr join lab_series_baseline b on b.series_id=sr.series_id)"
                + " order by r.completed_at, a.artifact_id limit ?",
                (row, n) -> new ArtifactReference(row.getString("artifact_id"), row.getString("run_id"), row.getString("relative_path"),
                        row.getString("media_type"), row.getLong("byte_size"), row.getString("sha256")), cutoffEpochMs, limit);
    }

    /** Counts runs kept only because they belong to a pinned baseline series. */
    public int baselineProtectedRuns() {
        Integer count = jdbc.queryForObject("select count(distinct sr.run_id) from lab_series_run sr join lab_series_baseline b on b.series_id=sr.series_id", Integer.class);
        return count == null ? 0 : count;
    }

    /** Removes one artifact reference; the caller deletes the file afterwards. */
    public void deleteArtifactReference(String artifactId) {
        int deleted = jdbc.update("delete from lab_artifact where artifact_id=?", artifactId);
        if (deleted != 1) throw new IllegalStateException("Artifact reference not found: " + artifactId);
    }

    @Override
    public RunSweep createSweep(String id, String type, String scenario, java.util.Map<String, List<Object>> grid, int repetitions, int warmup) {
        if (repetitions < 1 || repetitions > RunSeries.MAX_REPETITIONS || warmup < 0 || warmup > RunSeries.MAX_WARMUP)
            throw new IllegalArgumentException("Invalid sweep repetitions");
        String encoded = boundedEncode(grid, 65536);
        jdbc.update("insert into lab_sweep(sweep_id,experiment_type,scenario_id,grid_json,repetitions,warmup,status,created_at) values(?,?,?,?,?,?,'QUEUED',?)",
                id, type, scenario, encoded, repetitions, warmup, System.currentTimeMillis());
        return findSweep(id).orElseThrow();
    }

    @Override
    public void startSweep(String id) {
        requireTransition(jdbc.update("update lab_sweep set status='RUNNING' where sweep_id=? and status='QUEUED'", id), id);
    }

    @Override
    public void addSweepPoint(String id, RunSweep.Point point) {
        String encoded = boundedEncode(point.parameters(), 65536);
        transactions.executeWithoutResult(tx -> {
            var sweep = jdbc.query("select * from lab_sweep where sweep_id=? for update", this::readSweep, id).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown sweep: " + id));
            if (sweep.status() != RunStatus.RUNNING) throw new IllegalStateException("Sweep is not running: " + id);
            jdbc.update("insert into lab_sweep_point(sweep_id,position,series_id,parameters_json) values(?,?,?,?)",
                    id, point.position(), point.seriesId(), encoded);
        });
    }

    @Override
    public void completeSweep(String id, RunStatus status, String failure) {
        if (status != RunStatus.SUCCEEDED && status != RunStatus.FAILED && status != RunStatus.INTERRUPTED)
            throw new IllegalArgumentException("Sweep completion needs a terminal status");
        String message = failure == null ? null : failure.length() > 1000 ? failure.substring(0, 1000) : failure;
        requireTransition(jdbc.update("update lab_sweep set status=?, completed_at=?, failure_message=? where sweep_id=? and status in ('QUEUED','RUNNING')",
                status.name(), System.currentTimeMillis(), message, id), id);
    }

    @Override
    public Optional<RunSweep> findSweep(String id) {
        return jdbc.query("select * from lab_sweep where sweep_id=?", this::readSweep, id).stream().findFirst();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RunSweep.Point> sweepPoints(String id) {
        return jdbc.query("select * from lab_sweep_point where sweep_id=? order by position", (row, n) -> new RunSweep.Point(row.getInt("position"),
                row.getString("series_id"), json.readValue(row.getString("parameters_json"), java.util.LinkedHashMap.class)), id);
    }

    @Override
    public List<RunSweep> listSweeps(String type, int limit) {
        checkLimit(limit);
        if (type == null) return jdbc.query("select * from lab_sweep order by seq desc limit ?", this::readSweep, limit);
        return jdbc.query("select * from lab_sweep where experiment_type=? order by seq desc limit ?", this::readSweep, type, limit);
    }

    @Override
    public List<String> unfinishedRunIds(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return jdbc.queryForList("select run_id from lab_run where status in ('QUEUED','RUNNING') order by seq limit ?", String.class, limit);
    }

    @Override
    public List<String> runsActiveBetween(long from, long to, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        // A run with started_at 0 never executed; completed_at 0 means it is still running. Touching ends do not overlap.
        return jdbc.queryForList("select run_id from lab_run where started_at > 0 and started_at < ?"
                + " and (completed_at = 0 or completed_at > ?) order by started_at limit ?", String.class, to, from, limit);
    }

    @SuppressWarnings("unchecked")
    private RunSweep readSweep(ResultSet row, int n) throws SQLException {
        return new RunSweep(row.getLong("seq"), row.getString("sweep_id"), row.getString("experiment_type"), row.getString("scenario_id"),
                json.readValue(row.getString("grid_json"), java.util.LinkedHashMap.class), row.getInt("repetitions"), row.getInt("warmup"),
                RunStatus.valueOf(row.getString("status")), row.getLong("created_at"), row.getLong("completed_at"), row.getString("failure_message"));
    }

    private RunSeries readSeries(ResultSet row, int n) throws SQLException {
        return new RunSeries(row.getLong("seq"), row.getString("series_id"), row.getString("experiment_type"), row.getString("scenario_id"),
                row.getString("definition_sha256"), row.getInt("repetitions"), row.getInt("warmup"), RunStatus.valueOf(row.getString("status")),
                row.getLong("created_at"), row.getLong("completed_at"), row.getString("failure_message"));
    }

    @Override
    public void registerExecution(String id, RunExecution.Definition definition) {
        String encoded = boundedEncode(java.util.Objects.requireNonNull(definition), RunExecution.MAX_BYTES);
        transactions.executeWithoutResult(tx -> {
            lockActiveRun(id, false);
            var existing = execution(id);
            if (existing.isPresent()) {
                if (!existing.get().definition().equals(definition)) throw new IllegalStateException("Conflicting execution definition");
                return;
            }
            jdbc.update("insert into lab_execution(run_id,definition_json,phase) values(?,?,'PREPARING')", id, encoded);
        });
    }

    @Override
    public Optional<RunExecution> execution(String id) {
        return jdbc.query("select * from lab_execution where run_id=?", (row, n) -> new RunExecution(
                json.readValue(row.getString("definition_json"), RunExecution.Definition.class),
                row.getString("worker_json") == null ? null : json.readValue(row.getString("worker_json"), RunExecution.Worker.class),
                RunExecution.Phase.valueOf(row.getString("phase")), row.getBoolean("evidence_complete")), id).stream().findFirst();
    }

    @Override
    public void registerWorker(String id, RunExecution.Worker worker) {
        String encoded = boundedEncode(java.util.Objects.requireNonNull(worker), RunExecution.MAX_BYTES);
        transactions.executeWithoutResult(tx -> {
            lockActiveRun(id, true);
            var current = requireExecution(id);
            if (current.definition().kind() == RunExecution.Kind.IN_PROCESS) throw new IllegalStateException("Worker requires external execution");
            if (current.worker() != null) {
                if (!current.worker().equals(worker)) throw new IllegalStateException("Conflicting worker identity");
                return;
            }
            if (current.phase() != RunExecution.Phase.PREPARING || current.evidenceComplete()) {
                throw new IllegalStateException("Worker handshake requires preparation");
            }
            jdbc.update("update lab_execution set worker_json=? where run_id=?", encoded, id);
        });
    }

    @Override
    public void advanceExecution(String id, RunExecution.Phase phase) {
        java.util.Objects.requireNonNull(phase);
        transactions.executeWithoutResult(tx -> {
            lockActiveRun(id, true);
            var current = requireExecution(id);
            if (phase == current.phase()) return;
            if (current.evidenceComplete() || phase.ordinal() != current.phase().ordinal() + 1) {
                throw new IllegalStateException("Execution phase must advance one step");
            }
            requireWorkerIdentity(current);
            jdbc.update("update lab_execution set phase=? where run_id=?", phase.name(), id);
        });
    }

    @Override
    public void finishExecutionEvidence(String id) {
        transactions.executeWithoutResult(tx -> {
            lockActiveRun(id, true);
            var current = requireExecution(id);
            if (current.phase() != RunExecution.Phase.FINALIZING) throw new IllegalStateException("Evidence completion requires finalization");
            requireWorkerIdentity(current);
            jdbc.update("update lab_execution set evidence_complete=true where run_id=?", id);
        });
    }

    @Override
    public void appendCheck(String id, RunCheck check) {
        String encoded = boundedEncode(java.util.Objects.requireNonNull(check), RunCheck.MAX_BYTES);
        transactions.executeWithoutResult(tx -> {
            lockActiveRun(id, true);
            var current = requireExecution(id);
            if (current.evidenceComplete()) throw new IllegalStateException("Execution evidence is sealed");
            requireWorkerIdentity(current);
            var existing = jdbc.queryForList("select record_json from lab_check where run_id=? and seq=?", String.class, id, check.sequence());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().equals(encoded)) throw new IllegalStateException("Conflicting check sequence");
                return;
            }
            try {
                jdbc.update("insert into lab_check(run_id,seq,check_id,record_json) values(?,?,?,?)", id, check.sequence(), check.checkId(), encoded);
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                throw new IllegalStateException("Check ID already has a different sequence", ex);
            }
        });
    }

    @Override
    public List<RunCheck> checks(String id, long after, int limit) {
        checkLimit(limit);
        if (after < -1 || limit > RunCheck.MAX_PAGE) throw new IllegalArgumentException("Invalid check page");
        return jdbc.query("select record_json from lab_check where run_id=? and seq>? order by seq limit ?",
                (row, n) -> json.readValue(row.getString(1), RunCheck.class), id, after, limit);
    }

    private void lockActiveRun(String id, boolean runningOnly) {
        var states = jdbc.queryForList("select status from lab_run where run_id=? for update", String.class, id);
        if (states.size() != 1 || !("RUNNING".equals(states.getFirst()) || !runningOnly && "QUEUED".equals(states.getFirst()))) {
            throw new IllegalStateException("Execution evidence requires an active Run");
        }
    }
    private RunExecution requireExecution(String id) {
        return execution(id).orElseThrow(() -> new IllegalStateException("Execution is not registered"));
    }
    private static void requireWorkerIdentity(RunExecution execution) {
        if (execution.definition().kind() != RunExecution.Kind.IN_PROCESS && execution.worker() == null) {
            throw new IllegalStateException("Worker identity is unknown");
        }
    }
    private String boundedEncode(Object value, int maxBytes) {
        String encoded = encode(value);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("Execution evidence exceeds 64 KiB");
        }
        return encoded;
    }

    @Override
    public void appendDeadLetter(String id, DeadLetter row) {
        String encoded = encode(row);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > DeadLetter.MAX_BYTES) {
            throw new IllegalArgumentException("Dead-letter row exceeds 64 KiB");
        }
        transactions.executeWithoutResult(tx -> {
            var states = jdbc.queryForList("select status from lab_run where run_id=? for update", String.class, id);
            if (states.size() != 1 || !"RUNNING".equals(states.getFirst())) {
                throw new IllegalStateException("Dead letters require a running Run");
            }
            var existing = jdbc.queryForList("select record_json from lab_dead_letter where run_id=? and row_index=?", String.class, id, row.rowIndex());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().equals(encoded)) throw new IllegalStateException("Conflicting dead-letter row");
                return;
            }
            jdbc.update("insert into lab_dead_letter(run_id,row_index,record_json) values(?,?,?)", id, row.rowIndex(), encoded);
        });
    }

    @Override
    public List<DeadLetter> deadLetters(String id, int afterRow, int limit) {
        checkLimit(limit);
        if (afterRow < -1 || limit > DeadLetter.MAX_PAGE) throw new IllegalArgumentException("Invalid dead-letter page");
        return jdbc.query("select record_json from lab_dead_letter where run_id=? and row_index>? order by row_index limit ?",
                (row, n) -> json.readValue(row.getString(1), DeadLetter.class), id, afterRow, limit);
    }

    @Override
    public StoredRun create(String type, String scenario, RunMetadata metadata) {
        jdbc.update("insert into lab_run(run_id,experiment_type,scenario_id,status,created_at,metadata_json,summary_json) values(?,?,?,'QUEUED',?,?,?)",
                metadata.runId(), type, scenario, System.currentTimeMillis(), encode(metadata), encode(ExperimentSummary.empty()));
        return find(metadata.runId()).orElseThrow();
    }

    @Override
    public StoredRun create(String type, String scenario, RunMetadata metadata, RunExecution.Definition execution) {
        return transactions.execute(tx -> {
            var run = create(type, scenario, metadata);
            registerExecution(run.runId(), execution);
            return run;
        });
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
        transactions.executeWithoutResult(tx -> {
            lockActiveRun(id, false);
            var execution = execution(id);
            if (status == RunStatus.SUCCEEDED && execution.isPresent() && !execution.get().evidenceComplete()) {
                throw new IllegalStateException("Successful execution requires complete evidence");
            }
            int updated = jdbc.update("update lab_run set status=?, completed_at=?, summary_json=?, failure_message=? where run_id=? and status in ('QUEUED','RUNNING')",
                    status.name(), System.currentTimeMillis(), encode(summary), failure, id);
            requireTransition(updated, id);
        });
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
    public List<StoredRun> list(String type, String scenarioPrefix, RunStatus status, long before, int limit) {
        checkLimit(limit);
        var sql = new StringBuilder("select * from lab_run where seq < ?");
        var args = new java.util.ArrayList<Object>(List.of(before));
        if (type != null) { sql.append(" and experiment_type=?"); args.add(type); }
        if (scenarioPrefix != null) {
            sql.append(" and scenario_id like ? escape '!'");
            args.add(scenarioPrefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (status != null) { sql.append(" and status=?"); args.add(status.name()); }
        sql.append(" order by seq desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::readRun, args.toArray());
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
    public List<StoredMetric> recentMetrics(String id, int limit) {
        checkLimit(limit);
        var points = jdbc.query("select seq, point_json from lab_metric where run_id=? order by seq desc limit ?",
                (row, n) -> new StoredMetric(row.getLong(1), json.readValue(row.getString(2), MetricPoint.class)), id, limit);
        return List.copyOf(points.reversed());
    }

    @Override
    public List<StoredMetric> metricsBefore(String id, long before, int limit) {
        checkLimit(limit);
        if (before < 0) throw new IllegalArgumentException("beforeSequence must be nonnegative");
        return jdbc.query("select seq, point_json from lab_metric where run_id=? and seq<? order by seq desc limit ?",
                (row, n) -> new StoredMetric(row.getLong(1), json.readValue(row.getString(2), MetricPoint.class)), id, before, limit);
    }

    @Override
    public int recoverInterrupted() {
        return transactions.execute(tx -> {
            jdbc.update("update lab_execution set evidence_complete=false where run_id in (select run_id from lab_run where status in ('QUEUED','RUNNING'))");
            return jdbc.update("update lab_run set status='INTERRUPTED', completed_at=?, failure_message='Process stopped before durable completion' where status in ('QUEUED','RUNNING')",
                    System.currentTimeMillis());
        });
    }

    @Override
    public List<ArtifactReference> artifacts(String id) {
        return jdbc.query("select * from lab_artifact where run_id=? order by artifact_id limit 100",
                (row, n) -> new ArtifactReference(row.getString("artifact_id"), row.getString("run_id"),
                        row.getString("relative_path"), row.getString("media_type"), row.getLong("byte_size"), row.getString("sha256")), id);
    }

    @Override
    public void registerArtifact(ArtifactReference reference) {
        transactions.executeWithoutResult(tx -> {
            // Serialize registrations for this Run so the reference bound is enforced concurrently.
            var rows = jdbc.queryForList("select run_id from lab_run where run_id=? for update", String.class, reference.runId());
            if (rows.isEmpty()) throw new IllegalArgumentException("Run not found");
            var existing = artifacts(reference.runId());
            var sameId = existing.stream().filter(a -> a.artifactId().equals(reference.artifactId())).findFirst();
            if (sameId.isPresent()) {
                if (!sameId.get().equals(reference)) throw new IllegalArgumentException("Artifact ID already has different metadata");
                return;
            }
            if (existing.size() >= 100) throw new IllegalArgumentException("Run artifact reference limit is 100");
            try {
                jdbc.update("insert into lab_artifact values(?,?,?,?,?,?)", reference.artifactId(), reference.runId(),
                        reference.relativePath(), reference.mediaType(), reference.byteSize(), reference.sha256());
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                throw new IllegalArgumentException("Artifact ID belongs to another Run", ex);
            }
        });
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
    private void releaseOwnership() {
        try {
            if (ownershipLock.isValid()) ownershipLock.release();
            ownershipChannel.close();
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot release run storage ownership", ex); }
    }
    @Override public void close() {
        try { dataSource.close(); } finally { releaseOwnership(); }
    }
}
