package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.cli.internal.ConsentCommand;
import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.cli.internal.Terminal;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.engine.test.RegisteredProvider;
import com.holdmyspot.cat.common.installer.fakes.test.ExitedProgram;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that an update ends with the settings entries of the version that it installs. The installer that installs
 * another version, whose code may write entries that the new version no longer writes, runs the new version folder's
 * installer once with the same arguments, which finds its version active and writes every entry with its own code.
 * <p>
 * The installer of each version is CAT's real installer in this test process: the installer of release 1.0 stands for
 * an older build, and its harness integration writes no entry, while that of release 1.2 writes the entry
 * {@link Fixture#NEW_ENTRY}. The run of release 1.2's installer that the update starts is that real installer, given
 * the command line, environment and standard output of the process that the update starts.
 */
public final class UpdateEntriesTest
{
	/**
	 * The command line of an update that names release 1.2, which is also the command line of the run of release
	 * 1.2's installer that ends every update to release 1.2.
	 */
	private static final List<String> UPDATE = List.of("install", "--harness", "harness-a", PluginRelease.TAG);
	/**
	 * The optional change that the harness integrations of releases 1.0 and 1.2 both ask the user to allow.
	 */
	private static final String QUESTION = "CAT can add a profile to the shared configuration.";

	/**
	 * Creates the test.
	 */
	public UpdateEntriesTest()
	{
	}

	/**
	 * An update to another version runs that version's installer once, by the absolute path of its launcher, with the
	 * same arguments, and reports the update together with the result of that run, whose entries the new version's
	 * code wrote.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void updateRunsInstalledInstaller() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-run-")));

			Run update = fixture.update(UPDATE);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			JsonNode result = update.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("updated");
			requireThat(result.path("version").stringValue(), "version").isEqualTo(PluginArchive.VERSION);
			requireThat(result.path("previous_version").stringValue(), "previousVersion").isEqualTo(Fixture.ACTIVE);
			requireThat(fixture.started, "started").size().isEqualTo(1);
			List<String> command = fixture.started.getFirst();
			requireThat(fixture.fromLauncher(command), "command").isEqualTo(fixture.installedLine());
			requireThat(Path.of(command.get(command.size() - UPDATE.size() - 1)), "launcher").isAbsolute();
			requireThat(fixture.installed.callsWithSubjects(), "installedCalls").
				contains("installPermissions " + fixture.installedLauncher());
			requireThat(result.path("message").stringValue(), "message").contains(Fixture.NEW_NOTICE);
		}
	}

	/**
	 * When the harness deletes the folder of the version that the update replaces, which holds the running installer's
	 * Java runtime, while it registers the new version, the runtime's program that starts processes still exists when
	 * the new version's installer is started, and nothing of the old folder is left afterwards.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void deletedFolderKeepsSpawnHelper() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-deleted-")));
			fixture.old.deletingReplaced();

			Run update = fixture.update(UPDATE);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.started, "started").size().isEqualTo(1);
			requireThat(fixture.spawnHelperExisted, "spawnHelperExisted").containsExactly(List.of(true));
			requireThat(Files.exists(fixture.versionFolder(Fixture.ACTIVE)), "oldFolderExists").isFalse();
		}
	}

	/**
	 * The installer of the version that an update installed finds that version active and starts no installer.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void installedInstallerRunsNoInstaller() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-once-")));

			Run update = fixture.update(UPDATE);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.installedRuns, "installedRuns").size().isEqualTo(1);
			Run installed = fixture.installedRuns.getFirst();
			requireThat(installed.status, "installedStatus").withContext(installed.output, "output").
				withContext(installed.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(installed.result().path("result").stringValue(), "installedResult").isEqualTo("up_to_date");
			requireThat(fixture.startedByInstalled, "startedByInstalled").isEmpty();
		}
	}

	/**
	 * An install that finds the requested release active writes the entries itself and starts no installer.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void upToDateInstallRunsNoInstaller() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-current-")));
			Run update = fixture.update(UPDATE);
			requireThat(update.status, "updateStatus").withContext(update.output, "output").isEqualTo(0);
			fixture.started.clear();

			Run current = fixture.runOld(PluginArchive.VERSION, UPDATE);

			requireThat(current.status, "status").withContext(current.output, "output").
				withContext(current.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(current.result().path("result").stringValue(), "result").isEqualTo("up_to_date");
			requireThat(fixture.started, "started").isEmpty();
		}
	}

	/**
	 * When the run of the installed version's installer fails, the update reports the failure, keeps the version that
	 * it installed and names the command that writes the new version's entries: that version's installer with the
	 * same arguments.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void failedRunNamesCommand() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-failed-")));
			fixture.installed.fail("installPermissions");

			Run update = fixture.update(UPDATE);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(10);
			JsonNode result = update.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("failed");
			requireThat(result.path("version").stringValue(), "version").isEqualTo(PluginArchive.VERSION);
			requireThat(fixture.started, "started").size().isEqualTo(1);
			requireThat(result.path("message").stringValue(), "message").contains("harness-a refused installPermissions").
				contains(fixture.installedCommand());
			requireThat(fixture.registeredVersion(), "registered").isEqualTo(PluginArchive.VERSION);
		}
	}

	/**
	 * When the installed version's installer cannot be started, the update reports the failure and names the command
	 * that writes the new version's entries.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void unstartedRunNamesCommand() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-unstarted-")));
			fixture.startFailure = "The launcher cannot be executed";

			Run update = fixture.update(UPDATE);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(10);
			JsonNode result = update.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("failed");
			requireThat(result.path("message").stringValue(), "message").contains("The launcher cannot be executed").
				contains(fixture.installedCommand());
			requireThat(fixture.installedRuns, "installedRuns").isEmpty();
		}
	}

	/**
	 * An update to the latest release runs the installed version's installer with the tag of the release that it
	 * installed, so that a release published in between cannot make that run update again.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void latestUpdateNamesInstalledTag() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-latest-")));

			Run update = fixture.update(List.of("install", "--harness", "harness-a", "latest"));

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.started, "started").size().isEqualTo(1);
			List<String> command = fixture.started.getFirst();
			requireThat(fixture.fromLauncher(command), "command").isEqualTo(fixture.installedLine());
		}
	}

	/**
	 * An update that names no release runs the installed version's installer with the tag of the release that it
	 * installed.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void untaggedUpdateNamesInstalledTag() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-untagged-")));

			Run update = fixture.update(List.of("install", "--harness", "harness-a"));

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.started, "started").size().isEqualTo(1);
			List<String> command = fixture.started.getFirst();
			requireThat(fixture.fromLauncher(command), "command").isEqualTo(fixture.installedLine());
		}
	}

	/**
	 * When the run that ends an update that names no release fails, the command that the failure names carries the
	 * tag of the release that the update installed.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void untaggedFailureNamesInstalledTag() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-untagged-failed-")));
			fixture.installed.fail("installPermissions");

			Run update = fixture.update(List.of("install", "--harness", "harness-a"));

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(10);
			requireThat(update.result().path("message").stringValue(), "message").
				contains(fixture.installedCommand());
		}
	}

	/**
	 * When the user declines an optional change that the update asks about, the run of the installed version's
	 * installer that ends the update does not ask about it again, although the terminal that the update asked on
	 * would answer, and leaves the change unmade, as the user decided.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void declinedQuestionIsNotAskedAgain() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("update-entries-declined-")));
			Map<String, List<String>> profile = Map.of("config#profile", List.of("cat"));
			fixture.old.askingOptionally(QUESTION, profile, "CAT added a profile.");
			fixture.installed.askingOptionally(QUESTION, profile, "CAT added a profile.");
			fixture.oldAnswer = "n";

			Run update = fixture.update(UPDATE);

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.askedByOld, "askedByOld").size().isEqualTo(1);
			requireThat(fixture.askedByOld.getFirst(), "question").startsWith(QUESTION);
			requireThat(fixture.installedRuns, "installedRuns").size().isEqualTo(1);
			Run installed = fixture.installedRuns.getFirst();
			requireThat(installed.status, "installedStatus").withContext(installed.output, "output").
				withContext(installed.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.askedByInstalled, "askedByInstalled").isEmpty();
			requireThat(fixture.installed.calls(), "installedCalls").doesNotContain("installConsentedEntries");
		}
	}

	/**
	 * Returns a replacer whose isolated files never need a retry wait.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
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
		JsonNode result()
		{
			return JsonMapper.builder().build().readTree(output);
		}
	}

	/**
	 * An installation of {@code harness-a} whose active CAT version is {@link #ACTIVE}, with a policy that trusts the
	 * key that signs release 1.2, and the release source of release 1.2.
	 */
	private static final class Fixture
	{
		/**
		 * The active plugin version before the update.
		 */
		private static final String ACTIVE = "1.0+b0123456789ab";
		/**
		 * The location of the policy's release source.
		 */
		private static final URI RELEASE_BASE_URL = URI.create("https://mirror.example/cat/releases/download");
		/**
		 * The entry that only the harness integration of release 1.2 writes.
		 */
		private static final String NEW_ENTRY = "settings.json#allow";
		/**
		 * The sentence that tells the user about {@link #NEW_ENTRY}.
		 */
		private static final String NEW_NOTICE = "CAT 1.2 allows its installer to run.";
		private final Path home;
		private final Map<String, String> environment;
		private final PluginRelease source;
		/**
		 * The harness integration of release 1.0.
		 */
		private final RecordingIntegration old = new RecordingIntegration();
		/**
		 * The harness integration of release 1.2.
		 */
		private final RecordingIntegration installed = new RecordingIntegration().
			maintain(Map.of(NEW_ENTRY, List.of("installer install --harness harness-a"))).
			describing(NEW_ENTRY, NEW_NOTICE);
		/**
		 * The command line of each process that the installer of release 1.0 started.
		 */
		private final List<List<String>> started = new ArrayList<>();
		/**
		 * Whether the runtime's program that starts processes existed when release 1.0's installer started each
		 * process.
		 */
		private final List<Boolean> spawnHelperExisted = new ArrayList<>();
		/**
		 * The outcome of each run of release 1.2's installer that a process started.
		 */
		private final List<Run> installedRuns = new ArrayList<>();
		/**
		 * The command line of each process that the installer of release 1.2 started.
		 */
		private final List<List<String>> startedByInstalled = new ArrayList<>();
		/**
		 * The reason why no process starts, or null when processes start.
		 */
		private String startFailure;
		/**
		 * The answer that the user types on the terminal of release 1.0's installer, or null when that installer has
		 * no terminal.
		 */
		private String oldAnswer;
		/**
		 * The questions that release 1.0's installer asked on its terminal.
		 */
		private final List<String> askedByOld = new ArrayList<>();
		/**
		 * The questions that release 1.2's installer asked on the terminal, which stands for the controlling terminal
		 * that it shares with the installer that started it and on which the user would allow the change.
		 */
		private final List<String> askedByInstalled = new ArrayList<>();

		/**
		 * Creates the fixture.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the fixture cannot be created
		 * @throws TrustException if a test key or policy is invalid
		 */
		private Fixture(Path directory) throws IOException, TrustException
		{
			Path root = directory.toRealPath();
			this.home = Files.createDirectory(root.resolve("home"));
			Path user = Files.createDirectory(root.resolve("user"));
			Path program = RecordingIntegration.program(root.resolve("tools"));
			Map<String, String> environment = new HashMap<>(RecordingIntegration.windowsVariables());
			environment.put("HOME", user.toString());
			environment.put(Platform.current().homeVariable(), user.toString());
			environment.put("PATH", program.getParent().toString());
			this.environment = Map.copyOf(environment);
			HarnessCommand command = new HarnessCommand(List.of(program.toString()), Map.of("HOME", user.toString()));

			Path active = Files.createDirectories(versionFolder(ACTIVE).resolve("bin"));
			Files.writeString(active.resolve("VERSION"), ACTIVE + "\n");
			Path spawnHelper = Files.createDirectories(runtime(ACTIVE).resolve("lib")).resolve("jspawnhelper");
			Files.writeString(spawnHelper, "spawn helper");
			if (!spawnHelper.toFile().setExecutable(true))
				throw new IOException("Could not make " + spawnHelper + " executable");
			Files.writeString(RecordingIntegration.registeredVersion(home), ACTIVE + "\n");

			KeyPair key = keyPair();
			TrustedKey releaseKey = trusted("cat-release", key);
			Path policyFile = home.resolve("plugins/data/cat-cat/policy.json");
			Files.createDirectories(policyFile.getParent());
			TrustPolicy.of(List.of(releaseKey), RELEASE_BASE_URL, ReleaseVersion.parse("1.0"), 1,
				Map.of("harness-a", home), Map.of("harness-a", command)).write(policyFile, replacer());
			Path published = PluginArchive.create(root.resolve("published"), List.of(releaseKey));
			this.source = new PluginRelease(published, "harness-a", key, RELEASE_BASE_URL);
		}

		/**
		 * Returns the plugin version that the harness registered.
		 *
		 * @return the version
		 * @throws IOException if the registration cannot be read
		 */
		private String registeredVersion() throws IOException
		{
			return Files.readString(RecordingIntegration.registeredVersion(home)).strip();
		}

		/**
		 * Returns the Java runtime of the installer in a version folder.
		 *
		 * @param version the plugin version
		 * @return the runtime folder
		 */
		private Path runtime(String version)
		{
			return versionFolder(version).resolve("runtime");
		}

		/**
		 * Returns a version folder of the installation.
		 *
		 * @param version the plugin version
		 * @return the folder
		 */
		private Path versionFolder(String version)
		{
			return RecordingIntegration.layout("harness-a", home).versionFolder(PluginVersion.parse(version));
		}

		/**
		 * Returns the installer launcher of release 1.2's version folder.
		 *
		 * @return the launcher
		 */
		private Path installedLauncher()
		{
			return PluginInstall.installer(versionFolder(PluginArchive.VERSION), Platform.current().isWindows());
		}

		/**
		 * Returns the end of the command line that runs release 1.2's installer with the update's arguments.
		 *
		 * @return the launcher followed by the arguments
		 */
		private List<String> installedLine()
		{
			List<String> line = new ArrayList<>();
			line.add(installedLauncher().toString());
			line.addAll(UPDATE);
			return line;
		}

		/**
		 * Returns the part of a command line that starts with release 1.2's installer launcher, without the program
		 * that runs the launcher on Windows.
		 *
		 * @param command the command line
		 * @return the launcher and the arguments that follow it, or the whole command line when it does not name the
		 * 	launcher
		 */
		private List<String> fromLauncher(List<String> command)
		{
			int launcher = command.indexOf(installedLauncher().toString());
			if (launcher < 0)
				return command;
			return command.subList(launcher, command.size());
		}

		/**
		 * Returns the command that the user runs to run release 1.2's installer with the update's arguments.
		 *
		 * @return the command
		 */
		private String installedCommand()
		{
			return ConsentCommand.launcher(versionFolder(PluginArchive.VERSION), Platform.current()) + " " +
				String.join(" ", UPDATE);
		}

		/**
		 * Runs the installer of release 1.0 to update the installation to release 1.2.
		 *
		 * @param arguments the command line
		 * @return the outcome
		 */
		private Run update(List<String> arguments)
		{
			return runOld(ACTIVE, arguments);
		}

		/**
		 * Runs the installer of a version folder with the code of release 1.0, whose integration writes no entry.
		 *
		 * @param version   the plugin version of the folder
		 * @param arguments the command line
		 * @return the outcome
		 */
		private Run runOld(String version, List<String> arguments)
		{
			Terminal terminal = question ->
			{
				askedByOld.add(question);
				return Optional.ofNullable(oldAnswer);
			};
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
				environment, builder ->
				{
					started.add(List.copyOf(builder.command()));
					spawnHelperExisted.add(Files.exists(runtime(version).resolve("lib").resolve("jspawnhelper")));
					if (startFailure != null)
						throw new IOException(startFailure);
					return runInstalled(builder);
				}, runtime(version));
			return run(versionFolder(version), environment, old, terminal, installers,
				arguments.toArray(String[]::new));
		}

		/**
		 * Runs release 1.2's installer as the process that a process builder describes does.
		 *
		 * @param builder the process's command line, environment and output redirection
		 * @return the exited process, whose output is the installer's standard output
		 */
		private Process runInstalled(ProcessBuilder builder)
		{
			List<String> command = builder.command();
			int launcher = command.indexOf("install") - 1;
			Path image = Path.of(command.get(launcher)).getParent().getParent();
			String[] arguments = command.subList(launcher + 1, command.size()).toArray(String[]::new);
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
				builder.environment(), nested ->
				{
					startedByInstalled.add(List.copyOf(nested.command()));
					throw new IOException("Started " + nested.command());
				});
			Terminal terminal = question ->
			{
				askedByInstalled.add(question);
				return Optional.of("y");
			};
			Run run = run(image, builder.environment(), installed, terminal, installers, arguments);
			installedRuns.add(run);
			return new ExitedProgram(new CommandResult(run.status, run.output));
		}

		/**
		 * Runs one installer invocation whose harness program never starts.
		 *
		 * @param image       the plugin folder that the installer runs from
		 * @param environment the installer's environment
		 * @param integration the installer's harness integration
		 * @param terminal    the installer's terminal
		 * @param installers  runs the installer of another version folder
		 * @param arguments   the command line
		 * @return the outcome
		 */
		private Run run(Path image, Map<String, String> environment, RecordingIntegration integration,
			Terminal terminal, InstallerProcess installers, String... arguments)
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			int status;
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				Invocation invocation = new Invocation(image, Platform.current(), environment, null,
					List.of(new RegisteredProvider()), List.of(integration), (recorded, recordedHome) ->
					RecordingIntegration.cli(), Clock.systemUTC(), interval -> () -> {}, replacer(),
					terminal, command ->
				{
					throw new AssertionError("Started " + command);
				}, source::open, installers, stdout, stderr);
				status = invocation.run(arguments);
			}
			return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
		}
	}
}
