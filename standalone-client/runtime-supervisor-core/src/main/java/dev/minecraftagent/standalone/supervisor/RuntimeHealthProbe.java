package dev.minecraftagent.standalone.supervisor;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RuntimeHealthProbe {
  CompletionStage<Boolean> isReady(OwnedRuntimeProcess process, RuntimeLaunchSpec spec);
}
