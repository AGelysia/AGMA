package dev.minecraftagent.standalone.supervisor.install;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test subprocess that holds the same operating-system lock used by the installer. */
public final class CrossProcessLockHolder {
  private CrossProcessLockHolder() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2) {
      throw new IllegalArgumentException("expected lock and ready paths");
    }
    try (var channel = FileChannel.open(Path.of(arguments[0]), WRITE);
        var ignored = channel.lock()) {
      Files.writeString(
          Path.of(arguments[1]), "locked\n", StandardCharsets.US_ASCII, CREATE_NEW, WRITE);
      System.in.read();
    }
  }
}
