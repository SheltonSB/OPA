CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    slug VARCHAR(100) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$'),
    display_name VARCHAR(200) NOT NULL,
    home_region VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy_versions (
    id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    repository VARCHAR(512) NOT NULL,
    git_commit CHAR(40) NOT NULL CHECK (git_commit ~ '^[0-9a-f]{40}$'),
    object_key VARCHAR(1024) NOT NULL,
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    query_path VARCHAR(512) NOT NULL CHECK (query_path ~ '^data(\.[A-Za-z_][A-Za-z0-9_-]*)+$'),
    complexity_score BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, repository, git_commit, sha256)
);

CREATE TABLE dataset_versions (
    id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    object_key VARCHAR(1024) NOT NULL,
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    case_count BIGINT NOT NULL CHECK (case_count > 0),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, sha256)
);

CREATE TABLE benchmark_jobs (
    id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    baseline_version_id UUID NOT NULL,
    candidate_version_id UUID NOT NULL,
    historical_version_id UUID,
    dataset_version_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    thresholds JSONB NOT NULL CHECK (jsonb_typeof(thresholds) = 'object'),
    warmup_iterations INTEGER NOT NULL CHECK (warmup_iterations BETWEEN 0 AND 10000),
    measured_iterations INTEGER NOT NULL CHECK (measured_iterations BETWEEN 1 AND 1000000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','ANALYZING','PASSED','FAILED','ERROR','CANCELLED')),
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, idempotency_key),
    FOREIGN KEY (organization_id, baseline_version_id) REFERENCES policy_versions(organization_id, id),
    FOREIGN KEY (organization_id, candidate_version_id) REFERENCES policy_versions(organization_id, id),
    FOREIGN KEY (organization_id, historical_version_id) REFERENCES policy_versions(organization_id, id),
    FOREIGN KEY (organization_id, dataset_version_id) REFERENCES dataset_versions(organization_id, id)
);

CREATE INDEX benchmark_jobs_org_status_created_idx
    ON benchmark_jobs (organization_id, status, created_at DESC);
CREATE INDEX benchmark_jobs_recovery_idx
    ON benchmark_jobs (status, lease_expires_at)
    WHERE status = 'RUNNING';

CREATE TABLE benchmark_results (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    job_id UUID NOT NULL,
    branch_role VARCHAR(16) NOT NULL CHECK (branch_role IN ('BASELINE','CANDIDATE','HISTORICAL')),
    metrics JSONB NOT NULL CHECK (jsonb_typeof(metrics) = 'object'),
    decision_digest CHAR(64) NOT NULL,
    raw_samples_uri VARCHAR(2048) NOT NULL,
    raw_samples_sha256 CHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, id, completed_at)
) PARTITION BY RANGE (completed_at);

-- Create future monthly partitions before their month begins. This bootstrap partition prevents insert loss.
CREATE TABLE benchmark_results_default PARTITION OF benchmark_results DEFAULT;
CREATE INDEX benchmark_results_job_idx ON benchmark_results (organization_id, job_id, completed_at DESC);

CREATE TABLE benchmark_reports (
    organization_id UUID NOT NULL,
    job_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    markdown TEXT NOT NULL CHECK (octet_length(markdown) <= 1048576),
    html TEXT NOT NULL CHECK (octet_length(html) <= 4194304),
    report_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, job_id),
    FOREIGN KEY (organization_id, job_id) REFERENCES benchmark_jobs(organization_id, id)
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    topic VARCHAR(200) NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    partition_key VARCHAR(256) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error VARCHAR(1000)
);

CREATE INDEX outbox_unpublished_idx ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_events (
    consumer_group VARCHAR(200) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

ALTER TABLE policy_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE dataset_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmark_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmark_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmark_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE dataset_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE benchmark_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE benchmark_results FORCE ROW LEVEL SECURITY;
ALTER TABLE benchmark_reports FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_policy_versions ON policy_versions
    USING (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_dataset_versions ON dataset_versions
    USING (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_benchmark_jobs ON benchmark_jobs
    USING (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_benchmark_results ON benchmark_results
    USING (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_benchmark_reports ON benchmark_reports
    USING (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
