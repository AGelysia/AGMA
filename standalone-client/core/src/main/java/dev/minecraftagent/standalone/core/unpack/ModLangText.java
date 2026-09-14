package dev.minecraftagent.standalone.core.unpack;

import java.util.List;
import java.util.Map;

/**
 * Resolves raw document strings against the translation maps one mod ships. The preferred locale is
 * consulted first, then every other collected locale in scan order, and the raw value is the final
 * fallback, so a missing translation never drops the text.
 */
final class ModLangText {
  private ModLangText() {}

  /**
   * Resolves {@code value} through the mod's lang maps when it is a known translation key;
   * otherwise returns {@code value} itself. {@code null} becomes an empty string.
   */
  static String resolve(Map<String, Map<String, String>> lang, String preferred, String value) {
    if (value == null) {
      return "";
    }
    var translated = lookup(lang, preferred, value);
    return translated != null ? translated : value;
  }

  /**
   * Resolves one JSON text component: a bare string is literal text (still passed through the
   * translation lookup because guide content frequently stores keys as bare strings), a {@code
   * {"translate": key, "fallback": text}} object resolves its key with the explicit fallback before
   * the raw key, and a list concatenates its components. Anything else is empty.
   */
  static String resolveComponent(
      Map<String, Map<String, String>> lang, String preferred, Object component) {
    if (component instanceof String text) {
      return resolve(lang, preferred, text);
    }
    if (component instanceof List<?> list) {
      var combined = new StringBuilder();
      for (var element : list) {
        combined.append(resolveComponent(lang, preferred, element));
      }
      return combined.toString();
    }
    if (component instanceof Map<?, ?> map && map.get("translate") instanceof String key) {
      var translated = lookup(lang, preferred, key);
      if (translated != null) {
        return translated;
      }
      if (map.get("fallback") instanceof String fallback) {
        return fallback;
      }
      return key;
    }
    return "";
  }

  private static String lookup(
      Map<String, Map<String, String>> lang, String preferred, String key) {
    var preferredMap = lang.get(preferred);
    if (preferredMap != null) {
      var value = preferredMap.get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    for (var entry : lang.entrySet()) {
      if (entry.getKey().equals(preferred)) {
        continue;
      }
      var value = entry.getValue().get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
