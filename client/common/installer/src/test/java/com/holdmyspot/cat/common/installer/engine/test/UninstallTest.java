package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.engine.InstallRequest;
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
 * Tests that uninstall removes one harness's CAT installation, keeps CAT's plugin data unless the user asks to delete
 * it, and runs its steps in an order that an interrupted run can continue.
 */
public final class UninstallTest
{
	/**
	 * Uninstall's steps in order.
	 */
	private static final List<String> STEPS = List.of("permissions", "unregister", "plugin");

	/**
	 * Creates the test.
	 */
	public UninstallTest()
	{
	}

	/**
	 * Uninstall removes the recorded permission entries, unregisters the plugin and asks the harness to keep the
	 * plugin data, deletes the marketplace folder, and keeps the plugin data with its policy and release cache.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void keepsDataByDefault() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-")));
			RecordingIntegration integration = new RecordingIntegration().deletingData(fixture.data);

			UninstallResult result = fixture.uninstaller(integration).uninstall(false);

			requireThat(result.outcome().result(), "result").withContext(result.outcome().message(), "message").
				isEqualTo(Result.UNINSTALLED);
			requireThat(result.unregistered(), "unregistered").isTrue();
			requireThat(integration.calls(), "calls").isEqualTo(List.of("removePermissions", "unregister"));
			requireThat(integration.removed(), "removed").isEqualTo(List.of(Map.of("settings.json",
				List.of("entry"))));
			requireThat(integration.keptData(), "keptData").isEqualTo(List.of(true));
			requireThat(Files.exists(fixture.marketplace, NOFOLLOW_LINKS), "marketplace").isFalse();
			requireThat(fixture.policy, "policy").isRegularFile();
			requireThat(fixture.data.resolve("releases/1.1/kept"), "releases").isRegularFile();
			requireThat(Files.exists(fixture.data.resolve("state/permissions/harness-a.json"), NOFOLLOW_LINKS),
				"permissionRecord").isFalse();
		}
	}

	/**
	 * With data deletion, uninstall still removes the permission entries that the plugin data records, lets the
	 * harness delete the plugin data as its uninstall does, and leaves no plugin data behind.
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
			RecordingIntegration integration = new RecordingIntegration().deletingData(fixture.data);

			UpdateResult result = fixture.uninstaller(integration).uninstall(true).outcome();

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UNINSTALLED);
			requireThat(integration.removed(), "removed").isEqualTo(List.of(Map.of("settings.json",
				List.of("entry"))));
			requireThat(integration.keptData(), "keptData").isEqualTo(List.of(false));
			requireThat(Files.exists(fixture.data, NOFOLLOW_LINKS), "data").isFalse();
		}
	}

	/**
	 * Uninstall leaves the user's runner artifacts in {@code ~/.cat/bundles} and the user's rules alone.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void leavesUserFilesAlone() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-user-")));

			UpdateResult result = fixture.uninstaller(new RecordingIntegration()).uninstall(true).outcome();

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UNINSTALLED);
			requireThat(fixture.user.resolve(".cat/bundles/bundle"), "bundles").isRegularFile();
			requireThat(fixture.user.resolve(".cat/rules/rule.md"), "rules").isRegularFile();
		}
	}

	/**
	 * The folder of removed files that holds the running installer's version folder stays while the installer runs,
	 * and the outcome names it for deletion once the installer no longer needs its own files; every other folder is
	 * deleted.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void leavesRunningFolderForExit() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-running-")));

			UpdateResult result = fixture.uninstaller(new RecordingIntegration()).uninstall(false).outcome();

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UNINSTALLED);
			requireThat(result.exitDeletions(), "exitDeletions").isEqualTo(List.of(fixture.cache));
			requireThat(fixture.running.resolve("bin/installer"), "running").isRegularFile();
			requireThat(Files.exists(fixture.marketplace, NOFOLLOW_LINKS), "marketplace").isFalse();
		}
	}

	/**
	 * When the harness program that install recorded no longer exists, uninstall cannot unregister the plugin, so it
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
			requireThat(Files.exists(fixture.marketplace, NOFOLLOW_LINKS), "marketplace").isFalse();
			requireThat(Files.exists(fixture.data, NOFOLLOW_LINKS), "data").isFalse();
		}
	}

	/**
	 * Uninstall removes an installation whose harness home is reached through a symbolic link, and deletes a link
	 * inside the plugin as an entry, leaving what it points to.
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
			Files.createSymbolicLink(fixture.marketplace.resolve("escape"), outside);
			Path home = Files.createSymbolicLink(root.resolve("home-link"), fixture.home);
			TrustPolicy trust = TrustPolicy.of(fixture.trust.keys(), fixture.trust.releaseBaseUrl(),
				fixture.trust.minimum(), fixture.trust.maximumMajor(), Map.of("harness-a", home),
				Map.of("harness-a", fixture.trust.command("harness-a").orElseThrow()));
			RecordingIntegration integration = new RecordingIntegration();

			UninstallResult result = new Uninstaller(fixture.data, trust, new RegisteredProvider(),
				integration.session(), fixture.running, Clock.systemUTC(), interval -> () -> {}).uninstall(true);

			requireThat(result.outcome().message(), "message").isEqualTo("CAT was removed from this harness.");
			requireThat(result.outcome().result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.marketplace, NOFOLLOW_LINKS), "marketplace").isFalse();
			requireThat(Files.exists(fixture.data, NOFOLLOW_LINKS), "data").isFalse();
			requireThat(Files.readString(outside), "outside").isEqualTo("kept");
			requireThat(Files.isSymbolicLink(home), "homeLink").isTrue();
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
			RecordingIntegration integration = new RecordingIntegration().fail("unregister");

			UpdateResult failed = fixture.uninstaller(integration).uninstall(false).outcome();

			requireThat(failed.result(), "result").isEqualTo(Result.UNINSTALL_INCOMPLETE);
			requireThat(failed.result().exitStatus(), "exitStatus").isNotEqualTo(0);
			requireThat(failed.message(), "message").contains("unregister");
			requireThat(fixture.data.resolve("state/uninstall.json"), "journal").isRegularFile();
			requireThat(fixture.marketplace, "marketplace").isDirectory();

			UpdateResult retried = fixture.uninstaller(integration.succeed("unregister")).uninstall(false).outcome();

			requireThat(retried.result(), "retried").isEqualTo(Result.UNINSTALLED);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("removePermissions", "unregister",
				"unregister"));
		}
	}

	/**
	 * An uninstall that completed and kept the plugin data leaves no progress behind, so that an uninstall after a
	 * later install runs every step again.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void completedUninstallLeavesNoProgress() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-again-")));
			RecordingIntegration integration = new RecordingIntegration();
			fixture.uninstaller(integration).uninstall(false);
			Files.createDirectories(fixture.marketplace);

			UpdateResult result = fixture.uninstaller(integration).uninstall(false).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("removePermissions", "unregister",
				"unregister"));
			requireThat(Files.exists(fixture.marketplace, NOFOLLOW_LINKS), "marketplace").isFalse();
			requireThat(Files.exists(fixture.data.resolve("state/uninstall.json"), NOFOLLOW_LINKS), "journal").
				isFalse();
		}
	}

	/**
	 * An install after an uninstall that stopped part way discards that uninstall's progress, so that the next
	 * uninstall runs every step again: it removes the permission entries that the install added again, and the plugin.
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
			Path data = new RecordingIntegration.Layout(home).dataDirectory();
			Path program = Files.writeString(Files.createDirectories(root.resolve("bin")).resolve("harness-a"),
				"#!/bin/sh\n");
			requireThat(program.toFile().setExecutable(true), "executable").isTrue();
			ReleaseCatalog catalog = new ReleaseCatalog("1.1", "# No migration\n");
			TrustPolicy signed = catalog.policy(home, "1.0");
			TrustPolicy trust = TrustPolicy.of(signed.keys(), signed.releaseBaseUrl(), signed.minimum(),
				signed.maximumMajor(), signed.harnesses(), Map.of("harness-a",
					new HarnessCommand(List.of(program.toString()), Map.of())));
			UpdateEngine engine = new UpdateEngine(data, trust, catalog.source(), new RegisteredProvider(), "linux-x64",
				Clock.systemUTC(), interval -> () -> {});
			RecordingIntegration integration = new RecordingIntegration().
				adding(Map.of("settings.json", List.of("entry"))).fail("unregister");
			InstallRequest request = new InstallRequest(integration.session(), root.resolve("download/plugin"),
				List.of(), root.resolve("user/.cat/locks"), root.resolve("user/.cat/sessions"), _ -> false);
			requireThat(engine.install("v1.1", request).result(), "installed").
				isEqualTo(Result.INSTALLED);
			Uninstaller uninstaller = new Uninstaller(data, trust, new RegisteredProvider(), integration.session(),
				root.resolve("download/plugin"), Clock.systemUTC(), interval -> () -> {});
			requireThat(uninstaller.uninstall(false).outcome().result(), "stopped").
				isEqualTo(Result.UNINSTALL_INCOMPLETE);
			integration.adding(Map.of("settings.json", List.of("entry")));
			// The stopped uninstall left the plugin registered, so the install finds it up to date
			requireThat(engine.install("v1.1", request).result(), "reinstalled").isEqualTo(Result.UP_TO_DATE);
			integration.succeed("unregister");
			int before = integration.calls().size();

			UpdateResult result = uninstaller.uninstall(false).outcome();

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UNINSTALLED);
			requireThat(integration.calls().subList(before, integration.calls().size()), "calls").
				isEqualTo(List.of("removePermissions", "unregister"));
			requireThat(Files.exists(RecordingIntegration.registeredVersion(home), NOFOLLOW_LINKS), "plugin").isFalse();
		}
	}

	/**
	 * An uninstall that kept the plugin data and stopped at a later step leaves the data to the next uninstall, so
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
			RecordingIntegration integration = new RecordingIntegration().fail("unregister");
			fixture.uninstaller(integration).uninstall(false);
			requireThat(fixture.policy, "keptData").isRegularFile();

			UpdateResult result = fixture.uninstaller(integration.succeed("unregister")).uninstall(true).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.data, NOFOLLOW_LINKS), "data").isFalse();
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
				quoted + "}\n");
			RecordingIntegration integration = new RecordingIntegration();

			UpdateResult result = fixture.uninstaller(integration).uninstall(false).outcome();

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			List<String> expectedCalls = new ArrayList<>();
			if (!done.contains("permissions"))
				expectedCalls.add("removePermissions");
			if (!done.contains("unregister"))
				expectedCalls.add("unregister");
			requireThat(integration.calls(), "calls").isEqualTo(expectedCalls);
			requireThat(Files.exists(fixture.marketplace, NOFOLLOW_LINKS), "marketplace").isFalse();
		}
	}

	/**
	 * One harness's installation: its home, the user's home, its marketplace folder, the version folder that the
	 * running installer belongs to, its plugin data with the policy, and its harness program.
	 */
	private static final class Fixture
	{
		private final Path home;
		private final Path user;
		private final Path marketplace;
		private final Path cache;
		private final Path running;
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
			RecordingIntegration.Layout layout = new RecordingIntegration.Layout(home);
			this.marketplace = Files.createDirectories(layout.marketplacePlugin()).getParent().getParent();
			this.cache = layout.versionFolders().getParent();
			this.running = layout.versionFolders().resolve(Releases.pluginVersion("1.1"));
			Files.writeString(Files.createDirectories(running.resolve("bin")).resolve("installer"), "installer");
			Files.writeString(RecordingIntegration.registeredVersion(home), Releases.pluginVersion("1.1"));
			this.data = layout.dataDirectory();
			Files.writeString(Files.createDirectories(data.resolve("releases/1.1")).resolve("kept"), "release");
			Files.createDirectories(data.resolve("state/permissions"));
			Files.writeString(data.resolve("state/permissions/harness-a.json"),
				"{\"settings.json\": [\"entry\"]}\n");
			this.user = Files.createDirectories(root.resolve("user"));
			Files.createDirectories(user.resolve(".cat/bundles"));
			Files.writeString(user.resolve(".cat/bundles/bundle"), "bundle");
			Files.createDirectories(user.resolve(".cat/rules"));
			Files.writeString(user.resolve(".cat/rules/rule.md"), "rule");
			this.program = Files.createDirectories(root.resolve("bin")).resolve("harness-a");
			Files.writeString(program, "#!/bin/sh\n");
			requireThat(program.toFile().setExecutable(true), "executable").isTrue();
			HarnessCommand command = new HarnessCommand(List.of(program.toString()),
				Map.of("HOME", user.toString()));
			this.trust = TrustPolicy.of(List.of(trusted("key", keyPair())), URI.create("https://releases.example/cat"),
				ReleaseVersion.parse("1.0"), 1, Map.of("harness-a", home), Map.of("harness-a", command));
			this.policy = data.resolve("policy.json");
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
			return new Uninstaller(data, trust, new RegisteredProvider(), integration.session(), running,
				Clock.systemUTC(), interval -> () -> {});
		}
	}
}
