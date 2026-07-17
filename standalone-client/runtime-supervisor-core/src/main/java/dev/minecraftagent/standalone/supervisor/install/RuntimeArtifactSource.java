package dev.minecraftagent.standalone.supervisor.install;

import java.io.IOException;
import java.io.InputStream;

/** Opens a new stream for an embedded or otherwise locally supplied sidecar artifact. */
@FunctionalInterface
public interface RuntimeArtifactSource {
  InputStream open() throws IOException;
}
