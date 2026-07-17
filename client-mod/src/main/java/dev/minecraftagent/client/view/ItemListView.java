package dev.minecraftagent.client.view;

import java.util.List;
import java.util.Objects;

public record ItemListView(List<ItemStackView> items) implements ViewContent {
  public ItemListView {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
  }
}
