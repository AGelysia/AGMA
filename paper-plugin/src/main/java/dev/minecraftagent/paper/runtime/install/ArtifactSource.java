package dev.minecraftagent.paper.runtime.install;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ArtifactSource {
  InputStream open() throws IOException;
}
