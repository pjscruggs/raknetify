package raknetify.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual Java policy against temporary boundary and documentation probes. */
class QualityChecksTest {
  /** Disposable source directory supplied and removed by JUnit. */
  @TempDir Path directory;

  /**
   * Runs the checked-in policy on one temporary compilation unit without a game classpath.
   *
   * @param source complete Java probe source
   * @return reported policy messages
   * @throws Exception if the temporary source or policy cannot be read
   */
  private List<String> check(String source) throws Exception {
    Path file = directory.resolve("Probe.java");
    Files.writeString(file, source + "\n");
    List<String> messages = new ArrayList<>();
    Checker checker = new Checker();
    try {
      checker.setModuleClassLoader(getClass().getClassLoader());
      checker.addListener(new RecordingListener(messages));
      checker.configure(
          ConfigurationLoader.loadConfiguration(
              "checkstyle.xml", new PropertiesExpander(new Properties())));
      checker.process(List.of(file.toFile()));
    } finally {
      checker.destroy();
    }
    return messages;
  }

  /**
   * Constructs a known number of independent Java branch statements.
   *
   * @param count number of decisions, excluding the initial straight-line path
   * @return probe body containing the requested number of branches
   */
  private String decisions(int count) {
    return "if (value) { System.out.println(value); }\n".repeat(count);
  }

  /**
   * Requires a documented ten-path method to pass and an eleven-path method to fail.
   *
   * @throws Exception if the temporary probe cannot be checked
   */
  @Test
  void methodsHaveAHardTenPathLimit() throws Exception {
    String head =
        "/** Probe. */\nclass Probe {\n/** Checks value.\n"
            + " * @param value branch input\n"
            + " */ void run(boolean value) {";
    assertEquals(List.of(), check(head + decisions(9) + "} }"));
    assertTrue(
        check(head + decisions(10) + "} }").stream()
            .anyMatch(message -> message.contains("Complexity is 11")));
  }

  /**
   * Requires constructors and static initializers to obey the same ceiling.
   *
   * @throws Exception if a temporary probe cannot be checked
   */
  @Test
  void constructorsAndInitializersCannotBypassTheLimit() throws Exception {
    assertTrue(
        check("class Probe { Probe(boolean value) {" + decisions(10) + "} }").stream()
            .anyMatch(message -> message.contains("Complexity is 11")));
    assertTrue(
        check("class Probe { static { boolean value = true;" + decisions(10) + "} }").stream()
            .anyMatch(message -> message.contains("Complexity is 11")));
  }

  /**
   * Measures a lambda stored in a field independently from methods and constructors.
   *
   * @throws Exception if a temporary probe cannot be checked
   */
  @Test
  void fieldLambdasCannotBypassTheLimit() throws Exception {
    String head = "class Probe { Runnable run = () -> { boolean value = true;";
    assertTrue(
        check(head + decisions(9) + "}; }").stream()
            .noneMatch(message -> message.contains("Lambda cyclomatic")));
    assertTrue(
        check(head + decisions(10) + "}; }").stream()
            .anyMatch(message -> message.contains("Lambda cyclomatic complexity is 11")));
  }

  /**
   * Rejects undocumented private methods and overrides rather than silently skipping them.
   *
   * @throws Exception if a temporary probe cannot be checked
   */
  @Test
  void privateAndOverrideDocumentationIsMandatory() throws Exception {
    List<String> messages =
        check(
            "class Probe { private void hidden() {} @Override public String toString() { return"
                + " \"probe\"; } }");
    assertTrue(messages.stream().anyMatch(message -> message.contains("'hidden'")));
    assertTrue(messages.stream().anyMatch(message -> message.contains("'toString'")));
  }

  /**
   * Requires types, constructors, fields, and parameter contracts as well as method summaries.
   *
   * @throws Exception if a temporary probe cannot be checked
   */
  @Test
  void declarationsAndParameterContractsAreRequired() throws Exception {
    List<String> missing = check("class Probe { private int value; private Probe() {} }");
    assertTrue(missing.stream().anyMatch(message -> message.contains("'Probe'")));
    assertTrue(missing.stream().anyMatch(message -> message.contains("'value'")));
    assertTrue(
        check("/** Probe. */ class Probe { /** Computes a result. */ int run(int input) { return input; } }")
            .stream().anyMatch(message -> message.contains("@param")));
    assertTrue(
        check("/** Probe. */ class Probe { /** Computes a result. */ int run() { return 1; } }")
            .stream().anyMatch(message -> message.contains("@return")));
  }

  /**
   * Rejects empty documentation instead of treating comment delimiters as an explanation.
   *
   * @throws Exception if a temporary probe cannot be checked
   */
  @Test
  void emptyDocumentationDoesNotSatisfyThePolicy() throws Exception {
    assertTrue(!check("/** */ class Probe { /** */ private int value; /** */ void run() {} }").isEmpty());
  }

  /**
   * Collects violations while preserving the complete checked-in policy configuration.
   *
   * @param messages mutable collector receiving policy messages from checked probe sources
   */
  private record RecordingListener(List<String> messages) implements AuditListener {
    /** {@inheritDoc} */
    @Override
    public void auditStarted(AuditEvent event) {}

    /** {@inheritDoc} */
    @Override
    public void auditFinished(AuditEvent event) {}

    /** {@inheritDoc} */
    @Override
    public void fileStarted(AuditEvent event) {}

    /** {@inheritDoc} */
    @Override
    public void fileFinished(AuditEvent event) {}

    /** {@inheritDoc} */
    @Override
    public void addError(AuditEvent event) {
      messages.add(event.getMessage());
    }

    /** {@inheritDoc} */
    @Override
    public void addException(AuditEvent event, Throwable exception) {
      throw new IllegalStateException("Checkstyle could not inspect probe", exception);
    }
  }
}
