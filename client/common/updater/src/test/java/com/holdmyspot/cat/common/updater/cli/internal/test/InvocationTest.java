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
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.cli.internal.Invocation;
import com.holdmyspot.cat.common.updater.cli.internal.Locations;
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
	 */
	@Test
	public void forgedEnvironmentCannotSelectPolicy() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-policy-"));
			Path image = Files.createDirectories(root.resolve("trusted-data/1.0"));
			Files.writeString(image.getParent().resolve("current"), "1.0\n");
			Path home = Files.createDirectory(root.resolve("recorded-harness-home"));
			Path fakeHome = Files.createDirectory(root.resolve("forged-home"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path policyFile = root.resolve("trusted-policy.json");
			FileReplacer replacer = replacer();
			TrustPolicy defaults = policy(List.of(trusted("protected-key", keyPair())));
			TrustPolicy trusted = TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(),
				defaults.maximumMajor(), Map.of("harness-a", home));
			trusted.write(policyFile, replacer);
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, policyFile + "\n");
			Path forgedPolicyFile = fakeHome.resolve("config/cat-updater/policy.json");
			Files.createDirectories(forgedPolicyFile.getParent());
			TrustPolicy forged = TrustPolicy.of(List.of(trusted("forged-key", keyPair())),
				defaults.releaseBaseUrl(),
				defaults.minimum(), defaults.maximumMajor(), Map.of("harness-a", fakeHome));
			forged.write(forgedPolicyFile, replacer);
			Map<String, String> environment = Map.of("HOME", fakeHome.toString(), "XDG_DATA_HOME",
				fakeHome.resolve("data").toString(), "XDG_CONFIG_HOME", fakeHome.resolve("config").toString(),
				"LOCALAPPDATA", fakeHome.toString());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
			{
				Invocation invocation = new Invocation(image, project, Platform.current(), environment,
					List.of(new RecordedHomeProvider(home)), List.of(), Clock.systemUTC(), interval -> () -> {},
					replacer, stdout, stderr);

				int status = invocation.run(new String[]{"status", "--harness", "harness-a"});

				requireThat(status, "status").isEqualTo(0);
				JsonNode result = JsonMapper.builder().build().readTree(output.toByteArray());
				requireThat(result.path("result").stringValue(), "result").isEqualTo("status");
				requireThat(result.path("version").stringValue(), "version").isEqualTo("1.2");
				requireThat(result.path("key_ids").get(0).stringValue(), "keyId").isEqualTo("protected-key");
				requireThat(diagnostics.size(), "diagnosticsSize").isEqualTo(0);
				requireThat(Files.exists(root.resolve("trusted-data/state"), NOFOLLOW_LINKS), "stateExists").isFalse();
			}
		}
	}

	/**
	 * An image without setup refuses status with one JSON result and the explicit setup action.
	 */
	@Test
	public void missingSetupIsReported() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-no-setup-"));
			Path image = Files.createDirectory(root.resolve("image"));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(new ByteArrayOutputStream(), false, UTF_8))
			{
				Invocation invocation = new Invocation(image, root, Platform.current(), Map.of(),
					List.of(new RecordedHomeProvider(root)), List.of(), Clock.systemUTC(), interval -> () -> {},
					replacer(), stdout, stderr);
				int status = invocation.run(new String[]{"status", "--harness", "harness-a"});
				requireThat(status, "status").isEqualTo(3);
				JsonNode result = JsonMapper.builder().build().readTree(output.toByteArray());
				requireThat(result.path("result").stringValue(), "result").isEqualTo("refused");
				requireThat(result.path("message").stringValue(), "message").contains("--updater-only");
				requireThat(output.toString(UTF_8).lines().count(), "outputLines").isEqualTo(1L);
			}
		}
	}

	/**
	 * A second blank selector line is refused before a routine command attempts to read its policy.
	 */
	@Test
	public void malformedCurrentPreventsDispatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-current-"));
			Path image = Files.createDirectory(root.resolve("1.0"));
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, root.resolve("missing-policy.json") + "\n");
			Files.writeString(root.resolve("current"), "1.0\n\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(new ByteArrayOutputStream(), false, UTF_8))
			{
				Invocation invocation = new Invocation(image, root, Platform.current(), Map.of(),
					List.of(new RecordedHomeProvider(root)), List.of(), Clock.systemUTC(), interval -> () -> {},
					replacer(), stdout, stderr);

				int status = invocation.run(new String[]{"status", "--harness", "harness-a"});

				requireThat(status, "status").isEqualTo(3);
				JsonNode result = JsonMapper.builder().build().readTree(output.toByteArray());
				requireThat(result.path("message").stringValue(), "message").contains("current updater version");
				requireThat(output.toString(UTF_8).lines().count(), "outputLines").isEqualTo(1L);
			}
		}
	}

	/**
	 * Failure while an activation journal exists must report unknown restoration as inconsistent.
	 */
	@Test
	public void existingJournalPreventsConsistentFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-journal-"));
			Path image = Files.createDirectory(root.resolve("image"));
			Path journal = root.resolve("state/harness-a/activation.json");
			Files.createDirectories(journal.getParent());
			Files.writeString(journal, "{}\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream stdout = new PrintStream(output, false, UTF_8);
				PrintStream stderr = new PrintStream(new ByteArrayOutputStream(), false, UTF_8))
			{
				Invocation invocation = new Invocation(image, root, Platform.current(), Map.of(),
					List.of(new RecordedHomeProvider(root)), List.of(), Clock.systemUTC(), interval -> () -> {},
					replacer(), stdout, stderr);

				int status = invocation.run(new String[]{"status", "--harness", "harness-a"});

				requireThat(status, "status").isEqualTo(6);
				JsonNode result = JsonMapper.builder().build().readTree(output.toByteArray());
				requireThat(result.path("result").stringValue(), "result").isEqualTo("not_restored");
				requireThat(result.path("consistent").booleanValue(), "consistent").isFalse();
				requireThat(output.toString(UTF_8).lines().count(), "outputLines").isEqualTo(1L);
			}
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

		/**
		 * Creates the recorded provider.
		 *
		 * @param home the only directory containing the represented installation
		 */
		private RecordedHomeProvider(Path home)
		{
			this.home = home;
		}

		@Override
		public String harness()
		{
			return "harness-a";
		}

		@Override
		public String pluginManifest()
		{
			return ".harness-a/plugin.json";
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
	}
}
