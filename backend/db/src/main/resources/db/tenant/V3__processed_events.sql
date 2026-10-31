CREATE TABLE processed_events (
    event_id     uuid        NOT NULL,
    handler      text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, handler)
);
