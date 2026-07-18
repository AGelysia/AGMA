package dev.minecraftagent.standalone.common;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact USD form conversion for integer micro-USD configuration fields. */
public final class ClientUsdAmount {
  private static final long MAXIMUM_MICRO_USD = 1_000_000_000_000L;
  private static final Pattern DECIMAL = Pattern.compile("^[0-9]+(?:\\.[0-9]{1,6})?$");

  private ClientUsdAmount() {}

  public static long parse(String value, String field) {
    Objects.requireNonNull(field, "field");
    var normalized = value == null ? "" : value.strip();
    if (!DECIMAL.matcher(normalized).matches()) {
      throw invalid(field);
    }
    try {
      var amount = new BigDecimal(normalized).movePointRight(6).longValueExact();
      if (amount < 0 || amount > MAXIMUM_MICRO_USD) {
        throw invalid(field);
      }
      return amount;
    } catch (ArithmeticException failure) {
      throw new ClientConfigurationException(
          "USD_AMOUNT_INVALID",
          field,
          "Enter a non-negative USD amount with no more than six decimal places",
          failure);
    }
  }

  public static String format(long microUsd) {
    if (microUsd < 0 || microUsd > MAXIMUM_MICRO_USD) {
      throw new IllegalArgumentException("USD amount is out of range");
    }
    return BigDecimal.valueOf(microUsd, 6).stripTrailingZeros().toPlainString();
  }

  private static ClientConfigurationException invalid(String field) {
    return new ClientConfigurationException(
        "USD_AMOUNT_INVALID",
        field,
        "Enter a non-negative USD amount with no more than six decimal places");
  }
}
