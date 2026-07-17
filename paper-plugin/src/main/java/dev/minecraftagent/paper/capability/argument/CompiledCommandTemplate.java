package dev.minecraftagent.paper.capability.argument;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A command template whose only variable bytes come from typed argument codecs. */
public final class CompiledCommandTemplate {
  private static final Pattern COMMAND_ROOT = Pattern.compile("^[a-z0-9:_-]{1,64}$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-zA-Z0-9_]{0,63})}");
  private static final int MAX_TEMPLATE_LENGTH = 1024;
  private static final int MAX_RENDERED_COMMAND_LENGTH = 1024;

  private final String commandRoot;
  private final CapabilityArgumentSet arguments;
  private final List<Segment> segments;
  private final String suffix;

  private CompiledCommandTemplate(
      String commandRoot, CapabilityArgumentSet arguments, List<Segment> segments, String suffix) {
    this.commandRoot = commandRoot;
    this.arguments = arguments;
    this.segments = List.copyOf(segments);
    this.suffix = suffix;
  }

  public static CompiledCommandTemplate compile(
      String commandRoot, String template, JsonObject descriptors) {
    return compile(commandRoot, template, CapabilityArgumentSet.compile(descriptors));
  }

  public static CompiledCommandTemplate compile(
      String commandRoot, String template, CapabilityArgumentSet arguments) {
    Objects.requireNonNull(commandRoot);
    Objects.requireNonNull(template);
    Objects.requireNonNull(arguments);
    validateFixedTemplate(commandRoot, template);

    var matcher = PLACEHOLDER.matcher(template);
    var segments = new ArrayList<Segment>();
    var placeholders = new HashSet<String>();
    var offset = 0;
    while (matcher.find()) {
      var name = matcher.group(1);
      if (matcher.start() == 0
          || template.charAt(matcher.start() - 1) != ' '
          || (matcher.end() < template.length() && template.charAt(matcher.end()) != ' ')
          || !arguments.types().containsKey(name)
          || !placeholders.add(name)) {
        throw failure(CapabilityArgumentException.Failure.TEMPLATE_INVALID, name);
      }
      segments.add(new Segment(template.substring(offset, matcher.start()), name));
      offset = matcher.end();
    }
    var withoutPlaceholders = PLACEHOLDER.matcher(template).replaceAll("");
    if (withoutPlaceholders.indexOf('{') >= 0 || withoutPlaceholders.indexOf('}') >= 0) {
      throw failure(CapabilityArgumentException.Failure.TEMPLATE_INVALID, null);
    }
    for (var name : arguments.types().keySet()) {
      if (!placeholders.contains(name)) {
        throw failure(CapabilityArgumentException.Failure.TEMPLATE_INVALID, name);
      }
    }
    return new CompiledCommandTemplate(
        commandRoot, arguments, segments, template.substring(offset));
  }

  public String commandRoot() {
    return commandRoot;
  }

  public String render(JsonObject values) {
    var encoded = arguments.encode(values);
    var command = new StringBuilder();
    for (var segment : segments) {
      command.append(segment.literal()).append(encoded.get(segment.argumentName()));
    }
    command.append(suffix);
    if (command.length() > MAX_RENDERED_COMMAND_LENGTH) {
      throw failure(CapabilityArgumentException.Failure.COMMAND_TOO_LONG, null);
    }
    var rendered = command.toString();
    requireExactRoot(commandRoot, rendered);
    return rendered;
  }

  private static void validateFixedTemplate(String commandRoot, String template) {
    if (!COMMAND_ROOT.matcher(commandRoot).matches()
        || template.length() < 2
        || template.length() > MAX_TEMPLATE_LENGTH
        || template.charAt(template.length() - 1) == ' '
        || template.contains("  ")) {
      throw failure(CapabilityArgumentException.Failure.TEMPLATE_INVALID, null);
    }
    for (int index = 0; index < template.length(); index++) {
      var character = template.charAt(index);
      if (character < 0x20
          || character > 0x7e
          || character == ';'
          || character == '\\'
          || character == '\''
          || character == '"'
          || character == '|'
          || character == '&') {
        throw failure(CapabilityArgumentException.Failure.TEMPLATE_INVALID, null);
      }
    }
    requireExactRoot(commandRoot, template);
  }

  private static void requireExactRoot(String commandRoot, String command) {
    var prefix = "/" + commandRoot;
    if (!command.startsWith(prefix)
        || (command.length() > prefix.length() && command.charAt(prefix.length()) != ' ')) {
      throw failure(CapabilityArgumentException.Failure.TEMPLATE_INVALID, null);
    }
  }

  private static CapabilityArgumentException failure(
      CapabilityArgumentException.Failure failure, String name) {
    return new CapabilityArgumentException(failure, name);
  }

  private record Segment(String literal, String argumentName) {}
}
