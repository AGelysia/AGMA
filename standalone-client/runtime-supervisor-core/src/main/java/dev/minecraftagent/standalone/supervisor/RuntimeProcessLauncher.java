package dev.minecraftagent.standalone.supervisor;

import java.io.IOException;

@FunctionalInterface
public interface RuntimeProcessLauncher {
  OwnedRuntimeProcess start(RuntimeLaunchSpec spec) throws IOException;
}
