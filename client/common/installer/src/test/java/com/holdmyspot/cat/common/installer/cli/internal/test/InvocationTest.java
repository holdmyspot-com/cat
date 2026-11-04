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
import com.holdmyspot.cat.common.installer.cli.internal.Locations;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.engine.test.LauncherSwap;
import com.holdmyspot.cat.common.installer.engine.test.LiveProvider;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.engine.test.Releases;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
	 * When the recorded harness program no longer exists, an install or update, which must register the plugin, is
	 * refused with the entry command that records it again, before anything changes.
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
			for (String operation : List.of("install", "update"))
			{
				Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, operation, "--harness",
					"harness-a");

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
	 * the installer exits, prints nothing itself, and exits with the hand-over status.
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
	 * When the stable launcher did not delete the data directory after an uninstall that removed every part of CAT,
	 * including the policy, running the same uninstall again hands the deletion to the launcher once more instead of
	 * refusing for the missing policy.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void completedUninstallIsHandedOverAgain() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-again-")));
			installation.writePolicy(installation.trustPolicy());
			RecordingIntegration integration = new RecordingIntegration();
			Run first = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");
			requireThat(first.status, "firstStatus").isEqualTo(79);
			// The installer stopped after the policy step, before it saved the result
			Path saved = installation.data.resolve("state/uninstall-result.json");
			Files.delete(saved);
			int calls = integration.calls().size();

			Run again = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(again.status, "status").withContext(again.output, "output").isEqualTo(79);
			requireThat(again.output, "output").isEmpty();
			JsonNode result = JsonMapper.builder().build().readTree(Files.readString(saved, UTF_8));
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(result.path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls().size(), "calls").isEqualTo(calls);
		}
	}

	/**
	 * An uninstall whose step fails prints a result of its own, which tells the caller to run the same uninstall again
	 * to continue, and never the stable launcher's result for a data directory that it could not delete.
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
			installation.update();
			Map<String, String> before = Releases.snapshot(installation.root);

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(), "rollback",
				"--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(2);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("usage");
			requireThat(Releases.snapshot(installation.root), "after").isEqualTo(before);
		}
	}

	/**
	 * Status reports whether the installation keeps a cached bundle of the release that the last update replaced, which
	 * going back to it reuses, and the runtime replacement that the stable launcher has not finished.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void statusReportsBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-bundle-")));
			installation.update();

			JsonNode updated = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(),
				"status", "--harness", "harness-a").result();
			installation.requestUpdate("1.1");
			JsonNode requested = installation.run(Map.of(), List.of(new LiveProvider()), new RecordingIntegration(),
				"status", "--harness", "harness-a").result();

			requireThat(updated.path("version").stringValue(), "version").isEqualTo("1.0");
			requireThat(updated.path("cached_bundle").booleanValue(), "cachedBundle").isTrue();
			requireThat(updated.has("previous_bundle"), "previousBundle").isFalse();
			requireThat(updated.path("previous_version").stringValue(), "previousVersion").isEqualTo("0.9");
			requireThat(updated.has("pending_swap"), "pendingBeforeRequest").isFalse();
			JsonNode pending = requested.path("pending_swap");
			requireThat(pending.path("operation").stringValue(), "operation").isEqualTo("update");
			requireThat(pending.path("from_version").stringValue(), "from").isEqualTo("1.0");
			requireThat(pending.path("to_version").stringValue(), "to").isEqualTo("1.1");
		}
	}

	/**
	 * A complete uninstall leaves neither a previous bundle nor a runtime replacement that the stable launcher has not
	 * finished in its way: it removes CAT and hands the data directory that holds them to the stable launcher, which
	 * deletes it.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void uninstallHandsOverPendingSwap() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-uninstall-swap-")));
			installation.update();
			installation.requestUpdate("1.1");
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new LiveProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(79);
			JsonNode result = JsonMapper.builder().build().readTree(Files.readString(
				installation.data.resolve("state/uninstall-result.json"), UTF_8));
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(integration.calls(), "calls").contains("unregister");
			requireThat(Files.exists(installation.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
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

			requireThat(run.status, "status").isEqualTo(79);
			JsonNode result = JsonMapper.builder().build().readTree(Files.readString(
				installation.data.resolve("state/uninstall-result.json"), UTF_8));
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

			requireThat(run.status, "status").isEqualTo(79);
			JsonNode result = JsonMapper.builder().build().readTree(Files.readString(
				installation.data.resolve("state/uninstall-result.json"), UTF_8));
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(result.path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls(), "calls").doesNotContain("unregister");
		}
	}

	/**
	 * Setup from an extracted archive with a local plugin tree reports the installer version, the plugin version, whether
	 * the plugin got its startup cache, the stable launcher and the permission entries that it installed.
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
				adding(Map.of("settings.json", List.of("entry")));
			Path tools = RecordingIntegration.program(root.resolve("tools")).getParent();
			Map<String, String> environment = Map.of("HOME", user.toString(), "PATH", tools.toString(),
				"XDG_DATA_HOME", root.resolve("data").toString(), "XDG_CONFIG_HOME", root.resolve("config").toString());

			Run run = run(archive.resolve(SetupArchive.VERSION), project, environment, List.of(new LiveProvider()),
				integration, "setup", "--harness", "harness-a", "--home", home.toString(), "--plugin", tree.toString());

			requireThat(run.status, "status").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("set_up");
			requireThat(result.path("version").stringValue(), "version").isEqualTo("1.2");
			requireThat(result.path("plugin_version").stringValue(), "pluginVersion").isEqualTo("1.2");
			// The fixture plugin tree does not describe how to train its startup cache
			requireThat(result.path("aot_cache").isBoolean(), "aotCacheReported").isTrue();
			requireThat(result.path("aot_cache").booleanValue(), "aotCache").isFalse();
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
			requireThat(run.result().path("message").stringValue(), "message").contains("current installer version");
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
	 * Runs one installer invocation whose harness program never starts.
	 *
	 * @param image        the running installer version directory
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
		 * Writes the policy and replaces the installation with one that an update from 0.9 left after the stable
		 * launcher finished it, so that it keeps the 0.9 bundle that the update replaced.
		 *
		 * @throws IOException    if the installation cannot be written
		 * @throws TrustException if a test policy is invalid
		 */
		private void update() throws IOException, TrustException
		{
			writePolicy(trustPolicy());
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "0.9");
			Releases.installation(data, "0.9");
			Releases release = new Releases("1.0", "");
			UpdateResult result = new UpdateEngine(data, release.policy(home), release, new LiveProvider(),
				"linux-x64", Clock.systemUTC(), interval -> () -> {}, Duration.ZERO).
				update("v1.0", new RecordingIntegration().session());
			requireThat(result.result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.writeString(anchor, policyFile + "\n");
		}

		/**
		 * Leaves the request with which an update to a version hands the runtime replacement to a stable launcher that
		 * stopped before it finished it.
		 *
		 * @param version the version that the update selects
		 * @throws IOException if the request cannot be written
		 */
		private void requestUpdate(String version) throws IOException
		{
			Files.writeString(data.resolve("state/runtime-swap"), "update\n1.0\n" + version + "\n" + Long.MAX_VALUE +
				"\n-\n-\n", UTF_8);
		}

		/**
		 * Runs one installer invocation from the installation's version directory.
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
