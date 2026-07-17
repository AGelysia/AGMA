# Standalone Client Architecture: C0 Decision Record

Status: accepted for C1 implementation

This document records the minimum architecture agreed during C0. It is a
decision record, not a complete implementation design and not a copy of the
standalone development plan.

## 1. Baseline

The reviewed baseline is:

| Item | Baseline |
| --- | --- |
| Repository commit | `47f4791dd07526465b0246e9d48d939bfee038ed` |
| AGMA | `0.1.0` |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.4+1.21.11` |
| Runtime protocol | Paper protocol `1.0` |
| Runtime process | Node.js 22, loopback Fastify/WebSocket service |
| Managed Runtime package | Linux x86_64 only |

The baseline consists of a Paper authority boundary, a model Runtime, and an
optional Fabric presentation client. The `0.1.0` Fabric client is not a
standalone agent and must retain its current companion behavior.

The C0 compatibility rule is strict:

- Do not change the bytes, fields, message names, validation, or semantics of
  `paper.hello`, `runtime.hello`, the Paper application envelope, or `/agent`.
- Do not reinterpret the legacy `serverId` field as a client installation ID.
- Do not widen the legacy Paper HMAC transcript or authentication type unions.
- Keep all existing Paper protocol fixtures and tests valid without migration.
- Build the standalone connection as a separate profile and wire family.

## 2. Product Boundary

The standalone product runs entirely on the player's machine. It may call a
configured cloud provider or a literal-loopback model endpoint, but it does not
require an AGMA server component.

The standalone client must not:

- send an AGMA custom payload to a Minecraft server;
- execute a player or server command;
- automate movement, combat, crafting, inventory transfer, or world changes;
- claim knowledge of multiplayer facts that the normal client cannot see; or
- silently replace the behavior of the `0.1.0` presentation client.

The initial source tree is intentionally separate under `standalone-client/`.
Reuse occurs through reviewed interfaces or extracted platform-neutral code,
not by copying the complete Paper plugin or Runtime implementation.

## 3. Module and Class-Loading Boundaries

The names below describe ownership boundaries. C0 does not require every name
to become a Gradle subproject immediately, but package and dependency edges
must obey the same boundaries.

| Boundary | Responsibility | Forbidden dependencies |
| --- | --- | --- |
| `standalone-client-core` | Pure Java DTOs, immutable snapshots, source provenance, generation lifecycle ports, adapter SPI, and request-facing ports | Minecraft, Fabric, JEI, EMI, UI, Node.js APIs |
| `standalone-client-fabric` | Future Fabric entrypoint, lifecycle integration, hotkey/UI, Minecraft registry access, context collection, and connector client | JEI or EMI implementation classes in base entrypoints |
| `standalone-client-compat-jei` | Future JEI public-API adapter and JEI lifecycle bridge | EMI and JEI internal implementation classes |
| `standalone-client-compat-emi` | Future EMI public-API adapter, enabled only for a reviewed `1.21.11` artifact | JEI and EMI internal implementation classes |
| `runtime-supervisor-core` | Platform-neutral process state machine, health polling, cancellation, restart bounds, parent/child ownership, and shutdown | Paper API, Minecraft API, POSIX-only file policy |
| Platform runtime installer | Platform detection, private extraction, ACL or POSIX policy, manifest and hash checks, executable selection | Minecraft render/tick thread |
| Existing `agent-runtime` | Providers, request admission, sessions, budgets, SQLite, strict JSON, and profile-specific request assembly | Minecraft classes and viewer APIs |

The intended dependency direction is:

```text
compat-jei ----+
               |
compat-emi ----+--> standalone-client-core <-- standalone-client-fabric
                                                |
                                                +--> runtime-supervisor-core
                                                |
                                                +--> authenticated connector
                                                         |
                                                         v
                                                  agent-runtime
```

Optional viewer implementation classes must not be resolved from a base Fabric
entrypoint. An absent, unsupported, or linkage-failed viewer produces a bounded
diagnostic and falls back to the Minecraft client source.

The current Paper supervisor is useful as a behavioral reference, but it is not
portable source for the standalone client. Its installer, log checks, and
platform gate depend on POSIX attributes, `unix:nlink`, and Linux x86_64.

## 4. Runtime Profile Model

Runtime configuration gains a new raw configuration version for standalone
operation. Legacy version 2 remains the Paper profile and is parsed exactly as
before. A new version 3 document is a discriminated client profile.

Illustrative client profile fields are:

```yaml
configVersion: 3
profile: client
identity:
  installationId: 11111111-1111-4111-8111-111111111111
  scope: installation
transport:
  host: 127.0.0.1
  port: 38127
  connectorToken:
    source: environment
    reference: AGMA_CLIENT_CONNECTOR_TOKEN
  authenticationDomain: agma-connector-handshake-v1
model:
  provider: openai
  baseUrl: https://api.openai.com/v1
  apiKey:
    source: environment
    reference: OPENAI_API_KEY
  model: configured-model-name
```

The complete schema continues to require bounded model, storage, logging,
limits, and privacy sections. The example above only shows the profile split.

The loader maps raw configuration into an internal profile abstraction:

```text
RuntimeProfile
  kind: paper | client
  scopeId: bounded opaque identifier
  authenticationSecret: profile-specific secret
  transport: loopback host and bounded port
```

This abstraction may be used by admission, usage accounting, conversation
ownership, and safe diagnostics. Existing SQLite `server_id` columns remain
unchanged during C1 and hold the opaque scope key internally. Renaming or
migrating those columns is deferred because it adds risk without changing C1
behavior.

Profile rules:

- A version 2 document can only produce the Paper profile.
- A version 3 document can only produce the client profile.
- Mixed `server`, `identity`, `serverToken`, and `connectorToken` fields fail
  closed.
- A connector token and provider API key must use distinct references. C1 must also reject equal
  resolved values without logging either secret.
- The client launcher supplies a fresh high-entropy connector token through a
  narrow environment allowlist. The token is not written into the distributable
  or user-authored YAML.
- Only one profile-specific transport is registered in a Runtime process.

## 5. Connector Wire

The standalone wire is parallel to, not an extension of, the Paper wire.

| Property | Legacy Paper wire | Standalone connector wire |
| --- | --- | --- |
| Route | `/agent` | `/connector` |
| Initial message | `paper.hello` | `connector.hello` |
| Identity field | `serverId` | `scopeId` |
| Peer kind | `paper` | `standalone_client` |
| HMAC domain | Existing Paper domain | `agma-connector-handshake-v1` |
| Schema family | Existing protocol 1.0 schemas | New connector schemas |

The connector handshake includes a connector protocol version, scope ID,
component kind and version, supported versions, timestamp, nonce, challenge,
key ID, response correlation, and HMAC-SHA-256 proof. A client request has a
null `requestId`; the Runtime response sets `requestId` to the request's
`messageId` and echoes its challenge while using a distinct nonce.

The proof transcript is the following UTF-8 text with LF separators and no
trailing LF. A null request or selected protocol is encoded as the literal
`-`. Capabilities are sorted by ID and encoded as comma-separated
`id=version`; the current supported protocol list contains only `client-1.0`.

```text
agma-connector-handshake-v1
schemaVersion
connectorKind
type (`connector.hello`)
messageId
requestId-or--
timestamp
nonce
component
componentVersion
scopeId
comma-separated-supported-protocols
selected-protocol-or--
comma-separated-capabilities
authentication.scheme
authentication.keyId
authentication.challenge
```

`authentication.proof` is unpadded base64url of
`HMAC-SHA-256(connectorToken, transcript)`. It is not part of the transcript.
The Runtime uses constant-time proof comparison. The public C0 golden vector is
verified independently by the Node and JVM test lanes. Both lanes also reject
duplicate or unsorted capability IDs and non-canonical nonce, challenge, and
proof encodings as semantic errors; structural schema success alone is not
sufficient to admit a handshake.

The connector transport reuses implementation primitives only where they have
no Paper wire meaning:

- strict UTF-8 and duplicate-key-rejecting JSON parsing;
- message and nesting limits;
- nonce generation and bounded replay cache;
- clock-skew checks;
- WebSocket size limits and compression disabled; and
- cancellation on authenticated connection loss.

The hello document has its own complete message schema, carries
`type: connector.hello`, and uses `scopeId`; it is not shaped like the Paper
envelope. C1 must define a separate bounded application envelope before sending
post-handshake traffic. Authentication establishes the peer identity for the
connection. Payload fields cannot replace that identity.

Only one authenticated standalone connector is accepted by one client-profile
Runtime. Pending and duplicate connections are bounded. The Runtime binds the
literal IPv4 loopback address and never a wildcard interface.

Common post-authentication dispatch may be extracted later behind a generic
connector interface. Such extraction must happen below the schema and proof
layers so it cannot alter legacy Paper serialization or validation.

## 6. Profile-Specific Tools and Answers

The Paper and client profiles use separate module and tool catalogs. The client
profile never registers Paper permission, server command, proposal, landmark,
build preview, or world-write tools.

Future client tools are named in the `game.*` namespace. Their descriptors use
generic execution targets such as `connector_remote` or `runtime_local`, never
the internal name `paper_remote`. Results carry bounded source, trust,
completeness, and generation metadata.

The staged ownership is:

| Stage | Capability |
| --- | --- |
| C1 | Text request, cancellation, timeout, status, and text completion; no game catalog tool is required |
| C2 | Resource snapshot, local fuzzy search, context collection, and explicit disambiguation |
| C3/C4 | Optional JEI and EMI catalog adapters |
| C5 | Process lookup, uses, deterministic AND/OR planning, and process views |
| C6 | Controlled external evidence and cited guide answer compilation |

When game tools are introduced, an ambiguous resource search must terminate in
a deterministic selection response. A model is not allowed to choose a close
candidate silently. Structured recipe, process, or guide views must be compiled
from validated tool data; model text cannot manufacture trusted facts.

## 7. C1 Minimum Vertical Slice

C1 proves the product and process boundary before catalog work begins:

1. A future Fabric-side launcher loads or prepares a strict version 3 client
   profile without starting a Minecraft server.
2. The platform-neutral supervisor asynchronously starts a development Runtime
   using an external Node.js installation.
3. The launcher polls bounded health state without blocking a render or tick
   thread.
4. The client authenticates to `/connector` with `connector.hello`.
5. A local text request reaches the existing request admission, budget, session,
   and selected model Provider path.
6. The Runtime returns a validated text completion or a stable recoverable
   error.
7. Cancellation, connection loss, parent shutdown, and timeout release all
   request and process state.

C1 validates OpenAI, Anthropic, DeepSeek, Gemini, and reviewed
OpenAI-compatible base URLs through mocks and explicit low-frequency manual
checks. Tests and fixtures contain no real credential.

C1 exit requires one request from the main menu or a normal client session,
singleplayer, and an ordinary multiplayer connection without an AGMA server.
No Minecraft server process may be needed for the Runtime path. An offline
Provider must result in a recoverable state, and Runtime exit must not crash the
game.

## 8. Lifecycle State Machine

The minimum supervisor state is:

```text
UNCONFIGURED -> STOPPED -> STARTING -> READY
                    ^          |         |
                    |          v         v
                    +------- ERROR <--- STOPPING
```

Initialization only prepares state. Runtime startup occurs asynchronously after
configuration or the first AI request. A child is identified by the exact
process handle created by AGMA, not by executable name or listening port.

Shutdown first closes the authenticated connection and requests graceful child
termination. A bounded timeout may then terminate only that owned child.
Restart attempts have a count and backoff ceiling. Multiple Minecraft instances
use distinct state roots, scope IDs, ports, locks, logs, and databases.

## 9. Compatibility Invariants

The following are release-long invariants, not optional cleanup work:

- The presentation client and Paper server continue to use the existing wire.
- A client-profile Runtime cannot authenticate a Paper connector, and a
  Paper-profile Runtime cannot authenticate a standalone connector.
- Client mode does not register server-side effects or permissions.
- Provider code is reused, not forked per profile.
- Model and web output remain data and never grant authority.
- Profile-specific errors do not tell a local player to contact a server
  administrator.

## 10. Deferred Work

C0 creates contracts and review records only. It does not implement:

- a Fabric entrypoint, hotkey, settings screen, or in-game answer UI;
- Minecraft registry, recipe, inventory, or world snapshots;
- JEI or EMI linkage;
- resource fuzzy search or selection UI;
- process graph construction or deterministic planning;
- web search, page fetching, evidence claims, or citations;
- API key integration with an operating-system credential store;
- an embedded Node.js Runtime or public standalone package;
- Windows ACL and executable-installation policy;
- macOS support;
- SQLite physical column renames; or
- changes to the existing Paper or presentation-client protocol.

Web access is explicitly deferred to C6. Integrated platform packaging is
explicitly deferred to C8. C1 may require developer-installed Node.js and is
not a public release artifact.

## 11. C0 Decision

C0 approves implementation of the C1 text-only vertical slice under these
conditions:

- the legacy Paper wire remains covered by unchanged compatibility fixtures;
- the version 3 client profile is a strict, separate configuration shape;
- connector authentication uses a separate schema and HMAC domain;
- supervisor extraction contains no Paper, Minecraft, or POSIX-only policy;
- all transport and lifecycle failure paths are bounded; and
- no viewer, catalog, planner, web, or integrated packaging work is folded into
  the C1 change set.
