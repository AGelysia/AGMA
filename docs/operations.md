# AGMA 0.1.0 Operations

AGMA supports two server topologies. Both keep Paper as the authority and keep Runtime on the same
host at `127.0.0.1`.

## Choose a Server Package

Use `AGMA-Server-Integrated-0.1.0-mc1.21.11-linux-x86_64.jar` when the Paper host is glibc Linux
x86_64 and the plugin should install, start, and stop Runtime. The server needs Java 21 but does not
need Node.js or npm.

Use `AGMA-Server-Separated-0.1.0-mc1.21.11.zip` when Runtime should have its own OS account or
service, or when the host is not supported by the integrated package. This topology requires
Node.js 22.16-22.x. Production dependencies are included, so npm is not required to run it.

Do not install both server packages on one Paper instance.

## Integrated Installation

1. Stop Paper.
2. Put the integrated JAR in `plugins/` and remove any other AGMA server JAR.
3. Start Paper once.
4. Run `agma setup` in the server console. The expected configuration path is
   `plugins/AGMA/managed/config.yml`.
5. Edit that file as the Paper service account. Select exactly one provider profile and replace its
   API key, model, pricing, and any required base URL.
6. Run `agma retry`, wait for completion, then run `agma doctor`.
7. Confirm a player can use `/agent say Reply with the word ready`.

The plugin owns these tasks:

- generates a private Paper-to-Runtime token;
- verifies and installs the embedded Runtime under `plugins/AGMA/managed/runtime/`;
- starts Runtime with a restricted environment on a loopback port;
- checks health and performs the authenticated handshake; and
- terminates the child process when the plugin stops.

Do not alter `${AGMA_MANAGED_SERVER_ID}`, `${AGMA_MANAGED_RUNTIME_PORT}`, or
`${AGMA_MANAGED_SERVER_TOKEN}` in the managed Runtime file. They are private values supplied by the
plugin. Other environment references are intentionally rejected in managed mode, so the provider
API key is stored as a literal value in this private file. Never send it through `/agma` or
`/agent`.

Managed setup enforces mode `0700` on its directory and `0600` on its configuration. If ownership,
permissions, links, platform checks, installation integrity, or provider validation fail, AGMA stays
offline. Correct the cause and use `/agma retry`; do not weaken the checks.

Managed logs are private files under `plugins/AGMA/managed/logs/`. `/agma doctor` reports a bounded
diagnostic code without printing a path, provider, key, prompt, or response.

## Separated Installation

Extract `AGMA-Server-Separated-0.1.0-mc1.21.11.zip` into a private application directory. The archive
contains one top-level directory with the thin Paper plugin, a production Runtime, protocol schemas,
start scripts, and deployment examples.

1. Copy `AGMA-Server-Plugin.jar` from the extracted directory to Paper's `plugins/` directory.
2. Copy `runtime/config.example.yml` to `runtime/config.yml` and restrict it to the Runtime service
   account. Leave the initial `server.id` as `survival-main`, which matches Paper's generated
   default.
3. Generate a high-entropy Runtime token. Export it as `MINECRAFT_AGENT_SERVER_TOKEN` for both the
   Runtime process and Paper, without writing the value into either YAML file.
4. Configure the provider key in Runtime's environment and leave the YAML `apiKey` as the matching
   whole-value `${ENVIRONMENT_VARIABLE}` reference.
5. Start Runtime from the extracted package root:

   ```bash
   ./start-runtime.sh
   ```

6. Start Paper. Run `/agma doctor` and `/agent doctor` from its console.

The default endpoint is `ws://127.0.0.1:38127/agent`. Paper's URL, Runtime's port, server ID, and
shared token must agree. Do not bind Runtime to a LAN or public address. The package's `deploy/`
directory contains separate systemd service and environment-file examples; install them with
root-owned mode `0600` environment files.

To change the server ID after first startup, stop Paper and Runtime, set the same unique value in
`runtime/config.yml` and `plugins/AGMA/config.yml`, then start Runtime before Paper.

Only Runtime receives the provider key. Paper receives the shared transport token and, when build
previews are explicitly wanted, `MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true`.

## Provider Configuration

Start with `agent-runtime/config.example.yml` in source builds or `runtime/config.example.yml` in the
separated package.

| Provider | `provider` | Default URL | API behavior |
| --- | --- | --- | --- |
| OpenAI | `openai` | `https://api.openai.com/v1` | Responses |
| Anthropic Claude | `anthropic` | `https://api.anthropic.com/v1` | Messages |
| DeepSeek | `deepseek` | `https://api.deepseek.com` | Chat Completions |
| Google Gemini | `gemini` | `https://generativelanguage.googleapis.com/v1beta` | Stateless `generateContent` |
| Other compatible service | `openai-compatible` | none | Chat Completions |

Official profiles may omit `baseUrl`. They may use an explicit protocol-compatible gateway after it
has been reviewed. `openai-compatible` always requires `baseUrl`.

External OpenAI example:

```yaml
model:
  provider: openai
  apiKey: ${OPENAI_API_KEY}
  model: replace-with-a-tool-capable-model
  timeoutSeconds: 60
  inputMicroUsdPerMillionTokens: 1000000
  outputMicroUsdPerMillionTokens: 4000000
```

External OpenAI-compatible local example:

```yaml
model:
  provider: openai-compatible
  baseUrl: http://127.0.0.1:11434/v1
  apiKey: ${OPENAI_COMPATIBLE_API_KEY}
  model: replace-with-a-tool-capable-model
  timeoutSeconds: 60
  inputMicroUsdPerMillionTokens: 0
  outputMicroUsdPerMillionTokens: 0
```

The compatible endpoint must support model discovery and Chat Completions with serial function/tool
calls. A model that only generates text is insufficient for requests that need server tools. AGMA
does not discover protocols, translate arbitrary APIs, follow redirects, or fall back to another
provider.

Remote custom URLs must use HTTPS. HTTP is allowed only for a literal loopback IP. User information,
query strings, and fragments in `baseUrl` are rejected. The API key and request content are sent to
the selected endpoint, so its operator and retention policy must be trusted.

## Pricing and Limits

Pricing is explicit and expressed as integer micro-USD per million tokens:

- `inputMicroUsdPerMillionTokens`
- `outputMicroUsdPerMillionTokens`
- `limits.providerRoundReservationMicroUsd`

Set them from the selected provider's current price sheet. For DeepSeek, use the higher cache-miss
input price. A call without reliable usage is conservatively charged at the configured reservation.
`limits.monthlyBudgetUsd` controls local admission using settled charges and active reservations; it
is not a provider billing cap. Configure provider-side spending alerts and limits separately.

Other useful controls are global concurrency, queue depth, per-player cooldown, daily request count,
tool rounds, and context size. Start conservatively and inspect aggregate values with `/agent costs`.

## Privacy and Knowledge

`privacy.storeConversations` enables SQLite-backed resume. Set it to `false` when resume is not
needed. `privacy.retentionDays` limits retained conversations. Keep
`privacy.logMessageContent: false`.

Knowledge roots accept bounded Markdown from private directories configured as `server_rules` or
`local_docs`. Treat their contents as data, not policy instructions. Keep these directories and the
SQLite database private to the Runtime service account.

## Permissions and Commands

Players use `/agent say <message>`. `minecraftagent.use` is granted by default. A bare `/agent` is
the same as `/agent status` and uses `minecraftagent.admin.status`, which defaults to OP.

`/agma setup`, `/agma doctor`, and `/agma retry` are available to the console, OPs, and holders of
`minecraftagent.admin.setup`. They never accept secrets as arguments.

By default, only the console can persistently turn AGMA on or off because `owners: []` and
`security.allow-op-toggle: false`. Add canonical player UUIDs to `owners` for trusted administrators.
Set `allow-op-toggle: true` only when OP status plus `minecraftagent.admin.toggle` should also grant
that control.

Configuration reload is intentionally restricted. A change that affects transport, identity, state
paths, or deployment mode requires a Paper restart; supported policy-only changes may be applied by
`/agent reload`.

## Client and Build Previews

The Fabric client is optional. Text replies work without it. Install exactly the versions in
[CLIENT-COMPATIBILITY.md](../CLIENT-COMPATIBILITY.md) for rich presentation.

Build-preview publishing is disabled by default. Set
`MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true` on the Paper process before startup to enable bounded,
read-only preview creation. This does not enable block placement. Loading, material-list display,
and removal require explicit `/agent ui` actions from the player.

## Health and Recovery

Use this order when setup fails:

1. `/agma setup` to identify managed or external mode and the correct configuration file.
2. `/agma doctor` to obtain a redacted setup state and diagnostic code.
3. Check private Paper and Runtime logs locally.
4. Correct credentials, model, URL, ownership, permissions, port conflict, or service configuration.
5. `/agma retry` for initial setup recovery, or restart Paper when the diagnostic requires it.
6. Once online, `/agent doctor` for protocol, client, and capability diagnostics.

Do not post unredacted YAML, environment files, SQLite data, logs, or provider responses in an issue.
Use the private process in [SECURITY.md](../SECURITY.md) for a suspected vulnerability.

## Upgrading and Removing

Back up private configuration and state before changing versions. Server and client AGMA versions
must match, and 0.1.0 must not be installed on another Minecraft line.

To remove AGMA, stop Paper first, remove the plugin JAR, and stop any external Runtime service. Keep
or securely delete `plugins/AGMA/` and the external Runtime data according to the server's retention
requirements. Removing the plugin does not delete private data automatically.
