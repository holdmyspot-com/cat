package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.engine.UninstallResult;
import com.holdmyspot.cat.common.installer.engine.Uninstaller;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that uninstall removes one harness's CAT installation completely, in an order that an interrupted run can
 * continue.
 */
public final class UninstallTest
{
	/**
	 * Uninstall's steps in order.
	 */
	private static final List<String> STEPS = List.of("unregister", "plugin", "bundles", "data", "permissions",
		"policy");

	/**
	 * Creates the test.
	 */
	public UninstallTest()
	{
	}

	/**
	 * Uninstall unregisters the plugin, deletes its files and the downloaded bundles, keeps the plugin data and the
	 * user's rules, removes the recorded permission entries last, and deletes the policy and its directory.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void removesInstallation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-")));
			RecordingIntegration integration = new RecordingIntegration();

			UninstallResult result = fixture.uninstaller(integration).uninstall(false);

			requireThat(result.outcome().result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(result.unregistered(), "unregistered").isTrue();
			requireThat(integration.calls(), "calls").isEqualTo(List.of("unregister", "removePermissions"));
			requireThat(integration.removed(), "removed").isEqualTo(List.of(Map.of("settings.json",
				List.of("entry"))));
			requireThat(Files.exists(fixture.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(fixture.home.resolve(".cat-previous-live"), NOFOLLOW_LINKS), "previous").isFalse();
			requireThat(Files.exists(fixture.home.resolve("plugin-data/kept"), NOFOLLOW_LINKS), "data").isTrue();
			requireThat(Files.exists(fixture.user.resolve(".cat/bundles"), NOFOLLOW_LINKS), "bundles").isFalse();
			requireThat(Files.exists(fixture.user.resolve(".cat/rules/rule.md"), NOFOLLOW_LINKS), "rules").isTrue();
			requireThat(Files.exists(fixture.policy, NOFOLLOW_LINKS), "policy").isFalse();
			requireThat(Files.exists(fixture.policy.getParent(), NOFOLLOW_LINKS), "policyDirectory").isFalse();
		}
	}

	/**
	 * When the harness program that setup recorded no longer exists, uninstall cannot unregister the plugin, so it
	 * reports that, and still removes everything else.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void removesInstallationWithoutProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-program-")));
			Files.delete(fixture.program);
			RecordingIntegration integration = new RecordingIntegration();

			UninstallResult result = fixture.uninstaller(integration).uninstall(true);

			requireThat(result.outcome().result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(result.unregistered(), "unregistered").isFalse();
			requireThat(integration.calls(), "calls").isEqualTo(List.of("removePermissions"));
			requireThat(Files.exists(fixture.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "data").isFalse();
			requireThat(Files.exists(fixture.user.resolve(".cat/bundles"), NOFOLLOW_LINKS), "bundles").isFalse();
			requireThat(Files.exists(fixture.policy, NOFOLLOW_LINKS), "policy").isFalse();
		}
	}

	/**
	 * Uninstall removes an installation whose harness home, user's home and configuration directory are reached through
	 * symbolic links, and deletes a link inside the plugin as an entry, leaving what it points to.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void removesInstallationThroughLinks() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-linked-")));
			Path root = fixture.home.getParent();
			Path outside = Files.writeString(root.resolve("outside"), "kept");
			Files.createSymbolicLink(fixture.home.resolve("live/escape"), outside);
			Path home = Files.createSymbolicLink(root.resolve("home-link"), fixture.home);
			Path user = Files.createSymbolicLink(root.resolve("user-link"), fixture.user);
			Path config = Files.createSymbolicLink(root.resolve("config-link"), fixture.policy.getParent().getParent());
			HarnessCommand command = new HarnessCommand(List.of(fixture.program.toString()),
				Map.of("HOME", user.toString()));
			TrustPolicy trust = TrustPolicy.of(fixture.trust.keys(), fixture.trust.releaseBaseUrl(),
				fixture.trust.minimum(), fixture.trust.maximumMajor(), Map.of("harness-a", home),
				Map.of("harness-a", command));
			RecordingIntegration integration = new RecordingIntegration();

			UninstallResult result = new Uninstaller(fixture.data,
				config.resolve("harness-a/policy.json"), trust, new LiveProvider(), integration.session(),
				Clock.systemUTC(), interval -> () -> {}).uninstall(true);

			requireThat(result.outcome().message(), "message").isEqualTo("CAT was removed from this harness.");
			requireThat(result.outcome().result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.home.resolve("live"), NOFOLLOW_LINKS), "live").isFalse();
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "data").isFalse();
			requireThat(Files.exists(fixture.user.resolve(".cat/bundles"), NOFOLLOW_LINKS), "bundles").isFalse();
			requireThat(Files.exists(fixture.policy, NOFOLLOW_LINKS), "policy").isFalse();
			requireThat(Files.readString(outside), "outside").isEqualTo("kept");
			requireThat(Files.isSymbolicLink(home), "homeLink").isTrue();
		}
	}

	/**
	 * Uninstall with data deletion also deletes the plugin's data directory.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void deletesDataWhenAsked() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-data-")));

			UpdateResult result = fixture.uninstaller(new RecordingIntegration()).uninstall(true).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "data").isFalse();
		}
	}

	/**
	 * Uninstall deletes the downloaded bundles also while the other harness's CAT installation exists, which downloads
	 * them again when it needs them, and leaves that installation alone.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void deletesBundlesBesideOtherInstallation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-other-")));
			Path other = Files.createDirectories(fixture.data.resolveSibling("harness-b"));
			Files.writeString(other.resolve("current"), "1.0\n");

			UpdateResult result = fixture.uninstaller(new RecordingIntegration()).uninstall(false).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.user.resolve(".cat/bundles"), NOFOLLOW_LINKS), "bundles").isFalse();
			requireThat(Files.readString(other.resolve("current")), "otherInstallation").isEqualTo("1.0\n");
		}
	}

	/**
	 * Uninstall deletes the directory that holds every harness's policy directory once it is empty, and keeps it while
	 * another harness's policy directory remains.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void emptyPolicyParentIsDeleted() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture alone = new Fixture(cleanup.add(testDirectory("uninstall-policy-parent-")));
			Fixture shared = new Fixture(cleanup.add(testDirectory("uninstall-policy-shared-")));
			Path other = Files.createDirectories(shared.policy.getParent().resolveSibling("harness-b"));

			alone.uninstaller(new RecordingIntegration()).uninstall(false);
			shared.uninstaller(new RecordingIntegration()).uninstall(false);

			requireThat(Files.exists(alone.policy.getParent().getParent(), NOFOLLOW_LINKS), "emptyParent").isFalse();
			requireThat(Files.isDirectory(other, NOFOLLOW_LINKS), "otherPolicyDirectory").isTrue();
		}
	}

	/**
	 * A failed step stops uninstall with the step's name and a status that is not zero, and keeps the progress so that
	 * the next uninstall continues with that step.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void failedStepIsReportedAndRetried() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-failure-")));
			RecordingIntegration integration = new RecordingIntegration().fail("removePermissions");

			UpdateResult failed = fixture.uninstaller(integration).uninstall(false).outcome();

			requireThat(failed.result(), "result").isEqualTo(Result.UNINSTALL_INCOMPLETE);
			requireThat(failed.result().exitStatus(), "exitStatus").isNotEqualTo(0);
			requireThat(failed.message(), "message").contains("permissions");
			requireThat(Files.exists(fixture.data.resolve("state/uninstall.json"), NOFOLLOW_LINKS), "journal").isTrue();
			requireThat(Files.exists(fixture.policy, NOFOLLOW_LINKS), "policy").isTrue();

			UpdateResult retried = fixture.uninstaller(integration.succeed("removePermissions")).uninstall(false).outcome();

			requireThat(retried.result(), "retried").isEqualTo(Result.UNINSTALLED);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("unregister", "removePermissions",
				"removePermissions"));
		}
	}

	/**
	 * An install after an uninstall that stopped part way discards that uninstall's progress, so that the next
	 * uninstall runs every step again and removes the plugin that the install added.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installDiscardsStoppedUninstall() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("uninstall-reinstalled-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("home"));
			Path data = root.resolve("base/harness-a");
			Releases.installation(data, "1.1");
			Path program = Files.writeString(Files.createDirectories(root.resolve("bin")).resolve("harness-a"),
				"#!/bin/sh\n");
			requireThat(program.toFile().setExecutable(true), "executable").isTrue();
			Releases source = new Releases("1.1", "");
			TrustPolicy signed = source.policy(home);
			TrustPolicy trust = TrustPolicy.of(signed.keys(), signed.releaseBaseUrl(), signed.minimum(),
				signed.maximumMajor(), signed.harnesses(), Map.of("harness-a",
					new HarnessCommand(List.of(program.toString()), Map.of())));
			Path policy = Files.createDirectories(root.resolve("config/harness-a")).resolve("policy.json");
			Files.writeString(policy, "{}\n");
			UpdateEngine engine = new UpdateEngine(data, trust, source, new LiveProvider(), "linux-x64",
				Clock.systemUTC(), interval -> () -> {});
			RecordingIntegration integration = new RecordingIntegration().fail("removePermissions");
			requireThat(engine.install("v1.1", integration.session()).result(), "installed").
				isEqualTo(Result.INSTALLED);
			Files.createDirectories(data.resolve("state/permissions"));
			Files.writeString(data.resolve("state/permissions/harness-a.json"), "{\"settings.json\": [\"entry\"]}\n");
			Uninstaller uninstaller = new Uninstaller(data, policy, trust, new LiveProvider(),
				integration.session(), Clock.systemUTC(), interval -> () -> {});
			requireThat(uninstaller.uninstall(false).outcome().result(), "stopped").
				isEqualTo(Result.UNINSTALL_INCOMPLETE);
			requireThat(engine.install("v1.1", integration.session()).result(), "reinstalled").
				isEqualTo(Result.INSTALLED);
			integration.succeed("removePermissions");
			int before = integration.calls().size();

			UpdateResult result = uninstaller.uninstall(false).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(integration.calls().subList(before, integration.calls().size()), "calls").
				isEqualTo(List.of("unregister", "removePermissions"));
			requireThat(Files.exists(home.resolve("live"), NOFOLLOW_LINKS), "plugin").isFalse();
			requireThat(Files.exists(policy, NOFOLLOW_LINKS), "policy").isFalse();
		}
	}

	/**
	 * An uninstall that kept the plugin data and stopped at a later step leaves the data step to the next uninstall, so
	 * that continuing with data deletion deletes the data.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void continuedUninstallDeletesKeptData() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-continued-data-")));
			RecordingIntegration integration = new RecordingIntegration().fail("removePermissions");
			fixture.uninstaller(integration).uninstall(false);
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "keptData").isTrue();

			UpdateResult result = fixture.uninstaller(integration.succeed("removePermissions")).uninstall(true).
				outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "data").isFalse();
		}
	}

	/**
	 * Returns how many steps an interrupted uninstall completed.
	 *
	 * @return each count of completed steps
	 */
	@DataProvider
	public static Object[][] interruptions()
	{
		Object[][] cases = new Object[STEPS.size()][];
		for (int completed = 0; completed < STEPS.size(); ++completed)
			cases[completed] = new Object[]{completed};
		return cases;
	}

	/**
	 * An uninstall that stopped after some steps continues with the next step and repeats none of the completed ones.
	 *
	 * @param completed the number of steps that the interrupted uninstall completed
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "interruptions")
	public void resumesAfterInterruption(int completed) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-resume-")));
			List<String> done = STEPS.subList(0, completed);
			List<String> quoted = new ArrayList<>();
			for (String step : done)
				quoted.add("\"" + step + "\"");
			Files.writeString(fixture.data.resolve("state/uninstall.json"), "{\"format\": 1, \"completed\": " +
				quoted + ", \"retained\": []}\n");
			RecordingIntegration integration = new RecordingIntegration();

			UpdateResult result = fixture.uninstaller(integration).uninstall(true).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			List<String> expectedCalls = new ArrayList<>();
			if (!done.contains("unregister"))
				expectedCalls.add("unregister");
			if (!done.contains("permissions"))
				expectedCalls.add("removePermissions");
			requireThat(integration.calls(), "calls").isEqualTo(expectedCalls);
			requireThat(Files.exists(fixture.home.resolve("live"), NOFOLLOW_LINKS), "live").
				isEqualTo(done.contains("plugin"));
			requireThat(Files.exists(fixture.user.resolve(".cat/bundles"), NOFOLLOW_LINKS), "bundles").
				isEqualTo(done.contains("bundles"));
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "data").
				isEqualTo(done.contains("data"));
		}
	}

	/**
	 * One harness's installation: its home, the user's home, its data directory, its policy and its harness program.
	 */
	private static final class Fixture
	{
		private final Path home;
		private final Path user;
		private final Path data;
		private final Path policy;
		private final Path program;
		private final TrustPolicy trust;

		/**
		 * Creates the installation.
		 *
		 * @param root the test directory
		 * @throws IOException    if the fixture cannot be created
		 * @throws TrustException if the test policy is invalid
		 */
		private Fixture(Path root) throws IOException, TrustException
		{
			root = root.toRealPath();
			this.home = Files.createDirectories(root.resolve("home"));
			Files.createDirectories(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.1");
			Files.createDirectories(home.resolve("plugin-data"));
			Files.writeString(home.resolve("plugin-data/kept"), "data");
			this.user = Files.createDirectories(root.resolve("user"));
			Files.createDirectories(user.resolve(".cat/bundles"));
			Files.writeString(user.resolve(".cat/bundles/bundle"), "bundle");
			Files.createDirectories(user.resolve(".cat/rules"));
			Files.writeString(user.resolve(".cat/rules/rule.md"), "rule");
			this.data = Files.createDirectories(root.resolve("base/harness-a"));
			Files.writeString(data.resolve("current"), "1.1\n");
			Files.createDirectories(data.resolve("state/permissions"));
			Files.writeString(data.resolve("state/permissions/harness-a.json"),
				"{\"settings.json\": [\"entry\"]}\n");
			this.policy = root.resolve("config/harness-a/policy.json");
			Files.createDirectories(policy.getParent());
			this.program = Files.createDirectories(root.resolve("bin")).resolve("harness-a");
			Files.writeString(program, "#!/bin/sh\n");
			requireThat(program.toFile().setExecutable(true), "executable").isTrue();
			HarnessCommand command = new HarnessCommand(List.of(program.toString()),
				Map.of("HOME", user.toString()));
			this.trust = TrustPolicy.of(List.of(trusted("key", keyPair())), URI.create("https://releases.example/cat"),
				ReleaseVersion.parse("1.0"), 1, Map.of("harness-a", home), Map.of("harness-a", command));
			Files.writeString(policy, "{}\n");
		}

		/**
		 * Creates an uninstaller for the installation.
		 *
		 * @param integration the harness integration
		 * @return the uninstaller
		 */
		private Uninstaller uninstaller(RecordingIntegration integration)
		{
			return new Uninstaller(data, policy, trust, new LiveProvider(), integration.session(),
				Clock.systemUTC(), interval -> () -> {});
		}
	}
}
