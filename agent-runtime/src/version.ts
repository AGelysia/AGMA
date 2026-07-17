import { createRequire } from "node:module";

import { z } from "zod";

export const SUPPORTED_PROTOCOL_VERSION = "1.0" as const;

const require = createRequire(import.meta.url);
const packageJson: unknown = require("../package.json");

const packageIdentitySchema = z.object({
  name: z.literal("agma-runtime"),
  version: z.string().regex(/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u),
});

const packageIdentity = packageIdentitySchema.parse(packageJson);

export const runtimeIdentity = Object.freeze({
  ...packageIdentity,
  protocolVersion: SUPPORTED_PROTOCOL_VERSION,
});

export type RuntimeIdentity = typeof runtimeIdentity;
