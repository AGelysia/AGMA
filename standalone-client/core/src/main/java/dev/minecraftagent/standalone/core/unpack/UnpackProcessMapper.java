package dev.minecraftagent.standalone.core.unpack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Maps one unpacked recipe into an {@link UnpackedProcess} candidate using generic mod conventions
 * plus a small known-type table. Vanilla-typed recipes are skipped because the live game APIs
 * already cover them; dynamic or special serializers that carry no machine-readable item flow are
 * skipped as well. A recipe whose primary output cannot be resolved is counted as {@link
 * SkipReason#NO_ITEM_OUTPUT}; fluid outputs are supported because the contract allows fluid
 * resources, so "no item output" strictly means "no usable primary output at all" (for example a
 * brew definition that only names a potion id).
 *
 * <p>Fluid amounts use a documented heuristic: ingredient {@code amount} values above 1000 are
 * treated as fabric droplets (81 droplets = 1 millibucket) and divided by 81, smaller values are
 * taken as millibuckets directly; an explicit {@code millibuckets} value is always taken verbatim.
 */
public final class UnpackProcessMapper {
  /**
   * Synthetic recipe key carrying raw script arguments that the script parsers could not map
   * structurally; each entry becomes a process condition so the context stays visible.
   */
  public static final String SCRIPT_ARGUMENTS_KEY = "agma:script_arguments";

  public static final int MAXIMUM_ALTERNATIVES = 64;
  private static final int MAXIMUM_TAG_DEPTH = 8;
  private static final int MAXIMUM_GROUPS = 128;
  private static final int MAXIMUM_CONDITIONS = 64;
  private static final long MAXIMUM_AMOUNT = 1_000_000_000L;
  private static final BigDecimal MILLIBUCKET_DROPLETS = new BigDecimal("81");
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern GROUP_ID = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");
  private static final List<String> INPUT_KEYS =
      List.of("ingredients", "inputs", "ingredient", "input");
  private static final List<String> DURATION_KEYS =
      List.of("processingTime", "cookingtime", "time");
  private static final List<String> CONDITION_FIELDS =
      List.of("power", "energy", "mana", "heat", "mode", "circumstance", "acceptMirrored");
  private static final Set<String> SKIP_TYPES =
      Set.of("botania:dynamic", "ae2:facade", "create:item_copying", "reborncore:padded");

  /** Recipe type to workstation block id for the types the live game APIs cannot see. */
  private static final Map<String, String> KNOWN_WORKSTATIONS =
      Map.ofEntries(
          Map.entry("create:mechanical_crafting", "create:mechanical_crafter"),
          Map.entry("create:crushing", "create:crushing_wheel"),
          Map.entry("create:milling", "create:millstone"),
          Map.entry("create:mixing", "create:mechanical_mixer"),
          Map.entry("create:pressing", "create:mechanical_press"),
          Map.entry("create:deploying", "create:deployer"),
          Map.entry("create:item_application", "create:deployer"),
          Map.entry("create:splashing", "create:encased_fan"),
          Map.entry("create:haunting", "create:encased_fan"),
          Map.entry("create:filling", "create:spout"),
          Map.entry("create:emptying", "create:item_drain"),
          Map.entry("create:compacting", "create:mechanical_press"),
          Map.entry("create:cutting", "create:mechanical_saw"),
          Map.entry("botania:runic_altar", "botania:runic_altar"),
          Map.entry("botania:terra_plate", "botania:terra_plate"),
          Map.entry("botania:petal_apothecary", "botania:apothecary_default"),
          Map.entry("botania:mana_infusion", "botania:mana_pool"),
          Map.entry("botania:elven_trade", "botania:alfheim_portal"),
          Map.entry("botania:brew", "botania:brewery"),
          Map.entry("ae2:inscriber", "ae2:inscriber"),
          Map.entry("ae2:charger", "ae2:charger"),
          Map.entry("ad_astra:nasa_workbench", "ad_astra:nasa_workbench"),
          Map.entry("ad_astra:compressing", "ad_astra:compressor"),
          Map.entry("ad_astra:refining", "ad_astra:fuel_refinery"),
          Map.entry("ad_astra:cryo_freezing", "ad_astra:cryo_freezer"),
          Map.entry("ad_astra:oxygen_loading", "ad_astra:oxygen_loader"),
          // Script-defined recipe kinds map to the same stations their vanilla twins use.
          Map.entry("kubejs:shaped", "minecraft:crafting_table"),
          Map.entry("kubejs:shapeless", "minecraft:crafting_table"),
          Map.entry("kubejs:smelting", "minecraft:furnace"),
          Map.entry("kubejs:blasting", "minecraft:blast_furnace"),
          Map.entry("kubejs:smoking", "minecraft:smoker"),
          Map.entry("kubejs:campfire_cooking", "minecraft:campfire"),
          Map.entry("kubejs:stonecutting", "minecraft:stonecutter"),
          Map.entry("crafttweaker:shaped", "minecraft:crafting_table"),
          Map.entry("crafttweaker:shapeless", "minecraft:crafting_table"),
          Map.entry("crafttweaker:smelting", "minecraft:furnace"),
          Map.entry("crafttweaker:blasting", "minecraft:blast_furnace"),
          Map.entry("crafttweaker:smoking", "minecraft:smoker"),
          Map.entry("crafttweaker:campfire_cooking", "minecraft:campfire"));

  /** Access to the merged tag maps of the scanned modpack. */
  @FunctionalInterface
  public interface TagLookup {
    List<UnpackedTag.Entry> tag(UnpackedTag.Kind kind, String id);
  }

  public enum SkipReason {
    VANILLA_TYPE,
    DYNAMIC_TYPE,
    NO_ITEM_OUTPUT,
    INVALID_TYPE
  }

  public sealed interface Outcome {
    record Mapped(UnpackedProcess process) implements Outcome {}

    record Skipped(SkipReason reason) implements Outcome {}
  }

  public Outcome map(UnpackedRecipe recipe, TagLookup tags, Predicate<String> blockExists) {
    Objects.requireNonNull(recipe, "recipe");
    Objects.requireNonNull(tags, "tags");
    Objects.requireNonNull(blockExists, "blockExists");
    var type = recipe.type();
    if (!NAMESPACED_ID.matcher(type).matches()) {
      return new Outcome.Skipped(SkipReason.INVALID_TYPE);
    }
    if (type.startsWith("minecraft:")) {
      return new Outcome.Skipped(SkipReason.VANILLA_TYPE);
    }
    if (isDynamic(type)) {
      return new Outcome.Skipped(SkipReason.DYNAMIC_TYPE);
    }
    var json = recipe.json();
    var state = new MappingState(tags);

    var shaped = json.get("pattern") instanceof List<?> && json.get("key") instanceof Map<?, ?>;
    var inputs = shaped ? shapedGroups(json, state) : discoveredGroups(json, state);
    if (shaped) {
      state.condition("shaped: pattern retained in recipe viewer");
    }

    var outputs = discoverOutputs(json);
    if (outputs.isEmpty()) {
      return new Outcome.Skipped(SkipReason.NO_ITEM_OUTPUT);
    }

    var conditions = new ArrayList<String>(state.conditions);
    appendCostConditions(json, conditions);
    appendLoadConditions(json, conditions, state.warnings);
    if (json.get(SCRIPT_ARGUMENTS_KEY) instanceof List<?> scriptArguments) {
      for (var argument : scriptArguments) {
        if (argument instanceof String text
            && !text.isBlank()
            && conditions.size() < MAXIMUM_CONDITIONS) {
          conditions.add(text.length() <= 480 ? text : text.substring(0, 480));
        }
      }
    }

    var plannable = state.complete;
    var warnings = new ArrayList<String>(state.warnings);
    if (inputs.isEmpty()) {
      plannable = false;
      warnings.add("no input groups discovered");
    } else {
      for (var index = 0; index < inputs.size(); index++) {
        if (inputs.get(index).alternatives().isEmpty()) {
          plannable = false;
          warnings.add(
              "input group "
                  + groupName(inputs.get(index), index)
                  + " has no resolvable item or fluid alternative");
        }
      }
    }

    return new Outcome.Mapped(
        new UnpackedProcess(
            recipe.id(),
            type,
            inputs,
            outputs,
            catalystId(json),
            duration(json),
            List.copyOf(conditions.subList(0, Math.min(conditions.size(), MAXIMUM_CONDITIONS))),
            List.copyOf(warnings.subList(0, Math.min(warnings.size(), MAXIMUM_CONDITIONS))),
            workstation(type, blockExists),
            plannable));
  }

  private static boolean isDynamic(String type) {
    if (SKIP_TYPES.contains(type)) {
      return true;
    }
    var path = type.substring(type.indexOf(':') + 1);
    return path.equals("dynamic") || path.startsWith("dynamic/") || path.contains("/dynamic/");
  }

  private List<UnpackedProcess.Group> discoveredGroups(
      Map<String, Object> json, MappingState state) {
    Object source = null;
    for (var key : INPUT_KEYS) {
      if (json.containsKey(key)) {
        source = json.get(key);
        break;
      }
    }
    var groups = new ArrayList<UnpackedProcess.Group>();
    if (source == null) {
      return groups;
    }
    if (source instanceof List<?> list) {
      for (var element : list) {
        addGroup(groups, null, element, UnpackedTag.Kind.ITEM, null, state);
      }
    } else if (source instanceof Map<?, ?> map) {
      var wrapper = fluidWrapper(map);
      if (wrapper != null) {
        addGroup(
            groups,
            null,
            wrapper.ingredient(),
            UnpackedTag.Kind.FLUID,
            wrapper.millibuckets(),
            state);
      } else if (isIngredientMap(map)) {
        addGroup(groups, null, map, UnpackedTag.Kind.ITEM, null, state);
      } else {
        // An object map of named slots (for example an inscriber's top/middle/bottom).
        for (var entry : map.entrySet()) {
          if (entry.getKey() instanceof String slot) {
            addGroup(groups, slot, entry.getValue(), UnpackedTag.Kind.ITEM, null, state);
          }
        }
      }
    } else if (source instanceof String) {
      addGroup(groups, null, source, UnpackedTag.Kind.ITEM, null, state);
    }
    if (groups.size() > MAXIMUM_GROUPS) {
      state.fail("input groups truncated at " + MAXIMUM_GROUPS);
      return new ArrayList<>(groups.subList(0, MAXIMUM_GROUPS));
    }
    return groups;
  }

  private List<UnpackedProcess.Group> shapedGroups(Map<String, Object> json, MappingState state) {
    @SuppressWarnings("unchecked")
    var key = (Map<String, Object>) json.get("key");
    var pattern = (List<?>) json.get("pattern");
    var order = new LinkedHashSet<String>();
    for (var row : pattern) {
      if (row instanceof String line) {
        for (var index = 0; index < line.length(); index++) {
          var symbol = String.valueOf(line.charAt(index));
          if (!symbol.isBlank() && key.containsKey(symbol)) {
            order.add(symbol);
          }
        }
      }
    }
    var groups = new ArrayList<UnpackedProcess.Group>();
    for (var symbol : order) {
      addGroup(
          groups,
          "key_" + symbol.toLowerCase(java.util.Locale.ROOT),
          key.get(symbol),
          UnpackedTag.Kind.ITEM,
          null,
          state);
    }
    return groups;
  }

  private void addGroup(
      List<UnpackedProcess.Group> groups,
      String slot,
      Object ingredient,
      UnpackedTag.Kind context,
      BigDecimal fluidMillibuckets,
      MappingState state) {
    var alternatives = new ArrayList<UnpackedProcess.Amount>();
    alternatives(
        ingredient, context, fluidMillibuckets, alternatives, state, new LinkedHashSet<>(), 0);
    var deduped = new ArrayList<UnpackedProcess.Amount>();
    var seen = new LinkedHashSet<String>();
    for (var alternative : alternatives) {
      if (seen.add(alternative.kind() + ":" + alternative.id())) {
        deduped.add(alternative);
      }
    }
    groups.add(new UnpackedProcess.Group(slot, deduped));
  }

  private void alternatives(
      Object ingredient,
      UnpackedTag.Kind context,
      BigDecimal fluidMillibuckets,
      List<UnpackedProcess.Amount> out,
      MappingState state,
      Set<String> visitedTags,
      int depth) {
    if (out.size() >= MAXIMUM_ALTERNATIVES) {
      state.fail("alternatives truncated at " + MAXIMUM_ALTERNATIVES);
      return;
    }
    if (ingredient instanceof List<?> list) {
      for (var element : list) {
        alternatives(element, context, fluidMillibuckets, out, state, visitedTags, depth);
      }
      return;
    }
    if (ingredient instanceof String id) {
      addAmount(out, context, id, BigDecimal.ONE, state);
      return;
    }
    if (!(ingredient instanceof Map<?, ?> map)) {
      return;
    }
    if (map.get("item") instanceof String item) {
      addAmount(out, UnpackedTag.Kind.ITEM, item, count(map, "count", "amount"), state);
      return;
    }
    if (map.get("fluid") instanceof String fluid) {
      var amount =
          fluidMillibuckets != null
              ? fluidMillibuckets
              : fluidAmount(map.get("millibuckets"), map.get("amount"));
      addAmount(out, UnpackedTag.Kind.FLUID, fluid, amount, state);
      return;
    }
    if (map.get("tag") instanceof String tag) {
      if (map.get("type") instanceof String) {
        // A typed circumstance object (for example a fluid circumstance) is a process
        // condition, never an input.
        state.condition("circumstance=" + compact(map));
        return;
      }
      var amount =
          context == UnpackedTag.Kind.FLUID
              ? fluidMillibuckets != null
                  ? fluidMillibuckets
                  : fluidAmount(map.get("millibuckets"), map.get("amount"))
              : count(map, "count", "amount");
      expandTag(context, tag, amount, out, state, visitedTags, depth);
    }
  }

  private void expandTag(
      UnpackedTag.Kind kind,
      String tagId,
      BigDecimal amount,
      List<UnpackedProcess.Amount> out,
      MappingState state,
      Set<String> visitedTags,
      int depth) {
    if (depth > MAXIMUM_TAG_DEPTH) {
      state.fail("tag expansion exceeded depth " + MAXIMUM_TAG_DEPTH + " at " + tagId);
      return;
    }
    if (!visitedTags.add(kind + ":" + tagId)) {
      return;
    }
    var entries = state.tags.tag(kind, tagId);
    if (entries == null || entries.isEmpty()) {
      state.fail("tag " + tagId + " not found in mod archives");
      return;
    }
    for (var entry : entries) {
      if (!entry.required()) {
        continue;
      }
      if (out.size() >= MAXIMUM_ALTERNATIVES) {
        state.fail("alternatives truncated at " + MAXIMUM_ALTERNATIVES);
        return;
      }
      if (entry.tagReference()) {
        expandTag(kind, entry.id().substring(1), amount, out, state, visitedTags, depth + 1);
      } else {
        addAmount(out, kind, entry.id(), amount, state);
      }
    }
  }

  private void addAmount(
      List<UnpackedProcess.Amount> out,
      UnpackedTag.Kind kind,
      String id,
      BigDecimal amount,
      MappingState state) {
    if (!NAMESPACED_ID.matcher(id).matches()) {
      state.fail("resource id " + id + " is not a valid namespaced id");
      return;
    }
    out.add(new UnpackedProcess.Amount(kind, id, amount));
  }

  private static List<UnpackedProcess.Output> discoverOutputs(Map<String, Object> json) {
    var raw = new ArrayList<>();
    if (json.get("results") instanceof List<?> results) {
      raw.addAll(results);
    } else if (json.get("result") != null) {
      raw.add(json.get("result"));
    } else if (json.get("output") instanceof List<?> outputs) {
      raw.addAll(outputs);
    } else if (json.get("output") != null) {
      raw.add(json.get("output"));
    } else if (json.get("outputs") instanceof List<?> outputs) {
      raw.addAll(outputs);
    }
    var parsed = new ArrayList<UnpackedProcess.Output>();
    for (var element : raw) {
      var output = output(element, parsed.isEmpty());
      if (output != null) {
        parsed.add(output);
      }
    }
    return parsed;
  }

  private static UnpackedProcess.Output output(Object element, boolean primary) {
    if (element instanceof String id) {
      return NAMESPACED_ID.matcher(id).matches()
          ? new UnpackedProcess.Output(UnpackedTag.Kind.ITEM, id, BigDecimal.ONE, 1.0, primary)
          : null;
    }
    if (!(element instanceof Map<?, ?> map)) {
      return null;
    }
    String itemId = null;
    UnpackedTag.Kind kind = UnpackedTag.Kind.ITEM;
    BigDecimal amount = null;
    if (map.get("item") instanceof String item) {
      itemId = item;
      amount = count(map, "count", "amount");
    } else if (map.get("id") instanceof String id) {
      itemId = id;
      amount = count(map, "count", "amount");
    } else if (map.get("fluid") instanceof String fluid) {
      itemId = fluid;
      kind = UnpackedTag.Kind.FLUID;
      amount = fluidAmount(map.get("millibuckets"), map.get("amount"));
    }
    if (itemId == null || !NAMESPACED_ID.matcher(itemId).matches()) {
      return null;
    }
    var chance = primary ? 1.0 : chance(map.get("chance"));
    return new UnpackedProcess.Output(kind, itemId, amount, chance, primary);
  }

  private static double chance(Object raw) {
    if (raw instanceof Number number) {
      var value = number.doubleValue();
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        return 1.0;
      }
      return Math.min(1.0, Math.max(0.0, value));
    }
    return 1.0;
  }

  private static BigDecimal count(Map<?, ?> map, String... keys) {
    for (var key : keys) {
      if (map.get(key) instanceof Number number) {
        var value = number.longValue();
        return BigDecimal.valueOf(Math.min(MAXIMUM_AMOUNT, Math.max(1, value)));
      }
    }
    return BigDecimal.ONE;
  }

  /**
   * Fluid amount heuristic: explicit {@code millibuckets} are taken verbatim; an {@code amount}
   * above 1000 is treated as fabric droplets (81 droplets = 1 millibucket) and converted, anything
   * smaller is already millibuckets. Results round to a thousandth of a millibucket.
   */
  private static BigDecimal fluidAmount(Object millibuckets, Object amount) {
    if (millibuckets instanceof Number explicit) {
      return boundedFluid(BigDecimal.valueOf(Math.max(0.0, explicit.doubleValue())));
    }
    if (amount instanceof Number raw) {
      var value = raw.doubleValue();
      var converted = value > 1000 ? value / 81.0 : value;
      return boundedFluid(BigDecimal.valueOf(Math.max(0.0, converted)));
    }
    return BigDecimal.ONE;
  }

  private static BigDecimal boundedFluid(BigDecimal value) {
    var scaled = value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    if (scaled.signum() <= 0) {
      return BigDecimal.ONE;
    }
    return scaled.compareTo(BigDecimal.valueOf(MAXIMUM_AMOUNT)) > 0
        ? BigDecimal.valueOf(MAXIMUM_AMOUNT)
        : scaled;
  }

  /** An {@code input} wrapper holding an {@code ingredient} sub-key plus an optional amount. */
  private record FluidWrapper(Object ingredient, BigDecimal millibuckets) {}

  private static FluidWrapper fluidWrapper(Map<?, ?> map) {
    if (map.containsKey("ingredient") && map.size() <= 3) {
      var millibuckets =
          map.get("millibuckets") instanceof Number number
              ? boundedFluid(BigDecimal.valueOf(Math.max(0.0, number.doubleValue())))
              : null;
      return new FluidWrapper(map.get("ingredient"), millibuckets);
    }
    return null;
  }

  private static boolean isIngredientMap(Map<?, ?> map) {
    return map.containsKey("item") || map.containsKey("tag") || map.containsKey("fluid");
  }

  private static Long duration(Map<String, Object> json) {
    for (var key : DURATION_KEYS) {
      if (json.get(key) instanceof Number number) {
        var ticks = number.longValue();
        if (ticks >= 0) {
          return Math.min(ticks, 86_400_000L);
        }
      }
    }
    return null;
  }

  private static String catalystId(Map<String, Object> json) {
    if (json.get("catalyst") instanceof Map<?, ?> catalyst
        && catalyst.get("block") instanceof String block
        && NAMESPACED_ID.matcher(block).matches()) {
      return block;
    }
    return null;
  }

  private static void appendCostConditions(Map<String, Object> json, List<String> conditions) {
    for (var key : CONDITION_FIELDS) {
      var value = json.get(key);
      if (value == null) {
        continue;
      }
      var rendered =
          value instanceof Map<?, ?> || value instanceof List<?>
              ? key + "=" + compact(value)
              : key + "=" + value;
      conditions.add(rendered);
    }
  }

  private static void appendLoadConditions(
      Map<String, Object> json, List<String> conditions, java.util.Collection<String> warnings) {
    var conditional = false;
    for (var key : List.of("conditions", "fabric:load_conditions")) {
      if (json.get(key) instanceof List<?> list) {
        for (var element : list) {
          conditions.add(compact(element));
          conditional = true;
        }
      }
    }
    if (conditional) {
      warnings.add("conditional recipe: requirements may not be active");
    }
  }

  private static String compact(Object value) {
    String rendered;
    if (value instanceof String text) {
      rendered = text;
    } else {
      try {
        rendered = BoundedJson.write(value);
      } catch (IllegalArgumentException failure) {
        rendered = String.valueOf(value);
      }
    }
    return rendered.length() <= 480 ? rendered : rendered.substring(0, 480);
  }

  private String workstation(String type, Predicate<String> blockExists) {
    var candidates = new ArrayList<String>(2);
    var known = KNOWN_WORKSTATIONS.get(type);
    if (known != null) {
      candidates.add(known);
    } else if (type.startsWith("techreborn:")) {
      candidates.add(type);
    }
    candidates.add(type);
    for (var candidate : candidates) {
      try {
        if (blockExists.test(candidate)) {
          return candidate;
        }
      } catch (RuntimeException failure) {
        return null;
      }
    }
    return null;
  }

  private static String groupName(UnpackedProcess.Group group, int index) {
    return group.slot() != null && GROUP_ID.matcher(group.slot()).matches()
        ? group.slot()
        : "input_" + index;
  }

  /** Mutable per-recipe mapping state: collects conditions, warnings, and the plannable flag. */
  private static final class MappingState {
    private final TagLookup tags;
    private final List<String> conditions = new ArrayList<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private boolean complete = true;

    private MappingState(TagLookup tags) {
      this.tags = tags;
    }

    private void condition(String condition) {
      if (conditions.size() < MAXIMUM_CONDITIONS) {
        conditions.add(bounded(condition));
      }
    }

    private void fail(String warning) {
      complete = false;
      if (warnings.size() < MAXIMUM_CONDITIONS) {
        warnings.add(bounded(warning));
      }
    }

    private static String bounded(String value) {
      return value.length() <= 480 ? value : value.substring(0, 480);
    }
  }
}
