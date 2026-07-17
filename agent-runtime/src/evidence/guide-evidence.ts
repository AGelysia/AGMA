import { containsSensitiveExactClaim, type EvidenceClaim } from "./evidence-normalizer.js";

export interface GuideClaimBinding {
  readonly statement: string;
  readonly claimIds: readonly string[];
}

export interface GuideUnknown {
  readonly disposition: "unverified" | "client_not_visible" | "unsupported";
  readonly detail: string;
}

export interface CompiledGuideEvidence {
  readonly bossAndDrops: readonly GuideClaimBinding[];
  readonly preparation: readonly GuideClaimBinding[];
  readonly evidence: readonly EvidenceClaim[];
  readonly unknowns: readonly GuideUnknown[];
}

export interface ControlledGuideSource {
  readonly claimId: string;
  readonly title: string;
  readonly url: string;
  readonly publisher: string;
  readonly retrievedAt: string;
  readonly applicability: EvidenceClaim["applicability"];
  readonly warnings: readonly string[];
}

export interface CompiledGuideTextEvidence {
  readonly text: string;
  readonly sources: readonly ControlledGuideSource[];
  readonly unknowns: readonly GuideUnknown[];
}

export interface CompileGuideTextEvidenceOptions {
  readonly maximumTextLength?: number;
}

export interface GuideEvidenceDraft {
  readonly bossAndDrops: readonly GuideClaimBinding[];
  readonly preparation: readonly GuideClaimBinding[];
  readonly evidence: readonly EvidenceClaim[];
}

type BindingFailure = "missing" | "applicability" | "conflict" | "text_mismatch" | "exact_mismatch";

const SUPPORT_STOP_WORDS = new Set([
  "about",
  "after",
  "before",
  "could",
  "does",
  "from",
  "guide",
  "into",
  "minecraft",
  "might",
  "should",
  "source",
  "that",
  "their",
  "there",
  "these",
  "this",
  "version",
  "where",
  "which",
  "with",
  "would",
]);

function normalizedText(value: string): string {
  return value
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .replace(/\s+/gu, " ")
    .trim();
}

function supportTerms(value: string): ReadonlySet<string> {
  const normalized = normalizedText(value);
  const terms = new Set(
    normalized
      .split(" ")
      .filter((term) => term.length >= 3 && !SUPPORT_STOP_WORDS.has(term) && !/^\d+$/u.test(term)),
  );
  for (const run of normalized.match(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]+/gu) ??
    []) {
    const characters = [...run];
    for (let index = 0; index + 1 < characters.length; index += 1) {
      terms.add(`${characters[index]}${characters[index + 1]}`);
    }
  }
  return terms;
}

function hasSubstantiveTextSupport(statement: string, claims: readonly EvidenceClaim[]): boolean {
  const normalizedStatement = normalizedText(statement);
  if (normalizedStatement.length === 0) return false;
  const statementTerms = supportTerms(statement);
  return claims.some((claim) => {
    const normalizedClaim = normalizedText(claim.statement);
    if (
      normalizedClaim.length > 0 &&
      (normalizedStatement.includes(normalizedClaim) ||
        normalizedClaim.includes(normalizedStatement))
    ) {
      return true;
    }
    const claimTerms = supportTerms(claim.statement);
    const shared = [...statementTerms].filter((term) => claimTerms.has(term)).length;
    const minimum = Math.min(statementTerms.size, claimTerms.size) <= 1 ? 1 : 2;
    return shared >= minimum;
  });
}

function sensitiveFacts(value: string): readonly string[] {
  const facts = [
    ...value.matchAll(/\b[0-9]+(?:\.[0-9]+)?\s*%/gu),
    ...value.matchAll(/\b(?:x|y|z)\s*[=:]\s*-?[0-9]+\b/giu),
    ...value.matchAll(/\b(?:minecraft\s+)?version\s+[0-9]+\.[0-9]+(?:\.[0-9]+)?\b/giu),
  ];
  return facts.map((match) => match[0].normalize("NFKC").toLowerCase().replace(/\s+/gu, ""));
}

function bindingFailure(
  binding: GuideClaimBinding,
  claimsById: ReadonlyMap<string, EvidenceClaim>,
): BindingFailure | undefined {
  if (binding.claimIds.length < 1 || new Set(binding.claimIds).size !== binding.claimIds.length) {
    return "missing";
  }
  const claims = binding.claimIds.flatMap((claimId) => {
    const claim = claimsById.get(claimId);
    return claim === undefined ? [] : [claim];
  });
  if (claims.length !== binding.claimIds.length) return "missing";
  if (claims.some((claim) => claim.conflicts.length > 0)) return "conflict";
  if (claims.some((claim) => claim.applicability.match !== "match")) return "applicability";
  if (!hasSubstantiveTextSupport(binding.statement, claims)) return "text_mismatch";
  if (containsSensitiveExactClaim(binding.statement)) {
    const facts = sensitiveFacts(binding.statement);
    const claimStatements = claims.map((claim) =>
      claim.statement.normalize("NFKC").toLowerCase().replace(/\s+/gu, ""),
    );
    if (!facts.every((fact) => claimStatements.some((statement) => statement.includes(fact)))) {
      return "exact_mismatch";
    }
  }
  return undefined;
}

function failureDetail(failure: BindingFailure): string {
  switch (failure) {
    case "missing":
      return "A factual statement was omitted because it did not cite a claim from this evidence request.";
    case "applicability":
      return "A cited statement was omitted because its Minecraft or mod version applicability is unknown or mismatched.";
    case "conflict":
      return "A cited statement was omitted because the current evidence contains a conflicting claim.";
    case "text_mismatch":
      return "A cited statement was omitted because the claim did not substantively support it.";
    case "exact_mismatch":
      return "An exact rate, coordinate, or version statement was omitted because the cited claim did not contain the same exact fact.";
  }
}

export function compileGuideEvidence(draft: GuideEvidenceDraft): CompiledGuideEvidence {
  const claimsById = new Map(draft.evidence.map((claim) => [claim.claimId, claim]));
  if (claimsById.size !== draft.evidence.length) {
    throw new TypeError("Guide evidence contains duplicate claim identities.");
  }
  const unknowns: GuideUnknown[] = [];
  const filterBindings = (bindings: readonly GuideClaimBinding[]): GuideClaimBinding[] =>
    bindings.flatMap((binding) => {
      const failure = bindingFailure(binding, claimsById);
      if (failure === undefined) return [binding];
      unknowns.push({
        disposition: "unverified",
        detail: failureDetail(failure),
      });
      return [];
    });
  const bossAndDrops = filterBindings(draft.bossAndDrops);
  const preparation = filterBindings(draft.preparation);
  const referenced = new Set(
    [...bossAndDrops, ...preparation].flatMap((binding) => [...binding.claimIds]),
  );
  return {
    bossAndDrops,
    preparation,
    evidence: draft.evidence.filter((claim) => referenced.has(claim.claimId)),
    unknowns,
  };
}

const CLAIM_CITATION = /\[(claim\.[0-9a-f]{24})\]/gu;
const MAXIMUM_CONTROLLED_SOURCES = 5;
const MAXIMUM_GUIDE_TEXT_LENGTH = 8192;

function safeMetadata(value: string, maximumLength: number): string {
  return value
    .normalize("NFKC")
    .replace(/[\p{Cc}\p{Cf}]+/gu, " ")
    .replace(/\s+/gu, " ")
    .trim()
    .slice(0, maximumLength);
}

function controlledSources(
  claims: readonly EvidenceClaim[],
  referenced: ReadonlySet<string>,
): ControlledGuideSource[] {
  return claims.flatMap((claim) =>
    referenced.has(claim.claimId)
      ? [
          {
            claimId: claim.claimId,
            title: safeMetadata(claim.sourceTitle, 256),
            url: claim.sourceUrl,
            publisher: safeMetadata(claim.publisher, 256),
            retrievedAt: claim.retrievedAt,
            applicability: claim.applicability,
            warnings: claim.warnings.slice(0, 4).map((warning) => safeMetadata(warning, 256)),
          },
        ]
      : [],
  );
}

function renderControlledGuide(
  bindings: readonly GuideClaimBinding[],
  sources: readonly ControlledGuideSource[],
  unknowns: readonly GuideUnknown[],
): string {
  const lines = bindings.map(
    (binding) => `${binding.statement} ${binding.claimIds.map((id) => `[${id}]`).join(" ")}`,
  );
  const uniqueUnknowns = [...new Set(unknowns.map((unknown) => unknown.detail))].slice(0, 8);
  if (uniqueUnknowns.length > 0 || lines.length === 0) {
    lines.push(
      "Unknown:",
      ...(uniqueUnknowns.length > 0
        ? uniqueUnknowns.map((detail) => `- ${detail}`)
        : ["- No web-derived statement had a valid current evidence binding."]),
    );
  }
  if (sources.length > 0) {
    lines.push("Sources:");
    for (const source of sources) {
      const modVersions = Object.entries(source.applicability.modVersions)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([modId, version]) => `${modId}=${version}`)
        .join(", ");
      lines.push(
        `- [${source.claimId}] ${source.title} | ${source.publisher} | ${source.url}`,
        `  Retrieved: ${source.retrievedAt}; applicability: Minecraft ${source.applicability.minecraftVersion} (${source.applicability.match})${modVersions.length === 0 ? "" : `; mods ${modVersions}`}${source.applicability.modpackVersion === null ? "" : `; modpack ${source.applicability.modpackVersion}`}`,
      );
      if (source.warnings.length > 0) {
        lines.push(`  Warnings: ${source.warnings.join("; ")}`);
      }
    }
  }
  return lines.join("\n");
}

export function compileGuideTextEvidence(
  modelText: string,
  currentEvidence: readonly EvidenceClaim[],
  options: CompileGuideTextEvidenceOptions = {},
): CompiledGuideTextEvidence {
  const maximumTextLength = options.maximumTextLength ?? MAXIMUM_GUIDE_TEXT_LENGTH;
  if (
    !Number.isSafeInteger(maximumTextLength) ||
    maximumTextLength < 256 ||
    maximumTextLength > MAXIMUM_GUIDE_TEXT_LENGTH
  ) {
    throw new TypeError("The controlled guide text limit is invalid.");
  }
  const claimsById = new Map(currentEvidence.map((claim) => [claim.claimId, claim]));
  if (claimsById.size !== currentEvidence.length) {
    throw new TypeError("Current web evidence contains duplicate claim identities.");
  }
  const uncitedUnknowns: GuideUnknown[] = [];
  const draftBindings: GuideClaimBinding[] = [];
  const selectedClaimIds = new Set<string>();
  for (const rawLine of modelText.split(/\r?\n/gu).slice(0, 64)) {
    const line = rawLine.trim();
    if (line.length === 0) continue;
    const claimIds = [...line.matchAll(CLAIM_CITATION)].flatMap((match) =>
      match[1] === undefined ? [] : [match[1]],
    );
    if (claimIds.length === 0) {
      uncitedUnknowns.push({
        disposition: "unverified",
        detail:
          "A factual statement was omitted because it did not cite a claim from this evidence request.",
      });
      continue;
    }
    for (const claimId of claimIds) {
      if (selectedClaimIds.has(claimId)) continue;
      selectedClaimIds.add(claimId);
      const claim = claimsById.get(claimId);
      draftBindings.push({
        statement: claim?.statement ?? "Unknown cited claim.",
        claimIds: [claimId],
      });
    }
  }
  const compiled = compileGuideEvidence({
    bossAndDrops: draftBindings,
    preparation: [],
    evidence: currentEvidence,
  });
  const accepted: GuideClaimBinding[] = [];
  const referenced = new Set<string>();
  const unknowns = [...uncitedUnknowns, ...compiled.unknowns];
  for (const binding of compiled.bossAndDrops) {
    const nextReferences = new Set([...referenced, ...binding.claimIds]);
    if (nextReferences.size > MAXIMUM_CONTROLLED_SOURCES) {
      unknowns.push({
        disposition: "unverified",
        detail: "A cited statement was omitted because the controlled source limit was reached.",
      });
      continue;
    }
    accepted.push(binding);
    for (const claimId of binding.claimIds) referenced.add(claimId);
  }

  const visibleSourceReferences = (): Set<string> => {
    const visible = new Set(referenced);
    for (const claim of currentEvidence) {
      if (visible.size >= MAXIMUM_CONTROLLED_SOURCES) break;
      if (!selectedClaimIds.has(claim.claimId)) continue;
      visible.add(claim.claimId);
      for (const conflictId of claim.conflicts) {
        if (visible.size >= MAXIMUM_CONTROLLED_SOURCES) break;
        if (claimsById.has(conflictId)) visible.add(conflictId);
      }
    }
    return visible;
  };

  let sources = controlledSources(currentEvidence, visibleSourceReferences());
  let text = renderControlledGuide(accepted, sources, unknowns);
  while (text.length > maximumTextLength && accepted.length > 0) {
    accepted.pop();
    referenced.clear();
    for (const binding of accepted) {
      for (const claimId of binding.claimIds) referenced.add(claimId);
    }
    unknowns.push({
      disposition: "unverified",
      detail: "A cited statement was omitted because the controlled answer size limit was reached.",
    });
    sources = controlledSources(currentEvidence, visibleSourceReferences());
    text = renderControlledGuide(accepted, sources, unknowns);
  }
  if (text.length > maximumTextLength) {
    text = "Unknown:\n- No web-derived statement fit the controlled answer limits.";
    sources = [];
  }
  return { text, sources, unknowns };
}

export function guideClaimBindingsValid(draft: GuideEvidenceDraft): boolean {
  try {
    const compiled = compileGuideEvidence(draft);
    return (
      compiled.unknowns.length === 0 &&
      compiled.evidence.length === draft.evidence.length &&
      compiled.bossAndDrops.length === draft.bossAndDrops.length &&
      compiled.preparation.length === draft.preparation.length
    );
  } catch {
    return false;
  }
}
