package dev.minecraftagent.paper.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed structural validator for trusted server-owned view models. */
final class ClientStructuredViewValidator {
  private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
  private static final Pattern TOOL = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
  private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

  private ClientStructuredViewValidator() {}

  static ClientStructuredView fromJson(JsonObject source) {
    requireFields(
        source,
        Set.of(
            "viewSchemaVersion",
            "viewId",
            "requestId",
            "viewType",
            "revision",
            "title",
            "fallbackText",
            "pinnable",
            "content"));
    return new ClientStructuredView(
        string(source, "viewSchemaVersion", 1, 8),
        uuid(source, "viewId"),
        uuid(source, "requestId"),
        ClientViewType.fromWireName(string(source, "viewType", 1, 32)),
        integer(source, "revision", 1, Integer.MAX_VALUE),
        string(source, "title", 1, 128),
        string(source, "fallbackText", 1, 8192),
        bool(source, "pinnable"),
        object(source, "content"));
  }

  static void validate(ClientViewType type, JsonObject content) {
    switch (type) {
      case TEXT -> text(content);
      case ITEM_STACK -> itemStack(content);
      case ITEM_LIST -> itemList(content);
      case RECIPE -> recipeView(content);
      case BUILD_PREVIEW -> buildPreview(content);
      case PROPOSAL -> proposal(content);
      case SELECTION_LIST -> selectionList(content);
    }
  }

  private static void text(JsonObject content) {
    requireFields(content, Set.of("text"));
    visibleString(content, "text", 1, 32768, true);
  }

  private static void itemList(JsonObject content) {
    requireFields(content, Set.of("items"));
    var items = array(content, "items", 1, 128);
    items.forEach(item -> itemStack(asObject(item)));
  }

  private static void itemStack(JsonObject item) {
    requireFields(item, Set.of("itemId", "count", "components"));
    namespacedId(item, "itemId");
    integer(item, "count", 1, 999999);
    components(object(item, "components"));
  }

  private static void components(JsonObject components) {
    requireAllowed(
        components,
        Set.of("customName", "lore", "damage", "maxDamage", "customModelData", "enchantmentGlint"));
    if (components.has("customName")) {
      visibleString(components, "customName", 0, 512);
    }
    if (components.has("lore")) {
      var lore = array(components, "lore", 0, 32);
      lore.forEach(line -> visibleString(line, 0, 512));
    }
    if (components.has("damage")) {
      integer(components, "damage", 0, Integer.MAX_VALUE);
    }
    if (components.has("maxDamage")) {
      integer(components, "maxDamage", 1, Integer.MAX_VALUE);
    }
    if (components.has("customModelData")) {
      integer(components, "customModelData", 0, Integer.MAX_VALUE);
    }
    if (components.has("enchantmentGlint")) {
      bool(components, "enchantmentGlint");
    }
    if (components.has("damage")
        && components.has("maxDamage")
        && integer(components, "damage", 0, Integer.MAX_VALUE)
            > integer(components, "maxDamage", 1, Integer.MAX_VALUE)) {
      invalid();
    }
  }

  private static void recipeView(JsonObject content) {
    if (content.has("schemaVersion")
        && content.get("schemaVersion").isJsonPrimitive()
        && "2.0".equals(content.get("schemaVersion").getAsString())) {
      recipeViewV2(content);
      return;
    }
    requireFields(content, Set.of("schemaVersion", "query", "selectedRecipe", "recipes"));
    requireLiteral(content, "schemaVersion", "1.0");
    var query = object(content, "query");
    requireFields(query, Set.of("mode", "itemId"));
    requireEnum(query, "mode", Set.of("lookup", "uses"));
    namespacedId(query, "itemId");
    var selected = integer(content, "selectedRecipe", 0, 127);
    var recipes = array(content, "recipes", 1, 128);
    if (selected >= recipes.size()) {
      invalid();
    }
    recipes.forEach(recipe -> recipe(asObject(recipe)));
  }

  private static void recipeViewV2(JsonObject content) {
    requireFields(
        content,
        Set.of("schemaVersion", "query", "selectedRecipe", "totalMatches", "truncated", "recipes"));
    requireLiteral(content, "schemaVersion", "2.0");
    var query = object(content, "query");
    requireFields(query, Set.of("mode", "itemId"));
    requireEnum(query, "mode", Set.of("lookup", "uses"));
    namespacedId(query, "itemId");
    var recipes = array(content, "recipes", 1, 16);
    var selected = integer(content, "selectedRecipe", 0, 15);
    var totalMatches = integer(content, "totalMatches", recipes.size(), 1_000_000);
    var truncated = bool(content, "truncated");
    if (selected >= recipes.size() || (!truncated && totalMatches != recipes.size())) {
      invalid();
    }
    var ids = new HashSet<String>();
    for (var element : recipes) {
      var recipe = asObject(element);
      recipeV2(recipe);
      if (!ids.add(recipe.get("recipeId").getAsString())) {
        invalid();
      }
    }
  }

  private static void recipeV2(JsonObject recipe) {
    requireAllowed(
        recipe,
        Set.of(
            "recipeId",
            "recipeType",
            "source",
            "result",
            "layout",
            "remainingItems",
            "processing"));
    requirePresent(
        recipe, Set.of("recipeId", "recipeType", "source", "result", "layout", "remainingItems"));
    namespacedId(recipe, "recipeId");
    var type =
        requireEnum(
            recipe,
            "recipeType",
            Set.of(
                "shaped",
                "shapeless",
                "smelting",
                "blasting",
                "smoking",
                "campfire_cooking",
                "stonecutting",
                "smithing_transform",
                "smithing_trim",
                "transmute",
                "complex",
                "custom"));
    recipeSourceV2(object(recipe, "source"));
    if (!recipe.get("result").isJsonNull()) {
      itemStack(object(recipe, "result"));
    }
    var logicalSlots = layoutV2(type, object(recipe, "layout"));
    var remainingSlots = new HashSet<Integer>();
    for (var element : array(recipe, "remainingItems", 0, 9)) {
      var slot = remainingItem(element);
      if (!logicalSlots.contains(slot) || !remainingSlots.add(slot)) {
        invalid();
      }
    }
    var cooking = Set.of("smelting", "blasting", "smoking", "campfire_cooking").contains(type);
    if (cooking != recipe.has("processing")) {
      invalid();
    }
    if (cooking) {
      processingV2(object(recipe, "processing"));
    }
  }

  private static void recipeSourceV2(JsonObject source) {
    requireFields(source, Set.of("kind", "providerId"));
    var kind = requireEnum(source, "kind", Set.of("server_registry", "plugin_provider"));
    if ("server_registry".equals(kind)) {
      if (!source.get("providerId").isJsonNull()) {
        invalid();
      }
    } else {
      if (source.get("providerId").isJsonNull()) {
        invalid();
      }
      namespacedId(source, "providerId");
    }
  }

  private static Set<Integer> layoutV2(String recipeType, JsonObject layout) {
    var expected =
        switch (recipeType) {
          case "shaped", "shapeless" -> "grid";
          case "smelting", "blasting", "smoking", "campfire_cooking", "stonecutting" ->
              "single_input";
          case "smithing_transform", "smithing_trim" -> "smithing";
          case "transmute" -> "transmute";
          case "complex", "custom" -> "unsupported";
          default -> throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
        };
    if (!expected.equals(string(layout, "kind", 1, 32))) {
      invalid();
    }
    var slots = new HashSet<Integer>();
    switch (expected) {
      case "grid" -> {
        requireFields(layout, Set.of("kind", "width", "height", "ingredients"));
        var width = integer(layout, "width", 1, 3);
        var height = integer(layout, "height", 1, 3);
        var coordinates = new HashSet<String>();
        for (var element : array(layout, "ingredients", 1, 9)) {
          var ingredient = asObject(element);
          requireFields(ingredient, Set.of("slot", "x", "y", "ingredient"));
          var slot = integer(ingredient, "slot", 0, 8);
          var x = integer(ingredient, "x", 0, 2);
          var y = integer(ingredient, "y", 0, 2);
          if (x >= width
              || y >= height
              || slot != y * width + x
              || !slots.add(slot)
              || !coordinates.add(x + ":" + y)) {
            invalid();
          }
          ingredientChoiceV2(object(ingredient, "ingredient"));
        }
      }
      case "single_input" -> {
        requireFields(layout, Set.of("kind", "ingredient"));
        ingredientChoiceV2(object(layout, "ingredient"));
        slots.add(0);
      }
      case "smithing" -> {
        requireFields(layout, Set.of("kind", "template", "base", "addition"));
        ingredientChoiceV2(object(layout, "template"));
        ingredientChoiceV2(object(layout, "base"));
        ingredientChoiceV2(object(layout, "addition"));
        slots.addAll(Set.of(0, 1, 2));
      }
      case "transmute" -> {
        requireFields(layout, Set.of("kind", "input", "material"));
        ingredientChoiceV2(object(layout, "input"));
        ingredientChoiceV2(object(layout, "material"));
        slots.addAll(Set.of(0, 1));
      }
      case "unsupported" -> {
        requireFields(layout, Set.of("kind", "reason"));
        requireLiteral(layout, "reason", "UNSUPPORTED_RECIPE_LAYOUT");
      }
      default -> invalid();
    }
    return Set.copyOf(slots);
  }

  private static void ingredientChoiceV2(JsonObject choice) {
    requireAllowed(choice, Set.of("choiceType", "tagId", "reason", "alternatives"));
    requirePresent(choice, Set.of("choiceType", "alternatives"));
    var type =
        requireEnum(
            choice, "choiceType", Set.of("material", "exact", "item_type", "tag", "unsupported"));
    if ("unsupported".equals(type)) {
      requireFields(choice, Set.of("choiceType", "reason", "alternatives"));
      requireLiteral(choice, "reason", "UNSUPPORTED_INGREDIENT_CHOICE");
      array(choice, "alternatives", 0, 0);
      return;
    }
    if ("tag".equals(type)) {
      requireFields(choice, Set.of("choiceType", "tagId", "alternatives"));
      namespacedId(choice, "tagId");
    } else {
      requireFields(choice, Set.of("choiceType", "alternatives"));
    }
    array(choice, "alternatives", 1, 64).forEach(alternative -> itemStack(asObject(alternative)));
  }

  private static void processingV2(JsonObject processing) {
    requireFields(processing, Set.of("timeTicks", "experience"));
    integer(processing, "timeTicks", 0, 120000);
    decimal(processing, "experience", BigDecimal.ZERO, BigDecimal.valueOf(1_000_000));
  }

  private static void recipe(JsonObject recipe) {
    requireAllowed(
        recipe,
        Set.of(
            "recipeId",
            "recipeType",
            "source",
            "result",
            "layout",
            "remainingItems",
            "processing"));
    requirePresent(
        recipe, Set.of("recipeId", "recipeType", "source", "result", "layout", "remainingItems"));
    namespacedId(recipe, "recipeId");
    var type =
        requireEnum(
            recipe,
            "recipeType",
            Set.of(
                "shaped",
                "shapeless",
                "smelting",
                "blasting",
                "smoking",
                "campfire_cooking",
                "stonecutting",
                "smithing_transform",
                "smithing_trim",
                "custom"));
    source(object(recipe, "source"));
    itemStack(object(recipe, "result"));
    layout(object(recipe, "layout"));
    var remaining = array(recipe, "remainingItems", 0, 9);
    var remainingSlots = new HashSet<Integer>();
    for (var element : remaining) {
      if (!remainingSlots.add(remainingItem(element))) {
        invalid();
      }
    }
    if (Set.of("smelting", "blasting", "smoking", "campfire_cooking").contains(type)
        && !recipe.has("processing")) {
      invalid();
    }
    if (recipe.has("processing")) {
      processing(object(recipe, "processing"));
    }
  }

  private static void source(JsonObject source) {
    requireFields(source, Set.of("kind", "providerId"));
    requireEnum(
        source,
        "kind",
        Set.of(
            "server_registry",
            "plugin_provider",
            "server_docs",
            "web_documentation",
            "model_knowledge"));
    if (!source.get("providerId").isJsonNull()) {
      namespacedId(source, "providerId");
    }
  }

  private static void layout(JsonObject layout) {
    requireFields(layout, Set.of("width", "height", "ingredients"));
    var width = integer(layout, "width", 1, 3);
    var height = integer(layout, "height", 1, 3);
    var ingredients = array(layout, "ingredients", 1, 9);
    var slots = new HashSet<Integer>();
    for (var element : ingredients) {
      var slot = ingredientSlot(asObject(element), width, height);
      if (!slots.add(slot)) {
        invalid();
      }
    }
  }

  private static int ingredientSlot(JsonObject slot, int width, int height) {
    requireFields(slot, Set.of("slot", "x", "y", "ingredient"));
    var index = integer(slot, "slot", 0, 8);
    var x = integer(slot, "x", 0, 2);
    var y = integer(slot, "y", 0, 2);
    if (x >= width || y >= height || index != y * width + x) {
      invalid();
    }
    ingredientChoice(object(slot, "ingredient"));
    return index;
  }

  private static void ingredientChoice(JsonObject choice) {
    requireAllowed(choice, Set.of("choiceType", "tagId", "alternatives"));
    requirePresent(choice, Set.of("choiceType", "alternatives"));
    var type = requireEnum(choice, "choiceType", Set.of("material", "exact", "tag"));
    if ("tag".equals(type) != choice.has("tagId")) {
      invalid();
    }
    if (choice.has("tagId")) {
      namespacedId(choice, "tagId");
    }
    array(choice, "alternatives", 1, 64).forEach(alternative -> itemStack(asObject(alternative)));
  }

  private static int remainingItem(JsonElement element) {
    var remaining = asObject(element);
    requireFields(remaining, Set.of("slot", "item"));
    var slot = integer(remaining, "slot", 0, 8);
    itemStack(object(remaining, "item"));
    return slot;
  }

  private static void processing(JsonObject processing) {
    requireFields(processing, Set.of("timeTicks", "experience"));
    integer(processing, "timeTicks", 1, 120000);
    decimal(processing, "experience", BigDecimal.ZERO, BigDecimal.valueOf(1_000_000));
  }

  private static void selectionList(JsonObject content) {
    requireFields(content, Set.of("prompt", "options"));
    visibleString(content, "prompt", 1, 512);
    var ids = new HashSet<String>();
    for (var element : array(content, "options", 1, 64)) {
      var option = asObject(element);
      requireAllowed(option, Set.of("id", "label", "description"));
      requirePresent(option, Set.of("id", "label"));
      var id = string(option, "id", 1, 64);
      if (!SAFE_ID.matcher(id).matches() || !ids.add(id)) {
        invalid();
      }
      visibleString(option, "label", 1, 128);
      if (option.has("description")) {
        visibleString(option, "description", 0, 512);
      }
    }
  }

  private static void proposal(JsonObject content) {
    requireAllowed(
        content,
        Set.of(
            "proposalId",
            "sessionId",
            "playerUuid",
            "tool",
            "arguments",
            "argumentHash",
            "baseRegionHash",
            "changeSetHash",
            "risk",
            "summary",
            "expiresAt"));
    requirePresent(
        content,
        Set.of(
            "proposalId",
            "sessionId",
            "playerUuid",
            "tool",
            "arguments",
            "argumentHash",
            "risk",
            "summary",
            "expiresAt"));
    uuid(content, "proposalId");
    uuid(content, "sessionId");
    uuid(content, "playerUuid");
    var tool = string(content, "tool", 3, 128);
    if (!TOOL.matcher(tool).matches()) {
      invalid();
    }
    var arguments = object(content, "arguments");
    if (arguments.size() > 64) {
      invalid();
    }
    boundedJson(arguments, 0, new int[] {0});
    sha256(content, "argumentHash");
    if (content.has("baseRegionHash") != content.has("changeSetHash")) {
      invalid();
    }
    if (content.has("baseRegionHash")) {
      sha256(content, "baseRegionHash");
      sha256(content, "changeSetHash");
    }
    requireEnum(
        content, "risk", Set.of("WRITE_TEMPORARY", "WRITE_WORLD", "WRITE_PLAYER", "SERVER_ADMIN"));
    visibleString(content, "summary", 1, 2048);
    try {
      Instant.parse(string(content, "expiresAt", 1, 64));
    } catch (DateTimeParseException error) {
      invalid();
    }
  }

  private static void buildPreview(JsonObject content) {
    requireFields(
        content,
        Set.of(
            "schemaVersion",
            "previewId",
            "projectId",
            "revision",
            "operation",
            "dimension",
            "bounds",
            "origin",
            "transform",
            "baseRegionHash",
            "changeSetHash",
            "contentHash",
            "paletteHash",
            "contentFormat",
            "encoding",
            "compressedBytes",
            "uncompressedBytes",
            "blockCount",
            "difference",
            "palette",
            "chunkCount",
            "chunks"));
    requireLiteral(content, "schemaVersion", "1.0");
    uuid(content, "previewId");
    uuid(content, "projectId");
    integer(content, "revision", 1, Integer.MAX_VALUE);
    requireEnum(content, "operation", Set.of("create", "modify"));
    namespacedId(content, "dimension");
    var bounds = object(content, "bounds");
    requireFields(bounds, Set.of("min", "max"));
    position(object(bounds, "min"));
    position(object(bounds, "max"));
    position(object(content, "origin"));
    transform(object(content, "transform"));
    sha256(content, "baseRegionHash");
    sha256(content, "changeSetHash");
    sha256(content, "contentHash");
    sha256(content, "paletteHash");
    requireLiteral(content, "contentFormat", "minecraft-agent.palette-v1");
    requireEnum(content, "encoding", Set.of("identity+base64", "gzip+base64"));
    var compressedBytes = integer(content, "compressedBytes", 1, 16 * 1024 * 1024);
    integer(content, "uncompressedBytes", 1, 64 * 1024 * 1024);
    integer(content, "blockCount", 0, 250000);
    difference(object(content, "difference"));
    var palette = array(content, "palette", 0, 4096);
    for (var index = 0; index < palette.size(); index++) {
      if (paletteEntry(asObject(palette.get(index))) != index) {
        invalid();
      }
    }
    var chunkCount = integer(content, "chunkCount", 1, 256);
    var chunks = array(content, "chunks", 1, 256);
    if (chunks.size() != chunkCount) {
      invalid();
    }
    var total = 0L;
    var indexes = new HashSet<Integer>();
    for (var chunk : chunks) {
      var validated = previewChunk(asObject(chunk));
      if (!indexes.add(validated.index())) {
        invalid();
      }
      total += validated.byteLength();
    }
    for (var index = 0; index < chunkCount; index++) {
      if (!indexes.contains(index)) {
        invalid();
      }
    }
    if (total != compressedBytes) {
      invalid();
    }
  }

  private static void position(JsonObject position) {
    requireFields(position, Set.of("x", "y", "z"));
    integer(position, "x", -30000000, 30000000);
    integer(position, "y", -2048, 2048);
    integer(position, "z", -30000000, 30000000);
  }

  private static void transform(JsonObject transform) {
    requireFields(transform, Set.of("rotation", "mirror"));
    var rotation = integer(transform, "rotation", 0, 270);
    if (!Set.of(0, 90, 180, 270).contains(rotation)) {
      invalid();
    }
    requireEnum(transform, "mirror", Set.of("NONE", "LEFT_RIGHT", "FRONT_BACK"));
  }

  private static void difference(JsonObject difference) {
    requireFields(difference, Set.of("added", "replaced", "removed"));
    integer(difference, "added", 0, 250000);
    integer(difference, "replaced", 0, 250000);
    integer(difference, "removed", 0, 250000);
  }

  private static int paletteEntry(JsonObject entry) {
    requireFields(entry, Set.of("id", "blockId", "properties"));
    var id = integer(entry, "id", 0, 4095);
    namespacedId(entry, "blockId");
    var properties = object(entry, "properties");
    if (properties.size() > 32) {
      invalid();
    }
    for (var property : properties.entrySet()) {
      if (!property.getKey().matches("[a-z0-9_]+")) {
        invalid();
      }
      var value = string(property.getValue(), 1, 64);
      if (!value.matches("[A-Za-z0-9_.-]+")) {
        invalid();
      }
    }
    return id;
  }

  private static ValidatedChunk previewChunk(JsonObject chunk) {
    requireFields(chunk, Set.of("index", "byteLength", "sha256", "data"));
    var index = integer(chunk, "index", 0, 255);
    var byteLength = integer(chunk, "byteLength", 1, 1024 * 1024);
    var expectedHash = sha256(chunk, "sha256");
    var encoded = string(chunk, "data", 4, 1_398_104);
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
    }
    if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)
        || decoded.length != byteLength
        || !ClientTransferManager.sha256(decoded).equals(expectedHash)) {
      invalid();
    }
    return new ValidatedChunk(index, byteLength);
  }

  private record ValidatedChunk(int index, int byteLength) {}

  private static void boundedJson(JsonElement value, int depth, int[] count) {
    if (depth > 8 || ++count[0] > 512) {
      invalid();
    }
    if (value.isJsonObject()) {
      if (value.getAsJsonObject().size() > 64) {
        invalid();
      }
      value
          .getAsJsonObject()
          .entrySet()
          .forEach(entry -> boundedJson(entry.getValue(), depth + 1, count));
    } else if (value.isJsonArray()) {
      if (value.getAsJsonArray().size() > 128) {
        invalid();
      }
      value.getAsJsonArray().forEach(element -> boundedJson(element, depth + 1, count));
    } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      string(value, 0, 4096);
    } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
      try {
        new BigDecimal(value.getAsString());
      } catch (NumberFormatException error) {
        invalid();
      }
    }
  }

  private static String namespacedId(JsonObject object, String name) {
    var value = string(object, name, 3, 256);
    if (!NAMESPACED_ID.matcher(value).matches()) {
      invalid();
    }
    return value;
  }

  private static String sha256(JsonObject object, String name) {
    var value = string(object, name, 64, 64);
    if (!SHA256.matcher(value).matches()) {
      invalid();
    }
    return value;
  }

  private static void requireLiteral(JsonObject object, String name, String expected) {
    if (!expected.equals(string(object, name, expected.length(), expected.length()))) {
      invalid();
    }
  }

  private static String requireEnum(JsonObject object, String name, Set<String> values) {
    var value = string(object, name, 1, 64);
    if (!values.contains(value)) {
      invalid();
    }
    return value;
  }

  private static JsonObject object(JsonObject parent, String name) {
    var value = parent.get(name);
    return asObject(value);
  }

  private static JsonObject asObject(JsonElement value) {
    if (value == null || !value.isJsonObject()) {
      invalid();
    }
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonObject parent, String name, int minimum, int maximum) {
    var value = parent.get(name);
    if (value == null || !value.isJsonArray()) {
      invalid();
    }
    var array = value.getAsJsonArray();
    if (array.size() < minimum || array.size() > maximum) {
      invalid();
    }
    return array;
  }

  private static String string(JsonObject parent, String name, int minimum, int maximum) {
    return string(parent.get(name), minimum, maximum);
  }

  private static String string(JsonElement value, int minimum, int maximum) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      invalid();
    }
    var text = value.getAsString();
    int length = text.codePointCount(0, text.length());
    if (length < minimum || length > maximum || !wellFormed(text)) {
      invalid();
    }
    return text;
  }

  private static String visibleString(JsonObject parent, String name, int minimum, int maximum) {
    return visibleString(parent.get(name), minimum, maximum);
  }

  private static String visibleString(JsonElement value, int minimum, int maximum) {
    return visibleString(value, minimum, maximum, false);
  }

  private static String visibleString(
      JsonObject parent, String name, int minimum, int maximum, boolean allowLineFormatting) {
    return visibleString(parent.get(name), minimum, maximum, allowLineFormatting);
  }

  private static String visibleString(
      JsonElement value, int minimum, int maximum, boolean allowLineFormatting) {
    var text = string(value, minimum, maximum);
    if (text.codePoints()
        .anyMatch(codePoint -> unsafeVisibleCodePoint(codePoint, allowLineFormatting))) {
      invalid();
    }
    return text;
  }

  private static boolean unsafeVisibleCodePoint(int value, boolean allowLineFormatting) {
    if (allowLineFormatting && (value == '\n' || value == '\t')) {
      return false;
    }
    return value <= 0x1f
        || value >= 0x7f && value <= 0x9f
        || value == 0x061c
        || value == 0x200e
        || value == 0x200f
        || value >= 0x202a && value <= 0x202e
        || value >= 0x2066 && value <= 0x2069;
  }

  private static boolean bool(JsonObject parent, String name) {
    var value = parent.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      invalid();
    }
    return value.getAsBoolean();
  }

  private static int integer(JsonObject parent, String name, int minimum, int maximum) {
    var value = parent.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      invalid();
    }
    try {
      var number = new BigDecimal(value.getAsString()).intValueExact();
      if (number < minimum || number > maximum) {
        invalid();
      }
      return number;
    } catch (NumberFormatException | ArithmeticException error) {
      throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
    }
  }

  private static BigDecimal decimal(
      JsonObject parent, String name, BigDecimal minimum, BigDecimal maximum) {
    var value = parent.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      invalid();
    }
    try {
      var number = new BigDecimal(value.getAsString());
      if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
        invalid();
      }
      return number;
    } catch (NumberFormatException error) {
      throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
    }
  }

  private static UUID uuid(JsonObject parent, String name) {
    var text = string(parent, name, 36, 36);
    try {
      var uuid = UUID.fromString(text);
      if (!uuid.toString().equals(text)) {
        invalid();
      }
      return uuid;
    } catch (IllegalArgumentException error) {
      throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
    }
  }

  private static void requireFields(JsonObject object, Set<String> expected) {
    if (!object.keySet().equals(expected)) {
      invalid();
    }
  }

  private static void requireAllowed(JsonObject object, Set<String> allowed) {
    if (!allowed.containsAll(object.keySet())) {
      invalid();
    }
  }

  private static void requirePresent(JsonObject object, Set<String> required) {
    if (!object.keySet().containsAll(required)) {
      invalid();
    }
  }

  private static boolean wellFormed(String text) {
    for (var index = 0; index < text.length(); index++) {
      var value = text.charAt(index);
      if (Character.isHighSurrogate(value)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
          return false;
        }
      } else if (Character.isLowSurrogate(value)) {
        return false;
      }
    }
    return true;
  }

  private static void invalid() {
    throw new ClientProtocolException("CLIENT_VIEW_CONTENT_INVALID");
  }
}
