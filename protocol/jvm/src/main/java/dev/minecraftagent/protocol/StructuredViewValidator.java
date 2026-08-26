package dev.minecraftagent.protocol;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Closed structural validation for a structured view.
 *
 * <p>This is the single implementation of {@code structured-view.schema.json} and the content
 * schemas it delegates to. The Paper plugin validates a view through it before the view is offered
 * to a client, and the Fabric client mod checks the same shapes and bounds while it assembles its
 * renderable models.
 *
 * <p>The validator owns structure only. Which view types an end is willing to produce or render,
 * visibility policy for the outer title and fallback text, and how validated content is turned into
 * client side objects stay with each end.
 */
public final class StructuredViewValidator {
  /** A structurally valid structured view, with its content still as a raw tree. */
  public record ValidatedView(
      String viewSchemaVersion,
      UUID viewId,
      UUID requestId,
      String viewType,
      int revision,
      String title,
      String fallbackText,
      boolean pinnable,
      WireJson.ObjectNode content) {}

  private final String invalidCode;
  private final String schemaVersionCode;
  private final String viewTypeCode;

  /**
   * @param invalidCode reported when structure, a bound or a cross-field rule fails
   * @param schemaVersionCode reported when {@code viewSchemaVersion} is not the schema constant
   * @param viewTypeCode reported when {@code viewType} is not in the schema enum
   */
  public StructuredViewValidator(
      String invalidCode, String schemaVersionCode, String viewTypeCode) {
    this.invalidCode = invalidCode;
    this.schemaVersionCode = schemaVersionCode;
    this.viewTypeCode = viewTypeCode;
  }

  /** Validates a whole structured view and returns its decoded metadata. */
  public ValidatedView validate(WireJson.ObjectNode source) {
    requireFields(source, StructuredViewContract.VIEW_FIELDS);
    var viewSchemaVersion = string(source, "viewSchemaVersion", 1, 8);
    var viewId = uuid(source, "viewId");
    var requestId = uuid(source, "requestId");
    var viewType = string(source, "viewType", 1, StructuredViewContract.VIEW_TYPE_MAX_CHARS);
    if (!StructuredViewContract.VIEW_TYPES.contains(viewType)) {
      throw new ProtocolViolationException(viewTypeCode);
    }
    var revision =
        integer(
            source,
            "revision",
            StructuredViewContract.REVISION_MIN,
            StructuredViewContract.REVISION_MAX);
    var title =
        string(
            source,
            "title",
            StructuredViewContract.TITLE_MIN_CHARS,
            StructuredViewContract.TITLE_MAX_CHARS);
    var fallbackText =
        string(
            source,
            "fallbackText",
            StructuredViewContract.FALLBACK_TEXT_MIN_CHARS,
            StructuredViewContract.FALLBACK_TEXT_MAX_CHARS);
    var pinnable = bool(source, "pinnable");
    var content = object(source, "content");
    if (!StructuredViewContract.VIEW_SCHEMA_VERSION.equals(viewSchemaVersion)) {
      throw new ProtocolViolationException(schemaVersionCode);
    }
    validateContent(viewType, content);
    return new ValidatedView(
        viewSchemaVersion,
        viewId,
        requestId,
        viewType,
        revision,
        title,
        fallbackText,
        pinnable,
        content);
  }

  /** Validates only the content object of a view whose outer metadata was validated elsewhere. */
  public void validateContent(String viewType, WireJson.ObjectNode content) {
    switch (viewType) {
      case StructuredViewContract.VIEW_TYPE_TEXT -> text(content);
      case StructuredViewContract.VIEW_TYPE_ITEM_STACK -> itemStack(content);
      case StructuredViewContract.VIEW_TYPE_ITEM_LIST -> itemList(content);
      case StructuredViewContract.VIEW_TYPE_RECIPE -> recipeView(content);
      case StructuredViewContract.VIEW_TYPE_BUILD_PREVIEW -> buildPreview(content);
      case StructuredViewContract.VIEW_TYPE_PROPOSAL -> proposal(content);
      case StructuredViewContract.VIEW_TYPE_SELECTION_LIST -> selectionList(content);
      default -> throw new ProtocolViolationException(viewTypeCode);
    }
  }

  private void text(WireJson.ObjectNode content) {
    requireFields(content, StructuredViewContract.TEXT_CONTENT_FIELDS);
    visibleString(
        content,
        "text",
        StructuredViewContract.TEXT_MIN_CHARS,
        StructuredViewContract.TEXT_MAX_CHARS,
        true);
  }

  private void itemList(WireJson.ObjectNode content) {
    requireFields(content, StructuredViewContract.ITEM_LIST_FIELDS);
    var items =
        array(
            content,
            "items",
            StructuredViewContract.ITEM_LIST_MIN_ITEMS,
            StructuredViewContract.ITEM_LIST_MAX_ITEMS);
    items.forEach(item -> itemStack(asObject(item)));
  }

  private void itemStack(WireJson.ObjectNode item) {
    requireFields(item, StructuredViewContract.ITEM_STACK_FIELDS);
    namespacedId(item, "itemId");
    integer(
        item,
        "count",
        StructuredViewContract.ITEM_COUNT_MIN,
        StructuredViewContract.ITEM_COUNT_MAX);
    components(object(item, "components"));
  }

  private void components(WireJson.ObjectNode components) {
    requireAllowed(components, StructuredViewContract.COMPONENT_FIELDS);
    if (components.has("customName")) {
      visibleString(
          components,
          "customName",
          StructuredViewContract.COMPONENT_TEXT_MIN_CHARS,
          StructuredViewContract.COMPONENT_TEXT_MAX_CHARS,
          false);
    }
    if (components.has("lore")) {
      var lore =
          array(
              components,
              "lore",
              StructuredViewContract.LORE_MIN_ITEMS,
              StructuredViewContract.LORE_MAX_ITEMS);
      lore.forEach(
          line ->
              visibleString(
                  line,
                  StructuredViewContract.COMPONENT_TEXT_MIN_CHARS,
                  StructuredViewContract.COMPONENT_TEXT_MAX_CHARS,
                  false));
    }
    if (components.has("damage")) {
      integer(
          components,
          "damage",
          StructuredViewContract.DAMAGE_MIN,
          StructuredViewContract.DAMAGE_MAX);
    }
    if (components.has("maxDamage")) {
      integer(
          components,
          "maxDamage",
          StructuredViewContract.MAX_DAMAGE_MIN,
          StructuredViewContract.MAX_DAMAGE_MAX);
    }
    if (components.has("customModelData")) {
      integer(
          components,
          "customModelData",
          StructuredViewContract.CUSTOM_MODEL_DATA_MIN,
          StructuredViewContract.CUSTOM_MODEL_DATA_MAX);
    }
    if (components.has("enchantmentGlint")) {
      bool(components, "enchantmentGlint");
    }
    if (components.has("damage")
        && components.has("maxDamage")
        && integer(
                components,
                "damage",
                StructuredViewContract.DAMAGE_MIN,
                StructuredViewContract.DAMAGE_MAX)
            > integer(
                components,
                "maxDamage",
                StructuredViewContract.MAX_DAMAGE_MIN,
                StructuredViewContract.MAX_DAMAGE_MAX)) {
      throw invalid();
    }
  }

  private void recipeView(WireJson.ObjectNode content) {
    if (content.get("schemaVersion") instanceof WireJson.TextNode version
        && StructuredViewContract.RECIPE_SCHEMA_VERSION_V2.equals(version.value())) {
      recipeViewV2(content);
      return;
    }
    requireFields(content, StructuredViewContract.RECIPE_V1_FIELDS);
    requireLiteral(content, "schemaVersion", StructuredViewContract.RECIPE_SCHEMA_VERSION_V1);
    var query = object(content, "query");
    requireFields(query, StructuredViewContract.RECIPE_QUERY_FIELDS);
    requireEnum(query, "mode", StructuredViewContract.RECIPE_QUERY_MODES);
    namespacedId(query, "itemId");
    var selected =
        integer(
            content,
            "selectedRecipe",
            StructuredViewContract.RECIPE_V1_SELECTED_MIN,
            StructuredViewContract.RECIPE_V1_SELECTED_MAX);
    var recipes =
        array(
            content,
            "recipes",
            StructuredViewContract.RECIPE_V1_MIN_ITEMS,
            StructuredViewContract.RECIPE_V1_MAX_ITEMS);
    if (selected >= recipes.size()) {
      throw invalid();
    }
    recipes.forEach(recipe -> recipe(asObject(recipe), false));
  }

  private void recipeViewV2(WireJson.ObjectNode content) {
    requireFields(content, StructuredViewContract.RECIPE_V2_FIELDS);
    requireLiteral(content, "schemaVersion", StructuredViewContract.RECIPE_SCHEMA_VERSION_V2);
    var query = object(content, "query");
    requireFields(query, StructuredViewContract.RECIPE_QUERY_FIELDS);
    requireEnum(query, "mode", StructuredViewContract.RECIPE_QUERY_MODES);
    namespacedId(query, "itemId");
    var recipes =
        array(
            content,
            "recipes",
            StructuredViewContract.RECIPE_V2_MIN_ITEMS,
            StructuredViewContract.RECIPE_V2_MAX_ITEMS);
    var selected =
        integer(
            content,
            "selectedRecipe",
            StructuredViewContract.RECIPE_V2_SELECTED_MIN,
            StructuredViewContract.RECIPE_V2_SELECTED_MAX);
    var totalMatches =
        integer(
            content,
            "totalMatches",
            recipes.size(),
            StructuredViewContract.RECIPE_TOTAL_MATCHES_MAX);
    var truncated = bool(content, "truncated");
    if (selected >= recipes.size() || (!truncated && totalMatches != recipes.size())) {
      throw invalid();
    }
    var ids = new HashSet<String>();
    for (var element : recipes) {
      var recipe = asObject(element);
      recipe(recipe, true);
      if (!(recipe.get("recipeId") instanceof WireJson.TextNode recipeId)
          || !ids.add(recipeId.value())) {
        throw invalid();
      }
    }
  }

  private void recipe(WireJson.ObjectNode recipe, boolean versionTwo) {
    var types =
        versionTwo
            ? StructuredViewContract.RECIPE_V2_TYPES
            : StructuredViewContract.RECIPE_V1_TYPES;
    requireAllowed(recipe, StructuredViewContract.RECIPE_FIELDS);
    requirePresent(recipe, StructuredViewContract.RECIPE_REQUIRED_FIELDS);
    namespacedId(recipe, "recipeId");
    var type = requireEnum(recipe, "recipeType", types);
    if (versionTwo) {
      recipeSourceV2(object(recipe, "source"));
      if (!(recipe.get("result") instanceof WireJson.NullNode)) {
        itemStack(object(recipe, "result"));
      }
    } else {
      source(object(recipe, "source"));
      itemStack(object(recipe, "result"));
    }
    var logicalSlots = versionTwo ? layoutV2(type, object(recipe, "layout")) : Set.<Integer>of();
    if (!versionTwo) {
      layout(object(recipe, "layout"));
    }
    var remainingSlots = new HashSet<Integer>();
    for (var element :
        array(
            recipe,
            "remainingItems",
            StructuredViewContract.REMAINING_ITEMS_MIN_ITEMS,
            StructuredViewContract.REMAINING_ITEMS_MAX_ITEMS)) {
      var slot = remainingItem(element);
      if (versionTwo && !logicalSlots.contains(slot) || !remainingSlots.add(slot)) {
        throw invalid();
      }
    }
    var cooking = StructuredViewContract.RECIPE_COOKING_TYPES.contains(type);
    if (versionTwo) {
      if (cooking != recipe.has("processing")) {
        throw invalid();
      }
      if (cooking) {
        processing(object(recipe, "processing"), true);
      }
      return;
    }
    if (cooking && !recipe.has("processing")) {
      throw invalid();
    }
    if (recipe.has("processing")) {
      processing(object(recipe, "processing"), false);
    }
  }

  private void recipeSourceV2(WireJson.ObjectNode source) {
    requireFields(source, StructuredViewContract.RECIPE_V1_SOURCE_FIELDS);
    var kind = requireEnum(source, "kind", StructuredViewContract.RECIPE_V2_SOURCE_KINDS);
    if (StructuredViewContract.RECIPE_SOURCE_KIND_SERVER_REGISTRY.equals(kind)) {
      if (!(source.get("providerId") instanceof WireJson.NullNode)) {
        throw invalid();
      }
    } else {
      if (source.get("providerId") instanceof WireJson.NullNode) {
        throw invalid();
      }
      namespacedId(source, "providerId");
    }
  }

  private void source(WireJson.ObjectNode source) {
    requireFields(source, StructuredViewContract.RECIPE_V1_SOURCE_FIELDS);
    requireEnum(source, "kind", StructuredViewContract.RECIPE_V1_SOURCE_KINDS);
    if (!(source.get("providerId") instanceof WireJson.NullNode)) {
      namespacedId(source, "providerId");
    }
  }

  private Set<Integer> layoutV2(String recipeType, WireJson.ObjectNode layout) {
    var expected =
        switch (recipeType) {
          case "shaped", "shapeless" -> StructuredViewContract.LAYOUT_KIND_GRID;
          case "smelting", "blasting", "smoking", "campfire_cooking", "stonecutting" ->
              StructuredViewContract.LAYOUT_KIND_SINGLE_INPUT;
          case "smithing_transform", "smithing_trim" -> StructuredViewContract.LAYOUT_KIND_SMITHING;
          case "transmute" -> StructuredViewContract.LAYOUT_KIND_TRANSMUTE;
          case "complex", "custom" -> StructuredViewContract.LAYOUT_KIND_UNSUPPORTED;
          default -> throw new ProtocolViolationException(invalidCode);
        };
    if (!expected.equals(string(layout, "kind", 1, 32))) {
      throw invalid();
    }
    var slots = new HashSet<Integer>();
    switch (expected) {
      case StructuredViewContract.LAYOUT_KIND_GRID -> {
        requireFields(layout, StructuredViewContract.GRID_LAYOUT_FIELDS);
        var width =
            integer(
                layout,
                "width",
                StructuredViewContract.GRID_WIDTH_MIN,
                StructuredViewContract.GRID_WIDTH_MAX);
        var height =
            integer(
                layout,
                "height",
                StructuredViewContract.GRID_HEIGHT_MIN,
                StructuredViewContract.GRID_HEIGHT_MAX);
        var coordinates = new HashSet<String>();
        for (var element :
            array(
                layout,
                "ingredients",
                StructuredViewContract.INGREDIENTS_MIN_ITEMS,
                StructuredViewContract.INGREDIENTS_MAX_ITEMS)) {
          var ingredient = asObject(element);
          requireFields(ingredient, StructuredViewContract.INGREDIENT_SLOT_FIELDS);
          var slot =
              integer(
                  ingredient,
                  "slot",
                  StructuredViewContract.SLOT_MIN,
                  StructuredViewContract.SLOT_MAX);
          var x =
              integer(
                  ingredient,
                  "x",
                  StructuredViewContract.COORDINATE_MIN,
                  StructuredViewContract.COORDINATE_MAX);
          var y =
              integer(
                  ingredient,
                  "y",
                  StructuredViewContract.COORDINATE_MIN,
                  StructuredViewContract.COORDINATE_MAX);
          if (x >= width
              || y >= height
              || slot != y * width + x
              || !slots.add(slot)
              || !coordinates.add(x + ":" + y)) {
            throw invalid();
          }
          ingredientChoiceV2(object(ingredient, "ingredient"));
        }
      }
      case StructuredViewContract.LAYOUT_KIND_SINGLE_INPUT -> {
        requireFields(layout, StructuredViewContract.SINGLE_INPUT_LAYOUT_FIELDS);
        ingredientChoiceV2(object(layout, "ingredient"));
        slots.add(0);
      }
      case StructuredViewContract.LAYOUT_KIND_SMITHING -> {
        requireFields(layout, StructuredViewContract.SMITHING_LAYOUT_FIELDS);
        ingredientChoiceV2(object(layout, "template"));
        ingredientChoiceV2(object(layout, "base"));
        ingredientChoiceV2(object(layout, "addition"));
        slots.addAll(Set.of(0, 1, 2));
      }
      case StructuredViewContract.LAYOUT_KIND_TRANSMUTE -> {
        requireFields(layout, StructuredViewContract.TRANSMUTE_LAYOUT_FIELDS);
        ingredientChoiceV2(object(layout, "input"));
        ingredientChoiceV2(object(layout, "material"));
        slots.addAll(Set.of(0, 1));
      }
      case StructuredViewContract.LAYOUT_KIND_UNSUPPORTED -> {
        requireFields(layout, StructuredViewContract.UNSUPPORTED_LAYOUT_FIELDS);
        requireLiteral(layout, "reason", StructuredViewContract.LAYOUT_REASON_UNSUPPORTED);
      }
      default -> throw invalid();
    }
    return Set.copyOf(slots);
  }

  private void layout(WireJson.ObjectNode layout) {
    requireFields(layout, StructuredViewContract.RECIPE_V1_LAYOUT_FIELDS);
    var width =
        integer(
            layout,
            "width",
            StructuredViewContract.GRID_WIDTH_MIN,
            StructuredViewContract.GRID_WIDTH_MAX);
    var height =
        integer(
            layout,
            "height",
            StructuredViewContract.GRID_HEIGHT_MIN,
            StructuredViewContract.GRID_HEIGHT_MAX);
    var ingredients =
        array(
            layout,
            "ingredients",
            StructuredViewContract.INGREDIENTS_MIN_ITEMS,
            StructuredViewContract.INGREDIENTS_MAX_ITEMS);
    var slots = new HashSet<Integer>();
    for (var element : ingredients) {
      if (!slots.add(ingredientSlot(asObject(element), width, height))) {
        throw invalid();
      }
    }
  }

  private int ingredientSlot(WireJson.ObjectNode slot, int width, int height) {
    requireFields(slot, StructuredViewContract.INGREDIENT_SLOT_FIELDS);
    var index =
        integer(slot, "slot", StructuredViewContract.SLOT_MIN, StructuredViewContract.SLOT_MAX);
    var x =
        integer(
            slot,
            "x",
            StructuredViewContract.COORDINATE_MIN,
            StructuredViewContract.COORDINATE_MAX);
    var y =
        integer(
            slot,
            "y",
            StructuredViewContract.COORDINATE_MIN,
            StructuredViewContract.COORDINATE_MAX);
    if (x >= width || y >= height || index != y * width + x) {
      throw invalid();
    }
    ingredientChoice(object(slot, "ingredient"));
    return index;
  }

  private void ingredientChoiceV2(WireJson.ObjectNode choice) {
    requireAllowed(choice, StructuredViewContract.INGREDIENT_CHOICE_FIELDS);
    requirePresent(choice, StructuredViewContract.INGREDIENT_CHOICE_REQUIRED_FIELDS);
    var type = requireEnum(choice, "choiceType", StructuredViewContract.RECIPE_V2_CHOICE_TYPES);
    if (StructuredViewContract.LAYOUT_KIND_UNSUPPORTED.equals(type)) {
      requireFields(choice, StructuredViewContract.UNSUPPORTED_CHOICE_FIELDS);
      requireLiteral(choice, "reason", StructuredViewContract.CHOICE_REASON_UNSUPPORTED);
      array(choice, "alternatives", 0, 0);
      return;
    }
    if ("tag".equals(type)) {
      requireFields(choice, StructuredViewContract.TAGGED_CHOICE_FIELDS);
      namespacedId(choice, "tagId");
    } else {
      requireFields(choice, StructuredViewContract.SIMPLE_CHOICE_FIELDS);
    }
    array(
            choice,
            "alternatives",
            StructuredViewContract.ALTERNATIVES_MIN_ITEMS,
            StructuredViewContract.ALTERNATIVES_MAX_ITEMS)
        .forEach(alternative -> itemStack(asObject(alternative)));
  }

  private void ingredientChoice(WireJson.ObjectNode choice) {
    requireAllowed(choice, StructuredViewContract.RECIPE_V1_CHOICE_FIELDS);
    requirePresent(choice, StructuredViewContract.INGREDIENT_CHOICE_REQUIRED_FIELDS);
    var type = requireEnum(choice, "choiceType", StructuredViewContract.RECIPE_V1_CHOICE_TYPES);
    if ("tag".equals(type) != choice.has("tagId")) {
      throw invalid();
    }
    if (choice.has("tagId")) {
      namespacedId(choice, "tagId");
    }
    array(
            choice,
            "alternatives",
            StructuredViewContract.ALTERNATIVES_MIN_ITEMS,
            StructuredViewContract.ALTERNATIVES_MAX_ITEMS)
        .forEach(alternative -> itemStack(asObject(alternative)));
  }

  private int remainingItem(WireJson element) {
    var remaining = asObject(element);
    requireFields(remaining, StructuredViewContract.REMAINING_ITEM_FIELDS);
    var slot =
        integer(
            remaining, "slot", StructuredViewContract.SLOT_MIN, StructuredViewContract.SLOT_MAX);
    itemStack(object(remaining, "item"));
    return slot;
  }

  private void processing(WireJson.ObjectNode processing, boolean versionTwo) {
    requireFields(processing, StructuredViewContract.PROCESSING_FIELDS);
    var minimum =
        versionTwo
            ? StructuredViewContract.TIME_TICKS_V2_MIN
            : StructuredViewContract.TIME_TICKS_V1_MIN;
    integer(processing, "timeTicks", minimum, StructuredViewContract.TIME_TICKS_MAX);
    decimal(
        processing,
        "experience",
        StructuredViewContract.EXPERIENCE_MIN,
        StructuredViewContract.EXPERIENCE_MAX);
  }

  private void selectionList(WireJson.ObjectNode content) {
    requireFields(content, StructuredViewContract.SELECTION_LIST_FIELDS);
    visibleString(
        content,
        "prompt",
        StructuredViewContract.SELECTION_PROMPT_MIN_CHARS,
        StructuredViewContract.SELECTION_PROMPT_MAX_CHARS,
        false);
    var ids = new HashSet<String>();
    for (var element :
        array(
            content,
            "options",
            StructuredViewContract.SELECTION_MIN_OPTIONS,
            StructuredViewContract.SELECTION_MAX_OPTIONS)) {
      var option = asObject(element);
      requireAllowed(option, StructuredViewContract.SELECTION_OPTION_FIELDS);
      requirePresent(option, StructuredViewContract.SELECTION_OPTION_REQUIRED_FIELDS);
      var id =
          string(
              option,
              "id",
              StructuredViewContract.SELECTION_ID_MIN_CHARS,
              StructuredViewContract.SELECTION_ID_MAX_CHARS);
      if (!StructuredViewContract.SELECTION_ID.matcher(id).matches() || !ids.add(id)) {
        throw invalid();
      }
      visibleString(
          option,
          "label",
          StructuredViewContract.SELECTION_LABEL_MIN_CHARS,
          StructuredViewContract.SELECTION_LABEL_MAX_CHARS,
          false);
      if (option.has("description")) {
        visibleString(
            option,
            "description",
            StructuredViewContract.SELECTION_DESCRIPTION_MIN_CHARS,
            StructuredViewContract.SELECTION_DESCRIPTION_MAX_CHARS,
            false);
      }
    }
  }

  private void proposal(WireJson.ObjectNode content) {
    requireAllowed(content, StructuredViewContract.PROPOSAL_FIELDS);
    requirePresent(content, StructuredViewContract.PROPOSAL_REQUIRED_FIELDS);
    uuid(content, "proposalId");
    uuid(content, "sessionId");
    uuid(content, "playerUuid");
    var tool =
        string(
            content,
            "tool",
            StructuredViewContract.PROPOSAL_TOOL_MIN_CHARS,
            StructuredViewContract.PROPOSAL_TOOL_MAX_CHARS);
    if (!StructuredViewContract.TOOL_NAME.matcher(tool).matches()) {
      throw invalid();
    }
    var arguments = object(content, "arguments");
    if (arguments.size() > StructuredViewContract.PROPOSAL_MAX_ARGUMENTS) {
      throw invalid();
    }
    boundedJson(arguments, 0, new int[] {0});
    sha256(content, "argumentHash");
    if (content.has("baseRegionHash") != content.has("changeSetHash")) {
      throw invalid();
    }
    if (content.has("baseRegionHash")) {
      sha256(content, "baseRegionHash");
      sha256(content, "changeSetHash");
    }
    requireEnum(content, "risk", StructuredViewContract.PROPOSAL_RISKS);
    visibleString(
        content,
        "summary",
        StructuredViewContract.PROPOSAL_SUMMARY_MIN_CHARS,
        StructuredViewContract.PROPOSAL_SUMMARY_MAX_CHARS,
        false);
    try {
      Instant.parse(
          string(content, "expiresAt", 1, StructuredViewContract.PROPOSAL_EXPIRES_AT_MAX_CHARS));
    } catch (DateTimeParseException error) {
      throw invalid();
    }
  }

  private void buildPreview(WireJson.ObjectNode content) {
    requireFields(content, StructuredViewContract.PREVIEW_FIELDS);
    requireLiteral(content, "schemaVersion", StructuredViewContract.VIEW_SCHEMA_VERSION);
    uuid(content, "previewId");
    uuid(content, "projectId");
    integer(
        content,
        "revision",
        StructuredViewContract.REVISION_MIN,
        StructuredViewContract.REVISION_MAX);
    requireEnum(content, "operation", StructuredViewContract.PREVIEW_OPERATIONS);
    namespacedId(content, "dimension");
    var bounds = object(content, "bounds");
    requireFields(bounds, StructuredViewContract.PREVIEW_BOUNDS_FIELDS);
    position(object(bounds, "min"));
    position(object(bounds, "max"));
    position(object(content, "origin"));
    transform(object(content, "transform"));
    sha256(content, "baseRegionHash");
    sha256(content, "changeSetHash");
    sha256(content, "contentHash");
    sha256(content, "paletteHash");
    requireLiteral(content, "contentFormat", StructuredViewContract.PREVIEW_CONTENT_FORMAT);
    requireEnum(content, "encoding", StructuredViewContract.PREVIEW_ENCODINGS);
    var compressedBytes =
        integer(
            content,
            "compressedBytes",
            StructuredViewContract.PREVIEW_COMPRESSED_BYTES_MIN,
            StructuredViewContract.PREVIEW_COMPRESSED_BYTES_MAX);
    integer(
        content,
        "uncompressedBytes",
        StructuredViewContract.PREVIEW_UNCOMPRESSED_BYTES_MIN,
        StructuredViewContract.PREVIEW_UNCOMPRESSED_BYTES_MAX);
    integer(
        content,
        "blockCount",
        StructuredViewContract.PREVIEW_BLOCK_COUNT_MIN,
        StructuredViewContract.PREVIEW_BLOCK_COUNT_MAX);
    difference(object(content, "difference"));
    var palette =
        array(
            content,
            "palette",
            StructuredViewContract.PREVIEW_PALETTE_MIN_ITEMS,
            StructuredViewContract.PREVIEW_PALETTE_MAX_ITEMS);
    for (var index = 0; index < palette.size(); index++) {
      if (paletteEntry(asObject(palette.get(index))) != index) {
        throw invalid();
      }
    }
    var chunkCount =
        integer(
            content,
            "chunkCount",
            StructuredViewContract.PREVIEW_CHUNK_MIN_ITEMS,
            StructuredViewContract.PREVIEW_CHUNK_MAX_ITEMS);
    var chunks =
        array(
            content,
            "chunks",
            StructuredViewContract.PREVIEW_CHUNK_MIN_ITEMS,
            StructuredViewContract.PREVIEW_CHUNK_MAX_ITEMS);
    if (chunks.size() != chunkCount) {
      throw invalid();
    }
    var total = 0L;
    var indexes = new HashSet<Integer>();
    for (var chunk : chunks) {
      var validated = previewChunk(asObject(chunk));
      if (!indexes.add(validated.index())) {
        throw invalid();
      }
      total += validated.byteLength();
    }
    for (var index = 0; index < chunkCount; index++) {
      if (!indexes.contains(index)) {
        throw invalid();
      }
    }
    if (total != compressedBytes) {
      throw invalid();
    }
  }

  private void position(WireJson.ObjectNode position) {
    requireFields(position, StructuredViewContract.PREVIEW_POSITION_FIELDS);
    integer(
        position,
        "x",
        StructuredViewContract.POSITION_X_MIN,
        StructuredViewContract.POSITION_X_MAX);
    integer(
        position,
        "y",
        StructuredViewContract.POSITION_Y_MIN,
        StructuredViewContract.POSITION_Y_MAX);
    integer(
        position,
        "z",
        StructuredViewContract.POSITION_Z_MIN,
        StructuredViewContract.POSITION_Z_MAX);
  }

  private void transform(WireJson.ObjectNode transform) {
    requireFields(transform, StructuredViewContract.PREVIEW_TRANSFORM_FIELDS);
    var rotation =
        integer(
            transform,
            "rotation",
            StructuredViewContract.PREVIEW_ROTATION_MIN,
            StructuredViewContract.PREVIEW_ROTATION_MAX);
    if (!StructuredViewContract.PREVIEW_ROTATIONS.contains(rotation)) {
      throw invalid();
    }
    requireEnum(transform, "mirror", StructuredViewContract.PREVIEW_MIRRORS);
  }

  private void difference(WireJson.ObjectNode difference) {
    requireFields(difference, StructuredViewContract.PREVIEW_DIFFERENCE_FIELDS);
    integer(
        difference,
        "added",
        StructuredViewContract.PREVIEW_CHANGE_MIN,
        StructuredViewContract.PREVIEW_CHANGE_MAX);
    integer(
        difference,
        "replaced",
        StructuredViewContract.PREVIEW_CHANGE_MIN,
        StructuredViewContract.PREVIEW_CHANGE_MAX);
    integer(
        difference,
        "removed",
        StructuredViewContract.PREVIEW_CHANGE_MIN,
        StructuredViewContract.PREVIEW_CHANGE_MAX);
  }

  private int paletteEntry(WireJson.ObjectNode entry) {
    requireFields(entry, StructuredViewContract.PREVIEW_PALETTE_ENTRY_FIELDS);
    var id =
        integer(
            entry,
            "id",
            StructuredViewContract.PREVIEW_PALETTE_ID_MIN,
            StructuredViewContract.PREVIEW_PALETTE_ID_MAX);
    namespacedId(entry, "blockId");
    var properties = object(entry, "properties");
    if (properties.size() > StructuredViewContract.PREVIEW_PALETTE_MAX_PROPERTIES) {
      throw invalid();
    }
    for (var property : properties.fields().entrySet()) {
      if (!StructuredViewContract.BLOCK_PROPERTY_NAME.matcher(property.getKey()).matches()) {
        throw invalid();
      }
      var value =
          string(
              property.getValue(),
              StructuredViewContract.PREVIEW_PROPERTY_VALUE_MIN_CHARS,
              StructuredViewContract.PREVIEW_PROPERTY_VALUE_MAX_CHARS);
      if (!StructuredViewContract.BLOCK_PROPERTY_VALUE.matcher(value).matches()) {
        throw invalid();
      }
    }
    return id;
  }

  private record ValidatedChunk(int index, int byteLength) {}

  private ValidatedChunk previewChunk(WireJson.ObjectNode chunk) {
    requireFields(chunk, StructuredViewContract.PREVIEW_CHUNK_FIELDS);
    var index =
        integer(
            chunk,
            "index",
            StructuredViewContract.PREVIEW_CHUNK_INDEX_MIN,
            StructuredViewContract.PREVIEW_CHUNK_INDEX_MAX);
    var byteLength =
        integer(
            chunk,
            "byteLength",
            StructuredViewContract.PREVIEW_CHUNK_BYTES_MIN,
            StructuredViewContract.PREVIEW_CHUNK_BYTES_MAX);
    var expectedHash = sha256(chunk, "sha256");
    var encoded =
        string(
            chunk,
            "data",
            StructuredViewContract.PREVIEW_CHUNK_DATA_MIN_CHARS,
            StructuredViewContract.PREVIEW_CHUNK_DATA_MAX_CHARS);
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new ProtocolViolationException(invalidCode);
    }
    if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)
        || decoded.length != byteLength
        || !StructuredViewContract.sha256Hex(decoded).equals(expectedHash)) {
      throw invalid();
    }
    return new ValidatedChunk(index, byteLength);
  }

  private void boundedJson(WireJson value, int depth, int[] count) {
    if (depth > StructuredViewContract.PROPOSAL_ARGUMENTS_MAX_DEPTH
        || ++count[0] > StructuredViewContract.PROPOSAL_ARGUMENTS_MAX_NODES) {
      throw invalid();
    }
    if (value instanceof WireJson.ObjectNode object) {
      if (object.size() > StructuredViewContract.PROPOSAL_ARGUMENTS_MAX_FIELDS) {
        throw invalid();
      }
      object.fields().entrySet().forEach(entry -> boundedJson(entry.getValue(), depth + 1, count));
    } else if (value instanceof WireJson.ArrayNode array) {
      if (array.size() > StructuredViewContract.PROPOSAL_ARGUMENTS_MAX_ITEMS) {
        throw invalid();
      }
      array.forEach(element -> boundedJson(element, depth + 1, count));
    } else if (value instanceof WireJson.TextNode text) {
      string(text, 0, StructuredViewContract.PROPOSAL_ARGUMENTS_STRING_MAX_CHARS);
    } else if (value instanceof WireJson.NumberNode number) {
      try {
        new BigDecimal(number.literal());
      } catch (NumberFormatException error) {
        throw invalid();
      }
    }
  }

  private String namespacedId(WireJson.ObjectNode object, String name) {
    var value =
        string(
            object,
            name,
            StructuredViewContract.NAMESPACED_ID_MIN_CHARS,
            StructuredViewContract.NAMESPACED_ID_MAX_CHARS);
    if (!StructuredViewContract.NAMESPACED_ID.matcher(value).matches()) {
      throw invalid();
    }
    return value;
  }

  private String sha256(WireJson.ObjectNode object, String name) {
    var value =
        string(
            object, name, StructuredViewContract.SHA256_CHARS, StructuredViewContract.SHA256_CHARS);
    if (!StructuredViewContract.SHA_256.matcher(value).matches()) {
      throw invalid();
    }
    return value;
  }

  private void requireLiteral(WireJson.ObjectNode object, String name, String expected) {
    if (!expected.equals(string(object, name, expected.length(), expected.length()))) {
      throw invalid();
    }
  }

  private String requireEnum(WireJson.ObjectNode object, String name, Set<String> values) {
    var value = string(object, name, 1, 64);
    if (!values.contains(value)) {
      throw invalid();
    }
    return value;
  }

  private WireJson.ObjectNode object(WireJson.ObjectNode parent, String name) {
    return asObject(parent.get(name));
  }

  private WireJson.ObjectNode asObject(WireJson value) {
    if (!(value instanceof WireJson.ObjectNode object)) {
      throw invalid();
    }
    return object;
  }

  private WireJson.ArrayNode array(
      WireJson.ObjectNode parent, String name, int minimum, int maximum) {
    if (!(parent.get(name) instanceof WireJson.ArrayNode array)
        || array.size() < minimum
        || array.size() > maximum) {
      throw invalid();
    }
    return array;
  }

  private String string(WireJson.ObjectNode parent, String name, int minimum, int maximum) {
    return string(parent.get(name), minimum, maximum);
  }

  private String string(WireJson value, int minimum, int maximum) {
    if (!(value instanceof WireJson.TextNode text)) {
      throw invalid();
    }
    var result = text.value();
    var length = StructuredViewContract.codePointLength(result);
    if (length < minimum || length > maximum || !StructuredViewContract.isWellFormedUtf16(result)) {
      throw invalid();
    }
    return result;
  }

  private String visibleString(
      WireJson.ObjectNode parent,
      String name,
      int minimum,
      int maximum,
      boolean allowLineFormatting) {
    return visibleString(parent.get(name), minimum, maximum, allowLineFormatting);
  }

  private String visibleString(
      WireJson value, int minimum, int maximum, boolean allowLineFormatting) {
    var text = string(value, minimum, maximum);
    if (text.codePoints()
        .anyMatch(
            codePoint ->
                StructuredViewContract.isUnsafeVisibleCodePoint(codePoint, allowLineFormatting))) {
      throw invalid();
    }
    return text;
  }

  private boolean bool(WireJson.ObjectNode parent, String name) {
    if (!(parent.get(name) instanceof WireJson.BooleanNode value)) {
      throw invalid();
    }
    return value.value();
  }

  private int integer(WireJson.ObjectNode parent, String name, int minimum, int maximum) {
    if (!(parent.get(name) instanceof WireJson.NumberNode number)) {
      throw invalid();
    }
    try {
      var result = new BigDecimal(number.literal()).intValueExact();
      if (result < minimum || result > maximum) {
        throw invalid();
      }
      return result;
    } catch (NumberFormatException | ArithmeticException error) {
      throw new ProtocolViolationException(invalidCode);
    }
  }

  private BigDecimal decimal(
      WireJson.ObjectNode parent, String name, BigDecimal minimum, BigDecimal maximum) {
    if (!(parent.get(name) instanceof WireJson.NumberNode number)) {
      throw invalid();
    }
    try {
      var result = new BigDecimal(number.literal());
      if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
        throw invalid();
      }
      return result;
    } catch (NumberFormatException error) {
      throw new ProtocolViolationException(invalidCode);
    }
  }

  private UUID uuid(WireJson.ObjectNode parent, String name) {
    var text =
        string(parent, name, StructuredViewContract.UUID_CHARS, StructuredViewContract.UUID_CHARS);
    try {
      var result = UUID.fromString(text);
      if (!result.toString().equals(text)) {
        throw invalid();
      }
      return result;
    } catch (IllegalArgumentException error) {
      throw new ProtocolViolationException(invalidCode);
    }
  }

  private void requireFields(WireJson.ObjectNode object, Set<String> expected) {
    if (!object.names().equals(expected)) {
      throw invalid();
    }
  }

  private void requireAllowed(WireJson.ObjectNode object, Set<String> allowed) {
    if (!allowed.containsAll(object.names())) {
      throw invalid();
    }
  }

  private void requirePresent(WireJson.ObjectNode object, Set<String> required) {
    if (!object.names().containsAll(required)) {
      throw invalid();
    }
  }

  private RuntimeException invalid() {
    return new ProtocolViolationException(invalidCode);
  }
}
