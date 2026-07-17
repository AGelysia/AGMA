import { randomUUID } from "node:crypto";

import type { DatabaseSync, StatementSync } from "node:sqlite";

import type { ModuleId } from "../modules/module-manifest.js";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const SERVER_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/u;
const MAXIMUM_CONTEXT_MESSAGES = 100;

export interface ConversationOwner {
  readonly serverId: string;
  readonly playerUuid: string;
}

export interface ConversationSession extends ConversationOwner {
  readonly id: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ConversationMessage {
  readonly id: string;
  readonly requestId: string;
  readonly role: "user" | "assistant";
  readonly module: ModuleId;
  readonly content: string;
  readonly createdAt: string;
}

export interface CommitConversationExchange extends ConversationOwner {
  readonly sessionId: string;
  readonly createSession: boolean;
  readonly requestId: string;
  readonly module: ModuleId;
  readonly userContent: string;
  readonly assistantContent: string;
  readonly createdAt: string;
}

export interface ConversationRepository {
  readonly enabled: boolean;
  findOwned(sessionId: string, owner: ConversationOwner): ConversationSession | undefined;
  findLatestOwned(owner: ConversationOwner): ConversationSession | undefined;
  loadRecentOwned(
    sessionId: string,
    owner: ConversationOwner,
    limit: number,
  ): readonly ConversationMessage[];
  commitExchange(exchange: CommitConversationExchange): ConversationSession;
  purgeExpired(cutoff: string): number;
}

export class ConversationOwnershipError extends Error {
  public constructor() {
    super("CONVERSATION_NOT_FOUND");
    this.name = "ConversationOwnershipError";
  }
}

function assertUuid(value: string, field: string): void {
  if (!UUID.test(value)) {
    throw new TypeError(`${field} must be a canonical UUID.`);
  }
}

function assertOwner(owner: ConversationOwner): void {
  if (!SERVER_ID.test(owner.serverId)) {
    throw new TypeError("serverId is invalid.");
  }
  assertUuid(owner.playerUuid, "playerUuid");
}

function assertTimestamp(value: string): void {
  if (!Number.isFinite(Date.parse(value))) {
    throw new TypeError("Conversation timestamp is invalid.");
  }
}

function rowString(row: Record<string, unknown>, field: string): string {
  const value = row[field];
  if (typeof value !== "string") {
    throw new Error("Conversation storage returned an invalid row.");
  }
  return value;
}

function sessionFromRow(row: Record<string, unknown>): ConversationSession {
  return {
    id: rowString(row, "id"),
    serverId: rowString(row, "server_id"),
    playerUuid: rowString(row, "player_uuid"),
    createdAt: rowString(row, "created_at"),
    updatedAt: rowString(row, "updated_at"),
  };
}

function messageFromRow(row: Record<string, unknown>): ConversationMessage {
  return {
    id: rowString(row, "id"),
    requestId: rowString(row, "request_id"),
    role: rowString(row, "role") as ConversationMessage["role"],
    module: rowString(row, "module") as ModuleId,
    content: rowString(row, "content"),
    createdAt: rowString(row, "created_at"),
  };
}

export class DisabledConversationRepository implements ConversationRepository {
  public readonly enabled = false;

  public findOwned(): undefined {
    return undefined;
  }

  public findLatestOwned(): undefined {
    return undefined;
  }

  public loadRecentOwned(): readonly ConversationMessage[] {
    return [];
  }

  public commitExchange(): ConversationSession {
    throw new Error("Conversation storage is disabled.");
  }

  public purgeExpired(): number {
    return 0;
  }
}

export interface SqliteConversationRepositoryOptions {
  readonly randomUuid?: () => string;
}

export class SqliteConversationRepository implements ConversationRepository {
  public readonly enabled = true;

  readonly #database: DatabaseSync;
  readonly #randomUuid: () => string;
  readonly #findOwned: StatementSync;
  readonly #findLatestOwned: StatementSync;
  readonly #loadRecentOwned: StatementSync;
  readonly #insertSession: StatementSync;
  readonly #insertMessage: StatementSync;
  readonly #touchSession: StatementSync;
  readonly #purgeExpired: StatementSync;

  public constructor(database: DatabaseSync, options: SqliteConversationRepositoryOptions = {}) {
    this.#database = database;
    this.#randomUuid = options.randomUuid ?? randomUUID;
    this.#findOwned = database.prepare(`
      SELECT id, server_id, player_uuid, created_at, updated_at
      FROM sessions
      WHERE id = ? AND server_id = ? AND player_uuid = ? AND status = 'ACTIVE'
    `);
    this.#findLatestOwned = database.prepare(`
      SELECT id, server_id, player_uuid, created_at, updated_at
      FROM sessions
      WHERE server_id = ? AND player_uuid = ? AND status = 'ACTIVE'
      ORDER BY updated_sequence DESC, id DESC
      LIMIT 1
    `);
    this.#loadRecentOwned = database.prepare(`
      SELECT m.id, m.request_id, m.role, m.module, m.content, m.created_at
      FROM messages AS m
      INNER JOIN sessions AS s ON s.id = m.session_id
      WHERE s.id = ? AND s.server_id = ? AND s.player_uuid = ? AND s.status = 'ACTIVE'
      ORDER BY m.sequence DESC
      LIMIT ?
    `);
    this.#insertSession = database.prepare(`
      INSERT INTO sessions (id, server_id, player_uuid, status, created_at, updated_at)
      VALUES (?, ?, ?, 'ACTIVE', ?, ?)
    `);
    this.#insertMessage = database.prepare(`
      INSERT INTO messages
        (id, session_id, request_id, role, module, content, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `);
    this.#touchSession = database.prepare(`
      UPDATE sessions
      SET updated_at = ?,
          updated_sequence = (
            SELECT COALESCE(MAX(sequence), 0) FROM messages WHERE session_id = sessions.id
          )
      WHERE id = ? AND server_id = ? AND player_uuid = ? AND status = 'ACTIVE'
    `);
    this.#purgeExpired = database.prepare("DELETE FROM sessions WHERE updated_at < ?");
  }

  public findOwned(sessionId: string, owner: ConversationOwner): ConversationSession | undefined {
    assertUuid(sessionId, "sessionId");
    assertOwner(owner);
    const row = this.#findOwned.get(sessionId, owner.serverId, owner.playerUuid);
    return row === undefined ? undefined : sessionFromRow(row);
  }

  public findLatestOwned(owner: ConversationOwner): ConversationSession | undefined {
    assertOwner(owner);
    const row = this.#findLatestOwned.get(owner.serverId, owner.playerUuid);
    return row === undefined ? undefined : sessionFromRow(row);
  }

  public loadRecentOwned(
    sessionId: string,
    owner: ConversationOwner,
    limit: number,
  ): readonly ConversationMessage[] {
    assertUuid(sessionId, "sessionId");
    assertOwner(owner);
    if (!Number.isSafeInteger(limit) || limit < 0 || limit > MAXIMUM_CONTEXT_MESSAGES) {
      throw new TypeError("Conversation message limit is invalid.");
    }
    if (limit === 0) {
      return [];
    }
    return this.#loadRecentOwned
      .all(sessionId, owner.serverId, owner.playerUuid, limit)
      .map(messageFromRow)
      .reverse();
  }

  public commitExchange(exchange: CommitConversationExchange): ConversationSession {
    assertOwner(exchange);
    assertUuid(exchange.sessionId, "sessionId");
    assertUuid(exchange.requestId, "requestId");
    assertTimestamp(exchange.createdAt);

    this.#database.exec("BEGIN IMMEDIATE");
    try {
      if (exchange.createSession) {
        this.#insertSession.run(
          exchange.sessionId,
          exchange.serverId,
          exchange.playerUuid,
          exchange.createdAt,
          exchange.createdAt,
        );
      } else if (this.findOwned(exchange.sessionId, exchange) === undefined) {
        throw new ConversationOwnershipError();
      }

      this.#insertMessage.run(
        this.#randomUuid(),
        exchange.sessionId,
        exchange.requestId,
        "user",
        exchange.module,
        exchange.userContent,
        exchange.createdAt,
      );
      this.#insertMessage.run(
        this.#randomUuid(),
        exchange.sessionId,
        exchange.requestId,
        "assistant",
        exchange.module,
        exchange.assistantContent,
        exchange.createdAt,
      );
      const touched = this.#touchSession.run(
        exchange.createdAt,
        exchange.sessionId,
        exchange.serverId,
        exchange.playerUuid,
      );
      if (touched.changes !== 1) {
        throw new ConversationOwnershipError();
      }
      const session = this.findOwned(exchange.sessionId, exchange);
      if (session === undefined) {
        throw new ConversationOwnershipError();
      }
      this.#database.exec("COMMIT");
      return session;
    } catch (error) {
      if (this.#database.isTransaction) {
        this.#database.exec("ROLLBACK");
      }
      throw error;
    }
  }

  public purgeExpired(cutoff: string): number {
    assertTimestamp(cutoff);
    return Number(this.#purgeExpired.run(cutoff).changes);
  }
}
