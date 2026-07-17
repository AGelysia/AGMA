import { DatabaseSync } from "node:sqlite";
import { chmod, mkdir, rm, stat, symlink, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { RuntimeStartupError } from "../src/bootstrap/startup-error.js";
import { checkLogDirectory } from "../src/health/filesystem.js";
import { checkRuntimeSqlite } from "../src/health/sqlite.js";
import {
  acquireRuntimeDatabaseLock,
  RuntimeDatabaseLockBusyError,
} from "../src/health/runtime-lock.js";
import { migrateRuntimeStorage } from "../src/storage/migrations.js";
import { temporaryRuntimeDirectory } from "./helpers/runtime-fixture.js";

const temporaryDirectories: string[] = [];

async function fixtureDirectory(): Promise<string> {
  const directory = await temporaryRuntimeDirectory();
  temporaryDirectories.push(directory);
  return directory;
}

async function expectCode(
  operation: Promise<unknown>,
  code: RuntimeStartupError["code"],
): Promise<void> {
  await expect(operation).rejects.toMatchObject({ code });
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("runtime filesystem readiness", () => {
  it("creates private log and SQLite state and rolls back the write probe", async () => {
    const directory = await fixtureDirectory();
    const logDirectory = join(directory, "logs");
    const sqlitePath = join(directory, "data/runtime.db");

    await checkLogDirectory(directory, logDirectory);
    const sqlite = await checkRuntimeSqlite(directory, sqlitePath);
    const probeTables = sqlite.database
      .prepare("SELECT name FROM sqlite_master WHERE name LIKE 'runtime_probe_%'")
      .all();

    expect(probeTables).toEqual([]);
    expect((await stat(logDirectory)).mode & 0o777).toBe(0o700);
    expect((await stat(join(directory, "data"))).mode & 0o777).toBe(0o700);
    expect((await stat(sqlitePath)).mode & 0o777).toBe(0o600);

    sqlite.close();
    const reopened = new DatabaseSync(sqlitePath);
    expect(reopened.prepare("SELECT 1 AS value").get()?.["value"]).toBe(1);
    reopened.close();
  });

  it("rejects a symbolic-link log directory", async () => {
    const directory = await fixtureDirectory();
    const outside = await fixtureDirectory();
    await symlink(outside, join(directory, "logs"));

    await expectCode(checkLogDirectory(directory, join(directory, "logs")), "CONFIG_PATH_SYMLINK");
  });

  it("rejects corrupt and read-only SQLite files", async () => {
    const corruptDirectory = await fixtureDirectory();
    const corruptData = join(corruptDirectory, "data");
    const corruptPath = join(corruptData, "runtime.db");
    await mkdir(corruptData, { mode: 0o700 });
    await writeFile(corruptPath, "not a sqlite database", { mode: 0o600 });
    await chmod(corruptPath, 0o600);
    await expectCode(checkRuntimeSqlite(corruptDirectory, corruptPath), "SQLITE_INTEGRITY_FAILED");

    const readOnlyDirectory = await fixtureDirectory();
    const readOnlyPath = join(readOnlyDirectory, "data/runtime.db");
    const sqlite = await checkRuntimeSqlite(readOnlyDirectory, readOnlyPath);
    sqlite.close();
    await chmod(readOnlyPath, 0o400);
    await expectCode(checkRuntimeSqlite(readOnlyDirectory, readOnlyPath), "SQLITE_WRITE_FAILED");
  });

  it("serializes Runtime ownership through SQLite and releases it cleanly", async () => {
    const directory = await fixtureDirectory();
    const sqlitePath = join(directory, "data/runtime.db");
    const first = await checkRuntimeSqlite(directory, sqlitePath);
    const second = await checkRuntimeSqlite(directory, sqlitePath);
    migrateRuntimeStorage(first.database, "2026-07-14T00:00:00.000Z");
    migrateRuntimeStorage(second.database, "2026-07-14T00:00:00.000Z");

    const firstLock = acquireRuntimeDatabaseLock(first.database, "2026-07-14T00:00:01.000Z");
    expect(() => acquireRuntimeDatabaseLock(second.database, "2026-07-14T00:00:02.000Z")).toThrow(
      RuntimeDatabaseLockBusyError,
    );
    firstLock.release();

    const secondLock = acquireRuntimeDatabaseLock(second.database, "2026-07-14T00:00:03.000Z");
    secondLock.release();
    first.close();
    second.close();
  });

  it("atomically replaces a dead process owner", async () => {
    const directory = await fixtureDirectory();
    const sqlitePath = join(directory, "data/runtime.db");
    const sqlite = await checkRuntimeSqlite(directory, sqlitePath);
    migrateRuntimeStorage(sqlite.database, "2026-07-14T00:00:00.000Z");
    sqlite.database
      .prepare(
        `INSERT INTO runtime_process_lock
           (lock_id, instance_id, pid, process_start_token, acquired_at)
         VALUES (1, ?, ?, NULL, ?)`,
      )
      .run("99999999-9999-4999-8999-999999999999", 2_147_483_647, "2026-07-14T00:00:01.000Z");

    const recovered = acquireRuntimeDatabaseLock(sqlite.database, "2026-07-14T00:00:02.000Z");
    expect(
      sqlite.database
        .prepare("SELECT instance_id FROM runtime_process_lock WHERE lock_id = 1")
        .get()?.["instance_id"],
    ).not.toBe("99999999-9999-4999-8999-999999999999");
    recovered.release();
    sqlite.close();
  });

  it("rejects state directories with broad permissions even under a permissive umask", async () => {
    const directory = await fixtureDirectory();
    const logDirectory = join(directory, "logs");
    await mkdir(logDirectory, { mode: 0o755 });
    await chmod(logDirectory, 0o755);

    await expectCode(checkLogDirectory(directory, logDirectory), "PATH_INSECURE_PERMISSIONS");
  });
});
