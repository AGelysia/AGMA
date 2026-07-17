# AGMA Protocol 1.0

This directory is the source of truth for data exchanged by the AGMA Runtime, Paper plugin, and
optional Fabric client. Contracts use JSON Schema Draft 2020-12 and are loaded from local packaged
resources. A consumer must never resolve schema references over the network.

Protocol versions match exactly. Unknown versions, message types, fields, tools, and view types are
rejected rather than partially accepted or silently downgraded.

## Directory Layout

- `schemas/`: closed wire and tool schemas
- `fixtures/valid/`: documents every implementation must accept
- `fixtures/invalid/`: documents every implementation must reject
- `jvm-test/`: shared contract tests used by Paper and Fabric

Run the complete contract suite from the repository root with `./scripts/test.sh`.

## Runtime and Paper Channel

Runtime and Paper use an authenticated WebSocket restricted to loopback. Each application message
is a strict UTF-8 JSON envelope with a unique message ID, timestamp, nonce, authenticated server ID,
type, and closed payload. Receivers reject duplicate keys, stale timestamps, replayed nonces, and
messages larger than the 64 KiB application limit before dispatch.

The handshake uses HMAC-SHA-256 over the protocol-defined transcript. The shared server token is
never transmitted. Identity comes from the authenticated connection; a UUID or server ID inside a
payload cannot establish authority.

Implemented message families cover:

- Runtime and Paper handshake;
- request, completion, error, cancellation, and session resume;
- correlated tool call and result;
- redacted management cost request and result; and
- structured-view publication attached to a completion.

Schemas also define proposal documents and reserved envelope types. Schema validity alone does not
mean a handler or permission exists. Consumers reject reserved but unimplemented types with an
unsupported-message error.

Tool arguments and successful results receive a second validation against the locally registered
tool schema. Paper supplies player identity, permission, source, and trust metadata. The model
cannot add those fields or select an undeclared tool. Calls are serial, correlated to one active
request, and bounded to eight rounds.

## Paper and Fabric Channel

Paper and Fabric use the compatibility channel `minecraftagent:client`. This internal identifier is
stable even though the public product name is AGMA. Frames contain one strict UTF-8 JSON document
validated by `client-payload.schema.json`; the Minecraft connection supplies player identity.

Limits are applied before parsing:

| Direction or object | Limit |
| --- | --- |
| Client to Paper frame | 16 KiB |
| Paper to client frame | 40 KiB |
| Decoded transfer chunk | 24 KiB |
| Compressed structured view | 1 MiB |
| Uncompressed structured view | 1 MiB |
| Chunks per view | 64 |
| Incomplete transfer lifetime | 15 seconds |

The channel supports capability handshake, view transfer, acknowledgement, error, clear, and closed
UI-control messages. Transfers bind connection generation, transfer ID, view ID, request ID,
revision, lengths, encoding, and SHA-256. Reconnect, disconnect, world change, Offline cleanup, or a
new generation invalidates stale state.

The client validates chunk hashes, complete-content hash, exact lengths, bounded gzip expansion,
strict UTF-8, the structured-view schema, and the feature version it advertised before scheduling a
render-thread action. An acknowledgement or UI result has no server authority.

## Structured Views

The outer structured-view contract is version `1.0`. Closed content schemas cover text, item,
item-list, recipe, proposal, and build-preview representations. Paper publishes only a view the
connected client explicitly advertised. Runtime always supplies a text fallback; when a structured
completion would exceed the envelope limit, Runtime removes the views and preserves the fallback.

Native projection files are created only after a complete build-preview document has passed schema,
semantic, size, hash, registry, and exact-adapter checks. The server cannot provide a client file
path, and receiving a view never loads a placement automatically.

## Contract Rules

- Every object is closed unless its schema explicitly says otherwise.
- Transport limits apply before JSON parsing or decompression.
- Envelope validation happens before selecting a payload schema.
- Semantic validation and current Paper policy follow schema validation.
- `requestId`, session, player, server, and tool correlation must match trusted active state.
- Unknown tools and undeclared arguments or results are rejected.
- Model text, documents, signs, client claims, and custom-provider output remain untrusted data.
- Protocol acceptance never bypasses Offline state, permissions, confirmation, or server policy.

The schemas and fixtures are the normative contract. This README is an operational summary, not a
replacement for validation against those files.
