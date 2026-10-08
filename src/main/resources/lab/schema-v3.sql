-- Additive, restart-safe DDL. The version changes only after both tables exist.
create table if not exists lab_execution (
    run_id varchar(100) primary key references lab_run(run_id),
    definition_json clob not null,
    worker_json clob,
    phase varchar(30) not null,
    evidence_complete boolean not null default false
);
create table if not exists lab_check (
    run_id varchar(100) not null references lab_execution(run_id),
    seq bigint not null check (seq >= 0 and seq < 100000),
    check_id varchar(100) not null,
    record_json clob not null,
    primary key(run_id, seq),
    unique(run_id, check_id)
);
update lab_schema set version=3 where id=1 and version=2;
