create table if not exists lab_schema (id integer primary key, version integer not null);
create table if not exists lab_run (
    seq bigint generated always as identity unique,
    run_id varchar(100) primary key,
    experiment_type varchar(200) not null,
    scenario_id varchar(200) not null,
    status varchar(30) not null,
    created_at bigint not null,
    started_at bigint not null default 0,
    completed_at bigint not null default 0,
    metadata_json clob not null,
    summary_json clob not null,
    failure_message clob
);
create index if not exists lab_run_type_seq on lab_run(experiment_type, seq);
create table if not exists lab_metric (
    run_id varchar(100) not null references lab_run(run_id),
    seq bigint not null,
    point_json clob not null,
    primary key(run_id, seq)
);
create table if not exists lab_artifact (
    artifact_id varchar(100) primary key,
    run_id varchar(100) not null references lab_run(run_id),
    relative_path varchar(1000) not null,
    media_type varchar(200) not null,
    byte_size bigint not null,
    sha256 varchar(64) not null
);
insert into lab_schema (id, version) select 1, 1 where not exists (select 1 from lab_schema where id=1);
