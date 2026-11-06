package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.cli.internal.HumanReport;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.cli.internal.Terminal;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.engine.test.LiveProvider;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests what the installer prints to a person who runs it in a terminal, and that it prints its machine-readable
 * result to every other reader.
 */
public final class HumanReportTest
{
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public HumanReportTest()
	{
	}

	/**
	 * A person at a terminal reads, after a first install, which product CAT is installed for, the installed version,
	 * that the product must be restarted, and each permission and sandbox entry that install added with the file that
	 * holds it. The report is not JSON.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void terminalReadsInstalledReport() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("human-report-installed-")));
			RecordingIntegration integration = new RecordingIntegration().
				adding(Map.of("settings.json#permissions.allow", List.of("Bash(entry-one)", "Bash(entry-two)")));

			Run run = fixture.run(integration, terminal(), "install", "--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.output, "output").contains("Product harness-a").contains("1.2").
				contains("Start a new Product harness-a session").contains("restart Product harness-a").
				contains(fixture.home.resolve("settings.json").toString()).contains("permissions.allow").
				contains("Bash(entry-one)").contains("Bash(entry-two)").doesNotContain("{").doesNotContain("\"result\"");
			requireThat(run.diagnostics, "diagnostics").isEmpty();
		}
	}

	/**
	 * A person at a terminal who gives a command line that the installer rejects reads what is wrong and what to run
	 * instead, not JSON, and the exit status is the one that the machine-readable result has.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void terminalReadsUsageReport() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("human-report-usage-")));

			Run text = fixture.run(new RecordingIntegration(), terminal(), "rollback", "--harness", "harness-a");
			Run machine = fixture.run(new RecordingIntegration(), pipe(), "rollback", "--harness", "harness-a");

			requireThat(text.status, "status").withContext(text.output, "output").isEqualTo(2);
			requireThat(text.status, "status").isEqualTo(machine.status);
			requireThat(text.output, "output").contains("Next step:").contains("install --harness").
				doesNotContain("{").doesNotContain("\"result\"");
		}
	}

	/**
	 * A person at a terminal who does not allow a change that install asks to make reads that CAT changed nothing and
	 * how to allow the change, not JSON, and the exit status is the one that the machine-readable result has.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void terminalReadsRefusalReport() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("human-report-refused-")));
			RecordingIntegration integration = new RecordingIntegration().
				asking("May CAT change the shared setting?", Map.of("config#selection", List.of("built-in")));

			Run text = fixture.run(integration, terminal(), "install", "--harness", "harness-a");
			Run machine = fixture.run(integration, pipe(), "install", "--harness", "harness-a");

			requireThat(text.status, "status").withContext(text.output, "output").isEqualTo(3);
			requireThat(text.status, "status").isEqualTo(machine.status);
			requireThat(text.output, "output").contains("May CAT change the shared setting?").contains("Next step:").
				doesNotContain("{").doesNotContain("\"result\"");
			requireThat(text.diagnostics, "diagnostics").isEmpty();
		}
	}

	/**
	 * A reader that is not a terminal, as the update skill is, reads one line of JSON, which does not depend on the
	 * terminal's presence otherwise: the same command prints the same line with {@code --json} at a terminal.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void pipeAndJsonOptionReadMachineResult() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture piped = new Fixture(cleanup.add(testDirectory("human-report-pipe-")));
			Fixture json = new Fixture(cleanup.add(testDirectory("human-report-json-")));
			Map<String, List<String>> entries = Map.of("settings.json", List.of("entry"));

			Run machine = piped.run(new RecordingIntegration().adding(entries), pipe(), "install", "--harness",
				"harness-a");
			Run option = json.run(new RecordingIntegration().adding(entries), terminal(), "--json", "install",
				"--harness", "harness-a");

			requireThat(machine.status, "status").withContext(machine.output, "output").isEqualTo(0);
			requireThat(machine.output, "output").startsWith("{\"result\":\"installed\",\"harness\":\"harness-a\"," +
				"\"version\":\"1.2\",\"message\":\"CAT is installed for harness-a.").endsWith("}" + System.lineSeparator());
			requireThat(machine.output.lines().count(), "lines").isEqualTo(1L);
			requireThat(machine.result().path("permissions").path("settings.json").get(0).stringValue(), "entry").
				isEqualTo("entry");
			requireThat(option.status, "status").isEqualTo(0);
			requireThat(option.output.replace(json.root.toString(), "ROOT"), "jsonOption").
				isEqualTo(machine.output.replace(piped.root.toString(), "ROOT"));
		}
	}

	/**
	 * A command line that the installer rejects prints the same JSON line to a terminal that asked for {@code --json}
	 * as to a reader that is not a terminal.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void jsonOptionKeepsFailureJson() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("human-report-usage-json-")));

			Run machine = fixture.run(new RecordingIntegration(), pipe(), "rollback", "--harness", "harness-a");
			Run option = fixture.run(new RecordingIntegration(), terminal(), "--json", "rollback", "--harness",
				"harness-a");

			requireThat(machine.output, "output").startsWith("{\"result\":\"usage\"");
			requireThat(option.output, "output").isEqualTo(machine.output);
			requireThat(option.status, "status").isEqualTo(machine.status);
		}
	}

	/**
	 * Supplies each result that is not a success.
	 *
	 * @return the results
	 */
	@DataProvider
	public Object[][] failures()
	{
		return new Object[][]
			{
				{Result.USAGE},
				{Result.REFUSED},
				{Result.DOWNLOAD_FAILED},
				{Result.UNINSTALL_INCOMPLETE},
				{Result.FAILED},
				{Result.LOCKED},
				{Result.NOT_WRITABLE}
			};
	}

	/**
	 * Every result that is not a success tells a person the cause, the files involved and the next step, and holds
	 * no JSON.
	 *
	 * @param kind the result
	 */
	@Test(dataProvider = "failures")
	public void failureNamesCauseAndNextStep(Result kind)
	{
		ObjectNode result = result(kind, "The cause is in this sentence.");
		ArrayNode paths = result.putArray("paths");
		paths.add("/data/involved-file");

		String report = HumanReport.render(result, Optional.of("Product A"), Optional.empty());

		requireThat(report, "report").contains("The cause is in this sentence.").contains("/data/involved-file").
			contains("Next step:").doesNotContain("{").doesNotContain("\"result\"").doesNotContain("\tat ");
		if (kind != Result.USAGE)
			requireThat(report, "report").contains("Product A");
	}

	/**
	 * A failure whose harness the installer does not know is still reported with its cause and next step.
	 */
	@Test
	public void failureWithoutProductStillReportsCause()
	{
		ObjectNode result = result(Result.REFUSED, "The cause is in this sentence.");

		String report = HumanReport.render(result, Optional.empty(), Optional.empty());

		requireThat(report, "report").contains("The cause is in this sentence.").contains("Next step:").
			doesNotContain("{");
	}

	/**
	 * An install that the user can allow with a command tells the person to run that command.
	 */
	@Test
	public void withheldConsentNamesCommand()
	{
		ObjectNode result = result(Result.REFUSED, "May CAT change the setting? CAT changed nothing.");
		result.put("consent_command", "/launcher/bin/installer --create-profile install --harness harness-a");

		String report = HumanReport.render(result, Optional.of("Product A"), Optional.empty());

		requireThat(report, "report").contains("Next step:").
			contains("/launcher/bin/installer --create-profile install --harness harness-a").doesNotContain("{");
	}

	/**
	 * Supplies each result that is a success, with the sentence that opens its headline.
	 *
	 * @return the result and the words that its report holds
	 */
	@DataProvider
	public Object[][] successes()
	{
		return new Object[][]
			{
				{Result.INSTALLED, "is installed", true},
				{Result.UPDATED, "is updated", true},
				{Result.UP_TO_DATE, "up to date", false},
				{Result.UNINSTALLED, "removed", true}
			};
	}

	/**
	 * A success names the product and the version, tells the person to restart the product when it changed what the
	 * product loads, and holds no JSON.
	 *
	 * @param kind    the result
	 * @param words   the words that the headline holds
	 * @param restart whether the person must restart the product
	 */
	@Test(dataProvider = "successes")
	public void successNamesProductVersionAndRestart(Result kind, String words, boolean restart)
	{
		ObjectNode result = result(kind, "CAT was changed. A notice.");
		result.put("version", "2.3");
		result.put("previous_version", "2.2");

		String report = HumanReport.render(result, Optional.of("Product A"), Optional.empty());

		requireThat(report, "report").contains("Product A").contains(words).contains("A notice.").
			doesNotContain("{").doesNotContain("\"result\"");
		if (kind != Result.UNINSTALLED)
			requireThat(report, "report").contains("2.3");
		if (restart)
			requireThat(report, "report").contains("Start a new Product A session");
		else
			requireThat(report, "report").doesNotContain("Start a new");
	}

	/**
	 * A permission entry is listed with the file that holds it, in the harness home, and the setting in the file.
	 */
	@Test
	public void entriesNameFileAndSetting()
	{
		ObjectNode result = result(Result.INSTALLED, "CAT is installed for harness-a.");
		result.put("version", "2.3");
		ObjectNode permissions = result.putObject("permissions");
		permissions.putArray("settings.json#sandbox.excludedCommands").add("entry-one");
		permissions.putArray("rules/cat-installer.rules").add("entry-two");

		String report = HumanReport.render(result, Optional.of("Product A"), Optional.of(Path.of("/user/home")));

		requireThat(report, "report").contains(Path.of("/user/home/settings.json").toString()).
			contains("sandbox.excludedCommands").contains("entry-one").
			contains(Path.of("/user/home/rules/cat-installer.rules").toString()).contains("entry-two");
	}

	/**
	 * A status says whether CAT is installed, and says when the installation is inconsistent.
	 */
	@Test
	public void statusSaysWhetherInstalled()
	{
		ObjectNode installed = result(Result.STATUS, "The installation state was read.");
		installed.put("version", "2.3");
		installed.put("installed", true);
		ObjectNode missing = result(Result.STATUS, "Run the explicit CAT install command for this harness.");
		missing.put("installed", false);
		ObjectNode inconsistent = result(Result.STATUS, "The installation state was read.");
		inconsistent.put("version", "2.3");
		inconsistent.put("installed", true);
		inconsistent.put("consistent", false);

		String present = HumanReport.render(installed, Optional.of("Product A"), Optional.empty());
		String absent = HumanReport.render(missing, Optional.of("Product A"), Optional.empty());
		String broken = HumanReport.render(inconsistent, Optional.of("Product A"), Optional.empty());

		requireThat(present, "installed").contains("2.3").contains("installed").doesNotContain("not installed");
		requireThat(absent, "missing").contains("not installed").contains("Run the explicit CAT install command");
		requireThat(broken, "inconsistent").contains("2.3").contains("inconsistent");
	}

	/**
	 * Returns a result object as the installer encodes it.
	 *
	 * @param kind    the result
	 * @param message the message
	 * @return the object
	 */
	private static ObjectNode result(Result kind, String message)
	{
		ObjectNode result = MAPPER.createObjectNode();
		result.put("result", kind.name().toLowerCase(Locale.ROOT));
		result.put("harness", "harness-a");
		result.put("version", "");
		result.put("message", message);
		result.putArray("paths");
		result.put("consistent", true);
		result.putArray("key_ids");
		return result;
	}

	/**
	 * Returns a terminal on which the person reads the installer's standard output and answers no to every question.
	 *
	 * @return the terminal
	 */
	private static Terminal terminal()
	{
		return new TestTerminal(true, _ -> Optional.of("n"));
	}

	/**
	 * Returns a terminal whose standard output is redirected, as when the update skill runs the installer.
	 *
	 * @return the terminal
	 */
	private static Terminal pipe()
	{
		return new TestTerminal(false, _ -> Optional.of("n"));
	}

	/**
	 * A terminal with a given answer to every question.
	 *
	 * @param stdoutTerminal whether the person reads the standard output
	 * @param answers        the answer to a question
	 */
	private record TestTerminal(boolean stdoutTerminal, Function<String, Optional<String>> answers) implements Terminal
	{
		@Override
		public Optional<String> ask(String question)
		{
			return answers.apply(question);
		}

		@Override
		public boolean isStdoutTerminal()
		{
			return stdoutTerminal;
		}
	}

	/**
	 * The outcome of one invocation.
	 *
	 * @param status      the exit status
	 * @param output      the standard output
	 * @param diagnostics the standard error
	 */
	private record Run(int status, String output, String diagnostics)
	{
		/**
		 * Returns the printed result.
		 *
		 * @return the JSON object
		 */
		ObjectNode result()
		{
			return (ObjectNode) MAPPER.readTree(output);
		}
	}

	/**
	 * An extracted plugin archive of {@code harness-a} with a harness home into which nothing is installed yet.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path plugin;
		private final Path home;
		private final Map<String, String> environment;

		/**
		 * Creates the fixture.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the archive cannot be created
		 * @throws TrustException if the release's policy is invalid
		 */
		private Fixture(Path directory) throws IOException, TrustException
		{
			this.root = directory.toRealPath();
			this.plugin = PluginArchive.create(root.resolve("download"), List.of(trusted("release-key", keyPair())));
			this.home = Files.createDirectory(root.resolve("home"));
			Path user = Files.createDirectory(root.resolve("user"));
			Path tools = RecordingIntegration.program(root.resolve("tools")).getParent();
			Map<String, String> variables = new HashMap<>(RecordingIntegration.windowsVariables());
			variables.put("HOME", user.toString());
			variables.put(Platform.current().homeVariable(), user.toString());
			variables.put("PATH", tools.toString());
			this.environment = Map.copyOf(variables);
		}

		/**
		 * Runs the installer from the archive into the harness home.
		 *
		 * @param integration the harness integration
		 * @param terminal    the user's terminal
		 * @param operation   the operation and its options, after the harness home
		 * @return the outcome
		 */
		private Run run(RecordingIntegration integration, Terminal terminal, String... operation)
		{
			List<String> arguments = new ArrayList<>();
			// An option that precedes the operation goes before the harness home
			int start = 0;
			while (operation[start].startsWith("--"))
			{
				arguments.add(operation[start]);
				++start;
			}
			arguments.addAll(List.of("--home", home.toString()));
			for (int i = start; i < operation.length; ++i)
				arguments.add(operation[i]);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			int status;
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				Invocation invocation = new Invocation(plugin, Platform.current(),
					environment, List.of(new LiveProvider()), List.of(integration),
					(command, harnessHome) -> RecordingIntegration.cli(), Clock.systemUTC(), interval -> () ->
					{
					}, new FileReplacer(Platform.current(), () ->
					{
					}, Clock.systemUTC()), terminal, command ->
					{
						throw new AssertionError("Started " + command);
					}, stdout, stderr);
				status = invocation.run(arguments.toArray(String[]::new));
			}
			return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
		}
	}
}
