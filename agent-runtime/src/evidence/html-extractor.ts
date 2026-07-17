import { parse } from "parse5";

const MAXIMUM_SPANS = 64;
const MAXIMUM_SPAN_CHARACTERS = 2048;
const MAXIMUM_TOTAL_CHARACTERS = 32 * 1024;
const BLOCK_ELEMENTS = new Set([
  "p",
  "li",
  "blockquote",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "td",
  "th",
]);
const EXCLUDED_ELEMENTS = new Set([
  "script",
  "style",
  "noscript",
  "template",
  "svg",
  "canvas",
  "form",
  "input",
  "button",
  "select",
  "textarea",
]);

export interface ExtractedWebDocument {
  readonly title: string;
  readonly publisher: string;
  readonly publishedAt: string | null;
  readonly spans: readonly string[];
}

interface HtmlNode extends Readonly<Record<string, unknown>> {
  readonly nodeName?: string;
  readonly tagName?: string;
  readonly value?: string;
  readonly attrs?: readonly { readonly name: string; readonly value: string }[];
  readonly childNodes?: readonly unknown[];
}

function isNode(value: unknown): value is HtmlNode {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function cleanText(value: string): string {
  return value
    .normalize("NFKC")
    .replace(/\p{Cc}/gu, " ")
    .replace(/\s+/gu, " ")
    .trim();
}

function attribute(node: HtmlNode, name: string): string | undefined {
  return node.attrs?.find((candidate) => candidate.name.toLowerCase() === name)?.value;
}

function hidden(node: HtmlNode): boolean {
  if (attribute(node, "hidden") !== undefined || attribute(node, "inert") !== undefined) {
    return true;
  }
  if (attribute(node, "aria-hidden")?.trim().toLowerCase() === "true") return true;
  if (node.tagName?.toLowerCase() === "input" && attribute(node, "type") === "hidden") return true;
  const classes = (attribute(node, "class") ?? "").toLowerCase().split(/\s+/gu);
  if (
    classes.some((name) =>
      /^(?:hidden|secret|sr-only|visually-hidden|d-none|is-hidden|u-hidden)$/u.test(name),
    )
  ) {
    return true;
  }
  const style = attribute(node, "style")?.toLowerCase().replace(/\s+/gu, "") ?? "";
  return (
    /(?:^|;)display:none(?:!important)?(?:;|$)/u.test(style) ||
    /(?:^|;)visibility:hidden(?:!important)?(?:;|$)/u.test(style) ||
    /(?:^|;)content-visibility:hidden(?:!important)?(?:;|$)/u.test(style)
  );
}

function nodeText(node: HtmlNode): string {
  if (node.nodeName === "#text") return typeof node.value === "string" ? node.value : "";
  if (hidden(node) || (node.tagName !== undefined && EXCLUDED_ELEMENTS.has(node.tagName)))
    return "";
  return (node.childNodes ?? [])
    .flatMap((child) => (isNode(child) ? [nodeText(child)] : []))
    .join(" ");
}

function validTimestamp(value: string | undefined): string | null {
  if (value === undefined || value.length > 128) return null;
  const milliseconds = Date.parse(value);
  return Number.isFinite(milliseconds) ? new Date(milliseconds).toISOString() : null;
}

function boundedSpans(values: readonly string[]): readonly string[] {
  const spans: string[] = [];
  const seen = new Set<string>();
  let total = 0;
  for (const value of values) {
    const cleaned = cleanText(value);
    if (cleaned.length < 20 || cleaned.length > MAXIMUM_SPAN_CHARACTERS || seen.has(cleaned)) {
      continue;
    }
    if (total + cleaned.length > MAXIMUM_TOTAL_CHARACTERS) break;
    seen.add(cleaned);
    spans.push(cleaned);
    total += cleaned.length;
    if (spans.length === MAXIMUM_SPANS) break;
  }
  return spans;
}

export function extractHtmlDocument(html: string, sourceUrl: string): ExtractedWebDocument {
  const document = parse(html) as unknown;
  if (!isNode(document)) throw new TypeError("The parsed HTML document is invalid.");
  let title: string | undefined;
  let publisher: string | undefined;
  let publishedAt: string | null = null;
  const spanCandidates: string[] = [];

  const visit = (node: HtmlNode): void => {
    const tagName = node.tagName?.toLowerCase();
    if (hidden(node) || (tagName !== undefined && EXCLUDED_ELEMENTS.has(tagName))) return;
    if (tagName === "title" && title === undefined) title = cleanText(nodeText(node));
    if (tagName === "meta") {
      const key = (attribute(node, "property") ?? attribute(node, "name"))?.toLowerCase();
      const content = attribute(node, "content");
      if ((key === "og:title" || key === "twitter:title") && title === undefined) {
        title = content === undefined ? undefined : cleanText(content);
      }
      if (key === "og:site_name" || key === "application-name" || key === "author") {
        publisher ??= content === undefined ? undefined : cleanText(content);
      }
      if (key === "article:published_time" || key === "date" || key === "datepublished") {
        publishedAt ??= validTimestamp(content);
      }
    }
    if (tagName === "time" && publishedAt === null) {
      publishedAt = validTimestamp(attribute(node, "datetime"));
    }
    if (tagName !== undefined && BLOCK_ELEMENTS.has(tagName)) {
      spanCandidates.push(nodeText(node));
      return;
    }
    for (const child of node.childNodes ?? []) if (isNode(child)) visit(child);
  };
  visit(document);

  const hostname = new URL(sourceUrl).hostname;
  return {
    title: title === undefined || title.length === 0 ? hostname : title.slice(0, 512),
    publisher:
      publisher === undefined || publisher.length === 0 ? hostname : publisher.slice(0, 256),
    publishedAt,
    spans: boundedSpans(spanCandidates),
  };
}

export function extractPlainTextDocument(text: string, sourceUrl: string): ExtractedWebDocument {
  const hostname = new URL(sourceUrl).hostname;
  return {
    title: hostname,
    publisher: hostname,
    publishedAt: null,
    spans: boundedSpans(text.split(/(?:\r?\n){1,}|(?<=[.!?])\s+/u)),
  };
}
