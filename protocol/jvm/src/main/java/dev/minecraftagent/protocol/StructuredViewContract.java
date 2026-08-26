package dev.minecraftagent.protocol;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The limits and shapes of a structured view.
 *
 * <p>Constants are transcribed from {@code protocol/schemas/structured-view.schema.json} and the
 * schemas it delegates to ({@code recipe-view.schema.json}, {@code recipe-view-v2.schema.json},
 * {@code tools/common.schema.json}, {@code build-preview.schema.json} and {@code
 * proposal.schema.json}). Those schemas are authoritative: when the Paper plugin and the Fabric
 * client mod disagreed on a bound, this class takes the schema value and the reconciliation is
 * reported, not silently inherited from one end.
 */
public final class StructuredViewContract {
  /** View schema version every structured view declares. */
  public static final String VIEW_SCHEMA_VERSION = ClientChannelContract.VIEW_SCHEMA_VERSION;

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_TEXT = "text";

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_ITEM_STACK = "item_stack";

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_ITEM_LIST = "item_list";

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_RECIPE = "recipe";

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_BUILD_PREVIEW = "build_preview";

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_PROPOSAL = "proposal";

  /** View types, in structured-view.schema.json enum order. */
  public static final String VIEW_TYPE_SELECTION_LIST = "selection_list";

  /** Every view type the schema allows; which ones an end supports is that end's policy. */
  public static final Set<String> VIEW_TYPES =
      Set.of(
          VIEW_TYPE_TEXT,
          VIEW_TYPE_ITEM_STACK,
          VIEW_TYPE_ITEM_LIST,
          VIEW_TYPE_RECIPE,
          VIEW_TYPE_BUILD_PREVIEW,
          VIEW_TYPE_PROPOSAL,
          VIEW_TYPE_SELECTION_LIST);

  /** Exact key set of a structured view envelope. */
  public static final Set<String> VIEW_FIELDS =
      Set.of(
          "viewSchemaVersion",
          "viewId",
          "requestId",
          "viewType",
          "revision",
          "title",
          "fallbackText",
          "pinnable",
          "content");

  // Outer view bounds.
  public static final int VIEW_TYPE_MAX_CHARS = 32;
  public static final int TITLE_MIN_CHARS = 1;
  public static final int TITLE_MAX_CHARS = 128;
  public static final int FALLBACK_TEXT_MIN_CHARS = 1;
  public static final int FALLBACK_TEXT_MAX_CHARS = 8192;
  public static final int REVISION_MIN = ClientPayloadLimits.REVISION_MIN;
  public static final int REVISION_MAX = ClientPayloadLimits.REVISION_MAX;
  public static final int UUID_CHARS = ClientPayloadLimits.UUID_CHARS;

  // Text content.
  public static final Set<String> TEXT_CONTENT_FIELDS = Set.of("text");
  public static final int TEXT_MIN_CHARS = 1;
  public static final int TEXT_MAX_CHARS = 32768;

  // Item stacks and item lists.
  public static final Set<String> ITEM_STACK_FIELDS = Set.of("itemId", "count", "components");
  public static final Set<String> COMPONENT_FIELDS =
      Set.of("customName", "lore", "damage", "maxDamage", "customModelData", "enchantmentGlint");
  public static final Set<String> ITEM_LIST_FIELDS = Set.of("items");
  public static final int ITEM_LIST_MIN_ITEMS = 1;
  public static final int ITEM_LIST_MAX_ITEMS = 128;
  public static final int ITEM_COUNT_MIN = 1;
  public static final int ITEM_COUNT_MAX = 999999;
  public static final int COMPONENT_TEXT_MIN_CHARS = 0;
  public static final int COMPONENT_TEXT_MAX_CHARS = 512;
  public static final int LORE_MIN_ITEMS = 0;
  public static final int LORE_MAX_ITEMS = 32;
  public static final int DAMAGE_MIN = 0;
  public static final int DAMAGE_MAX = Integer.MAX_VALUE;
  public static final int MAX_DAMAGE_MIN = 1;
  public static final int MAX_DAMAGE_MAX = Integer.MAX_VALUE;
  public static final int CUSTOM_MODEL_DATA_MIN = 0;
  public static final int CUSTOM_MODEL_DATA_MAX = Integer.MAX_VALUE;

  // Selection list content.
  public static final Set<String> SELECTION_LIST_FIELDS = Set.of("prompt", "options");
  public static final int SELECTION_PROMPT_MIN_CHARS = 1;
  public static final int SELECTION_PROMPT_MAX_CHARS = 512;
  public static final int SELECTION_MIN_OPTIONS = 1;
  public static final int SELECTION_MAX_OPTIONS = 64;
  public static final Set<String> SELECTION_OPTION_FIELDS = Set.of("id", "label", "description");
  public static final Set<String> SELECTION_OPTION_REQUIRED_FIELDS = Set.of("id", "label");
  public static final int SELECTION_ID_MIN_CHARS = 1;
  public static final int SELECTION_ID_MAX_CHARS = 64;
  public static final int SELECTION_LABEL_MIN_CHARS = 1;
  public static final int SELECTION_LABEL_MAX_CHARS = 128;
  public static final int SELECTION_DESCRIPTION_MIN_CHARS = 0;
  public static final int SELECTION_DESCRIPTION_MAX_CHARS = 512;

  // Proposal content.
  public static final Set<String> PROPOSAL_FIELDS =
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
          "expiresAt");
  public static final Set<String> PROPOSAL_REQUIRED_FIELDS =
      Set.of(
          "proposalId",
          "sessionId",
          "playerUuid",
          "tool",
          "arguments",
          "argumentHash",
          "risk",
          "summary",
          "expiresAt");
  public static final int PROPOSAL_TOOL_MIN_CHARS = 3;
  public static final int PROPOSAL_TOOL_MAX_CHARS = 128;
  public static final int PROPOSAL_MAX_ARGUMENTS = 64;
  public static final int PROPOSAL_SUMMARY_MIN_CHARS = 1;
  public static final int PROPOSAL_SUMMARY_MAX_CHARS = 2048;
  public static final int PROPOSAL_EXPIRES_AT_MAX_CHARS = 64;
  public static final Set<String> PROPOSAL_RISKS =
      Set.of("WRITE_TEMPORARY", "WRITE_WORLD", "WRITE_PLAYER", "SERVER_ADMIN");

  /** Depth bound for the free-form proposal arguments object. */
  public static final int PROPOSAL_ARGUMENTS_MAX_DEPTH = 8;

  /** Total node bound for the free-form proposal arguments object. */
  public static final int PROPOSAL_ARGUMENTS_MAX_NODES = 512;

  /** Per-string bound inside the free-form proposal arguments object. */
  public static final int PROPOSAL_ARGUMENTS_STRING_MAX_CHARS = 4096;

  /** Field bound for an object inside the free-form proposal arguments object. */
  public static final int PROPOSAL_ARGUMENTS_MAX_FIELDS = 64;

  /** Item bound for an array inside the free-form proposal arguments object. */
  public static final int PROPOSAL_ARGUMENTS_MAX_ITEMS = 128;

  // Build preview content.
  public static final Set<String> PREVIEW_FIELDS =
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
          "chunks");
  public static final Set<String> PREVIEW_OPERATIONS = Set.of("create", "modify");
  public static final Set<String> PREVIEW_ENCODINGS = Set.of("identity+base64", "gzip+base64");
  public static final String PREVIEW_CONTENT_FORMAT = "minecraft-agent.palette-v1";
  public static final int PREVIEW_COMPRESSED_BYTES_MIN = 1;
  public static final int PREVIEW_COMPRESSED_BYTES_MAX = 16_777_216;
  public static final int PREVIEW_UNCOMPRESSED_BYTES_MIN = 1;
  public static final int PREVIEW_UNCOMPRESSED_BYTES_MAX = 67_108_864;
  public static final int PREVIEW_BLOCK_COUNT_MIN = 0;
  public static final int PREVIEW_BLOCK_COUNT_MAX = 250_000;
  public static final Set<String> PREVIEW_BOUNDS_FIELDS = Set.of("min", "max");
  public static final Set<String> PREVIEW_POSITION_FIELDS = Set.of("x", "y", "z");
  public static final Set<String> PREVIEW_TRANSFORM_FIELDS = Set.of("rotation", "mirror");
  public static final Set<Integer> PREVIEW_ROTATIONS = Set.of(0, 90, 180, 270);
  public static final int PREVIEW_ROTATION_MIN = 0;
  public static final int PREVIEW_ROTATION_MAX = 270;
  public static final Set<String> PREVIEW_MIRRORS = Set.of("NONE", "LEFT_RIGHT", "FRONT_BACK");
  public static final Set<String> PREVIEW_DIFFERENCE_FIELDS =
      Set.of("added", "replaced", "removed");
  public static final int PREVIEW_CHANGE_MIN = 0;
  public static final int PREVIEW_CHANGE_MAX = 250_000;
  public static final int PREVIEW_PALETTE_MIN_ITEMS = 0;
  public static final int PREVIEW_PALETTE_MAX_ITEMS = 4096;
  public static final Set<String> PREVIEW_PALETTE_ENTRY_FIELDS =
      Set.of("id", "blockId", "properties");
  public static final int PREVIEW_PALETTE_ID_MIN = 0;
  public static final int PREVIEW_PALETTE_ID_MAX = 4095;
  public static final int PREVIEW_PALETTE_MAX_PROPERTIES = 32;
  public static final int PREVIEW_PROPERTY_VALUE_MIN_CHARS = 1;
  public static final int PREVIEW_PROPERTY_VALUE_MAX_CHARS = 64;
  public static final int PREVIEW_CHUNK_MIN_ITEMS = 1;
  public static final int PREVIEW_CHUNK_MAX_ITEMS = 256;
  public static final Set<String> PREVIEW_CHUNK_FIELDS =
      Set.of("index", "byteLength", "sha256", "data");
  public static final int PREVIEW_CHUNK_INDEX_MIN = 0;
  public static final int PREVIEW_CHUNK_INDEX_MAX = 255;
  public static final int PREVIEW_CHUNK_BYTES_MIN = 1;
  public static final int PREVIEW_CHUNK_BYTES_MAX = 1_048_576;
  public static final int PREVIEW_CHUNK_DATA_MIN_CHARS = 4;
  public static final int PREVIEW_CHUNK_DATA_MAX_CHARS = 1_398_104;
  public static final int POSITION_X_MIN = -30_000_000;
  public static final int POSITION_X_MAX = 30_000_000;
  public static final int POSITION_Y_MIN = -2048;
  public static final int POSITION_Y_MAX = 2048;
  public static final int POSITION_Z_MIN = -30_000_000;
  public static final int POSITION_Z_MAX = 30_000_000;

  // Recipe content, shared by v1 and v2.
  public static final String RECIPE_SCHEMA_VERSION_V1 = "1.0";

  /** Recipe content schema version introduced with truncated, bounded recipe results. */
  public static final String RECIPE_SCHEMA_VERSION_V2 = "2.0";

  public static final Set<String> RECIPE_QUERY_FIELDS = Set.of("mode", "itemId");
  public static final Set<String> RECIPE_QUERY_MODES = Set.of("lookup", "uses");
  public static final Set<String> RECIPE_V1_FIELDS =
      Set.of("schemaVersion", "query", "selectedRecipe", "recipes");
  public static final int RECIPE_V1_SELECTED_MIN = 0;
  public static final int RECIPE_V1_SELECTED_MAX = 127;
  public static final int RECIPE_V1_MIN_ITEMS = 1;
  public static final int RECIPE_V1_MAX_ITEMS = 128;
  public static final Set<String> RECIPE_V2_FIELDS =
      Set.of("schemaVersion", "query", "selectedRecipe", "totalMatches", "truncated", "recipes");
  public static final int RECIPE_V2_SELECTED_MIN = 0;
  public static final int RECIPE_V2_SELECTED_MAX = 15;
  public static final int RECIPE_V2_MIN_ITEMS = 1;
  public static final int RECIPE_V2_MAX_ITEMS = 16;
  public static final int RECIPE_TOTAL_MATCHES_MIN = 1;
  public static final int RECIPE_TOTAL_MATCHES_MAX = 1_000_000;
  public static final Set<String> RECIPE_FIELDS =
      Set.of(
          "recipeId", "recipeType", "source", "result", "layout", "remainingItems", "processing");
  public static final Set<String> RECIPE_REQUIRED_FIELDS =
      Set.of("recipeId", "recipeType", "source", "result", "layout", "remainingItems");
  public static final Set<String> RECIPE_V1_TYPES =
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
          "custom");
  public static final Set<String> RECIPE_V2_TYPES =
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
          "custom");
  public static final Set<String> RECIPE_COOKING_TYPES =
      Set.of("smelting", "blasting", "smoking", "campfire_cooking");
  public static final Set<String> RECIPE_V1_SOURCE_FIELDS = Set.of("kind", "providerId");
  public static final Set<String> RECIPE_V1_SOURCE_KINDS =
      Set.of(
          "server_registry",
          "plugin_provider",
          "server_docs",
          "web_documentation",
          "model_knowledge");
  public static final Set<String> RECIPE_V2_SOURCE_KINDS =
      Set.of("server_registry", "plugin_provider");

  /** Registry kind, the only v2 source kind that must leave {@code providerId} null. */
  public static final String RECIPE_SOURCE_KIND_SERVER_REGISTRY = "server_registry";

  public static final Set<String> REMAINING_ITEM_FIELDS = Set.of("slot", "item");
  public static final int REMAINING_ITEMS_MIN_ITEMS = 0;
  public static final int REMAINING_ITEMS_MAX_ITEMS = 9;
  public static final Set<String> PROCESSING_FIELDS = Set.of("timeTicks", "experience");
  public static final int TIME_TICKS_V1_MIN = 1;
  public static final int TIME_TICKS_V2_MIN = 0;
  public static final int TIME_TICKS_MAX = 120_000;
  public static final BigDecimal EXPERIENCE_MIN = BigDecimal.ZERO;
  public static final BigDecimal EXPERIENCE_MAX = BigDecimal.valueOf(1_000_000);

  // Grid layouts.
  public static final Set<String> RECIPE_V1_LAYOUT_FIELDS =
      Set.of("width", "height", "ingredients");

  /**
   * Grid layout fields as of recipe schema 2.0, which introduced the {@code kind} discriminator.
   */
  public static final Set<String> GRID_LAYOUT_FIELDS =
      Set.of("kind", "width", "height", "ingredients");

  public static final Set<String> SINGLE_INPUT_LAYOUT_FIELDS = Set.of("kind", "ingredient");
  public static final Set<String> SMITHING_LAYOUT_FIELDS =
      Set.of("kind", "template", "base", "addition");
  public static final Set<String> TRANSMUTE_LAYOUT_FIELDS = Set.of("kind", "input", "material");
  public static final Set<String> UNSUPPORTED_LAYOUT_FIELDS = Set.of("kind", "reason");
  public static final String LAYOUT_KIND_GRID = "grid";

  /** Layout kind for smelting, blasting, smoking, campfire cooking and stonecutting. */
  public static final String LAYOUT_KIND_SINGLE_INPUT = "single_input";

  public static final String LAYOUT_KIND_SMITHING = "smithing";
  public static final String LAYOUT_KIND_TRANSMUTE = "transmute";
  public static final String LAYOUT_KIND_UNSUPPORTED = "unsupported";
  public static final String LAYOUT_REASON_UNSUPPORTED = "UNSUPPORTED_RECIPE_LAYOUT";
  public static final int GRID_WIDTH_MIN = 1;
  public static final int GRID_WIDTH_MAX = 3;
  public static final int GRID_HEIGHT_MIN = 1;
  public static final int GRID_HEIGHT_MAX = 3;
  public static final int INGREDIENTS_MIN_ITEMS = 1;
  public static final int INGREDIENTS_MAX_ITEMS = 9;
  public static final Set<String> INGREDIENT_SLOT_FIELDS = Set.of("slot", "x", "y", "ingredient");
  public static final int SLOT_MIN = 0;
  public static final int SLOT_MAX = 8;
  public static final int COORDINATE_MIN = 0;
  public static final int COORDINATE_MAX = 2;

  // Ingredient choices.
  public static final Set<String> RECIPE_V1_CHOICE_FIELDS =
      Set.of("choiceType", "tagId", "alternatives");

  /** Ingredient choice fields as of recipe schema 2.0, which introduced {@code reason}. */
  public static final Set<String> INGREDIENT_CHOICE_FIELDS =
      Set.of("choiceType", "tagId", "reason", "alternatives");

  public static final Set<String> INGREDIENT_CHOICE_REQUIRED_FIELDS =
      Set.of("choiceType", "alternatives");
  public static final Set<String> RECIPE_V1_CHOICE_TYPES = Set.of("material", "exact", "tag");
  public static final Set<String> RECIPE_V2_CHOICE_TYPES =
      Set.of("material", "exact", "item_type", "tag", "unsupported");
  public static final String CHOICE_REASON_UNSUPPORTED = "UNSUPPORTED_INGREDIENT_CHOICE";

  /** Exact key set of an ingredient choice that carries a tag. */
  public static final Set<String> TAGGED_CHOICE_FIELDS =
      Set.of("choiceType", "tagId", "alternatives");

  /** Exact key set of an ingredient choice that lists alternatives only. */
  public static final Set<String> SIMPLE_CHOICE_FIELDS = Set.of("choiceType", "alternatives");

  /** Exact key set of an ingredient choice an end is not expected to render. */
  public static final Set<String> UNSUPPORTED_CHOICE_FIELDS =
      Set.of("choiceType", "reason", "alternatives");

  public static final int ALTERNATIVES_MIN_ITEMS = 1;
  public static final int ALTERNATIVES_MAX_ITEMS = 64;

  // Shared shapes.
  public static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
  public static final int NAMESPACED_ID_MIN_CHARS = 3;
  public static final int NAMESPACED_ID_MAX_CHARS = 256;
  public static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
  public static final int SHA256_CHARS = 64;
  public static final Pattern SELECTION_ID = Pattern.compile("[A-Za-z0-9._-]+");
  public static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
  public static final Pattern BLOCK_PROPERTY_NAME = Pattern.compile("[a-z0-9_]+");
  public static final Pattern BLOCK_PROPERTY_VALUE = Pattern.compile("[A-Za-z0-9_.-]+");
  public static final Pattern INTEGER_LITERAL = Pattern.compile("-?(?:0|[1-9][0-9]*)");

  private StructuredViewContract() {}

  /** Number of Unicode code points in {@code text}, which is how the schemas measure length. */
  public static int codePointLength(String text) {
    return text.codePointCount(0, text.length());
  }

  /** Whether {@code text} is valid UTF-16, that is, built from well-formed surrogate pairs only. */
  public static boolean isWellFormedUtf16(String text) {
    for (var index = 0; index < text.length(); index++) {
      var character = text.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
          return false;
        }
      } else if (Character.isLowSurrogate(character)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a code point may be rendered to a player.
   *
   * <p>Rejects C0 and C1 control characters, bidi controls and isolate controls. Line feed and tab
   * are allowed only where the field is documented to carry formatted text.
   */
  public static boolean isUnsafeVisibleCodePoint(int codePoint, boolean allowLineFormatting) {
    if (allowLineFormatting && (codePoint == '\n' || codePoint == '\t')) {
      return false;
    }
    return codePoint <= 0x1f
        || codePoint >= 0x7f && codePoint <= 0x9f
        || codePoint == 0x061c
        || codePoint == 0x200e
        || codePoint == 0x200f
        || codePoint >= 0x202a && codePoint <= 0x202e
        || codePoint >= 0x2066 && codePoint <= 0x2069;
  }

  /** Whether {@code text} is well formed, in length and free of unsafe code points. */
  public static boolean isVisibleText(
      String text, int minimumChars, int maximumChars, boolean allowLineFormatting) {
    if (text == null
        || !isWellFormedUtf16(text)
        || codePointLength(text) < minimumChars
        || codePointLength(text) > maximumChars) {
      return false;
    }
    return text.codePoints()
        .noneMatch(codePoint -> isUnsafeVisibleCodePoint(codePoint, allowLineFormatting));
  }

  /** Whether {@code text} is a canonical lowercase UUID. */
  public static boolean isCanonicalUuid(String text) {
    try {
      return UUID.fromString(text).toString().equals(text);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  /** Lowercase hexadecimal SHA-256 of {@code bytes}. */
  public static String sha256Hex(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
