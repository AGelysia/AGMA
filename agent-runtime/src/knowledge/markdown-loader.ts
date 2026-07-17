import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir, realpath } from "node:fs/promises";
import { basename, extname, relative, resolve, sep } from "node:path";

import { fromMarkdown } from "mdast-util-from-markdown";

import { RuntimeStartupError } from "../bootstrap/startup-error.js";
import type { RuntimeKnowledgeRootPath } from "../config/runtime-config.js";
import {
  MarkdownKnowledgeIndex,
  type KnowledgeChunk,
  type KnowledgeDocumentKind,
} from "./markdown-index.js";

const MAXIMUM_ROOTS = 8;
const MAXIMUM_DIRECTORY_DEPTH = 8;
const MAXIMUM_FILES = 256;
const MAXIMUM_FILE_BYTES = 64 * 1024;
const MAXIMUM_TOTAL_BYTES = 2 * 1024 * 1024;
const MAXIMUM_AST_NODES = 8192;
const MAXIMUM_CHUNKS = 2048;
const MAXIMUM_CHUNK_CHARACTERS = 2048;

interface MarkdownNode {
  readonly type: string;
  readonly value?: unknown;
  readonly alt?: unknown;
  readonly depth?: unknown;
  readonly children?: readonly MarkdownNode[];
}

interface LoaderBudget {
  files: number;
  bytes: number;
  chunks: number;
}

function failure(
  code: "KNOWLEDGE_DIRECTORY_UNAVAILABLE" | "KNOWLEDGE_CONTENT_INVALID",
  safeMessage: string,
  cause?: unknown,
): RuntimeStartupError {
  return new RuntimeStartupError({
    code,
    stage: "knowledge",
    field: "/knowledge/roots",
    safeMessage,
    ...(cause === undefined ? {} : { cause }),
  });
}

function unsafeMetadata(metadata: Awaited<ReturnType<typeof lstat>>): boolean {
  const currentUser = process.getuid?.();
  return (
    (currentUser !== undefined && metadata.uid !== currentUser) ||
    (process.platform !== "win32" && (Number(metadata.mode) & 0o022) !== 0)
  );
}

function assertVisibleSource(source: string): void {
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
      throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge contains unsafe text.");
    }
  }
}

function boundedText(value: string, maximum: number, fallback: string): string {
  const normalized = value.replaceAll(/\s+/gu, " ").trim();
  const characters = [...normalized];
  return (characters.length === 0 ? fallback : characters.slice(0, maximum).join("")).trim();
}

function plainText(node: MarkdownNode): string {
  if (node.type === "html") {
    return "";
  }
  if (node.type === "text" || node.type === "inlineCode" || node.type === "code") {
    return typeof node.value === "string" ? node.value : "";
  }
  if (node.type === "break") {
    return " ";
  }
  if (node.type === "image") {
    return typeof node.alt === "string" ? node.alt : "";
  }
  return (node.children ?? []).map(plainText).join(" ");
}

function assertAstBudget(root: MarkdownNode): void {
  const stack = [root];
  let nodes = 0;
  while (stack.length > 0) {
    const node = stack.pop();
    if (node === undefined || ++nodes > MAXIMUM_AST_NODES) {
      throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge Markdown is too complex.");
    }
    stack.push(...(node.children ?? []));
  }
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

function documentChunks(
  source: string,
  kind: KnowledgeDocumentKind,
  relativePath: string,
  rootIndex: number,
): readonly KnowledgeChunk[] {
  let root: MarkdownNode;
  try {
    root = fromMarkdown(source) as MarkdownNode;
  } catch (error) {
    throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge Markdown is invalid.", error);
  }
  assertAstBudget(root);

  const documentId = createHash("sha256")
    .update(`${kind}\0${String(rootIndex)}\0${relativePath}`, "utf8")
    .digest("hex");
  let title = boundedText(basename(relativePath, extname(relativePath)), 128, "Document");
  let heading = title;
  let chunkNumber = 0;
  const chunks: KnowledgeChunk[] = [];
  for (const node of root.children ?? []) {
    if (node.type === "heading") {
      const value = boundedText(plainText(node), 128, heading);
      heading = value;
      if (node.depth === 1) {
        title = value;
      }
      continue;
    }
    const text = boundedText(plainText(node), MAXIMUM_CHUNK_CHARACTERS * 16, "");
    if (text.length === 0) {
      continue;
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
  }
  return chunks;
}

async function readBoundedFile(path: string): Promise<string> {
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
      throw failure("KNOWLEDGE_CONTENT_INVALID", "A local knowledge file is unsafe.");
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
      throw failure("KNOWLEDGE_CONTENT_INVALID", "A local knowledge file changed while read.");
    }
    let source: string;
    try {
      source = new TextDecoder("utf-8", { fatal: true }).decode(buffer.subarray(0, offset));
    } catch (error) {
      throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge is not strict UTF-8.", error);
    }
    assertVisibleSource(source);
    return source;
  } catch (error) {
    if (error instanceof RuntimeStartupError) {
      throw error;
    }
    throw failure("KNOWLEDGE_CONTENT_INVALID", "A local knowledge file could not be read.", error);
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

async function collectMarkdownFiles(
  directory: string,
  depth: number,
  target: string[],
): Promise<void> {
  if (depth > MAXIMUM_DIRECTORY_DEPTH) {
    throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge nesting exceeds its limit.");
  }
  const metadata = await lstat(directory).catch((error: unknown) => {
    throw failure(
      "KNOWLEDGE_DIRECTORY_UNAVAILABLE",
      "A configured local knowledge directory is unavailable.",
      error,
    );
  });
  if (metadata.isSymbolicLink() || !metadata.isDirectory() || unsafeMetadata(metadata)) {
    throw failure("KNOWLEDGE_DIRECTORY_UNAVAILABLE", "A local knowledge directory is unsafe.");
  }
  const entries = await readdir(directory, { withFileTypes: true }).catch((error: unknown) => {
    throw failure(
      "KNOWLEDGE_DIRECTORY_UNAVAILABLE",
      "A local knowledge directory could not be read.",
      error,
    );
  });
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const candidate = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) {
      throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge must not contain links.");
    }
    if (entry.isDirectory()) {
      await collectMarkdownFiles(candidate, depth + 1, target);
    } else if (entry.isFile() && extname(entry.name).toLowerCase() === ".md") {
      target.push(candidate);
      if (target.length > MAXIMUM_FILES) {
        throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge contains too many files.");
      }
    }
  }
}

function containedRelative(root: string, path: string): string {
  const result = relative(root, path);
  if (result.length === 0 || result === ".." || result.startsWith(`..${sep}`)) {
    throw failure("KNOWLEDGE_CONTENT_INVALID", "A local knowledge path escaped its root.");
  }
  const normalized = result.split(sep).join("/");
  assertVisibleSource(normalized);
  return normalized;
}

export async function loadMarkdownKnowledge(
  roots: readonly RuntimeKnowledgeRootPath[],
): Promise<MarkdownKnowledgeIndex> {
  if (roots.length > MAXIMUM_ROOTS) {
    throw failure("KNOWLEDGE_CONTENT_INVALID", "Too many local knowledge roots were configured.");
  }
  const budget: LoaderBudget = { files: 0, bytes: 0, chunks: 0 };
  const chunks: KnowledgeChunk[] = [];
  for (const [rootIndex, root] of roots.entries()) {
    const resolvedRoot = await realpath(root.directory).catch((error: unknown) => {
      throw failure(
        "KNOWLEDGE_DIRECTORY_UNAVAILABLE",
        "A configured local knowledge directory is unavailable.",
        error,
      );
    });
    if (resolvedRoot !== root.directory) {
      throw failure("KNOWLEDGE_DIRECTORY_UNAVAILABLE", "A local knowledge root contains a link.");
    }
    const paths: string[] = [];
    await collectMarkdownFiles(resolvedRoot, 0, paths);
    if (budget.files + paths.length > MAXIMUM_FILES) {
      throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge contains too many files.");
    }
    for (const path of paths) {
      const source = await readBoundedFile(path);
      budget.files += 1;
      budget.bytes += Buffer.byteLength(source, "utf8");
      if (budget.files > MAXIMUM_FILES || budget.bytes > MAXIMUM_TOTAL_BYTES) {
        throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge exceeds its total size limit.");
      }
      const additions = documentChunks(
        source,
        root.kind,
        containedRelative(resolvedRoot, path),
        rootIndex,
      );
      budget.chunks += additions.length;
      if (budget.chunks > MAXIMUM_CHUNKS) {
        throw failure("KNOWLEDGE_CONTENT_INVALID", "Local knowledge contains too many chunks.");
      }
      chunks.push(...additions);
    }
  }
  return new MarkdownKnowledgeIndex(chunks);
}
