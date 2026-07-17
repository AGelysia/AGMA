package dev.minecraftagent.paper.capability.load;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Closed v1 numeric version-range grammar with space-separated AND comparisons. */
public final class DeterministicVersionRange {
  private static final String COMPONENT = "(?:0|[1-9][0-9]*)";
  private static final Pattern VERSION =
      Pattern.compile(COMPONENT + "(?:\\." + COMPONENT + "){0,2}");
  private static final Pattern COMPARISON =
      Pattern.compile("(>=|<=|=|>|<)(" + COMPONENT + "(?:\\." + COMPONENT + "){0,2})");
  private static final int MAX_RANGE_LENGTH = 128;
  private static final int MAX_COMPARISONS = 16;

  private final List<Comparison> comparisons;

  private DeterministicVersionRange(List<Comparison> comparisons) {
    this.comparisons = List.copyOf(comparisons);
  }

  public static DeterministicVersionRange parse(String source) {
    Objects.requireNonNull(source);
    if (source.isEmpty()
        || source.length() > MAX_RANGE_LENGTH
        || !source.equals(source.strip())
        || source.contains("  ")) {
      throw new IllegalArgumentException("Invalid deterministic version range");
    }
    var tokens = source.split(" ", -1);
    if (tokens.length > MAX_COMPARISONS) {
      throw new IllegalArgumentException("Too many version comparisons");
    }
    var parsed = new ArrayList<Comparison>(tokens.length);
    for (var token : tokens) {
      var matcher = COMPARISON.matcher(token);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid deterministic version comparison");
      }
      parsed.add(new Comparison(Operator.from(matcher.group(1)), Version.parse(matcher.group(2))));
    }
    return new DeterministicVersionRange(parsed);
  }

  public boolean includes(String installedVersion) {
    var installed = Version.parse(installedVersion);
    return comparisons.stream().allMatch(comparison -> comparison.matches(installed));
  }

  public static boolean validInstalledVersion(String installedVersion) {
    try {
      Version.parse(installedVersion);
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private enum Operator {
    EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL;

    static Operator from(String value) {
      return switch (value) {
        case "=" -> EQUAL;
        case ">" -> GREATER_THAN;
        case ">=" -> GREATER_THAN_OR_EQUAL;
        case "<" -> LESS_THAN;
        case "<=" -> LESS_THAN_OR_EQUAL;
        default -> throw new IllegalArgumentException("Invalid version operator");
      };
    }
  }

  private record Comparison(Operator operator, Version version) {
    boolean matches(Version installed) {
      var comparison = installed.compareTo(version);
      return switch (operator) {
        case EQUAL -> comparison == 0;
        case GREATER_THAN -> comparison > 0;
        case GREATER_THAN_OR_EQUAL -> comparison >= 0;
        case LESS_THAN -> comparison < 0;
        case LESS_THAN_OR_EQUAL -> comparison <= 0;
      };
    }
  }

  private record Version(List<BigInteger> components) implements Comparable<Version> {
    static Version parse(String source) {
      Objects.requireNonNull(source);
      if (source.length() > MAX_RANGE_LENGTH || !VERSION.matcher(source).matches()) {
        throw new IllegalArgumentException("Invalid deterministic plugin version");
      }
      var parts = source.split("\\.");
      var result = new ArrayList<BigInteger>(3);
      for (var part : parts) {
        result.add(new BigInteger(part));
      }
      while (result.size() < 3) {
        result.add(BigInteger.ZERO);
      }
      return new Version(List.copyOf(result));
    }

    @Override
    public int compareTo(Version other) {
      for (var index = 0; index < components.size(); index++) {
        var comparison = components.get(index).compareTo(other.components.get(index));
        if (comparison != 0) {
          return comparison;
        }
      }
      return 0;
    }
  }
}
