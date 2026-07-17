package dev.minecraftagent.standalone.supervisor;

import java.util.concurrent.CompletionStage;

/** The exact process handle returned by AGMA's launcher; implementations never search by name. */
public interface OwnedRuntimeProcess {
  long pid();

  boolean isAlive();

  CompletionStage<Integer> onExit();

  void requestStop();

  void forceStop();
}
