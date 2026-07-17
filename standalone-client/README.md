# AGMA Standalone Client 0.2.0

AGMA Standalone Client is a pure Fabric client mod. It works in singleplayer and on ordinary
multiplayer servers without an AGMA Paper plugin. The mod builds a bounded catalog from data visible
to the client, plans material routes locally, and starts an authenticated loopback Runtime for
optional model and web-evidence requests.

The development plan is maintained outside this repository and is deliberately excluded from Git,
source archives, integrated JARs, and release assets.

## Release Assets

Choose the JAR matching both Minecraft and the operating system running the game:

| Minecraft | Java | Linux x86_64 | Windows x86_64 |
| --- | --- | --- | --- |
| 1.21.11 | 21+ | `AGMA-Client-Standalone-0.2.0-mc1.21.11-fabric-linux-x86_64.jar` | `AGMA-Client-Standalone-0.2.0-mc1.21.11-fabric-windows-x86_64.jar` |
| 1.18.2 | 17+ | `AGMA-Client-Standalone-0.2.0-mc1.18.2-fabric-linux-x86_64.jar` | `AGMA-Client-Standalone-0.2.0-mc1.18.2-fabric-windows-x86_64.jar` |

Every JAR contains the platform-specific Node.js 22.23.1 Runtime. A system Node installation is not
required. Verify the JAR and SBOM hashes with the published
`AGMA-Client-Standalone-0.2.0-SHA256SUMS` before installation.

## Install And Use

Install Fabric Loader 0.19.3, the matching Fabric API, and one standalone JAR in the instance's
`mods/` directory:

- Minecraft 1.21.11: Fabric API 0.141.5+1.21.11.
- Minecraft 1.18.2: Fabric API 0.77.0+1.18.2.

Open a world, then press `G`. The Catalog tab searches client-visible resources and displays local
Materials, Workstations, Steps, Catalysts, Energy, Conditions, unresolved nodes, and up to three
deterministic routes. This local view works when the Runtime or model provider is offline.

The Ask tab uses an exact selected target. Configure a model provider in Settings, start the local
Runtime, and submit the question. Inventory access is off by default and is a single-use grant for
only the route's bounded dependency set. Web search is also off by default and must be enabled for
each request from the assistant screen.

Supported model providers are OpenAI, Anthropic, DeepSeek, Gemini, and reviewed
OpenAI-compatible endpoints. Brave Search is the 0.2.0 web search backend. Model and search calls
can incur third-party charges; the UI shows the reported or estimated request cost and enforces the
configured local request and monthly search budgets.

## Data And Compatibility

The catalog is client-visible data, not server authority. Multiplayer servers may hide recipes,
conditions, inventories, loot rules, or machine state; incomplete and opaque entries remain marked
partial or unresolved. The model does not recalculate material totals. Those totals come from the
bounded local planner.

Reviewed optional viewer versions:

| Minecraft | JEI | EMI |
| --- | --- | --- |
| 1.21.11 | 27.17.0.50; public API catalog adapter | No reviewed matching artifact; unavailable |
| 1.18.2 | 10.2.1.1010; complete item-only recipes plannable, unsupported custom ingredients display-only | 0.7.3+1.18.2 detected for hover context; recipe enumeration fails closed |

The same JAR still starts when an optional viewer is absent or incompatible. Viewer adapters are
read-only and never change recipes or server state.

## Privacy And Storage

The Runtime listens only on literal loopback and authenticates the Fabric client with a private
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
- `fabric-common`: connector, configuration, tool bridge, and shared local guide presentation.
- `fabric-mc12111` and `fabric-mc1182`: version-specific Fabric lifecycle, catalog adapters, and UI.
- `contracts`: closed JSON Schemas shared with the local Runtime.
- `managed-runtime`: pinned cross-platform Runtime manifests and offline packaging fixtures.

Build and verify the four release JARs twice with:

```bash
./scripts/standalone-release-check.sh ./build/standalone-release
```

No real provider or search credential belongs in source, fixtures, logs, diagnostics, or release
artifacts.
