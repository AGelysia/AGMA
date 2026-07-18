package dev.minecraftagent.standalone.common;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Secret-bearing input used only while the in-game setup form is being committed. */
public record ClientSetup(
    String provider,
    URI baseUrl,
    String model,
    SecretInput apiKey,
    long inputMicroUsdPerMillionTokens,
    long outputMicroUsdPerMillionTokens,
    int dailyRequests,
    long monthlyBudgetMicroUsd,
    WebSearchSetup webSearch,
    boolean storeConversations,
    int retentionDays) {
  private static final Set<String> PROVIDERS =
      Set.of("openai", "anthropic", "deepseek", "gemini", "openai-compatible");
  private static final Pattern PLACEHOLDER =
      Pattern.compile("^(?:change-?me|replace-with-|your[-_])", Pattern.CASE_INSENSITIVE);

  public ClientSetup {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(apiKey, "apiKey");
    if (!PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException("Unsupported model provider");
    }
    validateSecret(apiKey, "/model/apiKey", "Provider API key is invalid");
    if (dailyRequests < 1 || dailyRequests > 1_000_000) {
      throw new IllegalArgumentException("Daily request limit is out of range");
    }
    if (monthlyBudgetMicroUsd < ClientProfileStore.PROVIDER_RESERVATION_MICRO_USD
        || monthlyBudgetMicroUsd > 1_000_000_000_000L) {
      throw invalidAmount("/limits/monthlyBudgetUsd", "Monthly model budget is out of range");
    }
    if (inputMicroUsdPerMillionTokens < 0 || inputMicroUsdPerMillionTokens > 1_000_000_000_000L) {
      throw invalidAmount("/model/inputPriceUsd", "Input pricing is out of range");
    }
    if (outputMicroUsdPerMillionTokens < 0 || outputMicroUsdPerMillionTokens > 1_000_000_000_000L) {
      throw invalidAmount("/model/outputPriceUsd", "Output pricing is out of range");
    }
    if (retentionDays < 0 || retentionDays > 3650 || (!storeConversations && retentionDays != 0)) {
      throw new IllegalArgumentException("Conversation retention is out of range");
    }
  }

  public ClientSetup(
      String provider,
      URI baseUrl,
      String model,
      String apiKey,
      long inputMicroUsdPerMillionTokens,
      long outputMicroUsdPerMillionTokens,
      int dailyRequests,
      long monthlyBudgetMicroUsd) {
    this(
        provider,
        baseUrl,
        model,
        apiKey,
        inputMicroUsdPerMillionTokens,
        outputMicroUsdPerMillionTokens,
        dailyRequests,
        monthlyBudgetMicroUsd,
        null,
        false,
        0);
  }

  public ClientSetup(
      String provider,
      URI baseUrl,
      String model,
      String apiKey,
      long inputMicroUsdPerMillionTokens,
      long outputMicroUsdPerMillionTokens,
      int dailyRequests,
      long monthlyBudgetMicroUsd,
      WebSearchSetup webSearch,
      boolean storeConversations,
      int retentionDays) {
    this(
        provider,
        baseUrl,
        model,
        SecretInput.replace(apiKey),
        inputMicroUsdPerMillionTokens,
        outputMicroUsdPerMillionTokens,
        dailyRequests,
        monthlyBudgetMicroUsd,
        webSearch,
        storeConversations,
        retentionDays);
  }

  public ClientSetup(
      String provider,
      URI baseUrl,
      String model,
      String apiKey,
      long inputMicroUsdPerMillionTokens,
      long outputMicroUsdPerMillionTokens,
      int dailyRequests,
      long monthlyBudgetMicroUsd,
      WebSearchSetup webSearch) {
    this(
        provider,
        baseUrl,
        model,
        apiKey,
        inputMicroUsdPerMillionTokens,
        outputMicroUsdPerMillionTokens,
        dailyRequests,
        monthlyBudgetMicroUsd,
        webSearch,
        false,
        0);
  }

  @Override
  public String toString() {
    return "ClientSetup[provider="
        + provider
        + ", model="
        + model
        + ", apiKey=redacted, webSearch="
        + (webSearch == null ? "disabled" : "configured")
        + "]";
  }

  /** A secret field is either explicitly replaced or retained without exposing its old value. */
  public record SecretInput(Action action, String replacement) {
    public SecretInput {
      Objects.requireNonNull(action, "action");
      if ((action == Action.KEEP_EXISTING) != (replacement == null)) {
        throw new IllegalArgumentException("Secret input action is invalid");
      }
    }

    public static SecretInput keepExisting() {
      return new SecretInput(Action.KEEP_EXISTING, null);
    }

    public static SecretInput replace(String value) {
      return new SecretInput(Action.REPLACE, Objects.requireNonNull(value, "value"));
    }

    public boolean keepsExisting() {
      return action == Action.KEEP_EXISTING;
    }

    @Override
    public String toString() {
      return "SecretInput[" + action + ", redacted]";
    }

    public enum Action {
      KEEP_EXISTING,
      REPLACE
    }
  }

  public record WebSearchSetup(
      SecretInput apiKey,
      long requestCostMicroUsd,
      long monthlyBudgetMicroUsd,
      String country,
      String searchLanguage) {
    public WebSearchSetup {
      Objects.requireNonNull(apiKey, "apiKey");
      validateSecret(apiKey, "/webEvidence/apiKey", "Search API key is invalid");
      if (requestCostMicroUsd < 1 || requestCostMicroUsd > 1_000_000_000L) {
        throw invalidAmount("/webEvidence/requestCostUsd", "Search request cost is out of range");
      }
      if (monthlyBudgetMicroUsd < requestCostMicroUsd
          || monthlyBudgetMicroUsd > 1_000_000_000_000L) {
        throw invalidAmount(
            "/webEvidence/monthlyBudgetUsd", "Monthly search budget is out of range");
      }
      if (country == null || !country.matches("^[A-Z]{2}$")) {
        throw new IllegalArgumentException("Search country is invalid");
      }
      if (searchLanguage == null || !searchLanguage.matches("^[a-z]{2,3}(?:-[a-z]{2})?$")) {
        throw new IllegalArgumentException("Search language is invalid");
      }
    }

    public WebSearchSetup(
        String apiKey,
        long requestCostMicroUsd,
        long monthlyBudgetMicroUsd,
        String country,
        String searchLanguage) {
      this(
          SecretInput.replace(apiKey),
          requestCostMicroUsd,
          monthlyBudgetMicroUsd,
          country,
          searchLanguage);
    }

    @Override
    public String toString() {
      return "WebSearchSetup[apiKey=redacted, country="
          + country
          + ", searchLanguage="
          + searchLanguage
          + "]";
    }
  }

  private static void validateSecret(SecretInput input, String field, String message) {
    if (input.keepsExisting()) {
      return;
    }
    var value = input.replacement();
    if (value.isBlank()
        || value.length() > 8192
        || value.codePoints().anyMatch(character -> character <= 0x1f || character == 0x7f)
        || PLACEHOLDER.matcher(value).find()) {
      throw new ClientConfigurationException("SECRET_INPUT_INVALID", field, message);
    }
  }

  private static ClientConfigurationException invalidAmount(String field, String message) {
    return new ClientConfigurationException("USD_AMOUNT_INVALID", field, message);
  }
}
