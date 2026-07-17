# AGMA 0.1.0 Release Artifacts

AGMA 0.1.0 publishes three runnable artifacts and one checksum file. Each user-facing variant is a
single JAR or ZIP.

## Server Integrated

File:

```text
AGMA-Server-Integrated-0.1.0-mc1.21.11-linux-x86_64.jar
```

Use this for the simplest Paper installation. It contains:

- the AGMA Paper plugin;
- the compiled AGMA Runtime;
- pinned Node.js 22.23.1 for Linux x86_64;
- production Node.js dependencies;
- protocol schemas; and
- integrity metadata verified before installation.

It requires Paper 1.21.11, Java 21 or newer, glibc Linux, and x86_64/AMD64. Alpine/musl, ARM, macOS,
and Windows are not supported by this artifact. It does not download Node.js or npm packages when
Paper starts.

Place this one JAR in `plugins/`. The plugin creates a private managed configuration and supervises
the embedded Runtime.

## Server Separated

File:

```text
AGMA-Server-Separated-0.1.0-mc1.21.11.zip
```

Use this when Paper and Runtime will be operated as separate processes. It contains one top-level
directory with:

- `AGMA-Server-Plugin.jar`, the thin Paper plugin;
- `runtime/`, including compiled Runtime, production dependencies, package metadata, and a strict
  configuration example;
- `protocol/schemas/` required by Runtime;
- `start-runtime.sh` and `start-runtime.ps1`;
- Paper and systemd deployment examples;
- package-specific documentation, license, security policy, and checksums.

The operator supplies Java 21, Paper 1.21.11, Node.js 22.16-22.x, provider credentials, and process
supervision. Production Node.js dependencies are included, so npm is not required to run the
package. The archive does not include a Minecraft server, world, Java runtime, provider account, or
credentials.

## Client

File:

```text
AGMA-Client-0.1.0-mc1.21.11-fabric.jar
```

This is the optional client-side Fabric presentation mod. It requires the exact Minecraft/Fabric
line in [CLIENT-COMPATIBILITY.md](../CLIENT-COMPATIBILITY.md). Put it in the player's `mods/`
directory together with Fabric API. Litematica and MaLiLib are optional and must match the reviewed
versions when projection support is wanted.

The client JAR does not contain Runtime, a provider adapter, or a Paper server. It cannot call cloud
or local model APIs by itself and is not a singleplayer/offline AGMA distribution.

## Checksums

File:

```text
SHA256SUMS
```

Download it beside all three assets and verify on Linux:

```bash
sha256sum -c SHA256SUMS
```

For one file on PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\AGMA-Client-0.1.0-mc1.21.11-fabric.jar
```

Compare the printed hash with the matching line in `SHA256SUMS`. A missing, renamed, modified, or
mismatched artifact must not be installed.

The separated ZIP also includes checksums for its own contents. Verify them after extraction before
installing the plugin or starting Runtime.

## Version Matching

- Use only Paper and Minecraft 1.21.11.
- Keep AGMA server and client at 0.1.0.
- Do not combine the integrated and thin Paper plugin.
- Do not substitute older projection mods or bypass exact compatibility checks.
- Use the named release assets rather than GitHub's automatically generated source archives for a
  ready-to-run installation.

Source builders can reproduce the distribution surface with `./scripts/package.sh`; verified
outputs are written to `release/`.
