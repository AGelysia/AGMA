# AGMA 0.1.0 Client Compatibility

AGMA client support is deliberately narrow. The server always provides a private text fallback, so
the Fabric client and projection mods are optional.

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
