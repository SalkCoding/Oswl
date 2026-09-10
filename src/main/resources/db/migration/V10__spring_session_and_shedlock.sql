-- Horizontal scaling / HA: cluster-wide HTTP session storage (Spring Session JDBC)
-- and a lock table for @Scheduled jobs (ShedLock), so a multi-instance deployment behind a load
-- balancer keeps users logged in on failover and never runs the nightly monitoring / defer-expiry /
-- trash-cleanup jobs more than once per cycle. Both are opt-in — see OSWL_SESSION_STORE_TYPE and
-- OSWL_SCHEDULER_LOCK_ENABLED in application.yaml; a single-instance deployment can leave this
-- schema unused (in-memory session registry, no scheduler lock).
--
-- Table/column names and shapes match the upstream defaults exactly (Spring Session's
-- JdbcIndexedSessionRepository and ShedLock's JdbcTemplateLockProvider both issue unquoted SQL
-- against these identifiers) — do not rename without also overriding the corresponding
-- spring.session.jdbc.* / LockProvider table-name properties.

CREATE TABLE IF NOT EXISTS spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS spring_session_ix1 ON spring_session (session_id);
CREATE INDEX IF NOT EXISTS spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX IF NOT EXISTS spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE IF NOT EXISTS spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
