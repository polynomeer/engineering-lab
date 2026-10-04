-- Additive and restart-safe: mark v2 only after creating its table.
create table if not exists lab_dead_letter (
    run_id varchar(100) not null references lab_run(run_id),
    row_index integer not null,
    record_json clob not null,
    primary key(run_id, row_index)
);
update lab_schema set version=2 where id=1 and version=1;
