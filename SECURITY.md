# Security Policy

## Supported Version

Security fixes are provided for the AGMA 0.1.0 Minecraft 1.21.11 server line and the separately
versioned AGMA Standalone Client 0.2.0 line for Minecraft 1.18.2 and 1.21.11. Modified artifacts and
dependency combinations not listed in [CLIENT-COMPATIBILITY.md](CLIENT-COMPATIBILITY.md) are
unsupported.

Paper is the authoritative boundary. Model output, player input, custom provider responses, server
documents, client payloads, optional mods, and client acknowledgements are all untrusted.

AGMA 0.1.0 has no shell, arbitrary command executor, automatic block placement, printer, generic
world-write interface, or automatic loading of a generated projection. A valid protocol document or
model tool call never grants a permission by itself.

The standalone client likewise exposes no server payload channel, shell, arbitrary URL fetch tool,
or world-write capability. Its managed Runtime binds to literal loopback, uses an authenticated
connector, and accepts only closed client catalog tools. Local planner output is client-visible
evidence, not multiplayer server authority.

## Report a Vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, Minecraft chat, or server
log. Use the repository's private
[Report a vulnerability](https://github.com/AGelysia/AGMA/security/advisories/new) form.

Include:

- AGMA artifact name, version, and SHA-256;
- exact Minecraft, Paper, Fabric, and optional-mod versions;
- minimal reproduction steps and observed impact;
- whether authentication, permissions, Offline state, storage, client payloads, custom endpoints,
  or cost accounting are involved; and
- sanitized diagnostics without credentials, player UUIDs, prompts, completions, private paths, or
  world data.

Never attach a real provider key or Runtime token. Rotate any credential that may have been exposed
before submitting the report.

## Deployment Boundary

Paper and Runtime are designed to run on the same trusted host. Their WebSocket connection and the
Runtime health endpoint are restricted to `127.0.0.1`. Do not publish the Runtime port, logs,
configuration, or SQLite database.

The external deployment uses the same high-entropy `MINECRAFT_AGENT_SERVER_TOKEN` value in the
Paper and Runtime processes. Only Runtime should receive the provider API key. The integrated
deployment generates its own transport token and keeps the provider key in
`plugins/AGMA/managed/config.yml`; that file must remain mode `0600` inside a mode `0700`
directory. Never put either secret in Git, a Minecraft command, a JAR, a screenshot, or a support
bundle.

For an Internet-facing server:

- keep `online-mode=true`;
- enable a whitelist and add only intended authenticated accounts;
- expose only the Minecraft port and restrict it with a host or network firewall;
- do not expose RCON, query, Runtime, database, or management ports; and
- use a separate test world and current backups before enabling player access.

## Model Providers and Custom URLs

Runtime sends the configured provider key, prompts, tool definitions, and selected server-derived
tool results to the configured endpoint. A custom `model.baseUrl` is therefore a credential and
private-data trust decision.

Remote custom URLs must use HTTPS. Plain HTTP is accepted only for a literal IP loopback host. URLs
with credentials, query strings, or fragments are rejected, and provider requests do not follow
redirects. Do not point AGMA at an endpoint solely because it claims OpenAI compatibility; verify
its operator, retention policy, protocol behavior, and tool-call handling first.

There is no automatic provider fallback or key rotation. Use provider-side spending controls in
addition to AGMA's local request and budget limits. AGMA's monthly value is a conservative local
admission bound, not a provider billing cap.

## Configuration and Data

- Keep `privacy.logMessageContent: false`.
- Set `privacy.storeConversations: false` when conversation resume is not required.
- Review `privacy.retentionDays`, knowledge roots, pricing, and request limits before admitting
  players.
- Keep Runtime configuration, SQLite files, private knowledge, logs, and Paper state readable only
  by their service accounts.
- Do not bypass ownership, file-mode, hard-link, or symbolic-link checks after a startup failure.
- Back up configuration and state privately; test restores without weakening permissions.

The client receives only bounded presentation data. It never receives the provider key or Runtime
token. Client feature claims and UI actions do not authorize server actions.

For the standalone client, provider and Brave Search keys remain in the local Minecraft instance.
Web access is disabled by default and the game UI grants it for one request at a time. AGMA does not
automatically collect account identity, UUID, server address, world seed, coordinates, chat history,
full inventory, local paths, or keys for a web request. The question typed by the player is sent with
bounded Minecraft, target, mod, and modpack context. Inventory authorization is single-use and
returns normalized quantities for only a bounded dependency set.

## Projection Safety

Build-preview integration is enabled only for the exact reviewed Minecraft 1.21.11, Fabric Loader
0.19.3, Litematica 0.26.12, and MaLiLib 0.27.16 tuple. Other tuples advertise no projection
features. Do not rename mods, edit metadata, patch the adapter, or relax version checks to force
compatibility.

Preview publishing is disabled unless the Paper process receives exactly:

```text
MINECRAFT_AGENT_BUILD_PREVIEW_ENABLED=true
```

Even when enabled, a preview is a bounded presentation artifact. Loading, opening its material
list, and removal each require an explicit player action. AGMA does not automatically place blocks.

## Release Integrity

Download the named release assets and verify them with the published `SHA256SUMS`; the standalone
release also publishes a CycloneDX SBOM covered by that checksum. Do not treat
GitHub's automatically generated source archives as ready-to-run distributions. The integrated JAR
contains a pinned offline Runtime and verifies it before installation; it does not download a
`latest` executable at server startup.
