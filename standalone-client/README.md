# AGMA Standalone Client 0.5.0

AGMA Standalone Client is a pure client mod for Fabric and Forge. It works in singleplayer and on
ordinary multiplayer servers without an AGMA Paper plugin. The mod builds a bounded catalog from
data visible to the client, plans material routes locally, and starts an authenticated loopback
Runtime for optional model and web-evidence requests.

The development plan is maintained outside this repository and is deliberately excluded from Git,
source archives, integrated JARs, and release assets.

## Release Assets

The `standalone-v0.5.0` release contains six runnable JARs. Choose the JAR matching Minecraft, mod
loader, and the operating system running the game:

| Minecraft | Loader | Java | Linux x86_64 | Windows x86_64 |
| --- | --- | --- | --- | --- |
| 1.21.11 | Fabric | 21+ | `AGMA-Client-Standalone-0.5.0-mc1.21.11-fabric-linux-x86_64.jar` | `AGMA-Client-Standalone-0.5.0-mc1.21.11-fabric-windows-x86_64.jar` |
| 1.18.2 | Fabric | 17+ | `AGMA-Client-Standalone-0.5.0-mc1.18.2-fabric-linux-x86_64.jar` | `AGMA-Client-Standalone-0.5.0-mc1.18.2-fabric-windows-x86_64.jar` |
| 1.18.2 | Forge | 17+ | `AGMA-Client-Standalone-0.5.0-mc1.18.2-forge-linux-x86_64.jar` | `AGMA-Client-Standalone-0.5.0-mc1.18.2-forge-windows-x86_64.jar` |

Every JAR contains the platform-specific Node.js 22.23.1 Runtime. A system Node installation is not
required. The other two release assets are `AGMA-Client-Standalone-0.5.0-SBOM.cdx.json` and
`AGMA-Client-Standalone-0.5.0-SHA256SUMS`; the checksum manifest covers all six JARs and the SBOM.

## Install And Use

Install one supported loader tuple and the matching standalone JAR in the instance's `mods/`
directory:

- Minecraft 1.21.11 Fabric: Fabric Loader 0.19.3 and Fabric API 0.141.5+1.21.11.
- Minecraft 1.18.2 Fabric: Fabric Loader 0.19.3 and Fabric API 0.77.0+1.18.2.
- Minecraft 1.18.2 Forge: Forge 40.2.21 or newer 40.x; do not install Fabric API for this target.

Open a world, then press `G`. The Catalog tab searches client-visible resources and displays local
Materials, Workstations, Steps, Catalysts, Energy, Conditions, unresolved nodes, and up to three
deterministic routes. This local view works when the Runtime or model provider is offline.

The Ask tab uses an exact selected target. Configure a model provider in Settings, start the local
Runtime, and submit the question. Inventory access is off by default and is a single-use grant for
only the route's bounded dependency set. Web search is also off by default and must be enabled for
each request from the assistant screen.

The Ask tab also builds. Ask the agent to design a building and preview it (for example
"在我身旁建一座两层小楼并给我投影预览") and it reads the live player position, stores a bounded
local project, and creates a client-local build preview from an ordered, multi-material shape list
(later shapes override earlier cells; clear shapes carve doors and windows; up to 24 shapes and
64³ / 16,384 cells per preview). Block ids are validated against the client registry, so modded
blocks work exactly like vanilla ones. The preview renders immediately as a HUD top-view panel
(`P` toggles); with Litematica installed, `O` additionally loads a full in-world hologram with a
generated `.litematic` schematic. A preview never writes to the world — it is a visualization aid,
not a world edit.

Supported model providers are OpenAI, Anthropic, DeepSeek, Gemini, and reviewed
OpenAI-compatible endpoints. Brave Search is the web search backend. Model and search calls can
incur third-party charges; the UI shows the reported or estimated request cost and enforces the
configured local request and monthly search budgets.

## Data And Compatibility

The catalog is data visible to the local game, not remote server authority. In singleplayer the
1.21.11 build reads the integrated server's complete recipe set as L0A data, including datapack
and mod recipe types that have recipe displays. On multiplayer the catalog remains client-visible
data: servers may hide recipes, conditions, inventories, loot rules, or machine state; incomplete
and opaque entries remain marked partial or unresolved. The model does not recalculate material
totals. Those totals come from the bounded local planner.

Reviewed optional viewer versions:

| Minecraft | Loader | JEI | EMI |
| --- | --- | --- | --- |
| 1.21.11 | Fabric | 27.17.0.50; public API catalog adapter | No reviewed matching artifact; unavailable |
| 1.18.2 | Fabric | 10.2.1.1010; complete item and fluid recipes plannable, unsupported custom ingredients display-only | 0.7.3+1.18.2 detected for hover context; recipe enumeration fails closed |
| 1.18.2 | Forge | 10.2.1.1010; complete item and fluid recipes plannable, unsupported custom ingredients display-only | Unavailable; the Forge artifact does not integrate EMI |

The same JAR still starts when an optional viewer is absent or incompatible. Viewer adapters are
read-only and never change recipes or server state.

## Privacy And Storage

The Runtime listens only on literal loopback and authenticates the game client with a private
installation token. Provider/search keys are stored in the instance's private AGMA configuration,
never in the JAR. Web queries contain only the authorized question plus bounded Minecraft, target,
mod, and modpack context. AGMA does not automatically collect account identity, player UUID,
server address, seed, coordinates, chat history, full inventory, local paths, or keys. Text that the
player types into the authorized question is sent as part of that query.

Conversation storage is optional. Retention, diagnostics, configuration deletion, and managed
Runtime removal are available in Settings. Diagnostics are redacted. Other mods or processes
running as the same operating-system account may still read local files, so use a dedicated trusted
Minecraft instance.

## Source Layout

- `core`: platform-neutral resource catalog, process graph, planner, contracts, and fixtures.
- `runtime-supervisor-core`: verified private extraction, lifecycle, upgrade, and cleanup.
- `fabric-common`: loader-neutral connector, configuration, tool bridge, shared presentation, and
  the game-free build preview engine (ordered shapes, transforms, diffing, hashes).
- `fabric-mc12111` and `fabric-mc1182`: version-specific Fabric lifecycle, catalog adapters, and UI;
  1.21.11 additionally carries the build preview executor, HUD projection overlay, and the
  reflection-only Litematica hologram bridge.
- `forge-mc1182`: Minecraft 1.18.2 Forge lifecycle, catalog adapter, JEI bridge, and UI.
- `contracts`: closed JSON Schemas shared with the local Runtime.
- `managed-runtime`: pinned cross-platform Runtime manifests and offline packaging fixtures.

Build and verify all six release JARs, the SBOM, and the checksum manifest twice with:

```bash
./scripts/standalone-release-check.sh ./build/standalone-release
```

No real provider or search credential belongs in source, fixtures, logs, diagnostics, or release
artifacts.
