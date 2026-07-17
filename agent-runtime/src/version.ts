import { z } from "zod";

import packageJson from "../package.json" with { type: "json" };

export const SUPPORTED_PROTOCOL_VERSION = "1.0" as const;

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
