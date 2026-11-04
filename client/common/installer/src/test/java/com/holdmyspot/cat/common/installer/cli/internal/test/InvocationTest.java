package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.policy;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.engine.test.LiveProvider;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.engine.test.RegisteredProvider;
import com.holdmyspot.cat.common.installer.engine.test.Releases;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
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
	 * Forged HOME, XDG and harness home variables cannot replace the policy of the version folder that the installer
	 * runs from.
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
			Path forgedPolicyFile = fakeHome.resolve("plugins/data/cat-cat/policy.json");
			Files.createDirectories(forgedPolicyFile.getParent());
			TrustPolicy defaults = policy(List.of(trusted("forged-key", keyPair())));
			TrustPolicy.of(defaults.keys(), defaults.releaseBaseUrl(), defaults.minimum(), defaults.maximumMajor(),
				Map.of("harness-a", fakeHome), Map.of("harness-a", installation.command)).
				write(forgedPolicyFile, replacer());
			Map<String, String> environment = Map.of("HOME", fakeHome.toString(), "XDG_DATA_HOME",
				fakeHome.resolve("data").toString(), "XDG_CONFIG_HOME", fakeHome.resolve("config").toString(),
				"LOCALAPPDATA", fakeHome.toString(), "HARNESS_A_HOME", fakeHome.toString());

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
	 * Status without a harness argument reports this installation's one harness in the same result as status that
	 * names it.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void statusWithoutHarnessMatchesNamedHarness() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-status-flat-")));
			installation.writePolicy(installation.trustPolicy());

			Run named = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "status",
				"--harness", "harness-a");
			Run unnamed = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(),
				"status");

			requireThat(unnamed.status, "status").isEqualTo(0);
			requireThat(unnamed.result(), "unnamed").isEqualTo(named.result());
		}
	}

	/**
	 * A version folder whose plugin data folder holds no policy refuses status with one JSON result that names the one
	 * entry command which installs the harness again.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void missingPolicyIsReported() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-no-policy-")));

			Run run = installation.run(Map.of(), List.of(new RecordedHomeProvider(installation.home)),
				new RecordingIntegration(), "status", "--harness", "harness-a");

			requireThat(run.status, "status").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				contains("cat.sh --harness harness-a").contains("cat.ps1 -Harness harness-a");
			requireThat(run.output.lines().count(), "outputLines").isEqualTo(1L);
		}
	}

	/**
	 * Returns installer locations that are not a version folder of the harness.
	 *
	 * @return each location's name
	 */
	@DataProvider
	public static Object[][] outsideVersionFolders()
	{
		return new Object[][]{{"download"}, {"marketplace"}, {"unversioned"}};
	}

	/**
	 * An installer that does not run from a version folder of the harness, such as an extracted download, the
	 * harness's marketplace folder or a folder in the version folders whose name is not a plugin version, refuses
	 * every routine operation, reading no policy and writing nothing.
	 *
	 * @param location where the installer runs
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "outsideVersionFolders")
	public void refusesImageOutsideVersionFolders(String location) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-outside-")));
			installation.writePolicy(installation.trustPolicy());
			Path image = switch (location)
			{
				case "download" -> Files.createDirectories(installation.root.resolve("download/cat-harness-a"));
				case "marketplace" -> Files.createDirectories(installation.home.resolve(
					"plugins/cat-marketplace/plugins/cat"));
				case "unversioned" -> Files.createDirectories(installation.home.resolve("plugins/cache/cat/cat/1.2"));
				default -> throw new AssertionError(location);
			};
			Map<String, String> before = Releases.snapshot(installation.root);

			for (String[] arguments : List.of(new String[]{"status", "--harness", "harness-a"},
				new String[]{"uninstall"}))
			{
				Run run = run(image, installation.root, Map.of(), List.of(new LiveProvider()), new RecordingIntegration(),
					arguments);

				requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
				requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
				requireThat(run.result().path("message").stringValue(), "message").contains(image.toString());
				requireThat(run.output.lines().count(), "outputLines").isEqualTo(1L);
			}
			requireThat(Releases.snapshot(installation.root), "after").isEqualTo(before);
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
	 * When the recorded harness program no longer exists, install from the version folder, which must ask the harness
	 * to install the plugin, is refused with
	 * the entry command that records it again, before anything changes.
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

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "install", "--harness",
				"harness-a");

			requireThat(run.status, "status").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				contains("cat.sh --harness harness-a").contains(installation.program.toString());
			requireThat(integration.calls(), "calls").isEmpty();
			requireThat(Files.exists(installation.data.resolve("state"), NOFOLLOW_LINKS), "state").isFalse();
			requireThat(Files.isRegularFile(installation.policyFile), "policy").isTrue();
		}
	}

	/**
	 * A completed uninstall prints its one-line result itself.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void uninstallPrintsResult() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-")));
			installation.writePolicy(installation.trustPolicy());
			Files.createDirectories(installation.home.resolve("live"));
			Files.writeString(installation.home.resolve("live/version"), "1.2");
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.output.lines().count(), "lines").isEqualTo(1L);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(run.result().path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls(), "calls").contains("unregister");
			requireThat(Files.exists(installation.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(installation.policyFile, NOFOLLOW_LINKS), "policy").isFalse();
		}
	}

	/**
	 * Running the same uninstall again after an uninstall that removed every part of CAT, including the policy,
	 * reports the completed uninstall again instead of refusing for the missing policy, and calls the harness no
	 * more.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void completedUninstallIsReportedAgain() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-again-")));
			installation.writePolicy(installation.trustPolicy());
			RecordingIntegration integration = new RecordingIntegration();
			Run first = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");
			requireThat(first.status, "firstStatus").withContext(first.output, "output").isEqualTo(0);
			int calls = integration.calls().size();

			Run again = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(again.status, "status").withContext(again.output, "output").isEqualTo(0);
			requireThat(again.result().path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(again.result().path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls().size(), "calls").isEqualTo(calls);
		}
	}

	/**
	 * An uninstall whose step fails prints a result of its own, which tells the caller to run the same uninstall again
	 * to continue.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void stoppedUninstallIsIncomplete() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-stopped-")));
			installation.writePolicy(installation.trustPolicy());
			RecordingIntegration integration = new RecordingIntegration().fail("unregister");

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").isEqualTo(9);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("uninstall_incomplete");
			requireThat(run.result().path("message").stringValue(), "message").contains("unregister").
				contains("run the same uninstall command again");
		}
	}

	/**
	 * An unexpected failure during an operation that may have changed installed state is reported as failed, never as
	 * a refusal, names the operation and how to continue, and lists only recovery files that exist.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void unexpectedFailureAfterChangesIsFailed() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-crashed-")));
			installation.writePolicy(installation.trustPolicy());
			RecordingIntegration integration = new RecordingIntegration().crash("unregister");

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").isEqualTo(10);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("failed");
			requireThat(run.result().path("message").stringValue(), "message").contains("uninstall").
				contains("run the same uninstall command again");
			for (JsonNode path : run.result().path("paths"))
				requireThat(Files.exists(Path.of(path.stringValue())), path.stringValue()).isTrue();
		}
	}

	/**
	 * The installer has no rollback operation: the command is refused as a usage error and changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void rollbackIsUnknownOperation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-rollback-")));
			installation.writePolicy(installation.trustPolicy());
			Map<String, String> before = Releases.snapshot(installation.root);

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "rollback",
				"--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(2);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("usage");
			requireThat(Releases.snapshot(installation.root), "after").isEqualTo(before);
		}
	}

	/**
	 * Status reports the installed version without fields of a runtime replacement or a cached bundle, which no
	 * installation has.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void statusHasNoSwapFields() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-status-fields-")));
			installation.writePolicy(installation.trustPolicy());
			Files.createDirectories(installation.home.resolve("live"));
			Files.writeString(installation.home.resolve("live/version"), "1.2");

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "status",
				"--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("version").stringValue(), "version").isEqualTo("1.2");
			requireThat(result.has("pending_swap"), "pendingSwap").isFalse();
			requireThat(result.has("cached_bundle"), "cachedBundle").isFalse();
			requireThat(result.has("previous_version"), "previousVersion").isFalse();
		}
	}

	/**
	 * The installer has no update operation, which install performs: the command is refused as a usage error and
	 * changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateIsUnknownOperation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-update-")));
			installation.writePolicy(installation.trustPolicy());
			Map<String, String> before = Releases.snapshot(installation.root);

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "update",
				"--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(2);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("usage");
			requireThat(Releases.snapshot(installation.root), "after").isEqualTo(before);
		}
	}

	/**
	 * Install from the version folder refuses a harness home other than the one that the installation's policy
	 * records, before it changes anything.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void versionFolderInstallRefusesOtherHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-other-home-")));
			installation.writePolicy(installation.trustPolicy());
			Path other = Files.createDirectory(installation.root.resolve("other-home"));
			RecordingIntegration integration = new RecordingIntegration();
			Map<String, String> before = Releases.snapshot(installation.root);

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "install", "--harness",
				"harness-a", "--home", other.toString());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("message").stringValue(), "message").contains(other.toString()).
				contains(installation.home.toString());
			requireThat(integration.calls(), "calls").isEmpty();
			requireThat(Releases.snapshot(installation.root), "after").isEqualTo(before);
		}
	}

	/**
	 * When the recorded harness program no longer exists, uninstall still removes CAT, skipping only the unregistration
	 * that needs the program, and its saved result says that the plugin was not unregistered.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void uninstallsWithoutHarnessProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-no-program-")));
			installation.writePolicy(installation.trustPolicy());
			Files.createDirectories(installation.home.resolve("live"));
			Files.writeString(installation.home.resolve("live/version"), "1.2");
			Files.delete(installation.program);
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(result.path("unregistered").isBoolean(), "isBoolean").isTrue();
			requireThat(result.path("unregistered").booleanValue(), "unregistered").isFalse();
			requireThat(integration.calls(), "calls").doesNotContain("unregister");
			requireThat(Files.exists(installation.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(installation.policyFile, NOFOLLOW_LINKS), "policy").isFalse();
		}
	}

	/**
	 * An uninstall that stopped after it unregistered the plugin continues without the harness program, which it no
	 * longer needs, and its saved result says that the plugin was unregistered.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void resumesUninstallWithoutProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-resume-")));
			installation.writePolicy(installation.trustPolicy());
			Files.createDirectories(installation.data.resolve("state"));
			Files.writeString(installation.data.resolve("state/uninstall.json"),
				"{\"format\": 1, \"completed\": [\"unregister\"], \"retained\": []}\n");
			Files.delete(installation.program);
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(result.path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls(), "calls").doesNotContain("unregister");
		}
	}

	/**
	 * Install from an extracted plugin archive reports the plugin version, whether the plugin got its startup cache, the
	 * installer launcher in the version folder and the permission entries that it installed.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void installReportsInstallation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("invocation-install-")).toRealPath();
			Path archive = PluginArchive.create(root.resolve("download"), List.of(trusted("release-key", keyPair())));
			Path home = Files.createDirectory(root.resolve("home"));
			Path user = Files.createDirectory(root.resolve("user"));
			Path project = Files.createDirectory(root.resolve("project"));
			RecordingIntegration integration = new RecordingIntegration().
				adding(Map.of("settings.json", List.of("entry")));
			Path tools = RecordingIntegration.program(root.resolve("tools")).getParent();
			Map<String, String> environment = Map.of("HOME", user.toString(), "PATH", tools.toString());

			Run run = run(archive, project, environment, List.of(new LiveProvider()), integration, "install",
				"--harness", "harness-a", "--home", home.toString());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			JsonNode result = run.result();
			Path versionFolder = home.resolve("plugins/cache/cat/cat").resolve(PluginArchive.VERSION);
			requireThat(result.path("result").stringValue(), "result").isEqualTo("installed");
			requireThat(result.path("harness").stringValue(), "harness").isEqualTo("harness-a");
			requireThat(result.path("version").stringValue(), "version").isEqualTo("1.2");
			requireThat(result.path("plugin_version").stringValue(), "pluginVersion").isEqualTo(PluginArchive.VERSION);
			// The fixture plugin does not describe how to train its startup cache
			requireThat(result.path("aot_cache").isBoolean(), "aotCacheReported").isTrue();
			requireThat(result.path("aot_cache").booleanValue(), "aotCache").isFalse();
			requireThat(result.path("launcher").stringValue(), "launcher").
				isEqualTo(versionFolder.resolve("bin/installer").toString());
			requireThat(result.path("permissions").path("settings.json").get(0).stringValue(), "permission").
				isEqualTo("entry");
		}
	}

	/**
	 * Install from an extracted plugin archive into a harness that has another CAT version updates it: the harness
	 * installs the archive's version, and the installation's policy stays as it was.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void archiveInstallUpdatesInstalledVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-archive-update-")));
			installation.writePolicy(installation.trustPolicy());
			String active = installation.image.getFileName().toString();
			Files.createDirectories(installation.image.resolve("bin"));
			Files.writeString(installation.image.resolve("bin/VERSION"), active + "\n");
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), active + "\n");
			byte[] policy = Files.readAllBytes(installation.policyFile);
			Path archive = PluginArchive.create(installation.root.resolve("download"),
				List.of(trusted("release-key", keyPair())));
			RecordingIntegration integration = new RecordingIntegration();

			Run run = run(archive, installation.root, Map.of(), List.of(new RegisteredProvider()), integration,
				"install", "--harness", "harness-a", "--home", installation.home.toString());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("updated");
			requireThat(run.result().path("version").stringValue(), "version").isEqualTo(PluginArchive.VERSION);
			requireThat(integration.registered().stream().map(Object::toString).toList(), "registered").
				isEqualTo(List.of(PluginArchive.VERSION));
			requireThat(Files.readAllBytes(installation.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * Runs one installer invocation whose harness program never starts.
	 *
	 * @param image        the plugin folder of the running installer
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
			// The user's home is the one that the environment names, as the installer's process has it
			Path userHome = Path.of(environment.getOrDefault("HOME", cwd.toString()));
			Invocation invocation = new Invocation(image, cwd, userHome, Platform.LINUX_X64, environment, activations,
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
	 * An installation of {@code harness-a}: its harness home, the version folder of its plugin, its plugin data folder
	 * with the policy, and the harness program.
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
			this.home = Files.createDirectory(root.resolve("home"));
			this.image = Files.createDirectories(home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab"));
			this.data = home.resolve("plugins/data/cat-cat");
			this.policyFile = data.resolve("policy.json");
			Path user = Files.createDirectory(root.resolve("user"));
			this.program = Files.createDirectories(root.resolve("tools")).resolve("harness-a");
			Files.writeString(program, "#!/bin/sh\n");
			program.toFile().setExecutable(true);
			this.command = new HarnessCommand(List.of(program.toString()), Map.of("HOME", user.toString()));
		}

		/**
		 * Returns the policy that install would have written.
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
		 * Runs one installer invocation from the installation's version folder.
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
