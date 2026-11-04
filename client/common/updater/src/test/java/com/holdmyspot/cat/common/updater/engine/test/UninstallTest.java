package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.engine.Uninstaller;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
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

			UpdateResult result = fixture.uninstaller(integration).uninstall(false);

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
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

			UpdateResult result = fixture.uninstaller(new RecordingIntegration()).uninstall(true);

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.home.resolve("plugin-data"), NOFOLLOW_LINKS), "data").isFalse();
		}
	}

	/**
	 * The downloaded bundles stay while the other harness's CAT installation exists.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void keepsBundlesForOtherInstallation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("uninstall-other-")));
			Path other = Files.createDirectories(fixture.data.resolveSibling("harness-b"));
			Files.writeString(other.resolve("current"), "1.0\n");

			UpdateResult result = fixture.uninstaller(new RecordingIntegration()).uninstall(false);

			requireThat(result.result(), "result").isEqualTo(Result.UNINSTALLED);
			requireThat(Files.exists(fixture.user.resolve(".cat/bundles/bundle"), NOFOLLOW_LINKS), "bundles").isTrue();
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

			UpdateResult failed = fixture.uninstaller(integration).uninstall(false);

			requireThat(failed.result(), "result").isEqualTo(Result.NOT_REMOVED);
			requireThat(failed.result().exitStatus(), "exitStatus").isNotEqualTo(0);
			requireThat(failed.message(), "message").contains("permissions");
			requireThat(Files.exists(fixture.data.resolve("state/uninstall.json"), NOFOLLOW_LINKS), "journal").isTrue();
			requireThat(Files.exists(fixture.policy, NOFOLLOW_LINKS), "policy").isTrue();

			UpdateResult retried = fixture.uninstaller(integration.succeed("removePermissions")).uninstall(false);

			requireThat(retried.result(), "retried").isEqualTo(Result.UNINSTALLED);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("unregister", "removePermissions",
				"removePermissions"));
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

			UpdateResult result = fixture.uninstaller(integration).uninstall(true);

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
	 * One harness's installation: its home, the user's home, its data directory and its policy.
	 */
	private static final class Fixture
	{
		private final Path home;
		private final Path user;
		private final Path data;
		private final Path policy;
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
			HarnessCommand command = new HarnessCommand(List.of(root.resolve("bin/harness-a").toString()),
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
			return new Uninstaller(data, policy, trust, new LiveProvider(), integration.registration(),
				Clock.systemUTC(), interval -> () -> {});
		}
	}
}
