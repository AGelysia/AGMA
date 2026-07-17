import { realpathSync } from "node:fs";
import { fileURLToPath } from "node:url";

type RealPathResolver = (path: string) => string;

export function isMainModule(
  entryPath: string | undefined,
  moduleUrl: string,
  resolveRealPath: RealPathResolver = realpathSync,
): boolean {
  if (entryPath === undefined) {
    return false;
  }
  try {
    return resolveRealPath(entryPath) === resolveRealPath(fileURLToPath(moduleUrl));
  } catch {
    return false;
  }
}
