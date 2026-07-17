import { DatabaseSync } from "node:sqlite";
import { chmod, rm } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  ConversationOwnershipError,
  SqliteConversationRepository,
} from "../src/storage/conversation-repository.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { temporaryRuntimeDirectory } from "./helpers/runtime-fixture.js";

const NOW = "2026-07-13T00:00:00.000Z";
const LATER = "2026-07-13T00:01:00.000Z";
const SESSION_ONE = "11111111-1111-4111-8111-111111111111";
const SESSION_TWO = "22222222-2222-4222-8222-222222222222";
const PLAYER_ONE = "33333333-3333-4333-8333-333333333333";
const PLAYER_TWO = "44444444-4444-4444-8444-444444444444";
const REQUEST_ONE = "55555555-5555-4555-8555-555555555555";
const REQUEST_TWO = "66666666-6666-4666-8666-666666666666";
const MESSAGE_IDS = [
  "77777777-7777-4777-8777-777777777777",
  "88888888-8888-4888-8888-888888888888",
  "99999999-9999-4999-8999-999999999999",
  "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
];

const temporaryDirectories: string[] = [];

function owner(serverId = "server-one", playerUuid = PLAYER_ONE) {
  return { serverId, playerUuid };
}

function exchange(sessionId: string, requestId: string, createSession: boolean, createdAt = NOW) {
  return {
    ...owner(),
    sessionId,
    createSession,
    requestId,
    module: "general" as const,
    userContent: `question:${requestId}`,
    assistantContent: `answer:${requestId}`,
    createdAt,
  };
}

function repository(database: DatabaseSync): SqliteConversationRepository {
  let index = 0;
  return new SqliteConversationRepository(database, {
    randomUuid: () => MESSAGE_IDS[index++] ?? "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  });
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("conversation storage", () => {
  it("migrates a fresh database idempotently and rejects future versions", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      migrateRuntimeStorage(database, LATER);
      expect(
        database
          .prepare("SELECT version, name FROM runtime_schema_migrations ORDER BY version")
          .all(),
      ).toEqual([
        { version: 1, name: "sessions-and-messages" },
        { version: 2, name: "projects-and-events" },
        { version: 3, name: "durable-usage-accounting" },
        { version: 4, name: "provider-round-start-state" },
        { version: 5, name: "runtime-process-lock" },
      ]);

      database
        .prepare(
          "INSERT INTO runtime_schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
        )
        .run(6, "future", NOW);
      expect(() => migrateRuntimeStorage(database, LATER)).toThrow(/unsupported/u);
      expect(database.isTransaction).toBe(false);
    } finally {
      database.close();
    }
  });

  it("persists complete exchanges across reopen and enforces owner isolation in SQL", async () => {
    const directory = await temporaryRuntimeDirectory();
    temporaryDirectories.push(directory);
    const path = join(directory, "conversation.db");
    const first = new DatabaseSync(path);
    migrateRuntimeStorage(first, NOW);
    const firstRepository = repository(first);
    firstRepository.commitExchange(exchange(SESSION_ONE, REQUEST_ONE, true));
    first.close();
    await chmod(path, 0o600);

    const reopened = new DatabaseSync(path);
    try {
      const reopenedRepository = repository(reopened);
      expect(reopenedRepository.findOwned(SESSION_ONE, owner())?.id).toBe(SESSION_ONE);
      expect(reopenedRepository.findOwned(SESSION_ONE, owner("server-two"))).toBeUndefined();
      expect(
        reopenedRepository.findOwned(SESSION_ONE, owner("server-one", PLAYER_TWO)),
      ).toBeUndefined();
      expect(
        reopenedRepository.findOwned("00000000-0000-0000-0000-000000000000", owner()),
      ).toBeUndefined();
      expect(
        reopenedRepository.findOwned("aaaaaaaa-aaaa-6aaa-8aaa-aaaaaaaaaaaa", owner()),
      ).toBeUndefined();
      expect(reopenedRepository.loadRecentOwned(SESSION_ONE, owner(), 10)).toMatchObject([
        { role: "user", content: `question:${REQUEST_ONE}` },
        { role: "assistant", content: `answer:${REQUEST_ONE}` },
      ]);
    } finally {
      reopened.close();
    }
  });

  it("selects the latest touched owned session and rolls back a failed exchange", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      const conversations = repository(database);
      conversations.commitExchange(exchange(SESSION_ONE, REQUEST_ONE, true, NOW));
      conversations.commitExchange(exchange(SESSION_TWO, REQUEST_TWO, true, LATER));
      expect(conversations.findLatestOwned(owner())?.id).toBe(SESSION_TWO);

      expect(() =>
        conversations.commitExchange({
          ...exchange(SESSION_ONE, "cccccccc-cccc-4ccc-8ccc-cccccccccccc", false, LATER),
          assistantContent: " ".repeat(8193),
        }),
      ).toThrow();
      expect(conversations.loadRecentOwned(SESSION_ONE, owner(), 10)).toHaveLength(2);
      expect(database.isTransaction).toBe(false);

      expect(() =>
        conversations.commitExchange({
          ...exchange(SESSION_ONE, "dddddddd-dddd-4ddd-8ddd-dddddddddddd", false, LATER),
          playerUuid: PLAYER_TWO,
        }),
      ).toThrow(ConversationOwnershipError);
      expect(conversations.loadRecentOwned(SESSION_ONE, owner(), 10)).toHaveLength(2);
    } finally {
      database.close();
    }
  });

  it("purges expired sessions and cascades their messages", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      const conversations = repository(database);
      conversations.commitExchange(exchange(SESSION_ONE, REQUEST_ONE, true, NOW));
      conversations.commitExchange(exchange(SESSION_TWO, REQUEST_TWO, true, LATER));

      expect(conversations.purgeExpired("2026-07-13T00:00:30.000Z")).toBe(1);
      expect(conversations.findOwned(SESSION_ONE, owner())).toBeUndefined();
      expect(conversations.findOwned(SESSION_TWO, owner())?.id).toBe(SESSION_TWO);
      expect(database.prepare("SELECT COUNT(*) AS count FROM messages").get()?.["count"]).toBe(2);
    } finally {
      database.close();
    }
  });
});
