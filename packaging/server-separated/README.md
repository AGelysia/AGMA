# AGMA separated server package

This package is for administrators who want to run the Paper plugin and the
AGMA Runtime as separate processes. It supports Minecraft and Paper 1.21.11.

## Requirements

- Java 21 and Paper 1.21.11
- Node.js 22.16.0 or newer, but lower than 23
- A supported model provider account or a reviewed OpenAI-compatible endpoint

## Install

1. Copy `AGMA-Server-Plugin.jar` into the Paper server's `plugins/` directory.
2. Copy `runtime/config.example.yml` to `runtime/config.yml` and configure one
   model provider.
3. Set the provider key and a new random `MINECRAFT_AGENT_SERVER_TOKEN` in the
   Runtime process environment.
4. Set the same `MINECRAFT_AGENT_SERVER_TOKEN` in the Paper process environment.
5. Start the Runtime with `./start-runtime.sh` or `./start-runtime.ps1`, then
   start Paper.

Paper creates `plugins/AGMA/config.yml` on first start. Its Runtime URL defaults
to `ws://127.0.0.1:38127/agent`, matching the packaged Runtime template.

For a systemd deployment, adapt the templates under `deploy/systemd/`. Keep the
Runtime bound to loopback and never put provider credentials in Paper's
environment or configuration.

See `OPERATIONS.md` for provider profiles, permissions, recovery, and upgrade
details. Verify this package before installation with:

```bash
sha256sum --check SHA256SUMS
```
