import { randomUUID } from "node:crypto";

import type { DatabaseSync, StatementSync } from "node:sqlite";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const SERVER_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/u;
const MAXIMUM_PROJECTS = 20;
const MAXIMUM_LIST_ITEMS = 16;

export interface ProjectOwner {
  readonly serverId: string;
  readonly playerUuid: string;
}

export interface ProjectPlan {
  readonly name: string;
  readonly summary: string;
  readonly goals: readonly string[];
  readonly constraints: readonly string[];
}

export interface StoredProject extends ProjectPlan {
  readonly projectId: string;
  readonly status: "ACTIVE" | "ARCHIVED";
  readonly revision: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ProjectSummary {
  readonly projectId: string;
  readonly name: string;
  readonly status: "ACTIVE" | "ARCHIVED";
  readonly revision: number;
  readonly updatedAt: string;
}

interface ProjectMutationContext extends ProjectOwner {
  readonly requestId: string;
  readonly toolCallId: string;
  readonly timestamp: string;
}

export interface CreateProject extends ProjectMutationContext {
  readonly plan: ProjectPlan;
}

export interface UpdateProject extends ProjectMutationContext {
  readonly projectId: string;
  readonly expectedRevision: number;
  readonly plan: ProjectPlan;
}

export type CreateProjectResult =
  | { readonly outcome: "CREATED"; readonly project: StoredProject }
  | { readonly outcome: "LIMIT_REACHED" | "NAME_CONFLICT"; readonly project: null };

export type UpdateProjectResult =
  | { readonly outcome: "UPDATED"; readonly project: StoredProject }
  | { readonly outcome: "NOT_FOUND_OR_CHANGED" | "NAME_CONFLICT"; readonly project: null };

export interface ProjectListResult {
  readonly projects: readonly ProjectSummary[];
  readonly truncated: boolean;
}

export interface ProjectRepository {
  listOwned(owner: ProjectOwner): ProjectListResult;
  findOwned(projectId: string, owner: ProjectOwner): StoredProject | undefined;
  create(input: CreateProject): CreateProjectResult;
  update(input: UpdateProject): UpdateProjectResult;
}

export interface SqliteProjectRepositoryOptions {
  readonly randomUuid?: () => string;
}

function assertUuid(value: string, field: string): void {
  if (!UUID.test(value)) {
    throw new TypeError(`${field} must be a canonical UUID.`);
  }
}

function assertOwner(owner: ProjectOwner): void {
  if (!SERVER_ID.test(owner.serverId)) {
    throw new TypeError("serverId is invalid.");
  }
  assertUuid(owner.playerUuid, "playerUuid");
}

function assertTimestamp(value: string): void {
  if (!Number.isFinite(Date.parse(value))) {
    throw new TypeError("Project timestamp is invalid.");
  }
}

function visibleText(value: string, maximum: number, field: string): string {
  const normalized = value.trim();
  let length = 0;
  for (const character of normalized) {
    length += 1;
    const codePoint = character.codePointAt(0);
    if (
      codePoint === undefined ||
      (codePoint >= 0xd800 && codePoint <= 0xdfff) ||
      ((codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) &&
        codePoint !== 0x09 &&
        codePoint !== 0x0a) ||
      codePoint === 0x061c ||
      codePoint === 0x200e ||
      codePoint === 0x200f ||
      (codePoint >= 0x202a && codePoint <= 0x202e) ||
      (codePoint >= 0x2066 && codePoint <= 0x2069)
    ) {
      throw new TypeError(`${field} contains unsafe text.`);
    }
  }
  if (length < 1 || length > maximum) {
    throw new TypeError(`${field} length is invalid.`);
  }
  return normalized;
}

function textList(values: readonly string[], field: string): readonly string[] {
  if (!Array.isArray(values) || values.length > MAXIMUM_LIST_ITEMS) {
    throw new TypeError(`${field} count is invalid.`);
  }
  return values.map((value) => visibleText(value, 256, field));
}

function normalizePlan(plan: ProjectPlan): ProjectPlan {
  const name = visibleText(plan.name, 80, "name").normalize("NFKC");
  return {
    name: visibleText(name, 80, "name"),
    summary: visibleText(plan.summary, 2048, "summary"),
    goals: textList(plan.goals, "goals"),
    constraints: textList(plan.constraints, "constraints"),
  };
}

function nameKey(name: string): string {
  return name.normalize("NFKC").toLowerCase();
}

function rowString(row: Record<string, unknown>, field: string): string {
  const value = row[field];
  if (typeof value !== "string") {
    throw new Error("Project storage returned an invalid row.");
  }
  return value;
}

function rowInteger(row: Record<string, unknown>, field: string): number {
  const value = Number(row[field]);
  if (!Number.isSafeInteger(value)) {
    throw new Error("Project storage returned an invalid row.");
  }
  return value;
}

function storedTextList(row: Record<string, unknown>, field: string): readonly string[] {
  let value: unknown;
  try {
    value = JSON.parse(rowString(row, field));
  } catch (error) {
    throw new Error("Project storage returned invalid structured text.", { cause: error });
  }
  if (!Array.isArray(value) || !value.every((entry) => typeof entry === "string")) {
    throw new Error("Project storage returned invalid structured text.");
  }
  return textList(value, field);
}

function projectFromRow(row: Record<string, unknown>): StoredProject {
  const status = rowString(row, "status");
  if (status !== "ACTIVE" && status !== "ARCHIVED") {
    throw new Error("Project storage returned an invalid status.");
  }
  const project = {
    projectId: rowString(row, "id"),
    name: rowString(row, "name"),
    summary: rowString(row, "summary"),
    goals: storedTextList(row, "goals_json"),
    constraints: storedTextList(row, "constraints_json"),
    status,
    revision: rowInteger(row, "revision"),
    createdAt: rowString(row, "created_at"),
    updatedAt: rowString(row, "updated_at"),
  } satisfies StoredProject;
  assertUuid(project.projectId, "projectId");
  assertTimestamp(project.createdAt);
  assertTimestamp(project.updatedAt);
  return { ...project, ...normalizePlan(project) };
}

function summaryFromRow(row: Record<string, unknown>): ProjectSummary {
  const project = projectFromRow(row);
  return {
    projectId: project.projectId,
    name: project.name,
    status: project.status,
    revision: project.revision,
    updatedAt: project.updatedAt,
  };
}

function snapshot(project: StoredProject): string {
  return JSON.stringify(project);
}

export class SqliteProjectRepository implements ProjectRepository {
  readonly #database: DatabaseSync;
  readonly #randomUuid: () => string;
  readonly #listOwned: StatementSync;
  readonly #countOwned: StatementSync;
  readonly #findOwned: StatementSync;
  readonly #findNameConflict: StatementSync;
  readonly #insertProject: StatementSync;
  readonly #updateProject: StatementSync;
  readonly #insertEvent: StatementSync;

  public constructor(database: DatabaseSync, options: SqliteProjectRepositoryOptions = {}) {
    this.#database = database;
    this.#randomUuid = options.randomUuid ?? randomUUID;
    const columns =
      "id, name, summary, goals_json, constraints_json, status, revision, created_at, updated_at";
    this.#listOwned = database.prepare(`
      SELECT ${columns}
      FROM projects
      WHERE server_id = ? AND player_uuid = ? AND status = 'ACTIVE'
      ORDER BY updated_at DESC, id DESC
      LIMIT ?
    `);
    this.#countOwned = database.prepare(`
      SELECT COUNT(*) AS count
      FROM projects
      WHERE server_id = ? AND player_uuid = ? AND status = 'ACTIVE'
    `);
    this.#findOwned = database.prepare(`
      SELECT ${columns}
      FROM projects
      WHERE id = ? AND server_id = ? AND player_uuid = ? AND status = 'ACTIVE'
    `);
    this.#findNameConflict = database.prepare(`
      SELECT id
      FROM projects
      WHERE server_id = ? AND player_uuid = ? AND name_key = ? AND status = 'ACTIVE' AND id <> ?
      LIMIT 1
    `);
    this.#insertProject = database.prepare(`
      INSERT INTO projects
        (id, server_id, player_uuid, name, name_key, summary, goals_json, constraints_json,
         status, revision, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
    `);
    this.#updateProject = database.prepare(`
      UPDATE projects
      SET name = ?, name_key = ?, summary = ?, goals_json = ?, constraints_json = ?,
          revision = revision + 1, updated_at = ?
      WHERE id = ? AND server_id = ? AND player_uuid = ? AND status = 'ACTIVE' AND revision = ?
    `);
    this.#insertEvent = database.prepare(`
      INSERT INTO project_events
        (id, project_id, request_id, tool_call_id, event_type, revision, snapshot_json, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `);
  }

  public listOwned(owner: ProjectOwner): ProjectListResult {
    assertOwner(owner);
    const rows = this.#listOwned.all(owner.serverId, owner.playerUuid, MAXIMUM_PROJECTS + 1);
    return {
      projects: rows.slice(0, MAXIMUM_PROJECTS).map(summaryFromRow),
      truncated: rows.length > MAXIMUM_PROJECTS,
    };
  }

  public findOwned(projectId: string, owner: ProjectOwner): StoredProject | undefined {
    assertUuid(projectId, "projectId");
    assertOwner(owner);
    const row = this.#findOwned.get(projectId, owner.serverId, owner.playerUuid);
    return row === undefined ? undefined : projectFromRow(row);
  }

  public create(input: CreateProject): CreateProjectResult {
    this.#assertMutation(input);
    const plan = normalizePlan(input.plan);
    const projectId = this.#randomUuid();
    assertUuid(projectId, "projectId");

    this.#database.exec("BEGIN IMMEDIATE");
    try {
      const count = this.#countOwned.get(input.serverId, input.playerUuid);
      if (count === undefined || rowInteger(count, "count") >= MAXIMUM_PROJECTS) {
        this.#database.exec("ROLLBACK");
        return { outcome: "LIMIT_REACHED", project: null };
      }
      if (
        this.#findNameConflict.get(
          input.serverId,
          input.playerUuid,
          nameKey(plan.name),
          projectId,
        ) !== undefined
      ) {
        this.#database.exec("ROLLBACK");
        return { outcome: "NAME_CONFLICT", project: null };
      }
      this.#insertProject.run(
        projectId,
        input.serverId,
        input.playerUuid,
        plan.name,
        nameKey(plan.name),
        plan.summary,
        JSON.stringify(plan.goals),
        JSON.stringify(plan.constraints),
        input.timestamp,
        input.timestamp,
      );
      const project = this.#requiredOwned(projectId, input);
      this.#recordEvent(project, input, "CREATED");
      this.#database.exec("COMMIT");
      return { outcome: "CREATED", project };
    } catch (error) {
      this.#rollback();
      throw error;
    }
  }

  public update(input: UpdateProject): UpdateProjectResult {
    this.#assertMutation(input);
    assertUuid(input.projectId, "projectId");
    if (
      !Number.isSafeInteger(input.expectedRevision) ||
      input.expectedRevision < 1 ||
      input.expectedRevision >= 2_147_483_647
    ) {
      throw new TypeError("expectedRevision is invalid.");
    }
    const plan = normalizePlan(input.plan);

    this.#database.exec("BEGIN IMMEDIATE");
    try {
      const current = this.findOwned(input.projectId, input);
      if (current === undefined || current.revision !== input.expectedRevision) {
        this.#database.exec("ROLLBACK");
        return { outcome: "NOT_FOUND_OR_CHANGED", project: null };
      }
      if (
        this.#findNameConflict.get(
          input.serverId,
          input.playerUuid,
          nameKey(plan.name),
          input.projectId,
        ) !== undefined
      ) {
        this.#database.exec("ROLLBACK");
        return { outcome: "NAME_CONFLICT", project: null };
      }
      const updated = this.#updateProject.run(
        plan.name,
        nameKey(plan.name),
        plan.summary,
        JSON.stringify(plan.goals),
        JSON.stringify(plan.constraints),
        input.timestamp,
        input.projectId,
        input.serverId,
        input.playerUuid,
        input.expectedRevision,
      );
      if (updated.changes !== 1) {
        throw new Error("Project changed during its bounded update transaction.");
      }
      const project = this.#requiredOwned(input.projectId, input);
      this.#recordEvent(project, input, "UPDATED");
      this.#database.exec("COMMIT");
      return { outcome: "UPDATED", project };
    } catch (error) {
      this.#rollback();
      throw error;
    }
  }

  #assertMutation(input: ProjectMutationContext): void {
    assertOwner(input);
    assertUuid(input.requestId, "requestId");
    assertUuid(input.toolCallId, "toolCallId");
    assertTimestamp(input.timestamp);
  }

  #requiredOwned(projectId: string, owner: ProjectOwner): StoredProject {
    const project = this.findOwned(projectId, owner);
    if (project === undefined) {
      throw new Error("Project transaction lost its owned row.");
    }
    return project;
  }

  #recordEvent(
    project: StoredProject,
    input: ProjectMutationContext,
    eventType: "CREATED" | "UPDATED",
  ): void {
    const eventId = this.#randomUuid();
    assertUuid(eventId, "eventId");
    this.#insertEvent.run(
      eventId,
      project.projectId,
      input.requestId,
      input.toolCallId,
      eventType,
      project.revision,
      snapshot(project),
      input.timestamp,
    );
  }

  #rollback(): void {
    if (this.#database.isTransaction) {
      this.#database.exec("ROLLBACK");
    }
  }
}
