package dev.minecraftagent.standalone.common;

import dev.minecraftagent.standalone.core.contract.RuntimeClientProfile;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict JSON codec for the Runtime v3 client profile. JSON is also valid Runtime YAML input. */
public final class ClientProfileCodec {
  public static final int MAXIMUM_CONFIG_BYTES = 64 * 1024;

  public RuntimeClientProfile load(Path path) {
    if (path == null
        || Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw failure("CONFIG_FILE_INVALID", "/", "Client configuration file is unavailable");
    }
    final byte[] bytes;
    try {
      var size = Files.size(path);
      if (size < 2 || size > MAXIMUM_CONFIG_BYTES) {
        throw failure("CONFIG_FILE_INVALID", "/", "Client configuration size is invalid");
      }
      bytes = Files.readAllBytes(path);
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw failure(
          "CONFIG_FILE_INVALID", "/", "Client configuration could not be read", exception);
    }
    if (bytes.length > MAXIMUM_CONFIG_BYTES) {
      throw failure("CONFIG_FILE_INVALID", "/", "Client configuration size is invalid");
    }
    try {
      var decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decode(decoder.decode(ByteBuffer.wrap(bytes)).toString());
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (Exception exception) {
      throw failure(
          "CONFIG_FILE_INVALID", "/", "Client configuration is not valid UTF-8", exception);
    }
  }

  public RuntimeClientProfile decode(String source) {
    if (source == null
        || source.length() > MAXIMUM_CONFIG_BYTES
        || source.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_CONFIG_BYTES) {
      throw failure("CONFIG_FILE_INVALID", "/", "Client configuration size is invalid");
    }
    try {
      return profile(StrictJson.parse(source));
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw failure(
          "CONFIG_SCHEMA_INVALID", "/", "Client configuration does not match version 3", exception);
    }
  }

  public String encode(RuntimeClientProfile profile) {
    requireSupportedProfile(profile);
    return StrictJson.write(document(profile));
  }

  private static RuntimeClientProfile profile(Object value) {
    var root = JsonFields.object(value, "/");
    var expected =
        new java.util.HashSet<>(
            Set.of(
                "configVersion",
                "profile",
                "identity",
                "transport",
                "model",
                "storage",
                "logging",
                "limits",
                "privacy",
                "toolPolicy",
                "networkPolicy",
                "storagePolicy"));
    if (root.containsKey("webEvidence")) {
      expected.add("webEvidence");
    }
    if (!root.keySet().equals(expected)) {
      throw JsonFields.invalid("/");
    }
    var identity =
        JsonFields.exactObject(root.get("identity"), "/identity", "installationId", "scope");
    var transport =
        JsonFields.exactObject(
            root.get("transport"),
            "/transport",
            "host",
            "port",
            "connectorToken",
            "authenticationDomain");
    var model =
        JsonFields.exactObject(
            root.get("model"),
            "/model",
            "provider",
            "baseUrl",
            "apiKey",
            "model",
            "timeoutSeconds",
            "inputMicroUsdPerMillionTokens",
            "outputMicroUsdPerMillionTokens");
    var storage = JsonFields.exactObject(root.get("storage"), "/storage", "sqlitePath");
    var logging = JsonFields.exactObject(root.get("logging"), "/logging", "directory", "level");
    var limits =
        JsonFields.exactObject(
            root.get("limits"),
            "/limits",
            "maxConcurrentRequests",
            "maxQueuedRequests",
            "maxToolRounds",
            "maxContextMessages",
            "maxContextCharacters",
            "requestCooldownSeconds",
            "dailyRequests",
            "monthlyBudgetMicroUsd",
            "providerRoundReservationMicroUsd");
    var privacy =
        JsonFields.exactObject(
            root.get("privacy"),
            "/privacy",
            "storeConversations",
            "retentionDays",
            "logMessageContent",
            "logToolCalls");
    var tools =
        JsonFields.exactObject(
            root.get("toolPolicy"), "/toolPolicy", "allowed", "denied", "inventoryDefaultEnabled");
    var network =
        JsonFields.exactObject(
            root.get("networkPolicy"),
            "/networkPolicy",
            "webSearchDefaultEnabled",
            "remoteCustomUrlRequiresHttps");
    var storagePolicy =
        JsonFields.exactObject(
            root.get("storagePolicy"), "/storagePolicy", "scope", "separateFromPaper");
    Map<String, Object> webEvidence = null;
    if (root.containsKey("webEvidence")) {
      webEvidence =
          JsonFields.exactObject(
              root.get("webEvidence"),
              "/webEvidence",
              "provider",
              "apiKey",
              "requestCostMicroUsd",
              "monthlyBudgetMicroUsd",
              "defaultAuthorization",
              "persistentAuthorizationEnabled",
              "country",
              "searchLanguage");
    }
    var allowed = JsonFields.stringArray(tools.get("allowed"), "/toolPolicy/allowed", 5);

    final URI baseUrl;
    var baseUrlValue = JsonFields.nullableString(model.get("baseUrl"), "/model/baseUrl", 2048);
    try {
      baseUrl = baseUrlValue == null ? null : new URI(baseUrlValue);
    } catch (URISyntaxException exception) {
      throw failure("CONFIG_SCHEMA_INVALID", "/model/baseUrl", "Model URL is invalid");
    }

    try {
      var result =
          new RuntimeClientProfile(
              JsonFields.integer(root.get("configVersion"), "/configVersion"),
              JsonFields.string(root.get("profile"), "/profile", 16),
              new RuntimeClientProfile.Identity(
                  JsonFields.uuid(identity.get("installationId"), "/identity/installationId"),
                  JsonFields.string(identity.get("scope"), "/identity/scope", 32)),
              new RuntimeClientProfile.Transport(
                  JsonFields.string(transport.get("host"), "/transport/host", 64),
                  JsonFields.integer(transport.get("port"), "/transport/port"),
                  secretReference(transport.get("connectorToken"), "/transport/connectorToken"),
                  JsonFields.string(
                      transport.get("authenticationDomain"),
                      "/transport/authenticationDomain",
                      64)),
              new RuntimeClientProfile.Model(
                  JsonFields.string(model.get("provider"), "/model/provider", 64),
                  baseUrl,
                  secretReference(model.get("apiKey"), "/model/apiKey"),
                  JsonFields.string(model.get("model"), "/model/model", 128),
                  JsonFields.integer(model.get("timeoutSeconds"), "/model/timeoutSeconds"),
                  JsonFields.longInteger(
                      model.get("inputMicroUsdPerMillionTokens"),
                      "/model/inputMicroUsdPerMillionTokens"),
                  JsonFields.longInteger(
                      model.get("outputMicroUsdPerMillionTokens"),
                      "/model/outputMicroUsdPerMillionTokens")),
              new RuntimeClientProfile.Storage(
                  JsonFields.string(storage.get("sqlitePath"), "/storage/sqlitePath", 256)),
              new RuntimeClientProfile.Logging(
                  JsonFields.string(logging.get("directory"), "/logging/directory", 256),
                  JsonFields.string(logging.get("level"), "/logging/level", 16)),
              new RuntimeClientProfile.Limits(
                  JsonFields.integer(
                      limits.get("maxConcurrentRequests"), "/limits/maxConcurrentRequests"),
                  JsonFields.integer(limits.get("maxQueuedRequests"), "/limits/maxQueuedRequests"),
                  JsonFields.integer(limits.get("maxToolRounds"), "/limits/maxToolRounds"),
                  JsonFields.integer(
                      limits.get("maxContextMessages"), "/limits/maxContextMessages"),
                  JsonFields.integer(
                      limits.get("maxContextCharacters"), "/limits/maxContextCharacters"),
                  JsonFields.integer(
                      limits.get("requestCooldownSeconds"), "/limits/requestCooldownSeconds"),
                  JsonFields.integer(limits.get("dailyRequests"), "/limits/dailyRequests"),
                  JsonFields.longInteger(
                      limits.get("monthlyBudgetMicroUsd"), "/limits/monthlyBudgetMicroUsd"),
                  JsonFields.longInteger(
                      limits.get("providerRoundReservationMicroUsd"),
                      "/limits/providerRoundReservationMicroUsd")),
              new RuntimeClientProfile.Privacy(
                  JsonFields.bool(privacy.get("storeConversations"), "/privacy/storeConversations"),
                  JsonFields.integer(privacy.get("retentionDays"), "/privacy/retentionDays"),
                  JsonFields.bool(privacy.get("logMessageContent"), "/privacy/logMessageContent"),
                  JsonFields.bool(privacy.get("logToolCalls"), "/privacy/logToolCalls")),
              new RuntimeClientProfile.ToolPolicy(
                  allowed,
                  JsonFields.stringArray(tools.get("denied"), "/toolPolicy/denied", 16),
                  JsonFields.bool(
                      tools.get("inventoryDefaultEnabled"), "/toolPolicy/inventoryDefaultEnabled")),
              new RuntimeClientProfile.NetworkPolicy(
                  JsonFields.bool(
                      network.get("webSearchDefaultEnabled"),
                      "/networkPolicy/webSearchDefaultEnabled"),
                  JsonFields.bool(
                      network.get("remoteCustomUrlRequiresHttps"),
                      "/networkPolicy/remoteCustomUrlRequiresHttps")),
              webEvidence == null
                  ? null
                  : new RuntimeClientProfile.WebEvidence(
                      JsonFields.string(webEvidence.get("provider"), "/webEvidence/provider", 32),
                      secretReference(webEvidence.get("apiKey"), "/webEvidence/apiKey"),
                      JsonFields.longInteger(
                          webEvidence.get("requestCostMicroUsd"),
                          "/webEvidence/requestCostMicroUsd"),
                      JsonFields.longInteger(
                          webEvidence.get("monthlyBudgetMicroUsd"),
                          "/webEvidence/monthlyBudgetMicroUsd"),
                      JsonFields.string(
                          webEvidence.get("defaultAuthorization"),
                          "/webEvidence/defaultAuthorization",
                          16),
                      JsonFields.bool(
                          webEvidence.get("persistentAuthorizationEnabled"),
                          "/webEvidence/persistentAuthorizationEnabled"),
                      JsonFields.string(webEvidence.get("country"), "/webEvidence/country", 2),
                      JsonFields.string(
                          webEvidence.get("searchLanguage"), "/webEvidence/searchLanguage", 8)),
              new RuntimeClientProfile.StoragePolicy(
                  JsonFields.string(storagePolicy.get("scope"), "/storagePolicy/scope", 32),
                  JsonFields.bool(
                      storagePolicy.get("separateFromPaper"), "/storagePolicy/separateFromPaper")));
      requireSupportedProfile(result);
      return result;
    } catch (ClientConfigurationException exception) {
      throw exception;
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw failure("CONFIG_SCHEMA_INVALID", "/", "Client configuration does not match version 3");
    }
  }

  private static RuntimeClientProfile.SecretReference secretReference(Object value, String field) {
    var document = JsonFields.exactObject(value, field, "source", "reference");
    var source = JsonFields.string(document.get("source"), field + "/source", 32);
    if (source.equals("credential_store")) {
      throw failure(
          "SECRET_REFERENCE_UNSUPPORTED",
          field + "/source",
          "Credential-store references are unavailable in C1");
    }
    if (!source.equals("environment") && !source.equals("private_file")) {
      throw failure("CONFIG_SCHEMA_INVALID", field + "/source", "Secret source is invalid");
    }
    var reference = JsonFields.string(document.get("reference"), field + "/reference", 256);
    if (source.equals("environment") && !reference.matches("[A-Z_][A-Z0-9_]*")) {
      throw failure(
          "CONFIG_SCHEMA_INVALID", field + "/reference", "Environment reference is invalid");
    }
    return new RuntimeClientProfile.SecretReference(source, reference);
  }

  private static void requireSupportedProfile(RuntimeClientProfile profile) {
    if (profile == null) {
      throw failure("CONFIG_SCHEMA_INVALID", "/", "Client profile is unavailable");
    }
    checkReference(profile.transport().connectorToken(), "/transport/connectorToken");
    checkReference(profile.model().apiKey(), "/model/apiKey");
    if (profile.webEvidence() != null) {
      checkReference(profile.webEvidence().apiKey(), "/webEvidence/apiKey");
    }
  }

  private static void checkReference(RuntimeClientProfile.SecretReference reference, String field) {
    if (reference.source().equals("credential_store")) {
      throw failure(
          "SECRET_REFERENCE_UNSUPPORTED",
          field + "/source",
          "Credential-store references are unavailable in C1");
    }
    if (reference.source().equals("environment")
        && !reference.reference().matches("[A-Z_][A-Z0-9_]*")) {
      throw failure(
          "CONFIG_SCHEMA_INVALID", field + "/reference", "Environment reference is invalid");
    }
  }

  private static Map<String, Object> document(RuntimeClientProfile profile) {
    var result =
        map(
            "configVersion",
            profile.configVersion(),
            "profile",
            profile.profile(),
            "identity",
            map(
                "installationId",
                profile.identity().installationId().toString(),
                "scope",
                profile.identity().scope()),
            "transport",
            map(
                "host",
                profile.transport().host(),
                "port",
                profile.transport().port(),
                "connectorToken",
                secretDocument(profile.transport().connectorToken()),
                "authenticationDomain",
                profile.transport().authenticationDomain()),
            "model",
            map(
                "provider",
                profile.model().provider(),
                "baseUrl",
                profile.model().baseUrl() == null ? null : profile.model().baseUrl().toString(),
                "apiKey",
                secretDocument(profile.model().apiKey()),
                "model",
                profile.model().model(),
                "timeoutSeconds",
                profile.model().timeoutSeconds(),
                "inputMicroUsdPerMillionTokens",
                profile.model().inputMicroUsdPerMillionTokens(),
                "outputMicroUsdPerMillionTokens",
                profile.model().outputMicroUsdPerMillionTokens()),
            "storage",
            map("sqlitePath", profile.storage().sqlitePath()),
            "logging",
            map("directory", profile.logging().directory(), "level", profile.logging().level()),
            "limits",
            map(
                "maxConcurrentRequests",
                profile.limits().maxConcurrentRequests(),
                "maxQueuedRequests",
                profile.limits().maxQueuedRequests(),
                "maxToolRounds",
                profile.limits().maxToolRounds(),
                "maxContextMessages",
                profile.limits().maxContextMessages(),
                "maxContextCharacters",
                profile.limits().maxContextCharacters(),
                "requestCooldownSeconds",
                profile.limits().requestCooldownSeconds(),
                "dailyRequests",
                profile.limits().dailyRequests(),
                "monthlyBudgetMicroUsd",
                profile.limits().monthlyBudgetMicroUsd(),
                "providerRoundReservationMicroUsd",
                profile.limits().providerRoundReservationMicroUsd()),
            "privacy",
            map(
                "storeConversations",
                profile.privacy().storeConversations(),
                "retentionDays",
                profile.privacy().retentionDays(),
                "logMessageContent",
                profile.privacy().logMessageContent(),
                "logToolCalls",
                profile.privacy().logToolCalls()),
            "toolPolicy",
            map(
                "allowed",
                profile.toolPolicy().allowed(),
                "denied",
                profile.toolPolicy().denied(),
                "inventoryDefaultEnabled",
                profile.toolPolicy().inventoryDefaultEnabled()),
            "networkPolicy",
            map(
                "webSearchDefaultEnabled",
                profile.networkPolicy().webSearchDefaultEnabled(),
                "remoteCustomUrlRequiresHttps",
                profile.networkPolicy().remoteCustomUrlRequiresHttps()),
            "storagePolicy",
            map(
                "scope",
                profile.storagePolicy().scope(),
                "separateFromPaper",
                profile.storagePolicy().separateFromPaper()));
    if (profile.webEvidence() != null) {
      result.put(
          "webEvidence",
          map(
              "provider",
              profile.webEvidence().provider(),
              "apiKey",
              secretDocument(profile.webEvidence().apiKey()),
              "requestCostMicroUsd",
              profile.webEvidence().requestCostMicroUsd(),
              "monthlyBudgetMicroUsd",
              profile.webEvidence().monthlyBudgetMicroUsd(),
              "defaultAuthorization",
              profile.webEvidence().defaultAuthorization(),
              "persistentAuthorizationEnabled",
              profile.webEvidence().persistentAuthorizationEnabled(),
              "country",
              profile.webEvidence().country(),
              "searchLanguage",
              profile.webEvidence().searchLanguage()));
    }
    return result;
  }

  private static Map<String, Object> secretDocument(
      RuntimeClientProfile.SecretReference reference) {
    return map("source", reference.source(), "reference", reference.reference());
  }

  private static Map<String, Object> map(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("Map entries are unbalanced");
    }
    var result = new LinkedHashMap<String, Object>();
    for (var index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }

  private static ClientConfigurationException failure(String code, String field, String message) {
    return new ClientConfigurationException(code, field, message);
  }

  private static ClientConfigurationException failure(
      String code, String field, String message, Throwable cause) {
    return new ClientConfigurationException(code, field, message, cause);
  }
}
