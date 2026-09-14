package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Objects;

/**
 * One tag file read from a mod archive ({@code data/<namespace>/tags/(items|fluids)/<path>.json}).
 * Values keep their raw form: an {@link Entry#id()} starting with {@code '#'} references another
 * tag of the same kind, and entries flagged {@code required: false} are preserved so the caller can
 * decide to drop them during expansion.
 */
public record UnpackedTag(Kind kind, String id, boolean replace, List<Entry> values) {
  public UnpackedTag {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
    values = List.copyOf(Objects.requireNonNull(values, "values"));
  }

  public enum Kind {
    ITEM,
    FLUID
  }

  public record Entry(String id, boolean required) {
    public Entry {
      Objects.requireNonNull(id, "id");
    }

    public boolean tagReference() {
      return id.startsWith("#");
    }
  }
}
