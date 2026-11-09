package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.engine.test.RegisteredProvider;
import com.holdmyspot.cat.common.installer.secrets.InstallerProcessSecrets;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests install of another harness's plugin by the installer of an installed version folder: where CAT is not
 * installed in that harness, the installer downloads that harness's plugin of its own release, verifies it like every
 * release that it installs, and installs it as a first install from that plugin's archive does.
 */
public final class OtherHarnessInstallTest
{
	/**
	 * Creates the test.
	 */
	public OtherHarnessInstallTest()
	{
	}

	/**
	 * Returns the release arguments with which install selects the running installer's own release.
	 *
	 * @return each install's arguments after the harness
	 */
	@DataProvider
	public static Object[][] ownRelease()
	{
		return new Object[][]{{List.of()}, {List.of(PluginRelease.TAG)}};
	}

	/**
	 * Install of another harness without a tag, or with the tag of the running installer's release, installs that
	 * harness's plugin of the running installer's release: the harness installs the downloaded plugin into its version
	 * folder, whose version is the running installer's, and the other harness's installation gets its own policy.
	 *
	 * @param release the release arguments
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test(dataProvider = "ownRelease")
	public void installsOtherHarnessFromSameRelease(List<String> release) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("other-harness-install-")));

			List<String> arguments = new ArrayList<>(List.of("install", "--harness", "harness-b"));
			arguments.addAll(release);
			Run run = fixture.run(arguments);

			requireThat(run.status, "status").withContext(run.output, "output").
				withContext(run.diagnostics, "diagnostics").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("installed");
			requireThat(result.path("harness").stringValue(), "harness").isEqualTo("harness-b");
			requireThat(result.path("plugin_version").stringValue(), "pluginVersion").isEqualTo(Fixture.RUNNING);
			requireThat(fixture.otherIntegration.registered(), "registered").
				isEqualTo(List.of(PluginVersion.parse(Fixture.RUNNING)));
			Path versionFolder = fixture.otherVersionFolder(Fixture.RUNNING);
			requireThat(Files.readString(versionFolder.resolve(".harness-b/plugin.json")), "manifest").
				contains(Fixture.RUNNING);
			requireThat(Files.readString(versionFolder.resolve("bin/VERSION")).strip(), "version").
				isEqualTo(Fixture.RUNNING);
			TrustPolicy policy = TrustPolicy.read(fixture.otherHome.resolve("plugins/data/cat-cat/policy.json"));
			requireThat(policy.harnesses(), "harnesses").isEqualTo(Map.of("harness-b", fixture.otherHome));
		}
	}

	/**
	 * Install of another harness with the tag of a release other than the running installer's is refused, naming the
	 * running installer's release, before anything is downloaded or installed.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void refusesOtherRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("other-harness-tag-")));
			// A refusal comes before any request, so a request would turn the refusal into a download failure
			fixture.source.goOffline();

			Run run = fixture.run(List.of("install", "--harness", "harness-b", "v1.3"));

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").contains("1.3").contains("1.2");
			fixture.requireNothingInstalled();
		}
	}

	/**
	 * Install of another harness that already has CAT is refused, naming the command of the installer in that
	 * installation's version folder that updates it to the running installer's release.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void refusesInstalledHarness() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("other-harness-installed-")));
			// A refusal comes before any request, so a request would turn the refusal into a download failure
			fixture.source.goOffline();
			String installed = "1.1+b0123456789ab";
			Files.createDirectories(fixture.otherVersionFolder(installed).resolve("bin"));
			Files.writeString(RecordingIntegration.registeredVersion(fixture.otherHome), installed + "\n");

			Run run = fixture.run(List.of("install", "--harness", "harness-b"));

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			// Windows runs the launcher's PowerShell script
			String installer = fixture.otherVersionFolder(installed).resolve("bin/installer").toString();
			if (Platform.current().isWindows())
			{
				installer = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" +
					fixture.otherVersionFolder(installed).resolve("bin/installer.ps1") + "\"";
			}
			requireThat(run.result().path("message").stringValue(), "message").
				contains(installer + " install --harness harness-b " + PluginRelease.TAG);
			requireThat(fixture.otherIntegration.calls(), "calls").isEmpty();
		}
	}

	/**
	 * Install of another harness by an installer whose policy trusts no release key, as a development build's does,
	 * is refused, saying that it cannot verify a download and naming the entry command that installs that harness's
	 * plugin instead.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void refusesWithoutReleaseKeys() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("other-harness-keys-")));
			// A refusal comes before any request, so a request would turn the refusal into a download failure
			fixture.source.goOffline();
			fixture.writePolicy(List.of());

			Run run = fixture.run(List.of("install", "--harness", "harness-b"));

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").contains("verify").
				contains(EntryCommands.forHarness("harness-b"));
			fixture.requireNothingInstalled();
		}
	}

	/**
	 * Install takes the user's home from the environment's home variable, the home that the runtime uses: the other
	 * harness's home lies under it, and the capacity locks that the installation may write are its locks.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void usesEnvironmentHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("other-harness-home-")));

			Run run = fixture.run(List.of("install", "--harness", "harness-b"));

			requireThat(run.status, "status").withContext(run.output, "output").
				withContext(run.diagnostics, "diagnostics").isEqualTo(0);
			requireThat(fixture.otherIntegration.callsWithSubjects(), "calls").contains("installSandboxEntries " +
				fixture.user.resolve("harness-a-home") + " " + fixture.user.resolve(".cat/locks"));
			requireThat(Files.isRegularFile(fixture.otherVersionFolder(Fixture.RUNNING).resolve("bin/VERSION")),
				"installed").isTrue();
		}
	}

	/**
	 * Returns the environments whose home variable gives no usable user home.
	 *
	 * @return each condition's name
	 */
	@DataProvider
	public static Object[][] unusableHomes()
	{
		return new Object[][]{{"unset"}, {"blank"}, {"relative"}};
	}

	/**
	 * Install refuses, installing nothing, when the environment does not set the user's home to an absolute path,
	 * rather than using another home than the runtime does; the refusal names the home variable.
	 *
	 * @param condition what is wrong with the home variable
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a test policy is invalid
	 */
	@Test(dataProvider = "unusableHomes")
	public void refusesUnusableHome(String condition) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("other-harness-no-home-")));
			// A refusal comes before any request, so a request would turn the refusal into a download failure
			fixture.source.goOffline();
			String variable = Platform.current().homeVariable();
			Map<String, String> environment = new HashMap<>(fixture.environment);
			environment.remove("HOME");
			switch (condition)
			{
				case "unset" -> environment.remove(variable);
				case "blank" -> environment.put(variable, " ");
				case "relative" -> environment.put(variable, "relative-home");
				default -> throw new AssertionError(condition);
			}

			Run run = fixture.run(environment, List.of("install", "--harness", "harness-b"));

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").contains(variable);
			fixture.requireNothingInstalled();
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
	 * An installation of {@code harness-a}, of release 1.2, whose installer runs from its version folder; the home of
	 * {@code harness-b}, which has no CAT installation, at the place that the user's environment selects; and the
	 * release source of release 1.2, which holds the plugin of {@code harness-b}.
	 */
	private static final class Fixture
	{
		/**
		 * The plugin version of the running installer.
		 */
		private static final String RUNNING = PluginArchive.VERSION;
		private final Path root;
		private final Path image;
		private final Path policyFile;
		private final Path home;
		private final Path otherHome;
		private final Path user;
		private final HarnessCommand command;
		private final Map<String, String> environment;
		private final RecordingIntegration otherIntegration = new RecordingIntegration("harness-b");
		private final PluginRelease source;

		/**
		 * Creates the fixture.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the fixture cannot be created
		 * @throws TrustException if a test policy is invalid
		 */
		private Fixture(Path directory) throws IOException, TrustException
		{
			this.root = directory.toRealPath();
			this.home = Files.createDirectory(root.resolve("home"));
			this.image = Files.createDirectories(home.resolve("plugins/cache/cat/cat").resolve(RUNNING));
			this.policyFile = home.resolve("plugins/data/cat-cat/policy.json");
			this.user = Files.createDirectory(root.resolve("user"));
			this.otherHome = Files.createDirectory(user.resolve("harness-b-home"));
			Path program = RecordingIntegration.program(root.resolve("tools"));
			Map<String, String> environment = new HashMap<>(RecordingIntegration.windowsVariables());
			environment.put("HOME", user.toString());
			environment.put(Platform.current().homeVariable(), user.toString());
			environment.put("PATH", program.getParent().toString());
			this.environment = Map.copyOf(environment);
			this.command = new HarnessCommand(List.of(program.toString()), Map.of("HOME", user.toString()));
			KeyPair key = keyPair();
			List<TrustedKey> keys = List.of(trusted("release-key", key));
			Path plugin = PluginArchive.create(root.resolve("published"), keys, "harness-b");
			this.source = new PluginRelease(plugin, "harness-b", key, ReleaseFixtures.RELEASE_BASE_URL);
			writePolicy(keys);
		}

		/**
		 * Writes the policy of the {@code harness-a} installation.
		 *
		 * @param trusted the release keys that the policy trusts
		 * @throws IOException    if the policy cannot be written
		 * @throws TrustException if the policy is invalid
		 */
		private void writePolicy(List<TrustedKey> trusted) throws IOException, TrustException
		{
			Files.createDirectories(policyFile.getParent());
			TrustPolicy.of(trusted, ReleaseFixtures.RELEASE_BASE_URL,
				ReleaseVersion.parse("1.0"), 1, Map.of("harness-a", home), Map.of("harness-a", command)).
				write(policyFile, replacer());
		}

		/**
		 * Returns a version folder of {@code harness-b}.
		 *
		 * @param version the plugin version
		 * @return the folder
		 */
		private Path otherVersionFolder(String version)
		{
			return RecordingIntegration.layout("harness-b", otherHome).versionFolder(PluginVersion.parse(version));
		}

		/**
		 * Requires that {@code harness-b} got no CAT installation.
		 *
		 * @throws IOException if the harness home cannot be listed
		 */
		private void requireNothingInstalled() throws IOException
		{
			requireThat(otherIntegration.calls(), "calls").isEmpty();
			requireThat(Files.exists(otherHome.resolve("plugins"), NOFOLLOW_LINKS), "plugins").isFalse();
		}

		/**
		 * Runs one installer invocation from the {@code harness-a} version folder, whose terminal answers nothing and
		 * whose harness program never starts.
		 *
		 * @param arguments the command line
		 * @return the outcome
		 */
		private Run run(List<String> arguments)
		{
			return run(environment, arguments);
		}

		/**
		 * Runs one installer invocation from the {@code harness-a} version folder in an environment, whose terminal
		 * answers nothing and whose harness program never starts.
		 *
		 * @param environment the caller's environment
		 * @param arguments   the command line
		 * @return the outcome
		 */
		private Run run(Map<String, String> environment, List<String> arguments)
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			int status;
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				Invocation invocation = new Invocation(image, Platform.current(), environment, null,
					List.of(new RegisteredProvider(), new RegisteredProvider("harness-b")),
					List.of(new RecordingIntegration(), otherIntegration), (recorded, recordedHome) ->
					RecordingIntegration.cli(), Clock.systemUTC(), interval -> () -> {}, replacer(),
					_ -> Optional.empty(), command ->
				{
					throw new AssertionError("Started " + command);
				}, source::open, InstallerProcessSecrets.getInstallerProcessAccess().create(Platform.current(),
					environment, builder ->
				{
					throw new AssertionError("Started " + builder.command());
				}), stdout, stderr);
				status = invocation.run(arguments.toArray(String[]::new));
			}
			return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
		}
	}
}
