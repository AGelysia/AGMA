package dev.minecraftagent.client.view;

import java.util.Objects;

public record TextView(String text) implements ViewContent {
  public TextView {
    Objects.requireNonNull(text, "text");
  }
}
