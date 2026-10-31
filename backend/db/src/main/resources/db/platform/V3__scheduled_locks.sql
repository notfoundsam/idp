CREATE TABLE scheduled_locks (
    name         text PRIMARY KEY,
    locked_until timestamptz NOT NULL,
    holder       uuid        NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON scheduled_locks TO worker_runtime;
