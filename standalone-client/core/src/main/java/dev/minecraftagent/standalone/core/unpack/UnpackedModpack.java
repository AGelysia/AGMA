package dev.minecraftagent.standalone.core.unpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of scanning a mods directory: every archive that declared mod metadata plus the scan
 * report. Tags and translations are also exposed as merged views across all mods because datapack
 * style content is defined by the union of all archives (the shared {@code c} namespace only works
 * when every jar contributes).
 */
public record UnpackedModpack(List<UnpackedMod> mods, ScanReport report) {
  public UnpackedModpack {
    mods = List.copyOf(Objects.requireNonNull(mods, "mods"));
    Objects.requireNonNull(report, "report");
  }

  public static UnpackedModpack empty() {
    return new UnpackedModpack(List.of(), ScanReport.empty());
  }

  /**
   * Merges the tags of one kind across all mods in scan order. A tag file marked {@code replace}
   * discards the values accumulated from earlier archives; otherwise values append. Duplicate
   * entries are removed while preserving first-seen order.
   */
  public Map<String, List<UnpackedTag.Entry>> mergedTags(UnpackedTag.Kind kind) {
    Objects.requireNonNull(kind, "kind");
    var merged = new LinkedHashMap<String, List<UnpackedTag.Entry>>();
    for (var mod : mods) {
      for (var tag : mod.tags()) {
        if (tag.kind() != kind) {
          continue;
        }
        var values = tag.replace() ? new ArrayList<UnpackedTag.Entry>() : merged.get(tag.id());
        if (values == null) {
          values = new ArrayList<>();
        }
        for (var entry : tag.values()) {
          if (!values.contains(entry)) {
            values.add(entry);
          }
        }
        merged.put(tag.id(), values);
      }
    }
    var immutable = new LinkedHashMap<String, List<UnpackedTag.Entry>>();
    merged.forEach((id, values) -> immutable.put(id, List.copyOf(values)));
    return Collections.unmodifiableMap(immutable);
  }

  /**
   * Merges one locale across all mods in scan order. Earlier archives win on key conflicts so the
   * result is deterministic for a deterministic scan order.
   */
  public Map<String, String> mergedLang(String locale) {
    Objects.requireNonNull(locale, "locale");
    var merged = new LinkedHashMap<String, String>();
    for (var mod : mods) {
      var translations = mod.lang().get(locale);
      if (translations != null) {
        translations.forEach(merged::putIfAbsent);
      }
    }
    return Collections.unmodifiableMap(merged);
  }
}
