package dev.minecraftagent.client.view;

/** A closed set of client-owned view models. */
public sealed interface ViewContent
    permits TextView, ItemStackView, ItemListView, RecipeView, BuildPreviewView {}
