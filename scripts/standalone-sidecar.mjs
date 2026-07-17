import { createHash } from "node:crypto";
import {
  copyFileSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  lstatSync,
  writeFileSync,
} from "node:fs";
import { basename, join, relative, resolve, sep } from "node:path";

const PRODUCT = "agma-standalone-runtime";
const ENTRYPOINT = "app/dist/standalone/bootstrap/index.js";
const MANIFEST = "sidecar-manifest.json";
const SCHEMA_ALLOWLIST = "standalone-client/contracts/runtime-schema-allowlist.json";
const SCHEMA_ALLOWLIST_SHA256 = "1f84cb4b8e5a5525af1d0bf9e4b33e4518358780ed1a8d89a2d5c1fb65f43bc3";
const PLATFORMS = new Map([
  ["linux-x86_64", "bin/node"],
  ["windows-x86_64", "bin/node.exe"],
]);
const ALLOWED_ROOTS = new Set(["app", "bin", "licenses", "standalone-client"]);
const FORBIDDEN_ROOTS = new Set(["paper-plugin", "client-mod", "capability-packs", "deploy", "release"]);
const WINDOWS_RESERVED = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu;
const VERSION = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$/u;
const SHA256 = /^[0-9a-f]{64}$/u;
const MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
const MAX_FILE_BYTES = 256 * 1024 * 1024;
const MAX_EXPANDED_BYTES = 768 * 1024 * 1024;
const MAX_BUNDLE_BYTES = 16 * 1024 * 1024;
const FORBIDDEN_BUNDLE_MARKERS = [
  "PAPER_CONFIG_VERSION",
  "assertPaperSecretValues",
  "SERVER_TOKEN_MISSING",
  "paper.hello",
  "paper.command",
  "paper.permission",
  "paper_remote",
  "paper-handshake",
  "server.payload",
  '"/agent"',
  "src/bootstrap/index.ts",
  "src/transport/paper-handshake.ts",
  "player.context.read",
  "server.recipe.lookup",
  "management.costs",
  "agent-request.schema.json",
  "management-costs-request.schema.json",
  "proposal.schema.json",
];

function fail(message) {
  throw new Error(message);
}

function exactKeys(value, expected) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

function byteCompare(left, right) {
  return Buffer.from(left).compare(Buffer.from(right));
}

function safePath(value) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    Buffer.byteLength(value, "utf8") > 240 ||
    value.startsWith("/") ||
    value.endsWith("/") ||
    value.includes("\\") ||
    !/^[\x20-\x7e]+$/u.test(value) ||
    /[\u0000-\u001f\u007f<>:"|?*]/u.test(value)
  ) {
    return false;
  }
  const segments = value.split("/");
  return segments.every(
    (segment) =>
      segment.length > 0 &&
      segment !== "." &&
      segment !== ".." &&
      !segment.endsWith(".") &&
      !segment.endsWith(" ") &&
      !WINDOWS_RESERVED.test(segment),
  );
}

function forbiddenPayloadPath(value) {
  const folded = value.toLowerCase();
  const [root] = folded.split("/");
  const standaloneEntrypoint = ENTRYPOINT.toLowerCase();
  const unexpectedDistPath =
    folded.startsWith("app/dist/") &&
    folded !== standaloneEntrypoint &&
    !standaloneEntrypoint.startsWith(`${folded}/`);
  return (
    FORBIDDEN_ROOTS.has(root) ||
    basename(folded) === "01-standalone-client-development-plan.md" ||
    folded.startsWith("protocol/") ||
    folded.startsWith("standalone-client/contracts/fixtures/") ||
    unexpectedDistPath ||
    (!folded.startsWith("app/node_modules/") &&
      (folded.endsWith(".yml") || folded.endsWith(".yaml"))) ||
    basename(folded) === ".env"
  );
}

function checkPayloadPath(value) {
  if (!safePath(value) || forbiddenPayloadPath(value)) fail(`unsafe standalone payload path: ${value}`);
  if (value === MANIFEST) return;
  if (!ALLOWED_ROOTS.has(value.split("/", 1)[0])) fail(`unexpected standalone payload root: ${value}`);
}

function walkFiles(root, payloadPaths = true) {
  const result = [];
  function visit(directory, prefix = "") {
    const entries = readdirSync(directory, { withFileTypes: true }).sort((a, b) => byteCompare(a.name, b.name));
    for (const entry of entries) {
      const path = prefix === "" ? entry.name : `${prefix}/${entry.name}`;
      if (payloadPaths) checkPayloadPath(path);
      else if (!safePath(path)) fail(`unsafe relative file path: ${path}`);
      const absolute = join(directory, entry.name);
      const metadata = lstatSync(absolute);
      if (entry.isSymbolicLink() || metadata.isSymbolicLink()) fail(`symbolic link in standalone payload: ${path}`);
      if (entry.isDirectory() && metadata.isDirectory()) {
        visit(absolute, path);
      } else if (entry.isFile() && metadata.isFile()) {
        result.push(path);
      } else {
        fail(`special entry in standalone payload: ${path}`);
      }
    }
  }
  visit(root);
  return result.sort(byteCompare);
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function readSchemaAllowlist(path) {
  const bytes = readFileSync(path);
  if (createHash("sha256").update(bytes).digest("hex") !== SCHEMA_ALLOWLIST_SHA256) {
    fail("standalone Runtime schema allowlist differs from its reviewed bytes");
  }
  const manifest = JSON.parse(bytes.toString("utf8"));
  if (
    !exactKeys(manifest, ["schemaVersion", "schemas"]) ||
    manifest.schemaVersion !== 1 ||
    !Array.isArray(manifest.schemas) ||
    manifest.schemas.length !== 28
  ) {
    fail("standalone Runtime schema allowlist is invalid");
  }
  for (const [index, schema] of manifest.schemas.entries()) {
    if (
      !safePath(schema) ||
      !schema.endsWith(".schema.json") ||
      (index > 0 && byteCompare(manifest.schemas[index - 1], schema) >= 0)
    ) {
      fail(`invalid standalone Runtime schema allowlist entry at index ${index}`);
    }
  }
  return manifest.schemas;
}

function validatePayloadSchemas(root) {
  const schemas = readSchemaAllowlist(join(root, SCHEMA_ALLOWLIST));
  const actual = walkFiles(join(root, "standalone-client/contracts/schemas"), false);
  if (
    actual.length !== schemas.length ||
    actual.some((path, index) => path !== schemas[index])
  ) {
    fail("standalone Runtime schemas differ from the exact client allowlist");
  }
}

function copySchemas(sourceValue, targetValue) {
  const source = absoluteDirectory(sourceValue);
  const target = absoluteDirectory(targetValue);
  const schemas = readSchemaAllowlist(join(source, "runtime-schema-allowlist.json"));
  const targetManifest = join(target, "runtime-schema-allowlist.json");
  if (lstatExists(targetManifest)) fail("standalone schema target already contains an allowlist");
  copyFileSync(join(source, "runtime-schema-allowlist.json"), targetManifest);
  for (const schema of schemas) {
    const sourcePath = join(source, "schemas", schema);
    const sourceMetadata = lstatSync(sourcePath);
    if (!sourceMetadata.isFile() || sourceMetadata.isSymbolicLink()) {
      fail(`standalone schema source is not an ordinary file: ${schema}`);
    }
    const targetPath = join(target, "schemas", schema);
    if (lstatExists(targetPath)) fail(`standalone schema target already exists: ${schema}`);
    mkdirSync(resolve(targetPath, ".."), { recursive: true, mode: 0o700 });
    copyFileSync(sourcePath, targetPath);
  }
}

function requirePayloadIdentity(root, platform, runtimeVersion, nodeVersion, files) {
  const nodeExecutable = PLATFORMS.get(platform);
  if (nodeExecutable === undefined) fail(`unsupported standalone platform: ${platform}`);
  if (!VERSION.test(runtimeVersion) || !VERSION.test(nodeVersion)) fail("invalid Runtime or Node version");
  const required = [
    nodeExecutable,
    ENTRYPOINT,
    "app/package.json",
    "app/package-lock.json",
    "app/node_modules/.package-lock.json",
    "licenses/node/LICENSE",
    "licenses/npm-production-packages.json",
    SCHEMA_ALLOWLIST,
  ];
  for (const path of required) if (!files.includes(path)) fail(`standalone payload is missing ${path}`);
  validatePayloadSchemas(root);

  const bundle = readFileSync(join(root, ENTRYPOINT));
  if (bundle.length === 0 || bundle.length > MAX_BUNDLE_BYTES) {
    fail("standalone Runtime bundle is empty or exceeds its byte limit");
  }
  for (const marker of FORBIDDEN_BUNDLE_MARKERS) {
    if (bundle.includes(marker)) fail(`standalone Runtime bundle contains forbidden server graph: ${marker}`);
  }

  const packageJson = JSON.parse(readFileSync(join(root, "app/package.json"), "utf8"));
  if (packageJson.name !== "agma-runtime" || packageJson.version !== runtimeVersion || packageJson.type !== "module") {
    fail("Runtime package identity does not match packaging inputs");
  }
  validateDependencyInventory(root);
  return nodeExecutable;
}

function normalizeLicense(value) {
  if (typeof value === "string" && value.trim() !== "") return value.trim();
  if (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    typeof value.type === "string" &&
    value.type.trim() !== ""
  ) {
    return value.type.trim();
  }
  if (Array.isArray(value)) {
    const values = value.map(normalizeLicense).filter((entry) => entry !== "UNKNOWN");
    if (values.length > 0) return values.join(" OR ");
  }
  return "UNKNOWN";
}

function expectedPackageName(packagePath) {
  const segments = packagePath.split("/");
  const name = segments.at(-1);
  const scope = segments.at(-2);
  return scope?.startsWith("@") === true ? `${scope}/${name}` : name;
}

function validateDependencyInventory(root) {
  const inventory = JSON.parse(readFileSync(join(root, "licenses/npm-production-packages.json"), "utf8"));
  if (
    !exactKeys(inventory, ["schemaVersion", "packages"]) ||
    inventory.schemaVersion !== 1 ||
    !Array.isArray(inventory.packages) ||
    inventory.packages.length === 0
  ) {
    fail("production dependency inventory is invalid or empty");
  }
  const productionLock = JSON.parse(
    readFileSync(join(root, "app/node_modules/.package-lock.json"), "utf8"),
  );
  const sourceLock = JSON.parse(readFileSync(join(root, "app/package-lock.json"), "utf8"));
  if (
    typeof productionLock.packages !== "object" ||
    productionLock.packages === null ||
    Array.isArray(productionLock.packages) ||
    productionLock.lockfileVersion !== 3 ||
    typeof sourceLock.packages !== "object" ||
    sourceLock.packages === null ||
    Array.isArray(sourceLock.packages) ||
    sourceLock.lockfileVersion !== 3
  ) {
    fail("Runtime lock files have no supported package inventory");
  }
  const packagePaths = Object.keys(productionLock.packages)
    .filter((path) => path.startsWith("node_modules/"))
    .map((path) => path.slice("node_modules/".length))
    .sort(byteCompare);
  if (packagePaths.length === 0 || packagePaths.length !== inventory.packages.length) {
    fail("production dependency inventory does not cover installed packages");
  }

  const expectedLicenses = [];
  const licenseName = /^(?:licen[cs]e|notice|copying)(?:$|[._ -])/iu;
  for (const [index, entry] of inventory.packages.entries()) {
    const packagePath = packagePaths[index];
    const packageKey = `node_modules/${packagePath}`;
    const installedLockEntry = productionLock.packages[packageKey];
    const sourceLockEntry = sourceLock.packages[packageKey];
    if (
      !exactKeys(entry, ["path", "name", "version", "license"]) ||
      entry.path !== packagePath ||
      !safePath(packagePath) ||
      Buffer.byteLength(packagePath, "utf8") > 180 ||
      entry.name !== expectedPackageName(packagePath) ||
      typeof entry.version !== "string" ||
      !VERSION.test(entry.version) ||
      typeof entry.license !== "string" ||
      entry.license.length === 0 ||
      entry.license.length > 512 ||
      entry.license === "UNKNOWN" ||
      typeof installedLockEntry !== "object" ||
      installedLockEntry === null ||
      installedLockEntry.dev === true ||
      typeof sourceLockEntry !== "object" ||
      sourceLockEntry === null ||
      installedLockEntry.version !== entry.version ||
      sourceLockEntry.version !== entry.version ||
      (sourceLockEntry.integrity !== undefined &&
        installedLockEntry.integrity !== sourceLockEntry.integrity) ||
      (index > 0 && byteCompare(inventory.packages[index - 1].path, packagePath) >= 0)
    ) {
      fail(`invalid production dependency inventory entry at index ${index}`);
    }
    const packageRoot = join(root, "app/node_modules", packagePath);
    const packageJson = JSON.parse(readFileSync(join(packageRoot, "package.json"), "utf8"));
    if (
      packageJson.name !== entry.name ||
      packageJson.version !== entry.version ||
      normalizeLicense(packageJson.license) !== entry.license
    ) {
      fail(`dependency metadata differs from inventory: ${packagePath}`);
    }
    for (const source of readdirSync(packageRoot, { withFileTypes: true }).filter((item) => licenseName.test(item.name))) {
      const sourcePath = join(packageRoot, source.name);
      const targetPath = join(root, "licenses/npm", packagePath, source.name);
      if (
        !source.isFile() ||
        lstatSync(sourcePath).isSymbolicLink() ||
        !lstatSync(targetPath).isFile() ||
        lstatSync(targetPath).isSymbolicLink() ||
        !readFileSync(sourcePath).equals(readFileSync(targetPath))
      ) {
        fail(`dependency license copy is invalid: ${packagePath}/${source.name}`);
      }
      expectedLicenses.push(`licenses/npm/${packagePath}/${source.name}`);
    }
  }
  const licenseRoot = join(root, "licenses/npm");
  const actualLicenses = walkFiles(licenseRoot, false)
    .map((path) => `licenses/npm/${path}`)
    .sort(byteCompare);
  expectedLicenses.sort(byteCompare);
  if (
    actualLicenses.length !== expectedLicenses.length ||
    actualLicenses.some((path, index) => path !== expectedLicenses[index])
  ) {
    fail("copied dependency licenses do not exactly match installed packages");
  }
}

function writeManifest(root, platform, runtimeVersion, nodeVersion) {
  const manifestPath = join(root, MANIFEST);
  if (lstatExists(manifestPath)) fail("payload already contains a sidecar manifest");
  const paths = walkFiles(root);
  const nodeExecutable = requirePayloadIdentity(root, platform, runtimeVersion, nodeVersion, paths);
  const files = paths.map((path) => {
    const absolute = join(root, path);
    return {
      path,
      size: lstatSync(absolute).size,
      sha256: sha256(absolute),
      executable: path === nodeExecutable,
    };
  });
  const manifest = {
    schemaVersion: 1,
    product: PRODUCT,
    runtimeVersion,
    nodeVersion,
    platform,
    nodeExecutable,
    entrypoint: ENTRYPOINT,
    files,
  };
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx", mode: 0o600 });
  verifyPayload(root, platform, runtimeVersion, nodeVersion);
}

function verifyPayload(root, expectedPlatform, expectedRuntimeVersion, expectedNodeVersion) {
  const manifestPath = join(root, MANIFEST);
  const manifestBytes = readFileSync(manifestPath);
  if (manifestBytes.length === 0 || manifestBytes.length > MAX_MANIFEST_BYTES) {
    fail("standalone sidecar manifest exceeds its byte limit");
  }
  const manifest = JSON.parse(manifestBytes.toString("utf8"));
  if (!manifestBytes.equals(Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, "utf8"))) {
    fail("standalone sidecar manifest is not canonical JSON");
  }
  if (
    !exactKeys(manifest, [
      "schemaVersion",
      "product",
      "runtimeVersion",
      "nodeVersion",
      "platform",
      "nodeExecutable",
      "entrypoint",
      "files",
    ]) ||
    manifest.schemaVersion !== 1 ||
    manifest.product !== PRODUCT ||
    manifest.platform !== expectedPlatform ||
    manifest.runtimeVersion !== expectedRuntimeVersion ||
    manifest.nodeVersion !== expectedNodeVersion ||
    manifest.nodeExecutable !== PLATFORMS.get(expectedPlatform) ||
    manifest.entrypoint !== ENTRYPOINT ||
    !Array.isArray(manifest.files) ||
    manifest.files.length === 0 ||
    manifest.files.length > 16384
  ) {
    fail("standalone sidecar manifest is invalid");
  }
  const actual = walkFiles(root).filter((path) => path !== MANIFEST);
  requirePayloadIdentity(root, expectedPlatform, expectedRuntimeVersion, expectedNodeVersion, actual);
  if (actual.length !== manifest.files.length) fail("manifest does not cover the exact payload file set");
  const folded = new Set();
  let expandedBytes = 0;
  for (const [index, entry] of manifest.files.entries()) {
    if (
      !exactKeys(entry, ["path", "size", "sha256", "executable"]) ||
      !safePath(entry.path) ||
      forbiddenPayloadPath(entry.path) ||
      entry.path !== actual[index] ||
      !Number.isSafeInteger(entry.size) ||
      entry.size < 0 ||
      entry.size > MAX_FILE_BYTES ||
      typeof entry.sha256 !== "string" ||
      !SHA256.test(entry.sha256) ||
      typeof entry.executable !== "boolean" ||
      entry.executable !== (entry.path === manifest.nodeExecutable) ||
      folded.has(entry.path.toLowerCase())
    ) {
      fail(`invalid standalone manifest file at index ${index}`);
    }
    expandedBytes += entry.size;
    if (!Number.isSafeInteger(expandedBytes) || expandedBytes > MAX_EXPANDED_BYTES) {
      fail("standalone payload exceeds its expanded byte limit");
    }
    folded.add(entry.path.toLowerCase());
    const absolute = join(root, entry.path);
    const metadata = lstatSync(absolute);
    if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size !== entry.size || sha256(absolute) !== entry.sha256) {
      fail(`standalone payload differs from manifest: ${entry.path}`);
    }
  }
}

function verifyEntryList(path) {
  const entries = readFileSync(path, "utf8").split("\n").filter(Boolean);
  if (entries.length === 0 || entries.length > 32770) fail("standalone archive entry list is invalid");
  const folded = new Set();
  for (const raw of entries) {
    const directory = raw.endsWith("/");
    const entry = directory ? raw.slice(0, -1) : raw;
    if (!safePath(entry)) fail(`unsafe standalone archive entry: ${raw}`);
    const key = entry.toLowerCase();
    if (folded.has(key)) fail(`duplicate standalone archive entry: ${raw}`);
    folded.add(key);
  }
  if (!folded.has(MANIFEST)) fail("standalone archive has no manifest");
}

function verifyDistributions(path) {
  const manifest = JSON.parse(readFileSync(path, "utf8"));
  if (
    !exactKeys(manifest, ["schemaVersion", "nodeVersion", "checksumSource", "distributions"]) ||
    manifest.schemaVersion !== 1 ||
    manifest.nodeVersion !== "22.23.1" ||
    manifest.checksumSource !== "https://nodejs.org/dist/v22.23.1/SHASUMS256.txt" ||
    !Array.isArray(manifest.distributions) ||
    manifest.distributions.length !== 2
  ) {
    fail("standalone Node distribution manifest is invalid");
  }
  const expected = new Map([
    [
      "linux-x86_64",
      {
        archive: "node-v22.23.1-linux-x64.tar.xz",
        archiveType: "tar.xz",
        rootDirectory: "node-v22.23.1-linux-x64",
        url: "https://nodejs.org/dist/v22.23.1/node-v22.23.1-linux-x64.tar.xz",
        sha256: "9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578",
      },
    ],
    [
      "windows-x86_64",
      {
        archive: "node-v22.23.1-win-x64.zip",
        archiveType: "zip",
        rootDirectory: "node-v22.23.1-win-x64",
        url: "https://nodejs.org/dist/v22.23.1/node-v22.23.1-win-x64.zip",
        sha256: "7df0bc9375723f4a86b3aa1b7cc73342423d9677a8df4538aca31a049e309c29",
      },
    ],
  ]);
  const seen = new Set();
  for (const entry of manifest.distributions) {
    if (!exactKeys(entry, ["platform", "archive", "archiveType", "rootDirectory", "url", "sha256"])) {
      fail("standalone Node distribution entry has unknown fields");
    }
    const wanted = expected.get(entry.platform);
    if (wanted === undefined || seen.has(entry.platform)) fail("standalone Node platform is duplicated or unknown");
    for (const [field, value] of Object.entries(wanted)) {
      if (entry[field] !== value) fail(`standalone Node pin differs for ${entry.platform}:${field}`);
    }
    seen.add(entry.platform);
  }
  if (seen.size !== expected.size) fail("standalone Node platform pin is missing");
}

function distribution(path, platform) {
  verifyDistributions(path);
  const manifest = JSON.parse(readFileSync(path, "utf8"));
  const entry = manifest.distributions.find((value) => value.platform === platform);
  if (entry === undefined) fail(`unsupported standalone Node platform: ${platform}`);
  for (const value of [
    manifest.nodeVersion,
    entry.archive,
    entry.archiveType,
    entry.rootDirectory,
    entry.url,
    entry.sha256,
  ]) {
    process.stdout.write(`${value}\n`);
  }
}

function writeInventory(appRootValue, licensesRootValue) {
  const appRoot = absoluteDirectory(appRootValue);
  const licensesRoot = absoluteDirectory(licensesRootValue);
  const modulesRoot = join(appRoot, "node_modules");
  const productionLock = JSON.parse(readFileSync(join(modulesRoot, ".package-lock.json"), "utf8"));
  if (
    typeof productionLock.packages !== "object" ||
    productionLock.packages === null ||
    Array.isArray(productionLock.packages)
  ) {
    fail("installed production lock has no package inventory");
  }
  const packageKeys = Object.keys(productionLock.packages)
    .filter((path) => path.startsWith("node_modules/"))
    .sort(byteCompare);
  if (packageKeys.length === 0) fail("installed production dependency inventory is empty");
  for (const relativePath of walkFiles(modulesRoot, false)) {
    if (relativePath.endsWith(".node")) fail(`native dependency is not cross-platform: ${relativePath}`);
  }

  const inventory = [];
  for (const packageKey of packageKeys) {
    const lockEntry = productionLock.packages[packageKey];
    if (
      lockEntry?.dev === true ||
      lockEntry?.hasInstallScript === true ||
      lockEntry?.os !== undefined ||
      lockEntry?.cpu !== undefined ||
      lockEntry?.libc !== undefined
    ) {
      fail(`platform-specific production dependency is forbidden: ${packageKey}`);
    }
    const packagePath = packageKey.slice("node_modules/".length);
    if (!safePath(packagePath) || Buffer.byteLength(packagePath, "utf8") > 180) {
      fail(`unsafe production package path: ${packagePath}`);
    }
    const packageRoot = join(appRoot, packageKey);
    const packageJson = JSON.parse(readFileSync(join(packageRoot, "package.json"), "utf8"));
    if (
      packageJson.name !== expectedPackageName(packagePath) ||
      typeof packageJson.version !== "string" ||
      !VERSION.test(packageJson.version) ||
      lockEntry.version !== packageJson.version ||
      normalizeLicense(packageJson.license) === "UNKNOWN"
    ) {
      fail(`invalid production package metadata: ${packagePath}`);
    }
    inventory.push({
      path: packagePath,
      name: packageJson.name,
      version: packageJson.version,
      license: normalizeLicense(packageJson.license),
    });
    const licenseName = /^(?:licen[cs]e|notice|copying)(?:$|[._ -])/iu;
    for (const source of readdirSync(packageRoot, { withFileTypes: true }).filter((item) => licenseName.test(item.name))) {
      const sourcePath = join(packageRoot, source.name);
      if (!source.isFile() || lstatSync(sourcePath).isSymbolicLink()) {
        fail(`dependency license source is not an ordinary file: ${packagePath}/${source.name}`);
      }
      const targetDirectory = join(licensesRoot, "npm", packagePath);
      mkdirSync(targetDirectory, { recursive: true, mode: 0o700 });
      copyFileSync(sourcePath, join(targetDirectory, source.name), 0);
    }
  }
  writeFileSync(
    join(licensesRoot, "npm-production-packages.json"),
    `${JSON.stringify({ schemaVersion: 1, packages: inventory }, null, 2)}\n`,
    { flag: "wx", mode: 0o600 },
  );
  validateDependencyInventory(resolve(appRoot, ".."));
}

function lstatExists(path) {
  try {
    lstatSync(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

function absoluteDirectory(value) {
  const root = resolve(value);
  const metadata = lstatSync(root);
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) fail("payload root is not an ordinary directory");
  return root;
}

const [command, ...arguments_] = process.argv.slice(2);
if (command === "write-manifest" && arguments_.length === 4) {
  writeManifest(absoluteDirectory(arguments_[0]), arguments_[1], arguments_[2], arguments_[3]);
} else if (command === "verify-payload" && arguments_.length === 4) {
  verifyPayload(absoluteDirectory(arguments_[0]), arguments_[1], arguments_[2], arguments_[3]);
} else if (command === "verify-entry-list" && arguments_.length === 1) {
  verifyEntryList(resolve(arguments_[0]));
} else if (command === "verify-distributions" && arguments_.length === 1) {
  verifyDistributions(resolve(arguments_[0]));
} else if (command === "print-distribution" && arguments_.length === 2) {
  distribution(resolve(arguments_[0]), arguments_[1]);
} else if (command === "write-inventory" && arguments_.length === 2) {
  writeInventory(arguments_[0], arguments_[1]);
} else if (command === "copy-schemas" && arguments_.length === 2) {
  copySchemas(arguments_[0], arguments_[1]);
} else {
  fail("usage: standalone-sidecar.mjs <write-manifest|verify-payload|verify-entry-list|verify-distributions|print-distribution|write-inventory|copy-schemas> ...");
}
