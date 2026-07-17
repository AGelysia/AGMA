# Managed Standalone Runtime

This directory defines the reviewed Node distributions used to assemble platform-specific AGMA
Standalone Runtime sidecars. A sidecar is an internal input to a platform-specific integrated
standalone client JAR; it is not a player-facing release by itself.

## Supported Targets

`node-distributions.json` pins the official Node archive URL and SHA-256 for exactly these targets:

- `linux-x86_64`
- `windows-x86_64`

Each sidecar contains one target's Node executable. The two Node distributions are never combined
into one sidecar.

## Production Build

Run the build from the repository root with a new output directory:

```bash
scripts/build-standalone-managed-runtime.sh linux-x86_64 /absolute/new/output-directory
scripts/build-standalone-managed-runtime.sh windows-x86_64 /absolute/new/output-directory
```

The build script verifies the reviewed distribution catalog before use, downloads official Node
archives into `AGMA_STANDALONE_MANAGED_CACHE` (or the user's build cache), verifies their SHA-256,
and uses the pinned Linux Node binary to build the Runtime and install production dependencies.
It does not use a system Node executable. Build hosts still require the ordinary archive tools
listed by the script.

`scripts/package-standalone-managed-runtime.sh` is the lower-level deterministic packager used by
the production build and offline fixtures. Direct invocations may use a developer's system Node;
that path is not a release input and does not imply a player prerequisite.

`scripts/package-standalone-release.sh` builds each platform sidecar once, embeds that exact ZIP and
a closed `runtime-artifact.json` descriptor into both supported Minecraft Fabric JARs, and emits
four independently named release assets. Linux and Windows Node distributions are never combined.
The descriptor binds the embedded archive's platform, Runtime and Node versions, byte size, and
SHA-256. The client verifies that descriptor before private installation.

The tag release gate invokes `scripts/standalone-release-check.sh`, performs two complete production
builds, and requires every JAR and the release checksum manifest to be byte-identical before upload.

## Payload Contract

The ZIP root contains:

- `bin/node` or `bin/node.exe` for the selected platform;
- `app/dist/standalone/bootstrap/index.js`, the single reviewed client-mode Runtime bundle;
- the Runtime package metadata and production-only `node_modules`;
- copied Node and production dependency license material;
- the exact standalone connector schemas; and
- `sidecar-manifest.json`, covering every payload file by path, size, SHA-256, and executable bit.

The packager rejects unknown roots, symbolic links, special files, unsafe or case-colliding paths,
native or platform-selected production dependencies, undeclared files, YAML outside dependencies,
the legacy Runtime bootstrap, Paper transport code, contract fixtures, and development plans.

## Installation Boundary

`runtime-supervisor-core` detects the current supported platform and installs a reviewed artifact
under an absolute caller-owned private directory. Installation uses an exclusive process lock,
bounded extraction, closed manifest validation, exact file-set and SHA-256 verification, private
permissions, a private staging directory, and an atomic publish into an immutable versioned path.
Failed upgrades do not replace an already installed version. Launch specifications resolve both
Node and the Runtime entrypoint from the verified managed installation; a player does not need a
system Node executable, a terminal, a startup script, or a YAML configuration file.

Maintenance uses the same cross-process lock. Ordinary installation retains the current and older
validated versions by default. Explicit cleanup removes only owner-private `install-*` or `remove-*`
crash staging trees; explicit prune preserves the caller's fully verified current installation; and
explicit uninstall removes only manifest-complete managed versions and empty internal directories.
Version removals are first moved atomically into private quarantine. Links, unexpected names,
unbounded trees, wide permissions, corrupt manifests, and hash drift fail closed.

## Verification

Offline deterministic packaging checks do not download Node:

```bash
scripts/test-standalone-managed-runtime.sh
scripts/test-standalone-client-release.sh
```

The installer and catalog checks run on Java 17 bytecode through the standalone supervisor Gradle
project. `scripts/verify-standalone-client-jar.sh` verifies the Fabric identity, exact embedded
descriptor, JAR and sidecar hashes, managed Runtime manifest, installer classes, and absence of
development plans, Paper/server payloads, external launch scripts, and YAML configuration. The
four-asset verifier also requires the two Minecraft versions to contain byte-identical sidecars for
the same operating system. Release CI executes the embedded `node.exe` on a real Windows runner.

Windows and Linux Minecraft cold start, upgrade, uninstall, and long-session behavior remain manual
release evidence in addition to these automated gates. A sidecar must never be presented as a public
standalone release by itself.
