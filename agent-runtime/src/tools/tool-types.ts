export const coreToolIds = [
  "player.context.read",
  "player.held_item.read",
  "server.info.read",
  "server.plugins.list",
  "server.recipe.lookup",
  "server.recipe.uses",
  "landmark.search",
  "build.preview.create",
  "server.docs.search",
  "project.list",
  "project.read",
  "project.create",
  "project.update",
] as const;

export type CoreToolId = (typeof coreToolIds)[number];

export type ToolResultStatus = "succeeded" | "rejected" | "failed";
export type ToolResultSource =
  | "paper_api"
  | "paper_policy"
  | "server_registry"
  | "plugin_provider"
  | "server_docs"
  | "web_documentation"
  | "model_knowledge"
  | "capability"
  | "runtime_storage";
export type ToolResultTrust = "authoritative" | "verified" | "untrusted";

export interface ToolResultError {
  readonly code: string;
  readonly message: string;
  readonly retryable: boolean;
}

export interface ToolExecutionResult {
  readonly status: ToolResultStatus;
  readonly source: ToolResultSource;
  readonly trust: ToolResultTrust;
  readonly result: Readonly<Record<string, unknown>> | null;
  readonly error: ToolResultError | null;
}

export interface ToolResultPayload extends ToolExecutionResult {
  readonly toolCallId: string;
  readonly sessionId: string;
  readonly playerUuid: string;
  readonly tool: string;
  readonly sequence: number;
}

export interface ToolCallPayload {
  readonly toolCallId: string;
  readonly sessionId: string;
  readonly playerUuid: string;
  readonly module: string;
  readonly tool: CoreToolId;
  readonly arguments: Readonly<Record<string, unknown>>;
  readonly sequence: number;
}
