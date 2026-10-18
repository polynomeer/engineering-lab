-- Additive, restart-safe DDL. Series membership stays outside Run metadata so per-repetition
-- labels never appear as condition differences. The version changes only after all tables exist.
create table if not exists lab_series (
    seq bigint generated always as identity unique,
    series_id varchar(100) primary key,
    experiment_type varchar(200) not null,
    scenario_id varchar(200) not null,
    definition_sha256 varchar(64) not null,
    repetitions integer not null check (repetitions between 1 and 50),
    warmup integer not null check (warmup between 0 and 10),
    status varchar(30) not null,
    created_at bigint not null,
    completed_at bigint not null default 0,
    failure_message varchar(1000)
);
create index if not exists lab_series_type_seq on lab_series(experiment_type, seq);
create table if not exists lab_series_run (
    series_id varchar(100) not null references lab_series(series_id),
    position integer not null check (position >= 0 and position < 60),
    run_id varchar(100) not null unique references lab_run(run_id),
    warmup boolean not null,
    primary key(series_id, position)
);
-- One pinned baseline series per experiment and scenario ID, for regression checks.
create table if not exists lab_series_baseline (
    experiment_type varchar(200) not null,
    scenario_id varchar(200) not null,
    series_id varchar(100) not null references lab_series(series_id),
    pinned_at bigint not null,
    primary key(experiment_type, scenario_id)
);
update lab_schema set version=4 where id=1 and version=3;
