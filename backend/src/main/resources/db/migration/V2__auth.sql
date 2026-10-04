-- Users, roles and audit trail. A single role column is deliberate: the set of
-- roles is fixed, so a join table plus a permissions table would be plumbing
-- nobody reads. Permissions are derived in code (RolePermissions).

CREATE TABLE users (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                TEXT        NOT NULL,
    password_hash        TEXT        NOT NULL,
    full_name            TEXT        NOT NULL,
    role                 TEXT        NOT NULL
        CHECK (role IN ('LECTOR', 'ADMINISTRATIVO', 'BIBLIOTECARIO', 'ADMINISTRADOR')),
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CITEXT would need an extension; lower() gives case-insensitive uniqueness for free.
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));

CREATE TABLE audit_log (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    action    TEXT        NOT NULL,
    entity    TEXT,
    entity_id TEXT,
    details   JSONB,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_at_idx ON audit_log (at DESC);
CREATE INDEX audit_log_user_idx ON audit_log (user_id, at DESC);

-- Spring Session JDBC schema, owned by Flyway instead of auto-initialised at boot.
-- ponytail: auto-init would DROP and recreate these tables on every restart, which
-- is exactly the "sessions lost on restart" failure we must not have.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);