package dev.minecraftagent.standalone.core.contract;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record RuntimeClientProfile(
    int configVersion,
    String profile,
    Identity identity,
    Transport transport,
    Model model,
    Storage storage,
    Logging logging,
    Limits limits,
    Privacy privacy,
    ToolPolicy toolPolicy,
    NetworkPolicy networkPolicy,
    WebEvidence webEvidence,
    StoragePolicy storagePolicy) {
  public static final int CONFIG_VERSION = 3;
  public static final String PROFILE = "client";
  private static final Pattern SAFE_REFERENCE =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,255}$");

  public RuntimeClientProfile {
    if (configVersion != CONFIG_VERSION || !PROFILE.equals(profile)) {
      throw new IllegalArgumentException("unsupported Runtime client profile identity");
    }
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(storage, "storage");
    Objects.requireNonNull(logging, "logging");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(privacy, "privacy");
    Objects.requireNonNull(toolPolicy, "toolPolicy");
    Objects.requireNonNull(networkPolicy, "networkPolicy");
    Objects.requireNonNull(storagePolicy, "storagePolicy");
    if (transport.connectorToken().equals(model.apiKey())) {
      throw new IllegalArgumentException(
          "connector and Provider secrets must use distinct references");
    }
    if (webEvidence != null
        && (webEvidence.apiKey().equals(transport.connectorToken())
            || webEvidence.apiKey().equals(model.apiKey()))) {
      throw new IllegalArgumentException("Web Search secret must use a distinct reference");
    }
  }

  public RuntimeClientProfile(
      int configVersion,
      String profile,
      Identity identity,
      Transport transport,
      Model model,
      Storage storage,
      Logging logging,
      Limits limits,
      Privacy privacy,
      ToolPolicy toolPolicy,
      NetworkPolicy networkPolicy,
      StoragePolicy storagePolicy) {
    this(
        configVersion,
        profile,
        identity,
        transport,
        model,
        storage,
        logging,
        limits,
        privacy,
        toolPolicy,
        networkPolicy,
        null,
        storagePolicy);
  }

  public record Identity(UUID installationId, String scope) {
    public Identity {
      Objects.requireNonNull(installationId, "installationId");
      if (!"installation".equals(scope)) {
        throw new IllegalArgumentException("client identity scope must be installation");
      }
    }
  }

  public record SecretReference(String source, String reference) {
    private static final Set<String> SOURCES =
        Set.of("environment", "credential_store", "private_file");

    public SecretReference {
      if (!SOURCES.contains(source)) {
        throw new IllegalArgumentException("unsupported secret reference source");
      }
      reference = safeReference(reference, "secret reference");
    }
  }

  public record Transport(
      String host, int port, SecretReference connectorToken, String authenticationDomain) {
    public Transport {
      if (!"127.0.0.1".equals(host)) {
        throw new IllegalArgumentException("client Runtime must bind literal IPv4 loopback");
      }
      if (port < 1024 || port > 65_535) {
        throw new IllegalArgumentException("client Runtime port is out of range");
      }
      Objects.requireNonNull(connectorToken, "connectorToken");
      if (!"agma-connector-handshake-v1".equals(authenticationDomain)) {
        throw new IllegalArgumentException("unsupported connector authentication domain");
      }
    }
  }

  public record Model(
      String provider,
      URI baseUrl,
      SecretReference apiKey,
      String model,
      int timeoutSeconds,
      long inputMicroUsdPerMillionTokens,
      long outputMicroUsdPerMillionTokens) {
    private static final Set<String> PROVIDERS =
        Set.of("openai", "anthropic", "deepseek", "gemini", "openai-compatible");

    public Model {
      if (!PROVIDERS.contains(provider)) {
        throw new IllegalArgumentException("unsupported model provider");
      }
      if (baseUrl != null) {
        validateModelBaseUrl(baseUrl);
      }
      if ("openai-compatible".equals(provider) && baseUrl == null) {
        throw new IllegalArgumentException("openai-compatible requires an explicit base URL");
      }
      Objects.requireNonNull(apiKey, "apiKey");
      model = ContractChecks.text(model, "model", 128);
      if (!model.matches("^[A-Za-z0-9][A-Za-z0-9._:/-]*$")) {
        throw new IllegalArgumentException("model contains unsupported characters");
      }
      if (timeoutSeconds < 1 || timeoutSeconds > 120) {
        throw new IllegalArgumentException("model timeout is out of range");
      }
      boundedMicroUsd(inputMicroUsdPerMillionTokens, "input pricing");
      boundedMicroUsd(outputMicroUsdPerMillionTokens, "output pricing");
    }
  }

  public record Storage(String sqlitePath) {
    public Storage {
      sqlitePath = privateRelativePath(sqlitePath, "sqlitePath");
    }
  }

  public record Logging(String directory, String level) {
    public Logging {
      directory = privateRelativePath(directory, "logging directory");
      if (!Set.of("debug", "info", "warn", "error").contains(level)) {
        throw new IllegalArgumentException("unsupported logging level");
      }
    }
  }

  public record Limits(
      int maxConcurrentRequests,
      int maxQueuedRequests,
      int maxToolRounds,
      int maxContextMessages,
      int maxContextCharacters,
      int requestCooldownSeconds,
      int dailyRequests,
      long monthlyBudgetMicroUsd,
      long providerRoundReservationMicroUsd) {
    public Limits {
      bounded(maxConcurrentRequests, 1, 8, "maxConcurrentRequests");
      bounded(maxQueuedRequests, 0, 128, "maxQueuedRequests");
      bounded(maxToolRounds, 1, 8, "maxToolRounds");
      bounded(maxContextMessages, 1, 100, "maxContextMessages");
      bounded(maxContextCharacters, 4096, 65_536, "maxContextCharacters");
      bounded(requestCooldownSeconds, 0, 3600, "requestCooldownSeconds");
      bounded(dailyRequests, 1, 1_000_000, "dailyRequests");
      boundedMicroUsd(monthlyBudgetMicroUsd, "monthlyBudgetMicroUsd");
      if (providerRoundReservationMicroUsd < 1) {
        throw new IllegalArgumentException("providerRoundReservationMicroUsd must be positive");
      }
      boundedMicroUsd(providerRoundReservationMicroUsd, "providerRoundReservationMicroUsd");
    }
  }

  public record Privacy(
      boolean storeConversations,
      int retentionDays,
      boolean logMessageContent,
      boolean logToolCalls) {
    public Privacy {
      bounded(retentionDays, 0, 3650, "retentionDays");
      if ((!storeConversations && retentionDays != 0) || logMessageContent || logToolCalls) {
        throw new IllegalArgumentException("client privacy defaults are inconsistent or unsafe");
      }
    }
  }

  public record ToolPolicy(
      List<String> allowed, List<String> denied, boolean inventoryDefaultEnabled) {
    private static final Set<String> KNOWN_TOOLS =
        Set.of(
            "game.resource.search",
            "game.process.lookup",
            "game.process.uses",
            "game.process.plan",
            "game.inventory.snapshot");
    private static final Set<String> REQUIRED_DENIALS =
        Set.of("paper.command", "server.payload", "world.write", "arbitrary.web.fetch");
    private static final Set<String> KNOWN_DENIALS =
        Set.of(
            "paper.command",
            "paper.permission",
            "server.payload",
            "world.write",
            "arbitrary.web.fetch");

    public ToolPolicy {
      allowed = uniqueIdentifiers(allowed, "allowed tools", 5);
      denied = uniqueIdentifiers(denied, "denied capabilities", 16);
      if (!KNOWN_TOOLS.containsAll(allowed)) {
        throw new IllegalArgumentException("allowed contains an unreviewed client tool");
      }
      if (!KNOWN_DENIALS.containsAll(denied)
          || !denied.containsAll(REQUIRED_DENIALS)
          || inventoryDefaultEnabled) {
        throw new IllegalArgumentException("client capability policy is incomplete or unsafe");
      }
    }
  }

  public record NetworkPolicy(
      boolean webSearchDefaultEnabled, boolean remoteCustomUrlRequiresHttps) {
    public NetworkPolicy {
      if (webSearchDefaultEnabled || !remoteCustomUrlRequiresHttps) {
        throw new IllegalArgumentException("client network defaults are unsafe");
      }
    }
  }

  public record WebEvidence(
      String provider,
      SecretReference apiKey,
      long requestCostMicroUsd,
      long monthlyBudgetMicroUsd,
      String defaultAuthorization,
      boolean persistentAuthorizationEnabled,
      String country,
      String searchLanguage) {
    public WebEvidence {
      if (!"brave".equals(provider)) {
        throw new IllegalArgumentException("unsupported Web Search provider");
      }
      Objects.requireNonNull(apiKey, "apiKey");
      if (requestCostMicroUsd < 1
          || requestCostMicroUsd > 1_000_000_000L
          || monthlyBudgetMicroUsd < requestCostMicroUsd
          || monthlyBudgetMicroUsd > 1_000_000_000_000L) {
        throw new IllegalArgumentException("Web Search budget is out of range");
      }
      if (!"off".equals(defaultAuthorization) || persistentAuthorizationEnabled) {
        throw new IllegalArgumentException("Web Search defaults require explicit consent");
      }
      if (country == null || !country.matches("^[A-Z]{2}$")) {
        throw new IllegalArgumentException("Web Search country is invalid");
      }
      if (searchLanguage == null || !searchLanguage.matches("^[a-z]{2,3}(?:-[a-z]{2})?$")) {
        throw new IllegalArgumentException("Web Search language is invalid");
      }
    }
  }

  public record StoragePolicy(String scope, boolean separateFromPaper) {
    public StoragePolicy {
      if (!"installation".equals(scope) || !separateFromPaper) {
        throw new IllegalArgumentException(
            "client storage must be installation-scoped and separate");
      }
    }
  }

  private static List<String> uniqueIdentifiers(
      List<String> values, String field, int maximumSize) {
    var copy =
        ContractChecks.list(values, field, maximumSize).stream()
            .map(value -> ContractChecks.symbolicId(value, field))
            .toList();
    if (new HashSet<>(copy).size() != copy.size()) {
      throw new IllegalArgumentException(field + " must be unique");
    }
    return copy;
  }

  private static String safeReference(String value, String field) {
    ContractChecks.text(value, field, 256);
    if (!SAFE_REFERENCE.matcher(value).matches() || containsParentTraversal(value)) {
      throw new IllegalArgumentException(field + " is not a safe reference");
    }
    return value;
  }

  private static String privateRelativePath(String value, String field) {
    value = safeReference(value, field);
    if (value.startsWith("/")) {
      throw new IllegalArgumentException(field + " must be relative");
    }
    return value;
  }

  private static boolean containsParentTraversal(String value) {
    return List.of(value.split("/", -1)).contains("..");
  }

  private static void validateModelBaseUrl(URI uri) {
    var https = "https".equalsIgnoreCase(uri.getScheme());
    var loopbackHttp =
        "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
    if ((!https && !loopbackHttp)
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("model base URL violates the client URL policy");
    }
  }

  private static void bounded(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " is out of range");
    }
  }

  private static void boundedMicroUsd(long value, String field) {
    if (value < 0 || value > 1_000_000_000_000L) {
      throw new IllegalArgumentException(field + " is out of range");
    }
  }
}
