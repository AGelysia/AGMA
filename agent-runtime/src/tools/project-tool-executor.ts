import type {
  ProjectPlan,
  ProjectRepository,
  StoredProject,
} from "../storage/project-repository.js";
import type { StandaloneKnowledgeIndex } from "../standalone/knowledge/knowledge-index.js";
import type {
  LocalToolCall,
  LocalToolDescriptor,
  LocalToolExecution,
} from "./local-tool-executor.js";
import type { ToolExecutionResult } from "./tool-types.js";

function stringArgument(argumentsValue: Readonly<Record<string, unknown>>, field: string): string {
  const value = argumentsValue[field];
  if (typeof value !== "string") {
    throw new TypeError(`Local Tool ${field} is invalid.`);
  }
  return value;
}

function integerArgument(argumentsValue: Readonly<Record<string, unknown>>, field: string): number {
  const value = argumentsValue[field];
  if (!Number.isSafeInteger(value)) {
    throw new TypeError(`Local Tool ${field} is invalid.`);
  }
  return Number(value);
}

function stringListArgument(
  argumentsValue: Readonly<Record<string, unknown>>,
  field: string,
): readonly string[] {
  const value = argumentsValue[field];
  if (!Array.isArray(value) || !value.every((entry) => typeof entry === "string")) {
    throw new TypeError(`Local Tool ${field} is invalid.`);
  }
  return value;
}

function projectPlan(argumentsValue: Readonly<Record<string, unknown>>): ProjectPlan {
  return {
    name: stringArgument(argumentsValue, "name"),
    summary: stringArgument(argumentsValue, "summary"),
    goals: stringListArgument(argumentsValue, "goals"),
    constraints: stringListArgument(argumentsValue, "constraints"),
  };
}

function projectRecord(project: StoredProject): Readonly<Record<string, unknown>> {
  return {
    projectId: project.projectId,
    name: project.name,
    summary: project.summary,
    goals: [...project.goals],
    constraints: [...project.constraints],
    status: project.status,
    revision: project.revision,
    createdAt: project.createdAt,
    updatedAt: project.updatedAt,
  };
}

function success(
  descriptor: LocalToolDescriptor,
  result: Readonly<Record<string, unknown>>,
): ToolExecutionResult {
  return {
    status: "succeeded",
    source: descriptor.source,
    trust: descriptor.trust,
    result,
    error: null,
  };
}

function failure(descriptor: LocalToolDescriptor): ToolExecutionResult {
  return {
    status: "failed",
    source: descriptor.source,
    trust: descriptor.trust,
    result: null,
    error: {
      code:
        descriptor.id === "local.knowledge.search"
          ? "KNOWLEDGE_SEARCH_FAILED"
          : "PROJECT_STORAGE_FAILED",
      message: "The Runtime could not complete the bounded local tool operation.",
      retryable: false,
    },
  };
}

/**
 * Executes only the client-side project storage Tools in-Runtime, plus the client-local knowledge
 * search when a knowledge index is configured. Unlike the Paper-line local executor it has no
 * server documentation or Paper capabilities, so the standalone bundle stays free of server
 * capabilities (the standalone build graph forbids them; the knowledge index here is the
 * standalone-local one, not the server-line knowledge module).
 */
export class ProjectToolExecutor implements LocalToolExecution {
  readonly #projects: ProjectRepository;
  readonly #knowledge: StandaloneKnowledgeIndex | undefined;

  public constructor(projects: ProjectRepository, knowledge?: StandaloneKnowledgeIndex) {
    this.#projects = projects;
    this.#knowledge = knowledge;
  }

  public execute(call: LocalToolCall): Promise<ToolExecutionResult> {
    if (call.descriptor.execution !== "runtime_local") {
      return Promise.reject(
        new TypeError("A client connector Tool cannot execute in the Runtime."),
      );
    }
    if (call.signal.aborted) {
      return Promise.reject(call.signal.reason);
    }
    try {
      const result = this.#executeBounded(call);
      if (call.signal.aborted) {
        return Promise.reject(call.signal.reason);
      }
      return Promise.resolve(result);
    } catch (error) {
      if (error instanceof TypeError) {
        return Promise.reject(error);
      }
      return Promise.resolve(failure(call.descriptor));
    }
  }

  #executeBounded(call: LocalToolCall): ToolExecutionResult {
    const owner = { serverId: call.serverId, playerUuid: call.playerUuid };
    switch (call.descriptor.id) {
      case "local.knowledge.search": {
        if (this.#knowledge === undefined) {
          throw new TypeError("The Runtime local Tool is not registered.");
        }
        const result = this.#knowledge.search(stringArgument(call.arguments, "query"));
        return success(call.descriptor, {
          query: result.query,
          matches: result.matches.map((match) => ({ ...match })),
          truncated: result.truncated,
        });
      }
      case "project.list": {
        const result = this.#projects.listOwned(owner);
        return success(call.descriptor, {
          projects: result.projects.map((project) => ({ ...project })),
          truncated: result.truncated,
        });
      }
      case "project.read": {
        const project = this.#projects.findOwned(
          stringArgument(call.arguments, "projectId"),
          owner,
        );
        return success(call.descriptor, {
          project: project === undefined ? null : projectRecord(project),
        });
      }
      case "project.create": {
        const result = this.#projects.create({
          ...owner,
          requestId: call.requestId,
          toolCallId: call.toolCallId,
          timestamp: new Date(call.now).toISOString(),
          plan: projectPlan(call.arguments),
        });
        return success(call.descriptor, {
          outcome: result.outcome,
          project: result.project === null ? null : projectRecord(result.project),
        });
      }
      case "project.update": {
        const result = this.#projects.update({
          ...owner,
          requestId: call.requestId,
          toolCallId: call.toolCallId,
          timestamp: new Date(call.now).toISOString(),
          projectId: stringArgument(call.arguments, "projectId"),
          expectedRevision: integerArgument(call.arguments, "expectedRevision"),
          plan: projectPlan(call.arguments),
        });
        return success(call.descriptor, {
          outcome: result.outcome,
          project: result.project === null ? null : projectRecord(result.project),
        });
      }
      default:
        throw new TypeError("The Runtime local Tool is not registered.");
    }
  }
}
