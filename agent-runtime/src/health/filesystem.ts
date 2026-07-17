import { randomBytes } from "node:crypto";
import { constants } from "node:fs";
import { chmod, lstat, mkdir, open, realpath, unlink } from "node:fs/promises";
import { dirname, relative, resolve, sep } from "node:path";

import {
  RuntimeStartupError,
  type RuntimeStartupErrorCode,
  type RuntimeStartupStage,
} from "../bootstrap/startup-error.js";

interface PrivateDirectoryOptions {
  readonly rootDirectory: string;
  readonly directory: string;
  readonly field: string;
  readonly stage: RuntimeStartupStage;
  readonly unavailableCode: RuntimeStartupErrorCode;
  readonly unavailableMessage: string;
}

function isErrno(error: unknown, code: string): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { readonly code?: unknown }).code === code
  );
}

function isContained(rootDirectory: string, candidate: string): boolean {
  const relativePath = relative(rootDirectory, candidate);
  return !(
    relativePath === ".." ||
    relativePath.startsWith(`..${sep}`) ||
    (relativePath.length === 0 && candidate !== rootDirectory)
  );
}

function assertPrivateMetadata(
  metadata: Awaited<ReturnType<typeof lstat>>,
  options: PrivateDirectoryOptions,
): void {
  const currentUser = process.getuid?.();
  const wrongOwner = currentUser !== undefined && metadata.uid !== currentUser;
  const permissionsWide = process.platform !== "win32" && (Number(metadata.mode) & 0o077) !== 0;

  if (wrongOwner || permissionsWide) {
    throw new RuntimeStartupError({
      code: "PATH_INSECURE_PERMISSIONS",
      stage: options.stage,
      field: options.field,
      safeMessage: "A Runtime state path has unsafe ownership or permissions.",
    });
  }
}

export async function ensurePrivateDirectory(options: PrivateDirectoryOptions): Promise<void> {
  const rootDirectory = await realpath(options.rootDirectory);
  const relativePath = relative(rootDirectory, options.directory);
  if (relativePath.length === 0) {
    throw new RuntimeStartupError({
      code: "PATH_INSECURE_PERMISSIONS",
      stage: options.stage,
      field: options.field,
      safeMessage: "Runtime state must use a private subdirectory.",
    });
  }
  if (
    relativePath === ".." ||
    relativePath.startsWith(`..${sep}`) ||
    resolve(rootDirectory, relativePath) !== options.directory
  ) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_ESCAPE",
      stage: options.stage,
      field: options.field,
      safeMessage: "A Runtime state path escapes the configuration directory.",
    });
  }

  let current = rootDirectory;
  for (const segment of relativePath.split(sep).filter((entry) => entry.length > 0)) {
    current = resolve(current, segment);
    let metadata;
    try {
      metadata = await lstat(current);
    } catch (error) {
      if (!isErrno(error, "ENOENT")) {
        throw new RuntimeStartupError({
          code: options.unavailableCode,
          stage: options.stage,
          field: options.field,
          safeMessage: options.unavailableMessage,
          cause: error,
        });
      }

      try {
        await mkdir(current, { mode: 0o700 });
        await chmod(current, 0o700);
        metadata = await lstat(current);
      } catch (createError) {
        throw new RuntimeStartupError({
          code: options.unavailableCode,
          stage: options.stage,
          field: options.field,
          safeMessage: options.unavailableMessage,
          cause: createError,
        });
      }
    }

    if (metadata.isSymbolicLink()) {
      throw new RuntimeStartupError({
        code: "CONFIG_PATH_SYMLINK",
        stage: options.stage,
        field: options.field,
        safeMessage: "Runtime state paths must not contain symbolic links.",
      });
    }
    if (!metadata.isDirectory()) {
      throw new RuntimeStartupError({
        code: options.unavailableCode,
        stage: options.stage,
        field: options.field,
        safeMessage: options.unavailableMessage,
      });
    }
    assertPrivateMetadata(metadata, options);

    let resolvedCurrent;
    try {
      resolvedCurrent = await realpath(current);
    } catch (error) {
      throw new RuntimeStartupError({
        code: options.unavailableCode,
        stage: options.stage,
        field: options.field,
        safeMessage: options.unavailableMessage,
        cause: error,
      });
    }
    if (!isContained(rootDirectory, resolvedCurrent)) {
      throw new RuntimeStartupError({
        code: "CONFIG_PATH_ESCAPE",
        stage: options.stage,
        field: options.field,
        safeMessage: "A Runtime state path escapes the configuration directory.",
      });
    }
  }
}

export async function checkLogDirectory(
  rootDirectory: string,
  logDirectory: string,
): Promise<void> {
  const options: PrivateDirectoryOptions = {
    rootDirectory,
    directory: logDirectory,
    field: "/logging/directory",
    stage: "logging",
    unavailableCode: "LOG_DIRECTORY_UNAVAILABLE",
    unavailableMessage: "Runtime log directory is not writable.",
  };
  await ensurePrivateDirectory(options);

  const probePath = resolve(
    logDirectory,
    `.runtime-write-probe-${randomBytes(12).toString("hex")}`,
  );
  let handle;
  let created = false;
  try {
    handle = await open(
      probePath,
      constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW,
      0o600,
    );
    created = true;
    await handle.writeFile("ok", { encoding: "utf8" });
    await handle.sync();
    await handle.chmod(0o600);
  } catch (error) {
    await handle?.close().catch(() => undefined);
    if (created) {
      await unlink(probePath).catch(() => undefined);
    }
    throw new RuntimeStartupError({
      code: "LOG_DIRECTORY_UNAVAILABLE",
      stage: "logging",
      field: "/logging/directory",
      safeMessage: "Runtime log directory is not writable.",
      cause: error,
    });
  }

  try {
    await handle.close();
    await unlink(probePath);
  } catch (error) {
    await unlink(probePath).catch(() => undefined);
    throw new RuntimeStartupError({
      code: "LOG_DIRECTORY_UNAVAILABLE",
      stage: "logging",
      field: "/logging/directory",
      safeMessage: "Runtime log directory is not writable.",
      cause: error,
    });
  }
}

export async function prepareSqliteFile(rootDirectory: string, sqlitePath: string): Promise<void> {
  const options: PrivateDirectoryOptions = {
    rootDirectory,
    directory: dirname(sqlitePath),
    field: "/storage/sqlitePath",
    stage: "sqlite",
    unavailableCode: "SQLITE_OPEN_FAILED",
    unavailableMessage: "Runtime SQLite database cannot be opened.",
  };
  await ensurePrivateDirectory(options);

  let metadata;
  try {
    metadata = await lstat(sqlitePath);
  } catch (error) {
    if (!isErrno(error, "ENOENT")) {
      throw new RuntimeStartupError({
        code: "SQLITE_OPEN_FAILED",
        stage: "sqlite",
        field: "/storage/sqlitePath",
        safeMessage: "Runtime SQLite database cannot be opened.",
        cause: error,
      });
    }

    let handle;
    try {
      handle = await open(
        sqlitePath,
        constants.O_RDWR | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW,
        0o600,
      );
      await handle.sync();
      await handle.chmod(0o600);
      metadata = await handle.stat();
    } catch (createError) {
      throw new RuntimeStartupError({
        code: "SQLITE_OPEN_FAILED",
        stage: "sqlite",
        field: "/storage/sqlitePath",
        safeMessage: "Runtime SQLite database cannot be opened.",
        cause: createError,
      });
    } finally {
      await handle?.close().catch(() => undefined);
    }
  }

  if (metadata.isSymbolicLink()) {
    throw new RuntimeStartupError({
      code: "CONFIG_PATH_SYMLINK",
      stage: "sqlite",
      field: "/storage/sqlitePath",
      safeMessage: "Runtime SQLite database must not be a symbolic link.",
    });
  }
  if (!metadata.isFile()) {
    throw new RuntimeStartupError({
      code: "SQLITE_OPEN_FAILED",
      stage: "sqlite",
      field: "/storage/sqlitePath",
      safeMessage: "Runtime SQLite path is not a regular file.",
    });
  }
  if (metadata.nlink !== 1) {
    throw new RuntimeStartupError({
      code: "SQLITE_OPEN_FAILED",
      stage: "sqlite",
      field: "/storage/sqlitePath",
      safeMessage: "Runtime SQLite database must not be a hard link.",
    });
  }
  assertPrivateMetadata(metadata, options);
}
