import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

final class VerifyTestResults {
  private static final Set<String> RUNTIME_REQUIRED =
      Set.of(
          "test/anthropic-messages-provider.test.ts",
          "test/gemini-generate-content-provider.test.ts",
          "test/live-provider-check.test.ts",
          "test/main-module.test.ts",
          "test/openai-chat-completions-provider.test.ts",
          "test/openai-responses-provider.test.ts",
          "test/standalone-protocol-contract.test.ts",
          "test/graphical-client-runtime.test.ts",
          "test/provider-factory.test.ts",
          "test/usage-accounting.test.ts");
  private static final Set<String> PAPER_REQUIRED =
      Set.of(
          "dev.minecraftagent.paper.capability.load.CapabilityDeterministicFuzzTest",
          "dev.minecraftagent.paper.command.AgentCommandMockBukkitTest",
          "dev.minecraftagent.protocol.SharedProtocolContractTest");
  private static final Set<String> CLIENT_REQUIRED =
      Set.of(
          "dev.minecraftagent.client.litematica.LitematicaAdapterTest",
          "dev.minecraftagent.protocol.SharedProtocolContractTest");
  private static final Set<String> STANDALONE_CORE_REQUIRED =
      Set.of(
          "dev.minecraftagent.protocol.SharedProtocolContractTest",
          "dev.minecraftagent.standalone.core.contract.C0AssetTest",
          "dev.minecraftagent.standalone.core.contract.ContractModelTest");
  private static final Set<String> SUPERVISOR_REQUIRED =
      Set.of(
          "dev.minecraftagent.standalone.supervisor.RuntimeSupervisorTest",
          "dev.minecraftagent.standalone.supervisor.install.ManagedRuntimeInstallerTest",
          "dev.minecraftagent.standalone.supervisor.install.NodeDistributionCatalogTest");
  private static final Set<String> FABRIC_COMMON_REQUIRED =
      Set.of(
          "dev.minecraftagent.standalone.common.ClientProfileStoreTest",
          "dev.minecraftagent.standalone.common.EmbeddedRuntimeDistributionTest",
          "dev.minecraftagent.standalone.common.JavaHttpLocalConnectorTest");
  private static final Set<String> FABRIC_VERSION_REQUIRED =
      Set.of("dev.minecraftagent.standalone.fabric.StandaloneModMetadataTest");

  private record Suite(String name, int tests, int failures, int errors, int skipped) {}

  private record Lane(
      String name, int minimumSuites, int minimumTests, Set<String> requiredSuites) {}

  private VerifyTestResults() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 8) {
      fail(
          "usage: VerifyTestResults.java <runtime.xml> <paper-results> <client-results>"
              + " <standalone-core-results> <supervisor-results> <fabric-common-results>"
              + " <fabric-mc12111-results> <fabric-mc1182-results>");
    }

    verify(
        new Lane("Runtime", 26, 287, RUNTIME_REQUIRED),
        suitesFromDocument(Path.of(args[0])));
    verify(
        new Lane("Paper", 59, 463, PAPER_REQUIRED),
        suitesFromDirectory(Path.of(args[1])));
    verify(
        new Lane("Client", 17, 210, CLIENT_REQUIRED),
        suitesFromDirectory(Path.of(args[2])));
    verify(
        new Lane("Standalone Core", 3, 19, STANDALONE_CORE_REQUIRED),
        suitesFromDirectory(Path.of(args[3])));
    verify(
        new Lane("Standalone Supervisor", 3, 20, SUPERVISOR_REQUIRED),
        suitesFromDirectory(Path.of(args[4])));
    verify(
        new Lane("Standalone Fabric Common", 10, 30, FABRIC_COMMON_REQUIRED),
        suitesFromDirectory(Path.of(args[5])));
    verify(
        new Lane("Standalone Minecraft 1.21.11", 1, 1, FABRIC_VERSION_REQUIRED),
        suitesFromDirectory(Path.of(args[6])));
    verify(
        new Lane("Standalone Minecraft 1.18.2", 1, 1, FABRIC_VERSION_REQUIRED),
        suitesFromDirectory(Path.of(args[7])));
  }

  private static List<Suite> suitesFromDirectory(Path directory) throws Exception {
    if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
      fail("test result directory is missing or unsafe: " + directory);
    }
    var files = new ArrayList<Path>();
    try (var paths = Files.list(directory)) {
      paths
          .filter(path -> path.getFileName().toString().startsWith("TEST-"))
          .filter(path -> path.getFileName().toString().endsWith(".xml"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(files::add);
    }
    if (files.isEmpty()) {
      fail("no JUnit XML files found in " + directory);
    }

    var suites = new ArrayList<Suite>();
    for (var file : files) {
      suites.addAll(suitesFromDocument(file));
    }
    return suites;
  }

  private static List<Suite> suitesFromDocument(Path path) throws Exception {
    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
      fail("test result file is missing or unsafe: " + path);
    }
    var root = parse(path);
    var suites = new ArrayList<Suite>();
    if (root.getTagName().equals("testsuite")) {
      suites.add(readSuite(root, path));
    } else if (root.getTagName().equals("testsuites")) {
      var children = root.getChildNodes();
      for (var index = 0; index < children.getLength(); index++) {
        if (children.item(index) instanceof Element child && child.getTagName().equals("testsuite")) {
          suites.add(readSuite(child, path));
        }
      }
    } else {
      fail("unexpected JUnit XML root in " + path);
    }
    if (suites.isEmpty()) {
      fail("JUnit XML contains no suites: " + path);
    }
    return suites;
  }

  private static Element parse(Path path)
      throws ParserConfigurationException, IOException, SAXException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setExpandEntityReferences(false);
    factory.setXIncludeAware(false);
    try (var input = Files.newInputStream(path)) {
      return factory.newDocumentBuilder().parse(input).getDocumentElement();
    }
  }

  private static Suite readSuite(Element element, Path source) {
    var name = element.getAttribute("name");
    if (name.isBlank()) {
      fail("test suite has no name in " + source);
    }
    return new Suite(
        name,
        nonNegativeAttribute(element, "tests", source),
        nonNegativeAttribute(element, "failures", source),
        nonNegativeAttribute(element, "errors", source),
        nonNegativeAttribute(element, "skipped", source));
  }

  private static int nonNegativeAttribute(Element element, String name, Path source) {
    var value = element.getAttribute(name);
    if (value.isEmpty() && name.equals("skipped")) {
      return 0;
    }
    try {
      var parsed = Integer.parseInt(value);
      if (parsed < 0) {
        fail("negative " + name + " count in " + source);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      fail("invalid " + name + " count in " + source);
      return 0;
    }
  }

  private static void verify(Lane lane, List<Suite> suites) {
    var tests = suites.stream().mapToInt(Suite::tests).sum();
    var failures = suites.stream().mapToInt(Suite::failures).sum();
    var errors = suites.stream().mapToInt(Suite::errors).sum();
    var skipped = suites.stream().mapToInt(Suite::skipped).sum();
    if (suites.size() < lane.minimumSuites() || tests < lane.minimumTests()) {
      fail(
          "%s inventory fell below the release baseline: suites=%d tests=%d"
              .formatted(lane.name(), suites.size(), tests));
    }
    if (failures != 0 || errors != 0 || skipped != 0) {
      fail(
          "%s results are not clean: failures=%d errors=%d skipped=%d"
              .formatted(lane.name(), failures, errors, skipped));
    }
    var names = new HashSet<String>();
    suites.forEach(suite -> names.add(suite.name()));
    var missing = new HashSet<>(lane.requiredSuites());
    missing.removeAll(names);
    if (!missing.isEmpty()) {
      fail(lane.name() + " required suites are missing: " + missing.stream().sorted().toList());
    }
    System.out.printf(
        "test-inventory lane=%s suites=%d tests=%d failures=0 errors=0 skipped=0%n",
        lane.name().toLowerCase(), suites.size(), tests);
  }

  private static void fail(String message) {
    throw new IllegalStateException(message);
  }
}
