import { randomBytes } from "node:crypto";

import { DatabaseSync } from "node:sqlite";

import { RuntimeStartupError } from "../bootstrap/startup-error.js";
import { prepareSqliteFile } from "./filesystem.js";

export interface RuntimeSqliteHandle {
  readonly database: DatabaseSync;
  readonly close: () => void;
}

type SqliteCheckStep = "open" | "read" | "write" | "integrity";

function classifySqliteFailure(error: unknown, step: SqliteCheckStep): RuntimeStartupError {
  const message = error instanceof Error ? error.message.toLowerCase() : "";
  if (message.includes("busy") || message.includes("locked")) {
    return new RuntimeStartupError({
      code: "SQLITE_BUSY",
      stage: "sqlite",
      field: "/storage/sqlitePath",
      safeMessage: "Runtime SQLite database is busy.",
      cause: error,
    });
  }
  if (message.includes("malformed") || message.includes("not a database")) {
    return new RuntimeStartupError({
      code: "SQLITE_INTEGRITY_FAILED",
      stage: "sqlite",
      field: "/storage/sqlitePath",
      safeMessage: "Runtime SQLite database failed its integrity check.",
      cause: error,
    });
  }

  const code =
    step === "open"
      ? "SQLITE_OPEN_FAILED"
      : step === "read"
        ? "SQLITE_READ_FAILED"
        : step === "integrity"
          ? "SQLITE_INTEGRITY_FAILED"
          : "SQLITE_WRITE_FAILED";
  const messageByStep = {
    open: "Runtime SQLite database cannot be opened.",
    read: "Runtime SQLite database cannot be read.",
    write: "Runtime SQLite database is not writable.",
    integrity: "Runtime SQLite database failed its integrity check.",
  } as const;

  return new RuntimeStartupError({
    code,
    stage: "sqlite",
    field: "/storage/sqlitePath",
    safeMessage: messageByStep[step],
    cause: error,
  });
}

export async function checkRuntimeSqlite(
  rootDirectory: string,
  sqlitePath: string,
): Promise<RuntimeSqliteHandle> {
  await prepareSqliteFile(rootDirectory, sqlitePath);

  let database: DatabaseSync;
  try {
    database = new DatabaseSync(sqlitePath, {
      allowExtension: false,
      enableDoubleQuotedStringLiterals: false,
      enableForeignKeyConstraints: true,
      timeout: 1000,
    });
  } catch (error) {
    throw classifySqliteFailure(error, "open");
  }

  let step: SqliteCheckStep = "read";
  try {
    const readProbe = database.prepare("SELECT 1 AS value").get();
    if (readProbe?.["value"] !== 1) {
      throw new Error("SQLite read probe returned an unexpected value");
    }

    step = "integrity";
    const integrity = database.prepare("PRAGMA quick_check(1)").get();
    if (integrity?.["quick_check"] !== "ok") {
      throw new Error("SQLite quick_check did not return ok");
    }

    step = "write";
    const probeTable = `runtime_probe_${randomBytes(12).toString("hex")}`;
    database.exec("BEGIN IMMEDIATE");
    try {
      database.exec(`CREATE TABLE "${probeTable}" (value TEXT NOT NULL) STRICT`);
      database.prepare(`INSERT INTO "${probeTable}" (value) VALUES (?)`).run("ok");
      const writeProbe = database.prepare(`SELECT value FROM "${probeTable}"`).get();
      if (writeProbe?.["value"] !== "ok") {
        throw new Error("SQLite write probe returned an unexpected value");
      }
    } finally {
      if (database.isTransaction) {
        database.exec("ROLLBACK");
      }
    }
  } catch (error) {
    if (database.isTransaction) {
      try {
        database.exec("ROLLBACK");
      } catch {
        // Closing the connection below is the final cleanup fallback.
      }
    }
    try {
      database.close();
    } catch {
      // Preserve the stable readiness failure instead of exposing close details.
    }
    throw classifySqliteFailure(error, step);
  }

  let closed = false;
  return {
    database,
    close: () => {
      if (!closed) {
        database.close();
        closed = true;
      }
    },
  };
}
