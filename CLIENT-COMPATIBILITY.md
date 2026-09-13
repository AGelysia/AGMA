# AGMA Client Compatibility

AGMA client support is deliberately narrow. The server always provides a private text fallback, so
the 0.1.0 Fabric companion and projection mods are optional.

## Standalone Client 0.5.0

The standalone product is independent of the 0.1.0 Paper/server companion. Install exactly one JAR
matching the Minecraft, loader, and operating-system column:

| Component | Minecraft 1.21.11 Fabric | Minecraft 1.18.2 Fabric | Minecraft 1.18.2 Forge |
| --- | --- | --- | --- |
| AGMA standalone | 0.5.0 | 0.5.0 | 0.5.0 |
| Java | 21+ | 17+ | 17+ |
| Loader | Fabric Loader 0.19.3 | Fabric Loader 0.19.3 | Forge 40.2.21 or newer 40.x |
| Fabric API | 0.141.5+1.21.11 | 0.77.0+1.18.2 | Not used |
| JEI, optional | 27.17.0.50 | 10.2.1.1010 | 10.2.1.1010 |
| EMI, optional | Unavailable; no older substitution | 0.7.3+1.18.2 hover context only; recipe enumeration fails closed | Unavailable; no Forge integration |
| Embedded Runtime | Node.js 22.23.1, Linux or Windows x86_64 | Node.js 22.23.1, Linux or Windows x86_64 | Node.js 22.23.1, Linux or Windows x86_64 |

Without JEI, the client uses its vanilla registry and recipe fallback; in singleplayer the
1.21.11 build reads the integrated server's complete recipe set instead of the partial recipe
book. Item and fluid JEI recipe ingredients are plannable; recipes with other unsupported custom
ingredients or ambiguous roles remain display-only. On 1.18.2 the same rule applies: complete item
and fluid JEI recipes are plannable. Missing, renamed, forked, or mismatched viewers do not prevent
the base standalone JAR from starting.

The standalone catalog contains only data visible to the client. Multiplayer recipe and process
coverage can be partial. Opaque inputs, server-only conditions, cycles, and bounded-planner limits
are shown as unresolved instead of being inferred by the model.

Build previews follow the same rule: they are computed from the client's own loaded chunks
(singleplayer included), never from server authority, and every changed cell must sit within 128
blocks of the player in loaded chunks. Previews and projects are local-only artifacts; nothing is
written to the world or sent to a server. Reviewed Litematica versions for the in-world hologram:
Litematica 0.26.12 with MaLiLib 0.27.16 on Minecraft 1.21.11 Fabric; without them the HUD
projection panel still works.

## Server Companion 0.1.0

The matrix below applies to the server companion. The standalone client's supported matrix is
maintained separately in [standalone-client/README.md](standalone-client/README.md).

## Supported Matrix

| Component | Supported or reviewed version |
| --- | --- |
| AGMA server | 0.1.0 |
| AGMA client | 0.1.0 |
| Minecraft client | 1.21.11 |
| Java | 21 or newer |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.4+1.21.11 |
| Litematica | 0.26.12, optional |
| MaLiLib | 0.27.16, required only with Litematica |

Paper must also run Minecraft 1.21.11. Server and client AGMA versions must match.

Older Minecraft releases and older projection-mod builds are not supported. No compatibility is
implied for a renamed mod, edited metadata, development build, fork, or another dependency tuple.

## Base Client Features

With only Fabric Loader, Fabric API, and the AGMA client installed, the client can negotiate:

- private rich text overlays;
- item and item-list presentation using the local Minecraft registry;
- structured recipe presentation; and
- explicit overlay pin, unpin, edit, and clear actions.

If the client is absent, incompatible, or cannot display a structured view, Paper sends the private
text fallback. Client negotiation never affects Paper permissions or model tool authorization.

## Litematica Integration

Projection features are advertised only when all four reviewed values match exactly:

```text
Minecraft 1.21.11
Fabric Loader 0.19.3
Litematica 0.26.12
MaLiLib 0.27.16
```

The client reports one bounded diagnostic state:

- `READY`: exact versions matched and the adapter linked successfully;
- `NOT_INSTALLED`: Litematica is absent;
- `MISSING_DEPENDENCY`: Litematica is present but MaLiLib is absent;
- `UNSUPPORTED_VERSION`: the complete version tuple does not match;
- `ADAPTER_LINKAGE_FAILED`: versions match but the reviewed adapter cannot link; or
- `PREVIEW_STORAGE_UNAVAILABLE`: the private managed preview directory is unavailable.

Every state other than `READY` disables Litematica Preview and Material List negotiation. Base
overlay and recipe features remain available.

## Preview Controls

The server does not publish build previews unless its process has
`MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true`. Receiving a preview does not load it automatically.
The player must use the view UUID shown by AGMA:

```text
/agent ui preview <view-id>
/agent ui materials <view-id>
/agent ui remove <view-id>
```

Files are stored only in AGMA's bounded client-managed preview directory. Server payloads cannot
choose an arbitrary local path. Disconnect cleanup and explicit removal affect AGMA-owned preview
artifacts only.

## Client Scope

`AGMA-Client-0.1.0-mc1.21.11-fabric.jar` is a presentation companion for a multiplayer server using
one of the AGMA 0.1.0 server packages. It is not a standalone AI mod and does not call cloud or
local model APIs in singleplayer. Singleplayer/offline operation is not supported in 0.1.0.

Do not loosen the decoder, transfer limits, dependency checks, or adapter checks to make an
unsupported combination appear ready.
