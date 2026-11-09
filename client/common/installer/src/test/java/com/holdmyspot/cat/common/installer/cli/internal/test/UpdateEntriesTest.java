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
	 * The command line of the update.
	 */
	private static final List<String> UPDATE = List.of("install", "--harness", "harness-a", PluginRelease.TAG);

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

			Run update = fixture.update();

			requireThat(update.status, "status").withContext(update.output, "output").
				withContext(update.diagnostics, "diagnostics").isEqualTo(0);
			JsonNode result = update.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("updated");
			requireThat(result.path("version").stringValue(), "version").isEqualTo(PluginArchive.VERSION);
			requireThat(result.path("previous_version").stringValue(), "previousVersion").isEqualTo(Fixture.ACTIVE);
			requireThat(fixture.started, "started").size().isEqualTo(1);
			List<String> command = fixture.started.getFirst();
			requireThat(command.subList(command.size() - UPDATE.size() - 1, command.size()), "command").
				isEqualTo(fixture.installedLine());
			requireThat(Path.of(command.get(command.size() - UPDATE.size() - 1)), "launcher").isAbsolute();
			requireThat(fixture.installed.callsWithSubjects(), "installedCalls").
				contains("installPermissions " + fixture.installedLauncher());
			requireThat(result.path("message").stringValue(), "message").contains(Fixture.NEW_NOTICE);
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

			Run update = fixture.update();

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
			Run update = fixture.update();
			requireThat(update.status, "updateStatus").withContext(update.output, "output").isEqualTo(0);
			fixture.started.clear();

			Run current = fixture.runOld(PluginArchive.VERSION);

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

			Run update = fixture.update();

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

			Run update = fixture.update();

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
		 * @return the outcome
		 */
		private Run update()
		{
			return runOld(ACTIVE);
		}

		/**
		 * Runs the installer of a version folder with the code of release 1.0, whose integration writes no entry, with
		 * the update's command line.
		 *
		 * @param version the plugin version of the folder
		 * @return the outcome
		 */
		private Run runOld(String version)
		{
			RecordingIntegration integration = new RecordingIntegration();
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
				environment, builder ->
				{
					started.add(List.copyOf(builder.command()));
					if (startFailure != null)
						throw new IOException(startFailure);
					return runInstalled(builder);
				});
			return run(versionFolder(version), environment, integration, installers, UPDATE.toArray(String[]::new));
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
			int launcher = command.size() - UPDATE.size() - 1;
			Path image = Path.of(command.get(launcher)).getParent().getParent();
			String[] arguments = command.subList(launcher + 1, command.size()).toArray(String[]::new);
			InstallerProcess installers = InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
				builder.environment(), nested ->
				{
					startedByInstalled.add(List.copyOf(nested.command()));
					throw new IOException("Started " + nested.command());
				});
			Run run = run(image, builder.environment(), installed, installers, arguments);
			installedRuns.add(run);
			return new ExitedProgram(new CommandResult(run.status, run.output));
		}

		/**
		 * Runs one installer invocation whose terminal answers nothing and whose harness program never starts.
		 *
		 * @param image       the plugin folder that the installer runs from
		 * @param environment the installer's environment
		 * @param integration the installer's harness integration
		 * @param installers  runs the installer of another version folder
		 * @param arguments   the command line
		 * @return the outcome
		 */
		private Run run(Path image, Map<String, String> environment, RecordingIntegration integration,
			InstallerProcess installers, String... arguments)
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
					_ -> Optional.empty(), command ->
				{
					throw new AssertionError("Started " + command);
				}, source::open, installers, stdout, stderr);
				status = invocation.run(arguments);
			}
			return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
		}
	}
}
