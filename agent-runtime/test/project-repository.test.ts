import { DatabaseSync } from "node:sqlite";

import { describe, expect, it } from "vitest";

import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { SqliteProjectRepository, type ProjectPlan } from "../src/storage/project-repository.js";

const NOW = "2026-07-13T00:00:00.000Z";
const LATER = "2026-07-13T00:01:00.000Z";
const PLAYER_ONE = "11111111-1111-4111-8111-111111111111";
const PLAYER_TWO = "22222222-2222-4222-8222-222222222222";
const REQUEST_ONE = "33333333-3333-4333-8333-333333333333";
const REQUEST_TWO = "44444444-4444-4444-8444-444444444444";
const TOOL_ONE = "55555555-5555-4555-8555-555555555555";
const TOOL_TWO = "66666666-6666-4666-8666-666666666666";

function owner(serverId = "server-one", playerUuid = PLAYER_ONE) {
  return { serverId, playerUuid };
}

function plan(name = "Harbor"): ProjectPlan {
  return {
    name,
    summary: "A compact trading harbor.",
    goals: ["Create three market stalls"],
    constraints: ["Keep the existing lighthouse"],
  };
}

function repository(database: DatabaseSync): SqliteProjectRepository {
  const ids = [
    "77777777-7777-4777-8777-777777777777",
    "88888888-8888-4888-8888-888888888888",
    "99999999-9999-4999-8999-999999999999",
    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  ];
  return new SqliteProjectRepository(database, {
    randomUuid: () => ids.shift() ?? "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
  });
}

describe("project repository", () => {
  it("migrates v2 and isolates every read by server and player owner", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      const projects = repository(database);
      const created = projects.create({
        ...owner(),
        requestId: REQUEST_ONE,
        toolCallId: TOOL_ONE,
        timestamp: NOW,
        plan: plan(),
      });
      expect(created.outcome).toBe("CREATED");
      if (created.project === null) {
        throw new Error("project was not created");
      }

      expect(projects.findOwned(created.project.projectId, owner())).toMatchObject({
        name: "Harbor",
        revision: 1,
      });
      expect(projects.findOwned(created.project.projectId, owner("server-two"))).toBeUndefined();
      expect(
        projects.findOwned(created.project.projectId, owner("server-one", PLAYER_TWO)),
      ).toBeUndefined();
      expect(projects.listOwned(owner()).projects).toHaveLength(1);
      expect(projects.listOwned(owner("server-one", PLAYER_TWO)).projects).toEqual([]);
      expect(
        database.prepare("SELECT COUNT(*) AS count FROM project_events").get()?.["count"],
      ).toBe(1);
    } finally {
      database.close();
    }
  });

  it("uses an NFKC owner-local name key and optimistic revisions", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      const projects = repository(database);
      const created = projects.create({
        ...owner(),
        requestId: REQUEST_ONE,
        toolCallId: TOOL_ONE,
        timestamp: NOW,
        plan: plan("Harbor"),
      });
      if (created.project === null) {
        throw new Error("project was not created");
      }
      expect(
        projects.create({
          ...owner(),
          requestId: REQUEST_TWO,
          toolCallId: TOOL_TWO,
          timestamp: LATER,
          plan: plan("ＨＡＲＢＯＲ"),
        }),
      ).toEqual({ outcome: "NAME_CONFLICT", project: null });

      expect(
        projects.update({
          ...owner("server-one", PLAYER_TWO),
          requestId: REQUEST_TWO,
          toolCallId: TOOL_TWO,
          timestamp: LATER,
          projectId: created.project.projectId,
          expectedRevision: 1,
          plan: plan("Other"),
        }),
      ).toEqual({ outcome: "NOT_FOUND_OR_CHANGED", project: null });
      const updated = projects.update({
        ...owner(),
        requestId: REQUEST_TWO,
        toolCallId: TOOL_TWO,
        timestamp: LATER,
        projectId: created.project.projectId,
        expectedRevision: 1,
        plan: { ...plan(), summary: "Expanded harbor plan." },
      });
      expect(updated).toMatchObject({ outcome: "UPDATED", project: { revision: 2 } });
      expect(
        projects.update({
          ...owner(),
          requestId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
          toolCallId: "ffffffff-ffff-4fff-8fff-ffffffffffff",
          timestamp: LATER,
          projectId: created.project.projectId,
          expectedRevision: 1,
          plan: plan(),
        }),
      ).toEqual({ outcome: "NOT_FOUND_OR_CHANGED", project: null });
    } finally {
      database.close();
    }
  });

  it("rolls back the project update when its event cannot be committed", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      const projects = repository(database);
      const created = projects.create({
        ...owner(),
        requestId: REQUEST_ONE,
        toolCallId: TOOL_ONE,
        timestamp: NOW,
        plan: plan(),
      });
      if (created.project === null) {
        throw new Error("project was not created");
      }
      expect(() =>
        projects.update({
          ...owner(),
          requestId: REQUEST_TWO,
          toolCallId: TOOL_ONE,
          timestamp: LATER,
          projectId: created.project.projectId,
          expectedRevision: 1,
          plan: { ...plan(), summary: "This update must roll back." },
        }),
      ).toThrow();
      expect(projects.findOwned(created.project.projectId, owner())).toMatchObject({
        revision: 1,
        summary: "A compact trading harbor.",
      });
      expect(database.isTransaction).toBe(false);
      expect(
        database.prepare("SELECT COUNT(*) AS count FROM project_events").get()?.["count"],
      ).toBe(1);
    } finally {
      database.close();
    }
  });

  it("limits each owner's active projects before allocating more storage", () => {
    const database = new DatabaseSync(":memory:");
    try {
      migrateRuntimeStorage(database, NOW);
      const insert = database.prepare(`
        INSERT INTO projects
          (id, server_id, player_uuid, name, name_key, summary, goals_json, constraints_json,
           status, revision, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, '[]', '[]', 'ACTIVE', 1, ?, ?)
      `);
      for (let index = 0; index < 20; index += 1) {
        const suffix = String(index).padStart(12, "0");
        insert.run(
          `00000000-0000-4000-8000-${suffix}`,
          "server-one",
          PLAYER_ONE,
          `Project ${String(index)}`,
          `project ${String(index)}`,
          "Bounded project.",
          NOW,
          NOW,
        );
      }

      expect(
        repository(database).create({
          ...owner(),
          requestId: REQUEST_ONE,
          toolCallId: TOOL_ONE,
          timestamp: NOW,
          plan: plan("One too many"),
        }),
      ).toEqual({ outcome: "LIMIT_REACHED", project: null });
      expect(database.isTransaction).toBe(false);
      expect(database.prepare("SELECT COUNT(*) AS count FROM projects").get()?.["count"]).toBe(20);
    } finally {
      database.close();
    }
  });
});
