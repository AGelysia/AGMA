package dev.minecraftagent.paper.runtime;

import java.io.IOException;

@FunctionalInterface
public interface ProcessFactory {
  Process start(ProcessBuilder builder) throws IOException;

  static ProcessFactory system() {
    return ProcessBuilder::start;
  }
}
