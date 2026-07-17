export type KnowledgeDocumentKind = "server_rules" | "local_docs";

export interface KnowledgeChunk {
  readonly documentId: string;
  readonly citation: string;
  readonly kind: KnowledgeDocumentKind;
  readonly title: string;
  readonly heading: string;
  readonly text: string;
}

export interface KnowledgeSearchMatch {
  readonly documentId: string;
  readonly citation: string;
  readonly kind: KnowledgeDocumentKind;
  readonly title: string;
  readonly heading: string;
  readonly excerpt: string;
}

export interface KnowledgeSearchResult {
  readonly query: string;
  readonly matches: readonly KnowledgeSearchMatch[];
  readonly truncated: boolean;
}

const MAXIMUM_QUERY_CHARACTERS = 256;
const MAXIMUM_QUERY_TOKENS = 32;
const MAXIMUM_MATCHES = 8;
const MAXIMUM_EXCERPT_CHARACTERS = 1024;

function safeText(value: string, maximum: number, field: string): string {
  const trimmed = value.trim();
  let count = 0;
  for (const character of trimmed) {
    count += 1;
    const codePoint = character.codePointAt(0);
    if (
      codePoint === undefined ||
      (codePoint >= 0xd800 && codePoint <= 0xdfff) ||
      codePoint <= 0x1f ||
      (codePoint >= 0x7f && codePoint <= 0x9f) ||
      codePoint === 0x061c ||
      codePoint === 0x200e ||
      codePoint === 0x200f ||
      (codePoint >= 0x202a && codePoint <= 0x202e) ||
      (codePoint >= 0x2066 && codePoint <= 0x2069)
    ) {
      throw new TypeError(`${field} contains unsafe text.`);
    }
  }
  if (count < 1 || count > maximum) {
    throw new TypeError(`${field} length is invalid.`);
  }
  return trimmed;
}

function normalized(value: string): string {
  return value.normalize("NFKC").toLowerCase();
}

function queryTokens(query: string): readonly string[] {
  const tokens = normalized(query).match(/[\p{L}\p{N}_-]+/gu) ?? [];
  const distinct = [...new Set(tokens)];
  if (distinct.length === 0) {
    return [normalized(query)];
  }
  if (distinct.length > MAXIMUM_QUERY_TOKENS) {
    throw new TypeError("Knowledge query contains too many terms.");
  }
  return distinct;
}

function occurrences(source: string, token: string): number {
  if (token.length === 0) {
    return 0;
  }
  let count = 0;
  let offset = source.indexOf(token);
  while (offset >= 0 && count < 64) {
    count += 1;
    offset = source.indexOf(token, offset + token.length);
  }
  return count;
}

function excerpt(text: string): string {
  return [...text].slice(0, MAXIMUM_EXCERPT_CHARACTERS).join("").trimEnd();
}

export class MarkdownKnowledgeIndex {
  readonly #chunks: readonly KnowledgeChunk[];

  public constructor(chunks: readonly KnowledgeChunk[] = []) {
    this.#chunks = Object.freeze([...chunks]);
  }

  public get size(): number {
    return this.#chunks.length;
  }

  public search(rawQuery: string): KnowledgeSearchResult {
    const query = safeText(rawQuery, MAXIMUM_QUERY_CHARACTERS, "query");
    const tokens = queryTokens(query);
    const matches = this.#chunks
      .map((chunk) => {
        const normalizedTitle = normalized(chunk.title);
        const normalizedHeading = normalized(chunk.heading);
        const normalizedText = normalized(chunk.text);
        const searchable = `${normalizedTitle}\n${normalizedHeading}\n${normalizedText}`;
        if (!tokens.every((token) => searchable.includes(token))) {
          return undefined;
        }
        const score = tokens.reduce(
          (total, token) =>
            total +
            occurrences(normalizedText, token) +
            occurrences(normalizedHeading, token) * 4 +
            occurrences(normalizedTitle, token) * 8,
          0,
        );
        return { chunk, score };
      })
      .filter((match): match is { readonly chunk: KnowledgeChunk; readonly score: number } =>
        Boolean(match),
      )
      .sort((left, right) => {
        const kind =
          Number(left.chunk.kind !== "server_rules") - Number(right.chunk.kind !== "server_rules");
        if (kind !== 0) {
          return kind;
        }
        return right.score - left.score || left.chunk.citation.localeCompare(right.chunk.citation);
      });

    return {
      query,
      matches: matches.slice(0, MAXIMUM_MATCHES).map(({ chunk }) => ({
        documentId: chunk.documentId,
        citation: chunk.citation,
        kind: chunk.kind,
        title: chunk.title,
        heading: chunk.heading,
        excerpt: excerpt(chunk.text),
      })),
      truncated: matches.length > MAXIMUM_MATCHES,
    };
  }
}
