# Standalone Client Threat Model

Status: C0 review accepted for the C1 scope only

This threat model applies to AGMA Standalone Client. It supplements the `0.1.0`
security policy; it does not weaken the existing Paper deployment rules.

## 1. Security Objectives

The standalone client must:

- keep model and future search credentials out of Minecraft chat, logs,
  diagnostics, distributables, and protocol messages;
- expose the Runtime only through an authenticated literal-loopback channel;
- preserve the provenance, completeness, and generation of local facts;
- prevent model, viewer, server-visible, and future web content from granting
  authority or causing effects;
- fail closed when a viewer or data source is absent, incompatible, stale, or
  malformed;
- stop only Runtime processes that the current client instance owns; and
- make multiplayer visibility limits explicit in every relevant answer.

Availability is important but secondary to preserving the game process. A
Runtime, Provider, viewer, or index failure must degrade the AGMA feature and
must not crash Minecraft or block its render/tick thread.

## 2. Assets

Protected assets include:

- model API keys and future SearchProvider keys;
- the per-launch connector token and HMAC proof material;
- questions, model completions, session history, and usage records;
- local catalog snapshots, component fingerprints, pack fingerprints, and
  future web caches;
- world/session identity and any explicitly authorized context;
- budget limits and usage accounting integrity;
- Runtime, Node.js, dependency, protocol-schema, and adapter artifacts;
- private configuration, SQLite, logs, locks, and extracted Runtime files; and
- the player's ability to distinguish confirmed, inferred, external, unknown,
  and client-invisible claims.

## 3. Attackers and Untrusted Inputs

The design assumes hostile or malformed input from:

- a model response or tool-call request;
- a custom or compromised model endpoint;
- a multiplayer server and data it makes visible to the normal client;
- item names, translations, resource packs, tags, recipes, and data components;
- an installed mod, optional viewer, or incompatible viewer API;
- another local application, including a browser page probing loopback ports;
- another operating-system account where local file permissions provide a
  boundary;
- a replayed, delayed, oversized, duplicated-key, or malformed connector frame;
- a future search result, redirect target, DNS answer, or fetched page; and
- corrupted or replaced Runtime, dependency, cache, or database files.

A malicious mod running inside the same Minecraft process and a malicious
process running as the same operating-system user may be able to inspect memory,
environment, or private files. The connector token and file permissions do not
claim to defend against complete same-user or same-process compromise. The UI
must not describe local file fallback as hardware-backed encryption.

## 4. Trust Boundaries

```text
Minecraft / Fabric process
  [UI and context]       untrusted game-visible data
          |
          v
  [Standalone core] ---- optional viewer adapter
          |
          | authenticated literal-loopback connector
          v
  [Local Runtime process] ---- private SQLite / logs
          |
          | HTTPS or explicit literal-loopback model URL
          v
  [Model Provider]       untrusted output

  [Future Search API / web pages]  untrusted, disabled until C6
                    |
                    +----> controlled evidence pipeline only
```

Important boundaries are:

1. Minecraft client data to immutable standalone DTOs.
2. Optional viewer API to the viewer-neutral core.
3. The Fabric process to the Runtime process over loopback.
4. Runtime state to private filesystem storage.
5. Runtime requests to a configured Provider endpoint.
6. In C6, controlled search and fetch output to an isolated evidence channel.

## 5. Data Trust Levels

Trust is attached to each record and claim. A higher level cannot be inferred
from a lower one by model wording.

| Level | Source | Permitted claim | Required limitation |
| --- | --- | --- | --- |
| L0A | Integrated logical server registry or recipe manager in singleplayer | Current singleplayer registry/recipe fact | Snapshot on the logical server thread; valid only for that generation and world session |
| L0B | Current client registry, connection state, or actual bounded item stack | Present in the client's visible state | Not proof of complete multiplayer server rules |
| L1 | Client recipe manager or reviewed JEI/EMI public API | Local catalog recipe, use, alias, or machine relation | May be hidden, partial, stale, or server-divergent |
| L2 | Bounded static JSON from loaded mods or resource packs | Installed local data hint | Does not prove runtime conditions or server acceptance |
| L3 | Search result or fetched page | External guide evidence with citation | Hostile and non-authoritative; requires version and conflict handling |
| L4 | Model synthesis | Explanation, ordering, or natural-language suggestion | Never a factual source and cannot raise another level |

Answer presentation maps these levels to explicit user-facing classes:

- locally confirmed;
- locally inferred by deterministic code;
- external evidence;
- unverified or conflicting; and
- invisible to the client.

## 6. Difference From the 0.1 Security Model

In `0.1.0`, Paper is the authority boundary. It supplies identity, permissions,
server facts, tool policy, and effect restrictions. The Fabric client is an
untrusted presentation peer and never receives a Provider key or Runtime token.

Standalone mode has no Paper authority. Therefore:

- only L0A singleplayer data can be authoritative for a current local world;
- L0B and L1 multiplayer data are client-visible, not server-authoritative;
- the Provider key and private Runtime state move to the player's machine;
- Paper permissions and proposal confirmation cannot be reused as client
  authorization;
- safety comes from a client-only read-oriented tool catalog and the absence of
  server commands, custom payloads, and world-write tools; and
- server-era wording such as "authoritative server recipe" or "contact an
  administrator" is invalid in client mode.

The existing `SECURITY.md` remains correct for `0.1.0` Paper deployments. The
standalone release must update public security documentation before release so
users do not assume Paper's authority or key isolation still applies.

## 7. Loopback Connector Threats and Controls

### 7.1 Network exposure

Threats include wildcard binding, DNS or `localhost` ambiguity, browser-driven
loopback requests, connection flooding, and attachment to an unrelated process.

Controls:

- bind only literal `127.0.0.1` in the initial supported configuration;
- never bind `0.0.0.0`, a LAN address, or a hostname;
- use a dedicated `/connector` route in the client profile;
- register no Paper route in a client-profile Runtime;
- cap pending connections and allow one authenticated connector;
- disable WebSocket compression and bound handshake/application payloads; and
- verify Runtime identity and health before treating the child as ready.

Loopback is a routing restriction, not authentication. Every connector session
also requires HMAC authentication.

### 7.2 Token and proof handling

The launcher generates a cryptographically random connector token for each
client Runtime launch. It must contain at least 32 bytes of entropy, be distinct
from Provider credentials, and be passed only through the child environment
allowlist and connector state.

The version 3 YAML stores an environment reference, not the token value. The
token is never sent as a handshake field, written to logs, placed in a crash
message, copied to chat, or included in diagnostics.

The C0 profile model rejects identical secret references. During C1, the
secret-resolution boundary must additionally compare the resolved connector
token and Provider credential without logging either value and reject equality.

The connector handshake uses:

- the exact domain `agma-connector-handshake-v1`;
- the exact transcript and public Node/JVM golden vector in the C0 architecture record;
- HMAC-SHA-256 and constant-time proof comparison;
- a fresh challenge and at least 128-bit nonce;
- bounded timestamp skew; and
- profile, scope, component, and version binding.

The Paper handshake keeps its existing domain and implementation unchanged.
Proofs from one wire family must not validate in the other.

### 7.3 Replay and correlation

Handshake and application message IDs and nonces enter a bounded TTL replay
cache only after structural validation. Duplicate IDs, duplicate nonces, stale
timestamps, malformed base64url, duplicate JSON keys, binary frames, and
oversized frames fail closed.

Request, session, player/profile identity, tool-call ID, sequence, and response
correlation must match active authenticated state. A disconnected connector
cancels outstanding remote work. Late results are ignored or treated as a
protocol violation and cannot revive a completed request.

## 8. Keys, Logs, Configuration, and Storage

### 8.1 Provider and search credentials

Credentials are entered only through the future masked settings UI or a private
development configuration. They are not accepted through a Minecraft command.
A custom endpoint is a credential and data-disclosure decision and must be
shown as such.

Official remote endpoints and custom remote endpoints require HTTPS. Plain HTTP
is allowed only for an explicit literal-loopback model endpoint. Credentials in
URLs, URL fragments, and query strings are rejected. Provider HTTP clients do
not follow redirects unless a Provider protocol explicitly reviews them.

Operating-system credential storage is preferred in C7. Private-file fallback
requires `0600`-equivalent POSIX permissions or a reviewed Windows ACL. The UI
must warn that malicious same-user software or mods may still read secrets.

### 8.2 Logging and diagnostics

Defaults must minimize retained data:

- message-content logging is off;
- tool argument/result logging is off or metadata-only in the standalone
  profile;
- conversation storage is opt-in and retention is bounded;
- URLs are logged without query, fragment, or credentials;
- stable errors expose codes and field paths, never secret values; and
- exported diagnostics exclude keys, tokens, prompts, completions, full paths,
  server addresses, world seed, coordinates, inventory, and future page bodies.

Tests use sentinel secrets and assert that logs, errors, UI state, SQLite, and
diagnostic archives do not contain them.

### 8.3 Local files and database

Runtime configuration, SQLite, logs, locks, snapshots, and extracted binaries
live under an AGMA-owned per-instance private root. Creation and verification
must reject path escape, symbolic links, hard-link surprises where the platform
supports detection, special files, wrong owner, and unsafe permissions or ACLs.

SQLite uses a process ownership lock and bounded retention. Existing physical
`server_id` columns may temporarily hold an opaque client scope ID, but client
APIs must not describe that value as server authority.

## 9. Viewer and Catalog Safety

JEI and EMI support is future work and fails closed:

- base client classes do not statically reference optional viewer classes;
- adapters use reviewed public APIs and compile-only dependencies;
- exact Minecraft and viewer versions are checked before linkage;
- adapters do not reflect private fields or invoke internal implementation
  classes;
- adapters are read-only and do not alter hidden recipes, search, bookmarks,
  cheat mode, or viewer state;
- unavailable, reloading, incompatible, or linkage-failed adapters return a
  bounded status and fall back to the vanilla client source; and
- installing both viewers does not merge duplicate catalogs by default.

Names, translations, tags, aliases, data components, viewer recipe IDs, and
workstation labels are untrusted data. All fields have character, collection,
and serialized-size limits before they can enter an index, prompt, log, or UI.

EMI remains disabled until an official reviewed `1.21.11` artifact exists. An
older artifact must not be force-loaded to satisfy a compatibility claim.

## 10. Snapshot Generation and Stale State

Each immutable catalog snapshot has a random or monotonic generation ID,
world/session scope, source inventory, pack fingerprint, creation time,
completeness, and warnings.

Controls:

- acquire L0A state on the integrated logical server thread;
- copy bounded values into DTOs and never retain live registry or viewer
  objects after collection;
- build indexes off-thread and atomically publish a complete generation;
- pin one generation for a request or deterministic plan;
- include the generation ID in tool calls, results, views, and cache keys;
- reject or restart a chain when subsequent results use another generation;
- invalidate the active generation on disconnect, world switch, resource
  reload, viewer unload, or pack change; and
- never attach a late completion from an old generation to a new world.

No-world state may expose only data proven safe at that lifecycle stage and must
state that the catalog is not ready.

## 11. Multiplayer Visibility

An ordinary multiplayer server can hide or alter recipes, loot, quests,
progression, tags, scripts, and permissions. The standalone client cannot infer
those facts from local installation state.

Controls:

- label L0B/L1/L2 multiplayer data as client-visible or local catalog data;
- include completeness and source warnings in results and views;
- mark hidden or server-only questions as client-invisible or unknown;
- do not claim that a local recipe is accepted by the remote server;
- do not read or upload server address, seed, coordinates, or full inventory by
  default;
- require a narrowly described, single-use authorization before any future
  inventory snapshot leaves the Fabric process; and
- never send an AGMA custom packet, command, chat message, or automated action
  to the multiplayer server.

A server rejecting unknown payloads must have no effect because standalone mode
does not register or send such a payload.

## 12. Model and Tool Safety

Model output is L4 untrusted data. A model may request only a tool present in the
active client-profile allowlist. Arguments and successful results pass closed
local schemas, size limits, source/trust checks, request correlation, and round
limits.

Client mode does not expose Paper permission, proposal, project mutation,
server command, arbitrary filesystem, shell, arbitrary URL fetch, or world-write
tools. Provider serial tool-call behavior remains bounded by request timeout,
budget, and maximum rounds.

Fuzzy candidate selection, arithmetic, recipe expansion, cycle detection, and
route planning are deterministic responsibilities. A model may explain a
validated result but cannot calculate or upgrade its provenance. An ambiguous
search produces a player selection step rather than a silent model choice.

## 13. Web Evidence: Disabled Until C6

Web search and page fetching are not implemented or enabled in C0 through C5.
The default remains off after C6 until the player gives informed single-use or
persistent authorization. No request may silently fall back to web access.

C6 requires a separate security review covering at least:

- a SearchProvider SPI with one reviewed API backend;
- no model-accessible arbitrary `fetch(url)` tool;
- HTTPS-only public fetches, with HTTP limited to an explicitly configured
  literal-loopback test backend;
- DNS resolution followed by rejection of loopback, private, link-local,
  multicast, metadata, unspecified, and reserved addresses;
- target revalidation after every DNS answer and redirect to address DNS
  rebinding and redirect-based SSRF;
- redirect, connection, read, byte, decompression, MIME, page, concurrency, and
  total-workflow limits;
- no JavaScript, forms, cookies, executable content, archives, or local files;
- structured HTML parsing and hidden-content removal;
- page text isolated as hostile evidence, never system or tool instructions;
- claim IDs bound to URL, title, publisher, retrieval time, evidence hash,
  applicable versions, quality, and conflicts; and
- an answer compiler that rejects unknown claim IDs and unsupported precise
  facts.

Prompt injection in a page must not initiate another tool, change instructions,
read local state, reveal a key, or select an internal URL. SSRF, DNS rebinding,
redirect, oversized body, compression, MIME, injection, stale-version, and
conflicting-source fixtures are C6 release gates.

## 14. Runtime Process Lifecycle

The Runtime is an owned child process, not a machine-wide service.

Controls:

- initialization and health polling occur off the render/tick thread;
- the child receives a cleared environment plus an explicit allowlist;
- configuration, working directory, entrypoint, executable, and logs are exact
  normalized paths under the instance state root;
- one instance uses one lock, database, port, scope ID, and connector token;
- readiness requires both health and authenticated identity checks;
- parent stdin/handle closure requests graceful Runtime shutdown;
- a timeout may terminate only the exact owned process handle;
- AGMA never kills a process by the name `node` or by an occupied port;
- crash restart has a maximum count and exponential or bounded backoff;
- an unknown listener or mismatched child identity fails startup; and
- game shutdown, world disconnect, and Runtime failure release requests and
  stale connector state without crashing Minecraft.

C1 may use an external developer-installed Node.js. A public release may not.
C8 must pin Node.js and production dependencies per platform, verify a manifest
and SHA-256 before execution, extract privately, and test upgrade, rollback,
file locking, and cleanup on real Windows and Linux systems.

## 15. Release Gates

### 15.1 C1 gates

- Existing Paper handshake, envelope, protocol, and semantic fixtures pass
  unchanged.
- Version 2 Paper and version 3 client configuration cannot be confused.
- The client connector passes success, wrong-token, cross-profile, replay,
  stale-time, duplicate-key, oversized-frame, second-connection, disconnect,
  timeout, and cancellation tests.
- Runtime binds literal loopback and registers only the profile route.
- All five current Provider profiles and a reviewed custom base URL pass mock
  contract tests; real credentials are never fixtures.
- Startup, Provider-offline, crash, and shutdown states are recoverable and do
  not block or crash the game.
- Secret sentinel scans pass for logs, errors, database, and diagnostics.

### 15.2 Later functional gates

- C2 generation replacement, stale-reference, Unicode search, ambiguity, and
  100k-resource performance tests pass.
- C3/C4 viewer absent, wrong-version, reload, unload, and linkage-failure tests
  demonstrate vanilla fallback.
- C5 planner quantity, alternatives, cycles, inventory consent, machine,
  depth/node/time budget, and deterministic repeatability tests pass.
- C6 SSRF, prompt injection, citation binding, conflict, version, authorization,
  and zero-traffic-when-disabled tests pass before any web feature ships.
- C7 credential storage, ACL/permission, redaction, deletion, and diagnostic
  export tests pass.
- C8 platform-specific artifact integrity, cold start, upgrade, uninstall, and
  no-system-Node tests pass on real Windows and Linux clients.

### 15.3 Product release gates

- No AGMA server is required for startup, configuration, or a request.
- No custom packet, command, automatic game action, or world write occurs.
- Multiplayer answers visibly preserve the client-visibility boundary.
- No-viewer and reviewed-JEI configurations work; EMI is gated by an actual
  reviewed `1.21.11` artifact.
- Local deterministic process results cannot be replaced by model arithmetic.
- External claims have valid citations and applicable version information.
- Disabling web produces zero search and fetch traffic.
- Windows and Linux each have a verified, self-contained artifact that requires
  no user-installed Node.js or manual Runtime management.
- Public compatibility, privacy, cost, and security documents describe the
  standalone boundary rather than the old Paper boundary.

## 16. C0 Review Conclusion

Decision: accept the architecture for C1 with constraints.

The review finds the text-only C1 vertical slice supportable without weakening
the `0.1.0` Paper security model, provided that:

- the Paper wire and HMAC domain remain unchanged;
- the client profile, `/connector` route, schemas, identity fields, and HMAC
  domain are independent;
- C1 exposes no game catalog, viewer, planner, inventory, web, command, or
  write-effect tool;
- supervisor code extracted in C1 is platform-neutral and does not claim that
  current POSIX installer code supports Windows;
- development use of external Node.js is clearly non-release behavior; and
- every C1 exit condition and negative transport test above passes.

This acceptance does not approve C2 through C8 implementation by implication.
Each stage must satisfy its listed threat controls and release gates before the
next trust boundary is enabled.
