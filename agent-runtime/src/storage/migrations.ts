import type { DatabaseSync } from "node:sqlite";

export const CURRENT_RUNTIME_SCHEMA_VERSION = 6;

const MIGRATION_ONE = `
  CREATE TABLE sessions (
    id TEXT PRIMARY KEY NOT NULL,
    server_id TEXT NOT NULL CHECK(length(server_id) BETWEEN 1 AND 64),
    player_uuid TEXT NOT NULL CHECK(length(player_uuid) = 36),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'ARCHIVED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    updated_sequence INTEGER NOT NULL DEFAULT 0 CHECK(updated_sequence >= 0)
  ) STRICT;
  CREATE INDEX sessions_owner_updated
    ON sessions(server_id, player_uuid, status, updated_sequence DESC, id DESC);

  CREATE TABLE messages (
    sequence INTEGER PRIMARY KEY,
    id TEXT UNIQUE NOT NULL CHECK(length(id) = 36),
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL CHECK(length(request_id) = 36),
    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
    module TEXT NOT NULL CHECK(module IN ('general', 'recipe', 'guide', 'locate', 'build', 'project')),
    content TEXT NOT NULL CHECK(
      (role = 'user' AND length(content) BETWEEN 1 AND 4096)
      OR (role = 'assistant' AND length(content) BETWEEN 1 AND 8192)
    ),
    created_at TEXT NOT NULL,
    UNIQUE(request_id, role)
  ) STRICT;
  CREATE INDEX messages_session_sequence ON messages(session_id, sequence DESC);
`;

const MIGRATION_TWO = `
  CREATE TABLE projects (
    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) = 36),
    server_id TEXT NOT NULL CHECK(length(server_id) BETWEEN 1 AND 64),
    player_uuid TEXT NOT NULL CHECK(length(player_uuid) = 36),
    name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 80),
    name_key TEXT NOT NULL CHECK(length(name_key) BETWEEN 1 AND 256),
    summary TEXT NOT NULL CHECK(length(summary) BETWEEN 1 AND 2048),
    goals_json TEXT NOT NULL CHECK(length(goals_json) BETWEEN 2 AND 8192),
    constraints_json TEXT NOT NULL CHECK(length(constraints_json) BETWEEN 2 AND 8192),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'ARCHIVED')),
    revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 2147483647),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  ) STRICT;
  CREATE UNIQUE INDEX projects_owner_active_name
    ON projects(server_id, player_uuid, name_key)
    WHERE status = 'ACTIVE';
  CREATE INDEX projects_owner_updated
    ON projects(server_id, player_uuid, status, updated_at DESC, id DESC);

  CREATE TABLE project_events (
    sequence INTEGER PRIMARY KEY,
    id TEXT UNIQUE NOT NULL CHECK(length(id) = 36),
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL CHECK(length(request_id) = 36),
    tool_call_id TEXT UNIQUE NOT NULL CHECK(length(tool_call_id) = 36),
    event_type TEXT NOT NULL CHECK(event_type IN ('CREATED', 'UPDATED')),
    revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 2147483647),
    snapshot_json TEXT NOT NULL CHECK(length(snapshot_json) BETWEEN 2 AND 16384),
    created_at TEXT NOT NULL,
    UNIQUE(project_id, revision)
  ) STRICT;
  CREATE INDEX project_events_project_sequence
    ON project_events(project_id, sequence DESC);
`;

const MIGRATION_THREE = `
  CREATE TABLE usage_request_admissions (
    server_id TEXT NOT NULL CHECK(length(server_id) BETWEEN 1 AND 64),
    request_id TEXT NOT NULL CHECK(length(request_id) = 36),
    player_uuid TEXT NOT NULL CHECK(length(player_uuid) = 36),
    usage_day TEXT NOT NULL CHECK(length(usage_day) = 10),
    usage_month TEXT NOT NULL CHECK(length(usage_month) = 7),
    state TEXT NOT NULL CHECK(state IN ('ACTIVE', 'CLOSED')),
    admitted_at TEXT NOT NULL,
    closed_at TEXT,
    PRIMARY KEY(server_id, request_id),
    CHECK(
      (state = 'ACTIVE' AND closed_at IS NULL)
      OR (state = 'CLOSED' AND closed_at IS NOT NULL)
    )
  ) STRICT, WITHOUT ROWID;
  CREATE INDEX usage_request_admissions_active
    ON usage_request_admissions(server_id, state, admitted_at);

  CREATE TABLE usage_round_reservations (
    server_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    provider_round INTEGER NOT NULL CHECK(provider_round BETWEEN 0 AND 64),
    usage_month TEXT NOT NULL CHECK(length(usage_month) = 7),
    reserved_micro_usd INTEGER NOT NULL CHECK(reserved_micro_usd >= 0),
    state TEXT NOT NULL CHECK(state IN ('ACTIVE', 'SETTLED', 'RELEASED')),
    reserved_at TEXT NOT NULL,
    settled_at TEXT,
    PRIMARY KEY(server_id, request_id, provider_round),
    FOREIGN KEY(server_id, request_id)
      REFERENCES usage_request_admissions(server_id, request_id) ON DELETE CASCADE,
    CHECK(
      (state = 'ACTIVE' AND settled_at IS NULL)
      OR (state <> 'ACTIVE' AND settled_at IS NOT NULL)
    )
  ) STRICT, WITHOUT ROWID;
  CREATE INDEX usage_round_reservations_active
    ON usage_round_reservations(server_id, usage_month, state);

  CREATE TABLE provider_usage_events (
    server_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    provider_round INTEGER NOT NULL CHECK(provider_round BETWEEN 0 AND 64),
    player_uuid TEXT NOT NULL CHECK(length(player_uuid) = 36),
    provider TEXT NOT NULL CHECK(length(provider) BETWEEN 1 AND 32),
    model TEXT NOT NULL CHECK(length(model) BETWEEN 1 AND 128),
    usage_kind TEXT NOT NULL CHECK(usage_kind IN ('REPORTED', 'ESTIMATED')),
    input_tokens INTEGER NOT NULL CHECK(input_tokens >= 0),
    output_tokens INTEGER NOT NULL CHECK(output_tokens >= 0),
    cost_micro_usd INTEGER NOT NULL CHECK(cost_micro_usd >= 0),
    occurred_at TEXT NOT NULL,
    usage_day TEXT NOT NULL CHECK(length(usage_day) = 10),
    usage_month TEXT NOT NULL CHECK(length(usage_month) = 7),
    PRIMARY KEY(server_id, request_id, provider_round),
    FOREIGN KEY(server_id, request_id, provider_round)
      REFERENCES usage_round_reservations(server_id, request_id, provider_round)
  ) STRICT, WITHOUT ROWID;
  CREATE INDEX provider_usage_events_recent
    ON provider_usage_events(server_id, occurred_at DESC);

  CREATE TABLE usage_player_daily (
    server_id TEXT NOT NULL,
    player_uuid TEXT NOT NULL CHECK(length(player_uuid) = 36),
    usage_day TEXT NOT NULL CHECK(length(usage_day) = 10),
    admitted_requests INTEGER NOT NULL CHECK(admitted_requests >= 0),
    PRIMARY KEY(server_id, player_uuid, usage_day)
  ) STRICT, WITHOUT ROWID;

  CREATE TABLE usage_daily (
    server_id TEXT NOT NULL,
    usage_day TEXT NOT NULL CHECK(length(usage_day) = 10),
    admitted_requests INTEGER NOT NULL CHECK(admitted_requests >= 0),
    provider_calls INTEGER NOT NULL CHECK(provider_calls >= 0),
    reported_provider_calls INTEGER NOT NULL CHECK(reported_provider_calls >= 0),
    estimated_provider_calls INTEGER NOT NULL CHECK(estimated_provider_calls >= 0),
    input_tokens INTEGER NOT NULL CHECK(input_tokens >= 0),
    output_tokens INTEGER NOT NULL CHECK(output_tokens >= 0),
    cost_micro_usd INTEGER NOT NULL CHECK(cost_micro_usd >= 0),
    PRIMARY KEY(server_id, usage_day),
    CHECK(provider_calls = reported_provider_calls + estimated_provider_calls)
  ) STRICT, WITHOUT ROWID;

  CREATE TABLE usage_monthly (
    server_id TEXT NOT NULL,
    usage_month TEXT NOT NULL CHECK(length(usage_month) = 7),
    admitted_requests INTEGER NOT NULL CHECK(admitted_requests >= 0),
    provider_calls INTEGER NOT NULL CHECK(provider_calls >= 0),
    reported_provider_calls INTEGER NOT NULL CHECK(reported_provider_calls >= 0),
    estimated_provider_calls INTEGER NOT NULL CHECK(estimated_provider_calls >= 0),
    input_tokens INTEGER NOT NULL CHECK(input_tokens >= 0),
    output_tokens INTEGER NOT NULL CHECK(output_tokens >= 0),
    cost_micro_usd INTEGER NOT NULL CHECK(cost_micro_usd >= 0),
    PRIMARY KEY(server_id, usage_month),
    CHECK(provider_calls = reported_provider_calls + estimated_provider_calls)
  ) STRICT, WITHOUT ROWID;
`;

const MIGRATION_FOUR = `
  ALTER TABLE usage_round_reservations ADD COLUMN started_at TEXT;
  UPDATE usage_round_reservations
    SET started_at = reserved_at
    WHERE state = 'ACTIVE';
  CREATE INDEX usage_round_reservations_started
    ON usage_round_reservations(server_id, state, started_at);
`;

const MIGRATION_FIVE = `
  CREATE TABLE runtime_process_lock (
    lock_id INTEGER PRIMARY KEY NOT NULL CHECK(lock_id = 1),
    instance_id TEXT NOT NULL CHECK(length(instance_id) = 36),
    pid INTEGER NOT NULL CHECK(pid > 0),
    process_start_token TEXT,
    acquired_at TEXT NOT NULL
  ) STRICT;
`;

const MIGRATION_SIX = `
  CREATE TABLE web_search_usage_monthly (
    scope_id TEXT NOT NULL CHECK(length(scope_id) BETWEEN 1 AND 64),
    usage_month TEXT NOT NULL CHECK(length(usage_month) = 7),
    charged_requests INTEGER NOT NULL CHECK(charged_requests >= 0),
    spent_micro_usd INTEGER NOT NULL CHECK(spent_micro_usd >= 0),
    updated_at TEXT NOT NULL,
    PRIMARY KEY(scope_id, usage_month)
  ) STRICT, WITHOUT ROWID;
`;

const migrations = [
  { version: 1, name: "sessions-and-messages", sql: MIGRATION_ONE },
  { version: 2, name: "projects-and-events", sql: MIGRATION_TWO },
  { version: 3, name: "durable-usage-accounting", sql: MIGRATION_THREE },
  { version: 4, name: "provider-round-start-state", sql: MIGRATION_FOUR },
  { version: 5, name: "runtime-process-lock", sql: MIGRATION_FIVE },
  { version: 6, name: "persistent-web-search-budget", sql: MIGRATION_SIX },
] as const;

export function migrateRuntimeStorage(database: DatabaseSync, appliedAt: string): void {
  if (!Number.isFinite(Date.parse(appliedAt))) {
    throw new TypeError("Migration timestamp is invalid.");
  }

  database.exec("BEGIN IMMEDIATE");
  try {
    database.exec(`
      CREATE TABLE IF NOT EXISTS runtime_schema_migrations (
        version INTEGER PRIMARY KEY,
        name TEXT UNIQUE NOT NULL,
        applied_at TEXT NOT NULL
      ) STRICT
    `);
    const rows = database
      .prepare("SELECT version FROM runtime_schema_migrations ORDER BY version")
      .all();
    const versions = rows.map((row) => Number(row["version"]));
    if (
      versions.some(
        (version, index) =>
          !Number.isSafeInteger(version) ||
          version !== index + 1 ||
          version > CURRENT_RUNTIME_SCHEMA_VERSION,
      )
    ) {
      throw new Error("Runtime SQLite schema version is unsupported.");
    }

    for (const migration of migrations.slice(versions.length)) {
      database.exec(migration.sql);
      database
        .prepare(
          "INSERT INTO runtime_schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
        )
        .run(migration.version, migration.name, appliedAt);
    }
    database.exec("COMMIT");
  } catch (error) {
    if (database.isTransaction) {
      database.exec("ROLLBACK");
    }
    throw error;
  }
}
