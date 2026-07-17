package dev.minecraftagent.paper.tool;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ReadToolExecutor {
  CompletionStage<ReadToolResult> execute(ReadToolCall call);
}
