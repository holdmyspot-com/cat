package com.holdmyspot.cat.common.updater.cli.internal.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.policy;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.cli.internal.Invocation;
import com.holdmyspot.cat.common.updater.cli.internal.Locations;
import com.holdmyspot.cat.common.updater.engine.test.LiveProvider;
import com.holdmyspot.cat.common.updater.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests CLI dispatch and machine-readable results with isolated installation authority.
 */
public final class InvocationTest
{
	/**
	 * Creates the test.
	 */
	public InvocationTest()
	{
	}

	/**
	 * Forged HOME and XDG roots cannot replace the protected policy selected by the running image.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if a test policy is invalid
	 */
	@Test
	public void forgedEnvironmentCannotSelectPolicy() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-policy-")));
			installation.writePolicy(installation.trustPolicy());
			Path fakeHome = Files.createDirectory(installation.root.resolve("forged-home"));
			Path forgedPolicyFile = fakeHome.resolve("config/cat-updater/harness-a/policy.json");
			Files.createDirectories(forgedPolicyFile.getParent());
			TrustPolicy defaults = policy(List.of(trusted("forged-key", keyPair())));
			TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(), defaults.maximumMajor(),
				Map.of("harness-a", fakeHome), Map.of("harness-a", installation.command)).
				write(forgedPolicyFile, replacer());
			Map<String, String> environment = Map.of("HOME", fakeHome.toString(), "XDG_DATA_HOME",
				fakeHome.resolve("data").toString(), "XDG_CONFIG_HOME", fakeHome.resolve("config").toString(),
				"LOCALAPPDATA", fakeHome.toString());

			Run run = installation.run(environment, List.of(new RecordedHomeProvider(installation.home)),
				new RecordingIntegration(), "status", "--harness", "harness-a");

			requireThat(run.status, "status").isEqualTo(0);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("status");
			requireThat(run.result().path("version").stringValue(), "version").isEqualTo("1.2");
			requireThat(run.result().path("installed").booleanValue(), "installed").isTrue();
			requireThat(run.result().path("key_ids").get(0).stringValue(), "keyId").isEqualTo("protected-key");
			requireThat(run.diagnostics, "diagnostics").isEmpty();
			requireThat(Files.exists(installation.data.resolve("state"), NOFOLLOW_LINKS), "stateExists").isFalse();
		}
	}

	/**
	 * Status reports a harness whose plugin was removed as not installed.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void statusReportsMissingPlugin() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-absent-")));
			installation.writePolicy(installation.trustPolicy());

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "status",
				"--harness", "harness-a");

			requireThat(run.status, "status").isEqualTo(0);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("status");
			requireThat(run.result().path("installed").booleanValue(), "installed").isFalse();
			requireThat(run.result().path("version").stringValue(), "version").isEqualTo("");
		}
	}

	/**
	 * An installation without its setup record or its policy refuses status with one JSON result that names the one
	 * entry command which sets the harness up again.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void missingSetupIsReported() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-no-setup-"));
			Path withoutAnchor = Files.createDirectories(root.resolve("first/harness-a/1.0"));
			Files.writeString(withoutAnchor.resolveSibling("current"), "1.0\n");
			Path withoutPolicy = Files.createDirectories(root.resolve("second/harness-a/1.0"));
			Files.writeString(withoutPolicy.resolveSibling("current"), "1.0\n");
			Path anchor = withoutPolicy.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, root.resolve("missing-policy.json") + "\n");
			for (Path image : List.of(withoutAnchor, withoutPolicy))
			{
				Run run = run(image, root, Map.of(), List.of(new RecordedHomeProvider(root)), new RecordingIntegration(),
					"status", "--harness", "harness-a");

				requireThat(run.status, "status").isEqualTo(3);
				requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
				requireThat(run.result().path("message").stringValue(), "message").
					contains("cat.sh --harness harness-a").contains("cat.ps1 -Harness harness-a").
					doesNotContain("--updater-only");
				requireThat(run.output.lines().count(), "outputLines").isEqualTo(1L);
			}
		}
	}

	/**
	 * A policy written by an earlier release, without the recorded harness program, is refused with the entry
	 * command that sets the harness up again.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesEarlierPolicyFormat() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-format-")));
			TrustPolicy current = installation.trustPolicy();
			installation.writePolicy(TrustPolicy.of(current.keys(), current.releaseBaseUrl(), current.minimum(),
				current.maximumMajor(), current.harnesses()));

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "status");

			requireThat(run.status, "status").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").contains("cat.sh --harness harness-a");
		}
	}

	/**
	 * Returns ways in which an operation can name a harness that the installation does not manage.
	 *
	 * @return each way's name
	 */
	@DataProvider
	public static Object[][] otherHarness()
	{
		return new Object[][]{{"policy"}, {"argument"}};
	}

	/**
	 * An installation manages only the harness whose data directory it occupies: a policy that records another harness,
	 * or an operation that names another harness, is refused.
	 *
	 * @param source where the other harness is named
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "otherHarness")
	public void refusesOtherHarness(String source) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-other-")));
			TrustPolicy current = installation.trustPolicy();
			String[] arguments = {"status", "--harness", "harness-a"};
			if (source.equals("policy"))
			{
				installation.writePolicy(TrustPolicy.of(current.keys(), current.releaseBaseUrl(), current.minimum(),
					current.maximumMajor(), Map.of("harness-b", installation.home),
					Map.of("harness-b", installation.command)));
			}
			else
			{
				installation.writePolicy(current);
				arguments = new String[]{"status", "--harness", "harness-b"};
			}

			Run run = installation.run(Map.of(), List.of(new LiveProvider(), new RecordedHomeProvider(installation.home,
				"harness-b")), new RecordingIntegration(), arguments);

			requireThat(run.status, "status").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").contains("harness-b");
		}
	}

	/**
	 * When the recorded harness program no longer exists, an operation that would run it is refused with the entry
	 * command that records it again, before anything changes.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesMissingHarnessProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-program-")));
			installation.writePolicy(installation.trustPolicy());
			Files.delete(installation.program);
			RecordingIntegration integration = new RecordingIntegration();
			for (String operation : List.of("install", "update", "uninstall"))
			{
				String[] arguments = {operation, "--harness", "harness-a"};
				if (operation.equals("uninstall"))
					arguments = new String[]{operation};

				Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, arguments);

				requireThat(run.status, "status").isEqualTo(3);
				requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
				requireThat(run.result().path("message").stringValue(), "message").
					contains("cat.sh --harness harness-a").contains(installation.program.toString());
			}
			requireThat(integration.calls(), "calls").isEmpty();
			requireThat(Files.exists(installation.data.resolve("state"), NOFOLLOW_LINKS), "state").isFalse();
			requireThat(Files.isRegularFile(installation.policyFile), "policy").isTrue();
		}
	}

	/**
	 * A completed uninstall saves its one-line result for the stable launcher, which deletes the data directory after
	 * the updater exits, prints nothing itself, and exits with the hand-over status.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void uninstallHandsResultToLauncher() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-")));
			installation.writePolicy(installation.trustPolicy());
			Files.createDirectories(installation.home.resolve("live"));
			Files.writeString(installation.home.resolve("live/version"), "1.2");
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").isEqualTo(79);
			requireThat(run.output, "output").isEmpty();
			String saved = Files.readString(installation.data.resolve("state/uninstall-result.json"), UTF_8);
			requireThat(saved.endsWith("\n"), "newline").isTrue();
			requireThat(saved.lines().count(), "lines").isEqualTo(1L);
			JsonNode result = JsonMapper.builder().build().readTree(saved);
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(integration.calls(), "calls").contains("unregister");
			requireThat(Files.exists(installation.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(installation.policyFile, NOFOLLOW_LINKS), "policy").isFalse();
		}
	}

	/**
	 * Setup from an extracted archive with a local plugin tree reports the updater version, the plugin version, the
	 * stable launcher and the permission entries that it installed.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void setupReportsInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-setup-")).toRealPath();
			Path archive = SetupArchive.create(root.resolve("archive"), List.of());
			Path tree = SetupArchive.pluginTree(root.resolve("plugin-tree"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path user = Files.createDirectory(root.resolve("user"));
			Path project = Files.createDirectory(root.resolve("project"));
			RecordingIntegration integration = new RecordingIntegration().
				discovering(new HarnessCommand(List.of(root.resolve("tools/harness-a").toString()), Map.of())).
				adding(Map.of("settings.json", List.of("entry")));
			Map<String, String> environment = Map.of("HOME", user.toString(), "XDG_DATA_HOME",
				root.resolve("data").toString(), "XDG_CONFIG_HOME", root.resolve("config").toString());

			Run run = run(archive.resolve(SetupArchive.VERSION), project, environment, List.of(new LiveProvider()),
				integration, "setup", "--harness", "harness-a", "--home", home.toString(), "--plugin", tree.toString());

			requireThat(run.status, "status").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("set_up");
			requireThat(result.path("version").stringValue(), "version").isEqualTo("1.2");
			requireThat(result.path("plugin_version").stringValue(), "pluginVersion").isEqualTo("1.2");
			requireThat(result.path("launcher").stringValue(), "launcher").
				isEqualTo(root.resolve("data/cat-updater/harness-a/bin/cat-updater").toString());
			requireThat(result.path("permissions").path("settings.json").get(0).stringValue(), "permission").
				isEqualTo("entry");
		}
	}

	/**
	 * A second blank selector line is refused before a routine command attempts to read its policy.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void malformedCurrentPreventsDispatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-current-"));
			Path image = Files.createDirectories(root.resolve("harness-a/1.0"));
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, root.resolve("missing-policy.json") + "\n");
			Files.writeString(image.resolveSibling("current"), "1.0\n\n");

			Run run = run(image, root, Map.of(), List.of(new RecordedHomeProvider(root)), new RecordingIntegration(),
				"status", "--harness", "harness-a");

			requireThat(run.status, "status").isEqualTo(3);
			requireThat(run.result().path("message").stringValue(), "message").contains("current updater version");
			requireThat(run.output.lines().count(), "outputLines").isEqualTo(1L);
		}
	}

	/**
	 * Failure while an activation journal exists must report unknown restoration as inconsistent.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void existingJournalPreventsConsistentFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-journal-"));
			Path data = Files.createDirectories(root.resolve("harness-a"));
			Path image = Files.createDirectory(data.resolve("image"));
			Path journal = data.resolve("state/harness-a/activation.json");
			Files.createDirectories(journal.getParent());
			Files.writeString(journal, "{}\n");

			Run run = run(image, root, Map.of(), List.of(new RecordedHomeProvider(root)), new RecordingIntegration(),
				"status", "--harness", "harness-a");

			requireThat(run.status, "status").isEqualTo(6);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("not_restored");
			requireThat(run.result().path("consistent").booleanValue(), "consistent").isFalse();
			requireThat(run.output.lines().count(), "outputLines").isEqualTo(1L);
		}
	}

	/**
	 * Runs one updater invocation whose harness program never starts.
	 *
	 * @param image        the running updater version directory
	 * @param cwd          the caller's working directory
	 * @param environment  the caller's environment
	 * @param activations  the installed activation providers
	 * @param integration  the harness integration
	 * @param arguments    the command line
	 * @return the outcome
	 */
	private static Run run(Path image, Path cwd, Map<String, String> environment,
		List<PluginActivation> activations, HarnessIntegration integration, String... arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		int status;
		try (PrintStream stdout = new PrintStream(output, false, UTF_8);
			PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
		{
			Invocation invocation = new Invocation(image, cwd, Platform.LINUX_X64, environment, activations,
				List.of(integration), (command, home) -> RecordingIntegration.cli(), Clock.systemUTC(),
				interval -> () -> {}, replacer(), stdout, stderr);
			status = invocation.run(arguments);
		}
		return new Run(status, output.toString(UTF_8), diagnostics.toString(UTF_8));
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
	 * A set-up installation of {@code harness-a}: its data directory, policy, harness home and harness program.
	 */
	private static final class Installation
	{
		private final Path root;
		private final Path data;
		private final Path image;
		private final Path policyFile;
		private final Path home;
		private final Path program;
		private final HarnessCommand command;

		/**
		 * Creates the installation without its policy.
		 *
		 * @param directory the test directory
		 * @throws IOException if the installation cannot be created
		 */
		private Installation(Path directory) throws IOException
		{
			this.root = directory.toRealPath();
			this.data = Files.createDirectories(root.resolve("base/harness-a"));
			this.image = Files.createDirectories(data.resolve("1.0"));
			Files.writeString(data.resolve("current"), "1.0\n");
			this.policyFile = root.resolve("config/harness-a/policy.json");
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, policyFile + "\n");
			this.home = Files.createDirectory(root.resolve("home"));
			Path user = Files.createDirectory(root.resolve("user"));
			this.program = Files.createDirectories(root.resolve("tools")).resolve("harness-a");
			Files.writeString(program, "#!/bin/sh\n");
			program.toFile().setExecutable(true);
			this.command = new HarnessCommand(List.of(program.toString()), Map.of("HOME", user.toString()));
		}

		/**
		 * Returns the policy that setup would have written.
		 *
		 * @return the policy
		 * @throws TrustException if the policy is invalid
		 */
		private TrustPolicy trustPolicy() throws TrustException
		{
			TrustPolicy defaults = policy(List.of(trusted("protected-key", keyPair())));
			return TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(),
				defaults.maximumMajor(), Map.of("harness-a", home), Map.of("harness-a", command));
		}

		/**
		 * Writes the installation's policy.
		 *
		 * @param policy the policy
		 * @throws IOException if the policy cannot be written
		 */
		private void writePolicy(TrustPolicy policy) throws IOException
		{
			Files.createDirectories(policyFile.getParent());
			policy.write(policyFile, replacer());
		}

		/**
		 * Runs one updater invocation from the installation's version directory.
		 *
		 * @param environment the caller's environment
		 * @param activations the installed activation providers
		 * @param integration the harness integration
		 * @param arguments   the command line
		 * @return the outcome
		 */
		private Run run(Map<String, String> environment, List<PluginActivation> activations,
			HarnessIntegration integration, String... arguments)
		{
			return InvocationTest.run(image, root, environment, activations, integration, arguments);
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
	 * Represents an installed provider whose status is known only at its policy-recorded home.
	 */
	private static final class RecordedHomeProvider implements PluginActivation
	{
		private final Path home;
		private final String harness;

		/**
		 * Creates the recorded provider of {@code harness-a}.
		 *
		 * @param home the only directory containing the represented installation
		 */
		private RecordedHomeProvider(Path home)
		{
			this(home, "harness-a");
		}

		/**
		 * Creates the recorded provider.
		 *
		 * @param home    the only directory containing the represented installation
		 * @param harness the provider's harness
		 */
		private RecordedHomeProvider(Path home, String harness)
		{
			this.home = home;
			this.harness = harness;
		}

		@Override
		public String harness()
		{
			return harness;
		}

		@Override
		public String pluginManifest()
		{
			return "." + harness + "/plugin.json";
		}

		@Override
		public InstalledState installed(Path home)
		{
			requireThat(home, "home").isEqualTo(this.home);
			return new InstalledState(Optional.of("1.2"), true);
		}

		@Override
		public List<ActivationStep> plan(Path home, String newVersion)
		{
			throw new UnsupportedOperationException("The status-only provider does not activate releases");
		}

		@Override
		public Path migrationVersionFile(Path home)
		{
			return home.resolve("migration-version");
		}

		@Override
		public List<ActivationStep> installPlan(Path home, String newVersion)
		{
			throw new UnsupportedOperationException("The status-only provider does not activate releases");
		}

		@Override
		public List<Path> removalPaths(Path home)
		{
			return List.of();
		}

		@Override
		public Path dataDirectory(Path home)
		{
			return home.resolve("plugin-data");
		}
	}
}
