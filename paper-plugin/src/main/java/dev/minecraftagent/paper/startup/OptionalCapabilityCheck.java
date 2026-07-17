package dev.minecraftagent.paper.startup;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public final class OptionalCapabilityCheck {
  private static final StartupWarning UNAVAILABLE =
      new StartupWarning(
          StartupWarning.Code.OPTIONAL_CAPABILITY_UNAVAILABLE,
          StartupWarning.Stage.OPTIONAL_CAPABILITIES);

  public List<StartupWarning> inspect(Path dataDirectory, Path capabilityDirectory) {
    try {
      var root = dataDirectory.toAbsolutePath().normalize();
      var target = capabilityDirectory.toAbsolutePath().normalize();
      if (target.equals(root) || !target.startsWith(root)) {
        return List.of(UNAVAILABLE);
      }
      if (!isDirectoryWithoutSymlink(root)) {
        return List.of(UNAVAILABLE);
      }
      var current = root;
      for (var segment : root.relativize(target)) {
        current = current.resolve(segment);
        if (!isDirectoryWithoutSymlink(current)) {
          return List.of(UNAVAILABLE);
        }
      }
      try (var ignored = Files.newDirectoryStream(target)) {
        return List.of();
      }
    } catch (IOException | SecurityException exception) {
      return List.of(UNAVAILABLE);
    }
  }

  private static boolean isDirectoryWithoutSymlink(Path path) throws IOException {
    if (!Files.exists(path, NOFOLLOW_LINKS)) {
      return false;
    }
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    return attributes.isDirectory() && !attributes.isSymbolicLink();
  }
}
