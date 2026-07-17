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
    String apiKey,
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
    if (apiKey.isBlank()
        || apiKey.length() > 8192
        || apiKey.codePoints().anyMatch(character -> character <= 0x1f || character == 0x7f)
        || PLACEHOLDER.matcher(apiKey).find()) {
      throw new IllegalArgumentException("Provider API key is invalid");
    }
    if (dailyRequests < 1 || dailyRequests > 1_000_000) {
      throw new IllegalArgumentException("Daily request limit is out of range");
    }
    if (monthlyBudgetMicroUsd < ClientProfileStore.PROVIDER_RESERVATION_MICRO_USD
        || monthlyBudgetMicroUsd > 1_000_000_000_000L) {
      throw new IllegalArgumentException("Monthly budget is out of range");
    }
    if (inputMicroUsdPerMillionTokens < 0
        || inputMicroUsdPerMillionTokens > 1_000_000_000_000L
        || outputMicroUsdPerMillionTokens < 0
        || outputMicroUsdPerMillionTokens > 1_000_000_000_000L) {
      throw new IllegalArgumentException("Model pricing is out of range");
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

  public record WebSearchSetup(
      String apiKey,
      long requestCostMicroUsd,
      long monthlyBudgetMicroUsd,
      String country,
      String searchLanguage) {
    public WebSearchSetup {
      Objects.requireNonNull(apiKey, "apiKey");
      if (apiKey.isBlank()
          || apiKey.length() > 8192
          || apiKey.codePoints().anyMatch(character -> character <= 0x1f || character == 0x7f)
          || PLACEHOLDER.matcher(apiKey).find()) {
        throw new IllegalArgumentException("Search API key is invalid");
      }
      if (requestCostMicroUsd < 1
          || requestCostMicroUsd > 1_000_000_000L
          || monthlyBudgetMicroUsd < requestCostMicroUsd
          || monthlyBudgetMicroUsd > 1_000_000_000_000L) {
        throw new IllegalArgumentException("Search budget is out of range");
      }
      if (country == null || !country.matches("^[A-Z]{2}$")) {
        throw new IllegalArgumentException("Search country is invalid");
      }
      if (searchLanguage == null || !searchLanguage.matches("^[a-z]{2,3}(?:-[a-z]{2})?$")) {
        throw new IllegalArgumentException("Search language is invalid");
      }
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
}
