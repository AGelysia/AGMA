# Capability packs

This directory contains non-executable examples of versioned capability manifests. A pack can expose
only typed arguments and a fixed execution template. It must never expose arbitrary console text,
shell input, scripts, reflection, or unrestricted files.

`status: example` and `status: draft` are permanent deny markers. The loader must never register or
execute either status, even if an owner approval happens to reference the same ID and version. An
absent status makes a file only a candidate; approval is separate Paper-owned state and is never
declared by pack content.

Console-source override is deliberately absent from the schema. A pack cannot relax Paper's local
default-deny policy. Nothing in this repository directory is approved or executable.

- `example-safe/server-version.example.json` is a closed read-only `example`.
- `example-homes/set-home.draft.json` is write-capability review material marked `draft`.
