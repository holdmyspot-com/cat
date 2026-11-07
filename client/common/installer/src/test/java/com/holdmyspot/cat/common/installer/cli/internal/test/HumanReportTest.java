package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.cli.internal.HumanReport;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessTerminal;
import com.holdmyspot.cat.common.installer.cli.internal.ReportedEntries;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.engine.test.LiveProvider;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
	 * A reader that is not a terminal, as the update skill is, reads one line of JSON and the exit status of the
	 * result. Under the test runner the installer's standard output is a pipe, which the process's own terminal
	 * reports.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void pipeReadsMachineResult() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture piped = new Fixture(cleanup.add(testDirectory("human-report-pipe-")));
			Map<String, List<String>> entries = Map.of("settings.json", List.of("entry"));

			Run machine = piped.run(new RecordingIntegration().adding(entries), "install", "--harness", "harness-a");

			requireThat(machine.status, "status").withContext(machine.output, "output").isEqualTo(0);
			requireThat(machine.output, "output").startsWith("{\"result\":\"installed\",\"harness\":\"harness-a\"," +
				"\"version\":\"1.2\",\"message\":\"CAT is installed for harness-a.").endsWith("}" + System.lineSeparator());
			requireThat(machine.output.lines().count(), "lines").isEqualTo(1L);
			requireThat(machine.result().path("permissions").path("settings.json").get(0).stringValue(), "entry").
				isEqualTo("entry");
			requireThat(machine.diagnostics, "diagnostics").isEmpty();
		}
	}

	/**
	 * A command line that the installer rejects prints one line of JSON and the usage exit status to a reader that is
	 * not a terminal.
	 *
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void pipeReadsFailureJson() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("human-report-usage-json-")));

			Run machine = fixture.run(new RecordingIntegration(), "rollback", "--harness", "harness-a");

			requireThat(machine.output, "output").startsWith("{\"result\":\"usage\"");
			requireThat(machine.output.lines().count(), "lines").isEqualTo(1L);
			requireThat(machine.status, "status").isEqualTo(Result.USAGE.exitStatus());
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

		String report = HumanReport.render(result, Optional.of("Product A"), null);

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

		String report = HumanReport.render(result, Optional.empty(), null);

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

		String report = HumanReport.render(result, Optional.of("Product A"), null);

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

		String report = HumanReport.render(result, Optional.of("Product A"), null);

		requireThat(report, "report").contains("Product A").contains(words).contains("A notice.").
			doesNotContain("{").doesNotContain("\"result\"");
		if (kind != Result.UNINSTALLED)
			requireThat(report, "report").contains("2.3");
		if (restart)
			requireThat(report, "report").contains("Restart Product A for the changes to take effect.");
		else
			requireThat(report, "report").doesNotContain("Restart");
	}

	/**
	 * An install lists its entries once per changed file, grouped by effect in a fixed order, with the installer's
	 * operations collapsed to their names, paths in the user's home directory written relative to {@code ~}, a legend
	 * for each abbreviation and the command that lists the entries exactly.
	 */
	@Test
	public void entriesGroupByEffect()
	{
		Path userHome = root().resolve("user").resolve("home");
		Path harnessHome = userHome.resolve(".claude");
		Path installer = harnessHome.resolve("plugins").resolve("cat").resolve("bin").resolve("installer");
		Path elsewhere = root().resolve("elsewhere").resolve("data");
		Path settings = Path.of("settings.json");
		List<EntryGroup> groups = List.of(
			new EntryGroup(EntryEffect.SANDBOX_MAY_NOT_WRITE, settings, "sandbox.filesystem.denyWrite",
				List.of(userHome.resolve(".codex").resolve("data").toString(), elsewhere.toString()),
				List.of("deny-one", "deny-two")),
			new EntryGroup(EntryEffect.RUNS_OUTSIDE_SANDBOX, settings, "sandbox.excludedCommands",
				List.of("uninstall", "install", "status", "install"), List.of("excluded")),
			new EntryGroup(EntryEffect.SANDBOX_MAY_WRITE, settings, "sandbox.filesystem.allowWrite",
				List.of(userHome.resolve(".cat").resolve("locks").toString()), List.of("allow-write")),
			new EntryGroup(EntryEffect.SETS_VALUE, settings, "unused", List.of(), List.of()),
			new EntryGroup(EntryEffect.RUNS_WITHOUT_ASKING, settings, "permissions.allow",
				List.of("status", "install"), List.of("allowed")));
		ObjectNode result = result(Result.INSTALLED, "CAT is installed for harness-a.");
		result.put("version", "2.3");

		String report = HumanReport.render(result, Optional.of("Product A"),
			new ReportedEntries(groups, harnessHome, userHome, installer, false));

		String separator = userHome.getFileSystem().getSeparator();
		requireThat(report, "report").isEqualTo(lines(
			"CAT 2.3 is installed for Product A. Restart Product A for the changes to take effect.",
			"",
			"CAT changed ~" + separator + ".claude" + separator + "settings.json. Uninstalling CAT undoes these " +
				"changes.",
			"",
			"  Runs without asking            (permissions.allow)",
			"    installer install, installer status",
			"",
			"  Runs outside the sandbox       (sandbox.excludedCommands)",
			"    installer install, installer status, installer uninstall",
			"",
			"  The sandbox may write to       (sandbox.filesystem.allowWrite)",
			"    ~" + separator + ".cat" + separator + "locks",
			"",
			"  The sandbox may not write to   (sandbox.filesystem.denyWrite)",
			"    " + elsewhere,
			"    ~" + separator + ".codex" + separator + "data",
			"",
			"  where ~         = " + userHome,
			"        installer = " + String.join(separator, "~", ".claude", "plugins", "cat", "bin", "installer"),
			"",
			"Run installer status --verbose to see the exact entries."));
	}

	/**
	 * Exact entries list each entry as the configuration holds it, name the setting of each file when they span more
	 * than one file, and leave out the installer's abbreviation and the command that lists them exactly.
	 */
	@Test
	public void exactEntriesNameEachFileAndSetting()
	{
		Path userHome = root().resolve("user");
		Path harnessHome = root().resolve("harness");
		Path installer = harnessHome.resolve("bin").resolve("installer");
		Path rules = Path.of("rules").resolve("cat-installer.rules");
		Path configuration = Path.of("config.toml");
		List<EntryGroup> groups = List.of(
			new EntryGroup(EntryEffect.SANDBOX_MAY_WRITE, configuration, "sandbox_workspace_write.writable_roots",
				List.of(userHome.resolve("locks").toString()), List.of("\"" + userHome.resolve("locks") + "\"")),
			new EntryGroup(EntryEffect.RUNS_WITHOUT_ASKING, rules, "", List.of("install"),
				List.of("prefix_rule(install)")));
		ObjectNode result = result(Result.STATUS, "The installation state was read.");
		result.put("version", "2.3");
		result.put("installed", true);

		String report = HumanReport.render(result, Optional.of("Product A"),
			new ReportedEntries(groups, harnessHome, userHome, installer, true));

		requireThat(report, "report").isEqualTo(lines(
			"CAT 2.3 is installed for Product A.",
			"",
			"CAT changed " + harnessHome.resolve(rules) + " and " + harnessHome.resolve(configuration) +
				". Uninstalling CAT undoes these changes.",
			"",
			"  Runs without asking        (" + rules + ")",
			"    prefix_rule(install)",
			"",
			"  The sandbox may write to   (config.toml: sandbox_workspace_write.writable_roots)",
			"    \"" + userHome.resolve("locks") + "\""));
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

		String present = HumanReport.render(installed, Optional.of("Product A"), null);
		String absent = HumanReport.render(missing, Optional.of("Product A"), null);
		String broken = HumanReport.render(inconsistent, Optional.of("Product A"), null);

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
	 * Returns the root of the file system's absolute paths.
	 *
	 * @return the first root directory of the default file system
	 */
	private static Path root()
	{
		return FileSystems.getDefault().getRootDirectories().iterator().next();
	}

	/**
	 * Joins the lines of a report.
	 *
	 * @param lines the lines
	 * @return the lines, separated by the platform's line separator
	 */
	private static String lines(String... lines)
	{
		return String.join(System.lineSeparator(), lines);
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
			Path root = directory.toRealPath();
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
		 * Runs the installer from the archive into the harness home, with the terminal of the test runner's process.
		 *
		 * @param integration the harness integration
		 * @param operation   the operation and its options, after the harness home
		 * @return the outcome
		 */
		private Run run(RecordingIntegration integration, String... operation)
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
					environment, null, List.of(new LiveProvider()), List.of(integration),
					(command, harnessHome) -> RecordingIntegration.cli(), Clock.systemUTC(), interval -> () ->
					{
					}, new FileReplacer(Platform.current(), () ->
					{
					}, Clock.systemUTC()), new ProcessTerminal(Platform.current()), command ->
					{
						throw new AssertionError("Started " + command);
					}, stdout, stderr);
				status = invocation.run(arguments.toArray(String[]::new));
			}
			return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
		}
	}
}
