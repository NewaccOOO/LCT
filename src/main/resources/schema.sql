CREATE TABLE IF NOT EXISTS jobs (
    id uuid PRIMARY KEY,
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    finished_at timestamptz,
    error jsonb,
    summary jsonb,
    input_path text,
    output_path text
);
