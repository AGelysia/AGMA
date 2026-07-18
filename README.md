# AGMA

AGMA (AG Minecraft Agent) provides both the 0.1.0 Paper deployment and a separate 0.3.1 pure-client
standalone mod for Fabric and Forge. The standalone client works in singleplayer and on ordinary
multiplayer servers without an AGMA server.

AGMA 0.1.0 targets Minecraft 1.21.11 only. It can use OpenAI, Anthropic Claude, DeepSeek, Gemini, or
a reviewed OpenAI-compatible cloud or local endpoint. Model replies are private to the requesting
player. Server context is exposed through a bounded tool catalog; AGMA does not provide a shell,
an arbitrary command executor, or automatic world modification.

## Download

Use one server package and, when wanted, the matching client package from the GitHub Release:

| Release asset | Intended user | Contents |
| --- | --- | --- |
| `AGMA-Server-Integrated-0.1.0-mc1.21.11-linux-x86_64.jar` | Server owners who want the simplest installation | Paper plugin, pinned Node.js Runtime, production dependencies, and protocol schemas in one offline JAR |
| `AGMA-Server-Separated-0.1.0-mc1.21.11.zip` | Operators who manage processes themselves | Thin Paper plugin, Runtime, start scripts, and service/configuration examples |
| `AGMA-Client-0.1.0-mc1.21.11-fabric.jar` | Players joining an AGMA server | Optional Fabric presentation mod for overlays, recipes, items, and supported build previews |

The integrated JAR supports glibc Linux x86_64 only. The separated package supports any platform
on which Java 21, Paper 1.21.11, and Node.js 22.16-22.x are available.

The `AGMA-Client-0.1.0` JAR remains a server companion. It is distinct from the six
`AGMA-Client-Standalone-0.3.1` JARs: Minecraft 1.18.2 is released for Fabric and Forge, while
Minecraft 1.21.11 is released for Fabric. Each target has a Linux x86_64 and Windows x86_64 JAR
with a pinned local Runtime. The `standalone-v0.3.1` release also contains one CycloneDX SBOM and
one SHA-256 checksum manifest, for eight assets total. See
[standalone-client/README.md](standalone-client/README.md) for standalone installation, privacy,
cost, viewer, and data-completeness details.

See [release-artifacts.md](docs/release-artifacts.md) for package contents and checksum verification.

## Quick Install

The integrated package is the normal choice for a server owner without process-management
experience.

Requirements:

- Paper 1.21.11
- Java 21 or newer
- glibc Linux on an x86_64/AMD64 machine

Install it as follows:

1. Stop Paper and place
   `AGMA-Server-Integrated-0.1.0-mc1.21.11-linux-x86_64.jar` in the server's `plugins/` directory.
2. Start Paper once. AGMA creates `plugins/AGMA/managed/config.yml` and remains unavailable until a
   provider is configured.
3. Open that file on the server, select one `model` profile, and replace the model name, API key,
   and pricing placeholders. Do not enter a key in a Minecraft command.
4. From the server console, run `/agma retry`, then `/agma doctor`.
5. A player can now send a private request with `/agent say <message>`.

The plugin creates its managed directory with private permissions, installs the embedded Runtime,
starts it on loopback, and stops it with the plugin. Node.js and npm are not required on the server.

For a local or third-party OpenAI-compatible endpoint, the selected model block must include:

```yaml
model:
  provider: openai-compatible
  baseUrl: http://127.0.0.1:11434/v1
  apiKey: replace-with-the-endpoint-key
  model: replace-with-the-model-name
  timeoutSeconds: 60
  inputMicroUsdPerMillionTokens: 0
  outputMicroUsdPerMillionTokens: 0
```

Only literal loopback IP addresses may use HTTP. Remote custom endpoints must use HTTPS. The
endpoint must provide OpenAI-compatible model discovery, Chat Completions, and serial tool calling.

Detailed provider profiles and the separated deployment procedure are in
[operations.md](docs/operations.md).

## Client Install

The client mod is optional. Without it, players still receive private text replies.

This section describes the 0.1.0 server companion, not the standalone client.

For the supported client configuration, install:

- Minecraft 1.21.11
- Java 21 or newer
- Fabric Loader 0.19.3
- Fabric API 0.141.4+1.21.11
- `AGMA-Client-0.1.0-mc1.21.11-fabric.jar`

Rich build-preview integration additionally requires the exact reviewed pair Litematica 0.26.12
and MaLiLib 0.27.16. Other versions fail closed and do not advertise preview support. See
[CLIENT-COMPATIBILITY.md](CLIENT-COMPATIBILITY.md).

## Commands

Player commands:

- `/agent say <message>` asks AGMA a private question.
- `/agent resume [session-id]` resumes the latest or selected stored conversation.
- `/agent module list` lists available request modules.
- `/agent module <name> <message>` uses one module for one request.
- `/agent ui pin`, `/agent ui unpin`, and `/agent ui clear` control the local overlay.
- `/agent ui preview <view-id>`, `/agent ui materials <view-id>`, and
  `/agent ui remove <view-id>` explicitly control a supported Litematica preview.

Administration commands:

- `/agma setup`, `/agma doctor`, and `/agma retry` report or recover Runtime setup without exposing
  secrets.
- `/agent status`, `/agent doctor`, `/agent capabilities`, and `/agent costs` provide redacted
  diagnostics.
- `/agent on` and `/agent off` control the persistent desired state.
- `/agent reload` atomically reloads supported policy changes.

A bare `/agent` is the same as the administrative status view and therefore requires its permission
(OP by default). Ordinary players should use `/agent say`. Setup commands require the console, OP,
or `minecraftagent.admin.setup`; player requests use `minecraftagent.use` and are allowed by
default. The Paper permission nodes remain under `minecraftagent.*` for wire and configuration
compatibility.

## Providers

| `model.provider` | Native API | Default base URL |
| --- | --- | --- |
| `openai` | Responses API | `https://api.openai.com/v1` |
| `anthropic` | Messages API | `https://api.anthropic.com/v1` |
| `deepseek` | Chat Completions | `https://api.deepseek.com` |
| `gemini` | `generateContent` | `https://generativelanguage.googleapis.com/v1beta` |
| `openai-compatible` | Chat Completions | Required explicit `baseUrl` |

There is no automatic fallback between providers. Review a custom endpoint before sending it an API
key, prompts, tool definitions, or server-derived tool results.

## Security Notes

- Keep the Runtime on `127.0.0.1`; do not expose its HTTP or WebSocket port.
- Keep provider keys, the Runtime token, configurations, logs, and SQLite data out of Git and
  support bundles.
- Use `online-mode=true`, a whitelist, current backups, and host firewall rules for Internet-facing
  servers.
- AGMA validates model tool calls against closed schemas and Paper permissions. Model output is not
  authority.
- Old Minecraft and projection-mod combinations are unsupported. Do not weaken version checks to
  load them.

Read [SECURITY.md](SECURITY.md) before production deployment.

## Build From Source

Requirements are JDK 21, Node.js 22.16-22.x, npm 10, and Bash. The repository includes its Gradle
wrapper.

```bash
./scripts/test.sh
./scripts/package.sh
```

The packaging command writes the three public artifacts and `SHA256SUMS` to `release/`. The full
release gate is:

```bash
./scripts/release-check.sh
```

Repository layout:

- `paper-plugin/`: authoritative Paper boundary and managed Runtime supervisor
- `agent-runtime/`: provider adapters, sessions, storage, and model/tool loop
- `client-mod/`: optional Fabric presentation layer
- `standalone-client/`: released 0.3.1 pure-client source, contracts, Fabric and Forge shells, local
  planner, Runtime supervisor, fixtures, and packaging
- `protocol/`: locally loaded JSON Schema contracts and fixtures
- `capability-packs/`: declarative, permission-gated capability examples
- `deploy/`: Paper and systemd configuration examples

## License

Apache License 2.0. See [LICENSE](LICENSE).
