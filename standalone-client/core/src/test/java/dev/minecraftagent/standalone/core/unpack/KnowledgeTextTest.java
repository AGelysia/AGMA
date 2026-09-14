package dev.minecraftagent.standalone.core.unpack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class KnowledgeTextTest {
  @Test
  void stripsFormattingCodesAndKeepsText() {
    assertEquals("bold text", KnowledgeText.stripMarkup("$(4)bold$() text"));
    assertEquals("label", KnowledgeText.stripMarkup("$(l:https://example.test)label$()"));
    assertEquals("press  to jump", KnowledgeText.stripMarkup("press $(k:key.jump) to jump"));
    assertEquals("plain", KnowledgeText.stripMarkup("plain"));
    assertEquals("", KnowledgeText.stripMarkup(null));
  }

  @Test
  void convertsLineBreakCodes() {
    assertEquals("one\ntwo", KnowledgeText.stripMarkup("one$(br)two"));
    assertEquals("one\ntwo", KnowledgeText.stripMarkup("one$(br2)two"));
  }

  @Test
  void sanitizeDropsControlAndFormattingCodepoints() {
    assertEquals("ab", KnowledgeText.sanitize("a\u0002b"));
    assertEquals("ab", KnowledgeText.sanitize("a\u007Fb"));
    assertEquals("ab", KnowledgeText.sanitize("a\u009Bb"));
    assertEquals("ab", KnowledgeText.sanitize("a\u202Eb"));
    assertEquals("ab", KnowledgeText.sanitize("a\u2066b"));
    assertEquals("ab", KnowledgeText.sanitize("a\u200Eb"));
    assertEquals("ab", KnowledgeText.sanitize("a\u200Fb"));
    assertEquals("ab", KnowledgeText.sanitize("a\u061Cb"));
    assertEquals("a b", KnowledgeText.sanitize("a\tb"));
    assertEquals("a\nb", KnowledgeText.sanitize("a\nb"));
  }

  @Test
  void sanitizeKeepsSupplementaryCharactersAndDropsLoneSurrogates() {
    assertEquals("gear ⚙", KnowledgeText.sanitize("gear ⚙"));
    assertEquals("ok", KnowledgeText.sanitize("ok\uD800"));
    assertEquals("ok", KnowledgeText.sanitize("\uDC00ok"));
  }

  @Test
  void sanitizedBoundsAndTrims() {
    assertEquals("abc", KnowledgeText.sanitized("  abc  ", 10));
    assertEquals("ab", KnowledgeText.sanitized("abcd", 2));
  }
}
