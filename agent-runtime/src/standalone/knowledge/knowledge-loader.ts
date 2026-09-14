import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir, realpath } from "node:fs/promises";
import { basename, extname, relative, resolve, sep } from "node:path";

import {
  StandaloneKnowledgeIndex,
  type StandaloneKnowledgeChunk,
  type StandaloneKnowledgeDocumentKind,
} from "./knowledge-index.js";

/**
 * Bounded loader for the client-local knowledge documents the standalone catalog services publish
 * under the Runtime root. Unlike the Paper-line loader it parses Markdown with a line-based
 * extractor (the standalone bundle cannot carry the server-line Markdown parser), and it never
 * aborts startup: unavailable or unsafe roots and files are skipped, so absent or empty roots
 * simply yield an empty index. The catalog writer can refresh documents concurrently, so a file
 * that changes mid-read is skipped rather than treated as fatal.
 */
export interface StandaloneKnowledgeRootPath {
  readonly directory: string;
  readonly kind: StandaloneKnowledgeDocumentKind;
}

const MAXIMUM_ROOTS = 8;
const MAXIMUM_DIRECTORY_DEPTH = 8;
const MAXIMUM_FILES = 256;
const MAXIMUM_FILE_BYTES = 64 * 1024;
const MAXIMUM_TOTAL_BYTES = 2 * 1024 * 1024;
const MAXIMUM_CHUNKS = 2048;
const MAXIMUM_CHUNK_CHARACTERS = 2048;

interface LoaderBudget {
  files: number;
  bytes: number;
  chunks: number;
}

function unsafeMetadata(metadata: Awaited<ReturnType<typeof lstat>>): boolean {
  const currentUser = process.getuid?.();
  return (
    (currentUser !== undefined && metadata.uid !== currentUser) ||
    (process.platform !== "win32" && (Number(metadata.mode) & 0o022) !== 0)
  );
}

function visibleSource(source: string): boolean {
  for (const character of source) {
    const codePoint = character.codePointAt(0);
    if (
      codePoint === undefined ||
      (codePoint >= 0xd800 && codePoint <= 0xdfff) ||
      ((codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) &&
        codePoint !== 0x09 &&
        codePoint !== 0x0a &&
        codePoint !== 0x0d) ||
      codePoint === 0x061c ||
      codePoint === 0x200e ||
      codePoint === 0x200f ||
      (codePoint >= 0x202a && codePoint <= 0x202e) ||
      (codePoint >= 0x2066 && codePoint <= 0x2069)
    ) {
      return false;
    }
  }
  return true;
}

function boundedText(value: string, maximum: number, fallback: string): string {
  const normalized = value.replaceAll(/\s+/gu, " ").trim();
  const characters = [...normalized];
  return (characters.length === 0 ? fallback : characters.slice(0, maximum).join("")).trim();
}

function inlineText(line: string): string {
  return line
    .replaceAll(/!\[([^\]]*)\]\([^)]*\)/gu, "$1")
    .replaceAll(/\[([^\]]*)\]\([^)]*\)/gu, "$1")
    .replaceAll(/`([^`]*)`/gu, "$1")
    .replaceAll(/\*\*([^*]*)\*\*/gu, "$1")
    .replaceAll(/\*([^*]*)\*/gu, "$1")
    .trim();
}

function splitText(value: string): readonly string[] {
  const characters = [...value];
  const parts: string[] = [];
  for (let offset = 0; offset < characters.length; offset += MAXIMUM_CHUNK_CHARACTERS) {
    const part = characters
      .slice(offset, offset + MAXIMUM_CHUNK_CHARACTERS)
      .join("")
      .trim();
    if (part.length > 0) {
      parts.push(part);
    }
  }
  return parts;
}

const HEADING = /^#{1,6}\s+(.+?)\s*#*\s*$/u;
const LEVEL_ONE = /^#\s/u;
const CODE_FENCE = /^(?:```|~~~)/u;
const THEMATIC_BREAK = /^(?:[-*_]\s*){3,}$/u;
const HTML_LINE = /^</u;

function documentChunks(
  source: string,
  kind: StandaloneKnowledgeDocumentKind,
  relativePath: string,
  rootIndex: number,
): readonly StandaloneKnowledgeChunk[] {
  const documentId = createHash("sha256")
    .update(`${kind}\0${String(rootIndex)}\0${relativePath}`, "utf8")
    .digest("hex");
  let title = boundedText(basename(relativePath, extname(relativePath)), 128, "Document");
  let heading = title;
  let chunkNumber = 0;
  const chunks: StandaloneKnowledgeChunk[] = [];
  let block: string[] = [];
  let inCodeFence = false;

  const flush = (): void => {
    const text = boundedText(block.join(" "), MAXIMUM_CHUNK_CHARACTERS * 16, "");
    block = [];
    if (text.length === 0) {
      return;
    }
    for (const part of splitText(text)) {
      chunkNumber += 1;
      chunks.push({
        documentId,
        citation: `${kind}/${documentId.slice(0, 12)}/${relativePath}#chunk-${String(chunkNumber)}`,
        kind,
        title,
        heading,
        text: part,
      });
    }
  };

  for (const line of source.split("\n")) {
    const trimmed = line.trim();
    if (CODE_FENCE.test(trimmed)) {
      inCodeFence = !inCodeFence;
      continue;
    }
    if (inCodeFence) {
      continue;
    }
    if (trimmed.length === 0) {
      flush();
      continue;
    }
    if (!inCodeFence && HEADING.test(trimmed)) {
      flush();
      const value = boundedText(inlineText(trimmed.replace(HEADING, "$1")), 128, heading);
      heading = value;
      if (LEVEL_ONE.test(trimmed)) {
        title = value;
      }
      continue;
    }
    if (THEMATIC_BREAK.test(trimmed) || HTML_LINE.test(trimmed)) {
      continue;
    }
    const text = inlineText(trimmed);
    if (text.length > 0) {
      block.push(text);
    }
  }
  flush();
  return chunks;
}

async function readBoundedFile(path: string): Promise<string | undefined> {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const before = await handle.stat();
    if (
      !before.isFile() ||
      before.nlink !== 1 ||
      before.size > MAXIMUM_FILE_BYTES ||
      unsafeMetadata(before)
    ) {
      return undefined;
    }
    const buffer = Buffer.alloc(MAXIMUM_FILE_BYTES + 1);
    let offset = 0;
    while (offset < buffer.length) {
      const read = await handle.read(buffer, offset, buffer.length - offset, offset);
      if (read.bytesRead === 0) {
        break;
      }
      offset += read.bytesRead;
    }
    const after = await handle.stat();
    if (
      offset > MAXIMUM_FILE_BYTES ||
      before.dev !== after.dev ||
      before.ino !== after.ino ||
      before.size !== after.size ||
      before.mtimeMs !== after.mtimeMs
    ) {
      return undefined;
    }
    let source: string;
    try {
      source = new TextDecoder("utf-8", { fatal: true }).decode(buffer.subarray(0, offset));
    } catch {
      return undefined;
    }
    return visibleSource(source) ? source : undefined;
  } catch {
    return undefined;
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

async function collectMarkdownFiles(
  directory: string,
  depth: number,
  target: string[],
): Promise<void> {
  if (depth > MAXIMUM_DIRECTORY_DEPTH || target.length > MAXIMUM_FILES) {
    return;
  }
  const metadata = await lstat(directory).catch(() => undefined);
  if (metadata === undefined || metadata.isSymbolicLink() || !metadata.isDirectory()) {
    return;
  }
  if (unsafeMetadata(metadata)) {
    return;
  }
  const entries = await readdir(directory, { withFileTypes: true }).catch(() => undefined);
  if (entries === undefined) {
    return;
  }
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const candidate = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) {
      continue;
    }
    if (entry.isDirectory()) {
      await collectMarkdownFiles(candidate, depth + 1, target);
    } else if (entry.isFile() && extname(entry.name).toLowerCase() === ".md") {
      target.push(candidate);
      if (target.length > MAXIMUM_FILES) {
        return;
      }
    }
  }
}

function containedRelative(root: string, path: string): string | undefined {
  const result = relative(root, path);
  if (result.length === 0 || result === ".." || result.startsWith(`..${sep}`)) {
    return undefined;
  }
  const normalized = result.split(sep).join("/");
  return visibleSource(normalized) ? normalized : undefined;
}

export async function loadStandaloneKnowledge(
  roots: readonly StandaloneKnowledgeRootPath[],
): Promise<StandaloneKnowledgeIndex> {
  if (roots.length > MAXIMUM_ROOTS) {
    throw new TypeError("Too many local knowledge roots were configured.");
  }
  const budget: LoaderBudget = { files: 0, bytes: 0, chunks: 0 };
  const chunks: StandaloneKnowledgeChunk[] = [];
  for (const [rootIndex, root] of roots.entries()) {
    if (budget.files >= MAXIMUM_FILES || budget.bytes >= MAXIMUM_TOTAL_BYTES) {
      break;
    }
    const resolvedRoot = await realpath(root.directory).catch(() => undefined);
    if (resolvedRoot === undefined || resolvedRoot !== root.directory) {
      continue;
    }
    const paths: string[] = [];
    await collectMarkdownFiles(resolvedRoot, 0, paths);
    for (const path of paths) {
      if (budget.files >= MAXIMUM_FILES || budget.bytes >= MAXIMUM_TOTAL_BYTES) {
        break;
      }
      const source = await readBoundedFile(path);
      const relativePath = source === undefined ? undefined : containedRelative(resolvedRoot, path);
      if (source === undefined || relativePath === undefined) {
        continue;
      }
      budget.files += 1;
      budget.bytes += Buffer.byteLength(source, "utf8");
      if (budget.chunks >= MAXIMUM_CHUNKS) {
        break;
      }
      const additions = documentChunks(source, root.kind, relativePath, rootIndex);
      const accepted = additions.slice(0, MAXIMUM_CHUNKS - budget.chunks);
      budget.chunks += accepted.length;
      chunks.push(...accepted);
    }
  }
  return new StandaloneKnowledgeIndex(chunks);
}
