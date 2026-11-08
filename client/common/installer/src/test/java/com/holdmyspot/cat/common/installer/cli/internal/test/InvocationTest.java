package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.policy;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.cli.internal.HarnessCliFactory;
import com.holdmyspot.cat.common.installer.cli.internal.Invocation;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessStarter;
import com.holdmyspot.cat.common.installer.cli.internal.Terminal;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.PermissionRecord;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	 * The home directory that the user database records for the account that runs the installer, which is not the
	 * home that the tests' environment names.
	 */
	private static final Path ACCOUNT_HOME = Path.of("/account");

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

			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			Run run = installation.run(environment, List.of(new RegisteredProvider()),
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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "status",
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

			Run named = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "status",
				"--harness", "harness-a");
			Run unnamed = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(),
				"status");

			requireThat(unnamed.status, "status").isEqualTo(0);
			requireThat(unnamed.result(), "unnamed").isEqualTo(named.result());
		}
	}

	/**
	 * A version folder whose plugin data folder holds no policy refuses status with one JSON result that names the one
	 * entry command which installs the harness again, in the form for the platform that the installer runs on.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void missingPolicyIsReported() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-no-policy-")));

			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()),
				new RecordingIntegration(), "status", "--harness", "harness-a");

			requireThat(run.status, "status").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				contains("cat.sh --harness harness-a").doesNotContain("cat.ps1");
			requireThat(run.output.lines().count(), "outputLines").isEqualTo(1L);
		}
	}

	/**
	 * The entry command that a refusal tells the user to run in a terminal sets {@code HOME} to the installer
	 * process's value when the account's home directory is another directory, so that the terminal installs into the
	 * harness home of the session that asked.
	 *
	 * @throws IOException if the fixture cannot be created or the output cannot be read
	 */
	@Test
	public void entryCommandSetsSessionHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-session-home-")));

			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()),
				new RecordingIntegration(), "status", "--harness", "harness-a");

			requireThat(run.result().path("message").stringValue(), "message").
				contains("HOME=" + ShellQuoting.posix(installation.root.toString()) + " cat.sh --harness harness-a");
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
				Run run = run(image, installation.root, Map.of(), List.of(new RegisteredProvider()),
					new RecordingIntegration(), arguments);

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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "status");

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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider(),
				new RegisteredProvider("harness-b")), new RecordingIntegration(), arguments);

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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "install", "--harness",
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
	 * When the recorded harness program no longer identifies itself as the harness's program, install from the version
	 * folder is refused before anything changes, telling the user how to start over.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesHarnessProgramFailingIdentity() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-identity-")));
			installation.writePolicy(installation.trustPolicy());
			byte[] policy = Files.readAllBytes(installation.policyFile);
			RecordingIntegration integration = new RecordingIntegration().fail("requireIdentity");

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "install", "--harness",
				"harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				contains(installation.policyFile.toString()).contains("cat.sh --harness harness-a");
			requireThat(Files.exists(installation.data.resolve("state"), NOFOLLOW_LINKS), "state").isFalse();
			requireThat(Files.readAllBytes(installation.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * When the recorded harness program cannot start in the environment that install recorded for it, exiting with
	 * status 127 as a shell does when the interpreter that the program names is missing, install from the version
	 * folder is refused before anything changes. The refusal reports the status and output of the program, does not
	 * call it another program, and tells the user how to start over.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesRecordedProgramThatCannotStart() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-unstarted-")));
			installation.writePolicy(installation.trustPolicy());
			byte[] policy = Files.readAllBytes(installation.policyFile);

			Run run = run(installation.image, installation.root, Map.of(), List.of(new RegisteredProvider()),
				new RecordingIntegration(), _ -> Optional.empty(), Platform.LINUX_X64, command ->
				{
					throw new AssertionError("Started " + command);
				}, (command, home) -> arguments -> new CommandResult(127, "node: nvm is not installed\n"), "install",
				"--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				startsWith("Product harness-a at " + installation.program + " could not start in this environment " +
					"(status 127). Its output: node: nvm is not installed. Install recorded this program").
				contains(installation.policyFile.toString()).contains("cat.sh --harness harness-a").
				doesNotContain("no longer");
			requireThat(Files.readAllBytes(installation.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * When the installer's PATH holds no program of the harness, a first install is refused before anything changes,
	 * because running it again in the same environment finds no program either. The refusal does not ask for a run of
	 * the CAT entry command.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void refusesFirstInstallWithoutHarnessProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-no-program-")));
			Path empty = Files.createDirectories(archive.root.resolve("empty"));
			Map<String, String> environment = new HashMap<>(archive.environment);
			environment.put("PATH", empty.toString());
			Map<String, String> before = Releases.snapshot(archive.root);
			RecordingIntegration integration = new RecordingIntegration();

			Run run = run(archive.plugin, archive.project, environment, List.of(new RegisteredProvider()), integration,
				"--home", archive.home.toString(), "install", "--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").isEqualTo("The program " +
				"\"harness-a\" was not found on PATH in the installer's environment (" + empty + "). Install " +
				"Product harness-a, or run the installer with the PATH your Product harness-a sessions use, then run " +
				"the same command again.");
			requireThat(integration.calls(), "calls").isEmpty();
			requireThat(Releases.snapshot(archive.root), "after").isEqualTo(before);
		}
	}

	/**
	 * When the recorded harness program no longer exists, the CAT entry command's install from an extracted plugin
	 * archive takes no other program from the search path: it is refused before anything changes, telling the user how
	 * to start over.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void archiveInstallKeepsMissingProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-archive-missing-")));
			installation.writePolicy(installation.trustPolicy());
			String active = installation.image.getFileName().toString();
			Files.createDirectories(installation.image.resolve("bin"));
			Files.writeString(installation.image.resolve("bin/VERSION"), active + "\n");
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), active + "\n");
			Files.delete(installation.program);
			byte[] policy = Files.readAllBytes(installation.policyFile);
			Path archive = PluginArchive.create(installation.root.resolve("download"),
				List.of(trusted("release-key", keyPair())));
			Path tools = RecordingIntegration.program(installation.root.resolve("other-tools")).getParent();
			Path user = installation.root.resolve("user");
			RecordingIntegration integration = new RecordingIntegration();

			Run run = run(archive, installation.root, installEnvironment(user, tools), List.of(new RegisteredProvider()),
				integration, "--home", installation.home.toString(), "install", "--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				contains(installation.policyFile.toString()).contains("cat.sh --harness harness-a");
			requireThat(integration.calls(), "calls").doesNotContain("register");
			requireThat(Files.readAllBytes(installation.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * A completed uninstall prints its one-line result itself and keeps CAT's plugin data with the policy.
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
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.output.lines().count(), "lines").isEqualTo(1L);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(run.result().path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls(), "calls").contains("unregister");
			requireThat(Files.exists(RecordingIntegration.registeredVersion(installation.home), NOFOLLOW_LINKS),
				"registered").isFalse();
			requireThat(installation.policyFile, "policy").isRegularFile();
		}
	}

	/**
	 * Running the same uninstall again after an uninstall that kept CAT's plugin data runs every step again and
	 * reports the uninstall again.
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
			Run first = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");
			requireThat(first.status, "firstStatus").withContext(first.output, "output").isEqualTo(0);
			int calls = integration.calls().size();
			// The first uninstall deleted the version folder that it ran from; the second runs from a copy in its place
			Files.createDirectories(installation.image);

			Run again = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

			requireThat(again.status, "status").withContext(again.output, "output").isEqualTo(0);
			requireThat(again.result().path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(again.result().path("unregistered").booleanValue(), "unregistered").isTrue();
			requireThat(integration.calls().subList(calls, integration.calls().size()), "calls").
				isEqualTo(List.of("unregister"));
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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "rollback",
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
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "status",
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
	 * Status lists the permission and sandbox entries that install recorded only when asked with {@code --verbose}: in
	 * the machine-readable result, and to a person as the harness configuration holds each entry, under the file and
	 * the setting that hold it.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void verboseStatusListsRecordedEntries() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-status-verbose-")));
			installation.writePolicy(installation.trustPolicy());
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			PermissionRecord.add(InstallerState.of(installation.data, "harness-a").permissions(),
				Map.of("settings.json#sandbox.excludedCommands", List.of("first entry", "second entry")));
			Terminal person = new Terminal()
			{
				@Override
				public Optional<String> ask(String question)
				{
					return Optional.empty();
				}

				@Override
				public boolean isStdoutTerminal()
				{
					return true;
				}
			};

			Run plain = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "status",
				"--harness", "harness-a");
			Run verbose = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "status",
				"--harness", "harness-a", "--verbose");
			Run shown = run(installation.image, installation.root, Map.of(), List.of(new RegisteredProvider()),
				new RecordingIntegration(), person, Platform.current(), command ->
				{
					throw new AssertionError("Started " + command);
				}, "status", "--verbose");

			requireThat(plain.status, "plain").withContext(plain.output, "output").isEqualTo(0);
			requireThat(plain.result().has("permissions"), "plainPermissions").isFalse();
			requireThat(verbose.status, "verbose").withContext(verbose.output, "output").isEqualTo(0);
			JsonNode permissions = verbose.result().path("permissions").path("settings.json#sandbox.excludedCommands");
			requireThat(permissions.size(), "permissions").withContext(verbose.output, "output").isEqualTo(2);
			requireThat(permissions.get(1).stringValue(), "second").isEqualTo("second entry");
			requireThat(shown.status, "shown").withContext(shown.output, "output").isEqualTo(0);
			String settings = "~" + installation.root.getFileSystem().getSeparator() +
				installation.root.relativize(installation.home.resolve("settings.json"));
			String lineSeparator = System.lineSeparator();
			requireThat(shown.output, "output").contains(String.join(lineSeparator,
				"CAT changed " + settings + ". Uninstalling CAT undoes these changes.",
				"",
				"  Sets   (sandbox.excludedCommands)",
				"    first entry",
				"    second entry",
				"",
				"  where ~ = " + installation.root)).
				doesNotContain("installer =").doesNotContain("--verbose").doesNotContain("{");
		}
	}

	/**
	 * Status with {@code --verbose}, whose report abbreviates the user's home folder, refuses a {@code HOME} that is not
	 * an absolute path as install does, while status without it reports the installation.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void verboseStatusRefusesRelativeHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-verbose-home-")));
			installation.writePolicy(installation.trustPolicy());
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			PermissionRecord.add(InstallerState.of(installation.data, "harness-a").permissions(),
				Map.of("settings.json#sandbox.excludedCommands", List.of("first entry")));
			Map<String, String> environment = Map.of("HOME", "relative-home");

			Run install = installation.run(environment, List.of(new RegisteredProvider()), new RecordingIntegration(),
				"install", "--harness", "harness-a");
			Run verbose = installation.run(environment, List.of(new RegisteredProvider()), new RecordingIntegration(),
				"status", "--harness", "harness-a", "--verbose");
			Run plain = installation.run(environment, List.of(new RegisteredProvider()), new RecordingIntegration(),
				"status", "--harness", "harness-a");

			String refusal = "The user's home folder is wrong: relative-home is not an absolute path. CAT takes it " +
				"from HOME in the environment, as the runtime does; set it to an absolute path and run the command again.";
			requireThat(install.status, "install").withContext(install.output, "output").isEqualTo(3);
			requireThat(install.result().path("message").stringValue(), "installMessage").isEqualTo(refusal);
			requireThat(verbose.status, "verbose").withContext(verbose.output, "output").isEqualTo(3);
			requireThat(verbose.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(verbose.result().path("message").stringValue(), "verboseMessage").isEqualTo(refusal);
			requireThat(plain.status, "plain").withContext(plain.output, "output").isEqualTo(0);
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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), new RecordingIntegration(), "update",
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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "--home",
				other.toString(), "install", "--harness", "harness-a");

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
			Files.writeString(RecordingIntegration.registeredVersion(installation.home), "1.2");
			Files.delete(installation.program);
			RecordingIntegration integration = new RecordingIntegration();

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			JsonNode result = run.result();
			requireThat(result.path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(result.path("unregistered").isBoolean(), "isBoolean").isTrue();
			requireThat(result.path("unregistered").booleanValue(), "unregistered").isFalse();
			requireThat(integration.calls(), "calls").doesNotContain("unregister");
			requireThat(Files.exists(RecordingIntegration.registeredVersion(installation.home), NOFOLLOW_LINKS),
				"registered").isFalse();
			requireThat(installation.policyFile, "policy").isRegularFile();
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

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

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
			Map<String, String> environment = installEnvironment(user, tools);

			Run run = run(archive, project, environment, List.of(new RegisteredProvider()), integration, Platform.current(),
				command ->
				{
					throw new AssertionError("Started " + command);
				}, "--home", home.toString(), "install", "--harness", "harness-a");

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
			// Windows runs the launcher's PowerShell script
			Path launcher = versionFolder.resolve("bin/installer");
			if (Platform.current().isWindows())
				launcher = launcher.resolveSibling("installer.ps1");
			requireThat(result.path("launcher").stringValue(), "launcher").isEqualTo(launcher.toString());
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
				"--home", installation.home.toString(), "install", "--harness", "harness-a");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("updated");
			requireThat(run.result().path("version").stringValue(), "version").isEqualTo(PluginArchive.VERSION);
			requireThat(integration.registered().stream().map(Object::toString).toList(), "registered").
				isEqualTo(List.of(PluginArchive.VERSION));
			requireThat(Files.readAllBytes(installation.policyFile), "policy").isEqualTo(policy);
		}
	}

	/**
	 * Install appends what the harness integration says about the entries that it added to the installed message.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void installTellsAboutAddedEntries() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-notice-")));
			RecordingIntegration integration = new RecordingIntegration().
				adding(Map.of("config#profile", List.of("locks"))).
				describing("config#profile", "CAT added the lock folder to a shared profile.");

			Run run = archive.run(integration, _ -> Optional.empty());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.result().path("message").stringValue(), "message").
				isEqualTo("CAT is installed for harness-a. CAT added the lock folder to a shared profile.");
		}
	}

	/**
	 * Install from an extracted plugin archive into a harness that has no CAT version deletes the version folders that
	 * an earlier installation left behind, as every later install does.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void archiveInstallDeletesOldVersionFolders() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-old-folders-")));
			Path leftBehind = Files.createDirectories(archive.home.resolve("plugins/cache/cat/cat").
				resolve(Releases.pluginVersion("0.9")));
			Files.writeString(leftBehind.resolve("file"), "left behind");

			Run run = archive.run(new RecordingIntegration(), _ -> Optional.empty());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(Files.exists(leftBehind, NOFOLLOW_LINKS), "leftBehind").isFalse();
			requireThat(archive.home.resolve("plugins/cache/cat/cat").resolve(PluginArchive.VERSION), "active").
				isDirectory();
		}
	}

	/**
	 * Install from an extracted plugin archive discards the progress of an uninstall that stopped, so that a later
	 * uninstall runs every step again.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void archiveInstallDiscardsStoppedUninstall() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-stopped-uninstall-")));
			Path journal = archive.home.resolve("plugins/data/cat-cat/state/uninstall.json");
			Files.createDirectories(journal.getParent());
			Files.writeString(journal, "{\"format\": 1, \"completed\": [\"unregister\"], \"retained\": []}\n");

			Run run = archive.run(new RecordingIntegration(), _ -> Optional.empty());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(Files.exists(journal, NOFOLLOW_LINKS), "journal").isFalse();
		}
	}

	/**
	 * When the integration needs the user's permission and the user answers yes in the terminal, install makes the
	 * change and installs CAT. The answers that mean yes are those that {@code ConsentReplyTest} lists.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void installMakesChangeThatUserAllows() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-consent-")));
			RecordingIntegration integration = new RecordingIntegration().
				asking("May CAT change the shared setting?", Map.of("config#selection", List.of("built-in")));
			List<String> questions = new ArrayList<>();

			Run run = archive.run(integration, question ->
			{
				questions.add(question);
				return Optional.of("y");
			});

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("installed");
			requireThat(questions.size(), "questions").isEqualTo(1);
			requireThat(questions.getFirst(), "question").startsWith("May CAT change the shared setting?");
			requireThat(integration.calls(), "calls").contains("installConsentedEntries");
			requireThat(run.result().path("permissions").path("config#selection").get(0).stringValue(),
				"recorded").isEqualTo("built-in");
		}
	}

	/**
	 * When the user does not answer yes in the terminal, install refuses with the question and changes nothing. The
	 * answers that do not mean yes are those that {@code ConsentReplyTest} lists.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void installChangesNothingWhenUserDeclines() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-decline-")));
			RecordingIntegration integration = new RecordingIntegration().
				asking("May CAT change the shared setting?", Map.of("config#selection", List.of("built-in")));
			Map<String, String> before = Releases.snapshot(archive.root);

			Run run = archive.run(integration, _ -> Optional.of("n"));

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("refused");
			requireThat(run.result().path("message").stringValue(), "message").
				startsWith("May CAT change the shared setting?").contains("You answered no, so CAT changed nothing.");
			requireThat(run.result().has("consent_command"), "consentCommand").isFalse();
			requireThat(integration.calls(), "calls").doesNotContain("installConsentedEntries").
				doesNotContain("register");
			requireThat(Releases.snapshot(archive.root), "after").isEqualTo(before);
		}
	}

	/**
	 * Without an interactive terminal, the extracted plugin archive's install refuses with the question and changes
	 * nothing, and says to run the entry command in a terminal, where install can ask.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void archiveInstallNeedsTerminal() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-no-terminal-")));
			RecordingIntegration integration = new RecordingIntegration().
				asking("May CAT change the shared setting?", Map.of("config#selection", List.of("built-in")));
			Map<String, String> before = Releases.snapshot(archive.root);

			Run run = archive.run(integration, _ -> Optional.empty());

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(3);
			requireThat(run.result().path("message").stringValue(), "message").
				startsWith("May CAT change the shared setting?").contains("CAT changed nothing.").
				contains("cat.sh --harness harness-a").contains("open a terminal");
			requireThat(run.result().has("consent_command"), "consentCommand").isFalse();
			requireThat(Releases.snapshot(archive.root), "after").isEqualTo(before);
		}
	}

	/**
	 * With {@code --create-profile} on the command line, install makes the change without asking.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the release's policy is invalid
	 */
	@Test
	public void consentFlagAllowsWithoutAsking() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Archive archive = new Archive(cleanup.add(testDirectory("invocation-consent-flag-")));
			RecordingIntegration integration = new RecordingIntegration().
				asking("May CAT change the shared setting?", Map.of("config#selection", List.of("built-in")));

			Run run = archive.run(integration, _ ->
			{
				throw new AssertionError("install asked although the command line allows the change");
			}, "--create-profile");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(integration.calls(), "calls").contains("installConsentedEntries");
		}
	}

	/**
	 * Uninstall appends what the harness integration says about the entries that it left in place.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void uninstallTellsAboutRetainedEntries() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-retained-")));
			installation.writePolicy(installation.trustPolicy());
			Files.createDirectories(installation.data.resolve("state/permissions"));
			Files.writeString(installation.data.resolve("state/permissions/harness-a.json"),
				"{\"config#selection\": [\"built-in\"]}\n");
			RecordingIntegration integration = new RecordingIntegration().
				retaining(List.of("CAT left the shared setting as you changed it."));

			Run run = installation.run(Map.of(), List.of(new RegisteredProvider()), integration, "uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.result().path("message").stringValue(), "message").
				isEqualTo("CAT was removed from this harness. CAT left the shared setting as you changed it.");
		}
	}

	/**
	 * On Linux and macOS, uninstall deletes the folder of plugin files that holds the running installer's version
	 * folder itself once it has printed its result, and starts no other program.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void uninstallDeletesRunningFolderAfterResult() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-self-removal-")));
			installation.writePolicy(installation.trustPolicy());
			List<List<String>> started = new ArrayList<>();

			Run run = run(installation.image, installation.root, Map.of(),
				List.of(new RegisteredProvider()), new RecordingIntegration(), Platform.LINUX_X64, started::add,
				"uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			requireThat(run.result().path("result").stringValue(), "result").isEqualTo("uninstalled");
			requireThat(Files.exists(installation.home.resolve("plugins/cache/cat"), NOFOLLOW_LINKS), "cache").
				isFalse();
			requireThat(started, "started").isEmpty();
		}
	}

	/**
	 * On Windows, which refuses to delete the files of a running program, uninstall leaves the folder of plugin files
	 * that holds the running installer's version folder and starts the system's Windows PowerShell, which starts
	 * another, hidden, through the Windows shell, so that it holds none of the handles through which the installer's
	 * caller reads the result. That one waits for the installer's process to exit and then deletes the folder,
	 * retrying for at most 60 seconds, without a script file.
	 *
	 * @throws IOException    if the fixture cannot be created or the output cannot be read
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void windowsUninstallLeavesFolderToWaiter() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Installation installation = new Installation(cleanup.add(testDirectory("invocation-windows-removal-")));
			installation.writePolicy(installation.trustPolicy());
			List<List<String>> started = new ArrayList<>();

			Run run = run(installation.image, installation.root, Map.of("SystemRoot", "C:\\Windows"),
				List.of(new RegisteredProvider()), new RecordingIntegration(), Platform.WINDOWS_X64, started::add,
				"uninstall");

			requireThat(run.status, "status").withContext(run.output, "output").isEqualTo(0);
			Path cache = installation.home.resolve("plugins/cache/cat");
			requireThat(cache, "cache").isDirectory();
			requireThat(started.size(), "started").isEqualTo(1);
			List<String> command = started.getFirst();
			requireThat(command.getFirst(), "program").
				isEqualTo("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
			requireThat(command, "command").contains("-EncodedCommand");
			String starter = new String(Base64.getDecoder().decode(command.getLast()), UTF_16LE);
			Matcher start = Pattern.compile("^Start-Process -WindowStyle Hidden -FilePath " +
				"'C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1\\.0\\\\powershell\\.exe' " +
				"-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','([A-Za-z0-9+/=]+)'\n$").
				matcher(starter);
			requireThat(start.matches(), "startsThroughShell").withContext(starter, "starter").isTrue();
			String script = new String(Base64.getDecoder().decode(start.group(1)), UTF_16LE);
			requireThat(script, "script").contains("Wait-Process -Id " + ProcessHandle.current().pid() + "\n").
				contains("'" + cache + "'").contains("AddSeconds(60)");
		}
	}

	/**
	 * Runs one installer invocation whose harness program never starts.
	 *
	 * @param image        the plugin folder of the running installer
	 * @param defaultHome  the user's home when the environment names none in the platform's home variable
	 * @param environment  the caller's environment
	 * @param activations  the installed activation providers
	 * @param integration  the harness integration
	 * @param arguments    the command line
	 * @return the outcome
	 */
	private static Run run(Path image, Path defaultHome, Map<String, String> environment,
		List<PluginActivation> activations, HarnessIntegration integration, String... arguments)
	{
		return run(image, defaultHome, environment, activations, integration, _ -> Optional.empty(), arguments);
	}

	/**
	 * Runs one installer invocation, with a terminal, whose harness program never starts.
	 *
	 * @param image        the plugin folder of the running installer
	 * @param defaultHome  the user's home when the environment names none in the platform's home variable
	 * @param environment  the caller's environment
	 * @param activations  the installed activation providers
	 * @param integration  the harness integration
	 * @param terminal     the user's terminal
	 * @param arguments    the command line
	 * @return the outcome
	 */
	private static Run run(Path image, Path defaultHome, Map<String, String> environment,
		List<PluginActivation> activations, HarnessIntegration integration, Terminal terminal, String... arguments)
	{
		return run(image, defaultHome, environment, activations, integration, terminal, Platform.LINUX_X64, command ->
		{
			throw new AssertionError("Started " + command);
		}, arguments);
	}

	/**
	 * Runs one installer invocation on a platform, whose harness program never starts.
	 *
	 * @param image        the plugin folder of the running installer
	 * @param defaultHome  the user's home when the environment names none in the platform's home variable
	 * @param environment  the caller's environment
	 * @param activations  the installed activation providers
	 * @param integration  the harness integration
	 * @param platform     the platform that the installer runs on
	 * @param starter      starts the programs that outlive the installer's process
	 * @param arguments    the command line
	 * @return the outcome
	 */
	private static Run run(Path image, Path defaultHome, Map<String, String> environment,
		List<PluginActivation> activations, HarnessIntegration integration, Platform platform, ProcessStarter starter,
		String... arguments)
	{
		return run(image, defaultHome, environment, activations, integration, _ -> Optional.empty(), platform, starter,
			arguments);
	}

	/**
	 * Runs one installer invocation on a platform, with a terminal, whose harness program never starts.
	 *
	 * @param image        the plugin folder of the running installer
	 * @param defaultHome  the user's home when the environment names none in the platform's home variable
	 * @param environment  the caller's environment
	 * @param activations  the installed activation providers
	 * @param integration  the harness integration
	 * @param terminal     the user's terminal
	 * @param platform     the platform that the installer runs on
	 * @param starter      starts the programs that outlive the installer's process
	 * @param arguments    the command line
	 * @return the outcome
	 */
	private static Run run(Path image, Path defaultHome, Map<String, String> environment,
		List<PluginActivation> activations, HarnessIntegration integration, Terminal terminal, Platform platform,
		ProcessStarter starter, String... arguments)
	{
		return run(image, defaultHome, environment, activations, integration, terminal, platform, starter,
			(command, home) -> RecordingIntegration.cli(), arguments);
	}

	/**
	 * Runs one installer invocation on a platform, with a terminal and a harness program.
	 *
	 * @param image        the plugin folder of the running installer
	 * @param defaultHome  the user's home when the environment names none in the platform's home variable
	 * @param environment  the caller's environment
	 * @param activations  the installed activation providers
	 * @param integration  the harness integration
	 * @param terminal     the user's terminal
	 * @param platform     the platform that the installer runs on
	 * @param starter      starts the programs that outlive the installer's process
	 * @param cliFactory   runs the harness program
	 * @param arguments    the command line
	 * @return the outcome
	 */
	private static Run run(Path image, Path defaultHome, Map<String, String> environment,
		List<PluginActivation> activations, HarnessIntegration integration, Terminal terminal, Platform platform,
		ProcessStarter starter, HarnessCliFactory cliFactory, String... arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		int status;
		try (PrintStream stdout = new PrintStream(output, false, UTF_8);
			PrintStream stderr = new PrintStream(diagnostics, false, UTF_8))
		{
			// The user's home is the one that the environment names, as the installer's process has it
			Map<String, String> variables = new HashMap<>(environment);
			variables.putIfAbsent(platform.homeVariable(), defaultHome.toString());
			Invocation invocation = new Invocation(image, platform, variables, ACCOUNT_HOME, activations,
				List.of(integration), cliFactory, Clock.systemUTC(), interval -> () -> {}, replacer(), terminal, starter,
				stdout, stderr);
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
	 * An extracted plugin archive of {@code harness-a} with a harness home into which nothing is installed yet.
	 */
	private static final class Archive
	{
		private final Path root;
		private final Path plugin;
		private final Path home;
		private final Path project;
		private final Map<String, String> environment;

		/**
		 * Creates the archive.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the archive cannot be created
		 * @throws TrustException if the release's policy is invalid
		 */
		private Archive(Path directory) throws IOException, TrustException
		{
			this.root = directory.toRealPath();
			this.plugin = PluginArchive.create(root.resolve("download"), List.of(trusted("release-key", keyPair())));
			this.home = Files.createDirectory(root.resolve("home"));
			Path user = Files.createDirectory(root.resolve("user"));
			this.project = Files.createDirectory(root.resolve("project"));
			Path tools = RecordingIntegration.program(root.resolve("tools")).getParent();
			this.environment = installEnvironment(user, tools);
		}

		/**
		 * Runs install from the archive into the harness home.
		 *
		 * @param integration the harness integration
		 * @param terminal    the user's terminal
		 * @param options     the options before the operation, after the harness home
		 * @return the outcome
		 */
		private Run run(HarnessIntegration integration, Terminal terminal, String... options)
		{
			List<String> arguments = new ArrayList<>(List.of("--home", home.toString()));
			arguments.addAll(List.of(options));
			arguments.addAll(List.of("install", "--harness", "harness-a"));
			return InvocationTest.run(plugin, project, environment, List.of(new RegisteredProvider()), integration,
				terminal, Platform.current(), command ->
				{
					throw new AssertionError("Started " + command);
				}, arguments.toArray(String[]::new));
		}
	}

	/**
	 * Returns the environment of a user whose harness program install finds, on the platform that runs the test.
	 *
	 * @param user  the user's home
	 * @param tools the directory that holds the harness program
	 * @return the environment
	 */
	private static Map<String, String> installEnvironment(Path user, Path tools)
	{
		Map<String, String> environment = new HashMap<>(RecordingIntegration.windowsVariables());
		environment.put("HOME", user.toString());
		environment.put(Platform.current().homeVariable(), user.toString());
		environment.put("PATH", tools.toString());
		return Map.copyOf(environment);
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
}
