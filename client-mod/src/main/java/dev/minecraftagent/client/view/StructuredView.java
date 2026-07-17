package dev.minecraftagent.client.view;

import java.util.Objects;
import java.util.UUID;

public record StructuredView(
    String viewSchemaVersion,
    UUID viewId,
    UUID requestId,
    ViewType viewType,
    int revision,
    String title,
    String fallbackText,
    boolean pinnable,
    ViewContent content) {

  public StructuredView {
    Objects.requireNonNull(viewSchemaVersion, "viewSchemaVersion");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(viewType, "viewType");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(fallbackText, "fallbackText");
    Objects.requireNonNull(content, "content");
    if (!contentMatches(viewType, content)) {
      throw new IllegalArgumentException("View content does not match view type");
    }
  }

  private static boolean contentMatches(ViewType type, ViewContent content) {
    return switch (type) {
      case TEXT -> content instanceof TextView;
      case ITEM_STACK -> content instanceof ItemStackView;
      case ITEM_LIST -> content instanceof ItemListView;
      case RECIPE -> content instanceof RecipeView;
      case BUILD_PREVIEW -> content instanceof BuildPreviewView;
    };
  }
}
