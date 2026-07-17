package dev.minecraftagent.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OverlayPreferenceStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void atomicallyRoundTripsClosedPreferences() throws Exception {
    OverlayPreferenceStore store = new OverlayPreferenceStore(temporaryDirectory);
    OverlayPreferences expected = new OverlayPreferences(40, 50, 360, 240, true);
    store.save(expected);

    OverlayPreferenceStore.LoadResult result = store.load();
    assertEquals(OverlayPreferenceStore.LoadStatus.LOADED, result.status());
    assertEquals(expected, result.preferences());
    try (var files = Files.list(store.path().getParent())) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  @Test
  void rejectsUnknownDuplicateAndOversizedConfiguration() throws Exception {
    OverlayPreferenceStore store = new OverlayPreferenceStore(temporaryDirectory);
    Files.createDirectories(store.path().getParent());
    Files.writeString(
        store.path(),
        "{\"version\":1,\"x\":1,\"y\":1,\"width\":320,\"height\":200,\"pinned\":false,\"extra\":1}",
        StandardCharsets.UTF_8);
    assertEquals(OverlayPreferenceStore.LoadStatus.INVALID, store.load().status());

    Files.writeString(
        store.path(),
        "{\"version\":1,\"x\":1,\"x\":2,\"y\":1,\"width\":320,\"height\":200,\"pinned\":false}",
        StandardCharsets.UTF_8);
    assertEquals(OverlayPreferenceStore.LoadStatus.INVALID, store.load().status());

    Files.write(store.path(), new byte[OverlayPreferenceStore.MAX_FILE_BYTES + 1]);
    assertEquals(OverlayPreferenceStore.LoadStatus.INVALID, store.load().status());
  }

  @Test
  void rejectsSymlinkTargetWhenSupported() throws Exception {
    OverlayPreferenceStore store = new OverlayPreferenceStore(temporaryDirectory);
    Files.createDirectories(store.path().getParent());
    Path outside = temporaryDirectory.resolve("outside.json");
    Files.writeString(outside, "{}", StandardCharsets.UTF_8);
    try {
      Files.createSymbolicLink(store.path(), outside);
    } catch (UnsupportedOperationException exception) {
      return;
    }
    assertEquals(OverlayPreferenceStore.LoadStatus.UNSAFE_PATH, store.load().status());
  }
}
