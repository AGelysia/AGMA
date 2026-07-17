import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

import type { DatabaseSync } from "node:sqlite";

interface LockOwner {
  readonly instanceId: string;
  readonly pid: number;
  readonly processStartToken?: string;
}

export interface RuntimeDatabaseLock {
  readonly release: () => void;
}

export class RuntimeDatabaseLockBusyError extends Error {
  public constructor() {
    super("Runtime SQLite database is busy in another Runtime process.");
    this.name = "RuntimeDatabaseLockBusyError";
  }
}

function processStartToken(pid: number): string | undefined {
  try {
    const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
    const commandEnd = stat.lastIndexOf(") ");
    if (commandEnd < 0) {
      return undefined;
    }
    return stat
      .slice(commandEnd + 2)
      .trim()
      .split(/\s+/u)[19];
  } catch {
    return undefined;
  }
}

function processIsOwner(owner: LockOwner): boolean {
  try {
    process.kill(owner.pid, 0);
  } catch (error) {
    return (error as NodeJS.ErrnoException).code !== "ESRCH";
  }
  if (owner.processStartToken === undefined) {
    return true;
  }
  const currentStartToken = processStartToken(owner.pid);
  return currentStartToken === undefined || currentStartToken === owner.processStartToken;
}

function storedOwner(row: Record<string, unknown>): LockOwner {
  const instanceId = row["instance_id"];
  const pid = Number(row["pid"]);
  const startToken = row["process_start_token"];
  if (
    typeof instanceId !== "string" ||
    !Number.isSafeInteger(pid) ||
    pid < 1 ||
    (startToken !== null && typeof startToken !== "string")
  ) {
    throw new Error("Runtime process lock storage is invalid.");
  }
  return {
    instanceId,
    pid,
    ...(startToken === null ? {} : { processStartToken: startToken }),
  };
}

export function acquireRuntimeDatabaseLock(
  database: DatabaseSync,
  acquiredAt: string,
): RuntimeDatabaseLock {
  if (!Number.isFinite(Date.parse(acquiredAt))) {
    throw new TypeError("Runtime process lock timestamp is invalid.");
  }
  const instanceId = randomUUID();
  const startToken = processStartToken(process.pid);

  database.exec("BEGIN IMMEDIATE");
  try {
    const existing = database
      .prepare(
        `SELECT instance_id, pid, process_start_token
         FROM runtime_process_lock WHERE lock_id = 1`,
      )
      .get();
    if (existing !== undefined && processIsOwner(storedOwner(existing))) {
      throw new RuntimeDatabaseLockBusyError();
    }
    database
      .prepare(
        `INSERT INTO runtime_process_lock
           (lock_id, instance_id, pid, process_start_token, acquired_at)
         VALUES (1, ?, ?, ?, ?)
         ON CONFLICT(lock_id) DO UPDATE SET
           instance_id = excluded.instance_id,
           pid = excluded.pid,
           process_start_token = excluded.process_start_token,
           acquired_at = excluded.acquired_at`,
      )
      .run(instanceId, process.pid, startToken ?? null, acquiredAt);
    database.exec("COMMIT");
  } catch (error) {
    if (database.isTransaction) {
      database.exec("ROLLBACK");
    }
    throw error;
  }

  let released = false;
  return {
    release: () => {
      if (released) {
        return;
      }
      database.exec("BEGIN IMMEDIATE");
      try {
        const deleted = database
          .prepare("DELETE FROM runtime_process_lock WHERE lock_id = 1 AND instance_id = ?")
          .run(instanceId);
        if (deleted.changes !== 1) {
          throw new Error("Runtime process lock ownership was lost.");
        }
        database.exec("COMMIT");
        released = true;
      } catch (error) {
        if (database.isTransaction) {
          database.exec("ROLLBACK");
        }
        throw error;
      }
    },
  };
}
