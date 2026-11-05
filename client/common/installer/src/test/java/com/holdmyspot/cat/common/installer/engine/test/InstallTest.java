package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.engine.InstallRequest;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests install where the harness has no consistent CAT version: which release it installs, a harness home reached
 * through a symbolic link, the startup cache, and the repair of a home that predates version folders.
 */
public final class InstallTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";

	/**
	 * Creates the test.
	 */
	public InstallTest()
	{
	}

	/**
	 * Install without a named release installs the latest release.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsLatestRelease() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("install-latest-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION));

			for (String tag : Arrays.asList(null, UpdateEngine.LATEST))
			{
				Files.deleteIfExists(RecordingIntegration.registeredVersion(fixture.home));
				UpdateResult result = fixture.install(tag);

				requireThat(result.result(), "result").withContext(result.message(), "message").
					isEqualTo(Result.INSTALLED);
				requireThat(result.version(), "version").isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
			}
		}
	}

	/**
	 * An install whose plugin cannot get a startup cache, because the installer runs with administrative rights, still
	 * installs the plugin and reports that no startup cache was written.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsWithoutStartupCache() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("install-uncached-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			UpdateEngine engine = fixture.engine(fixture.catalog, Clock.systemUTC(), interval -> () ->
			{
			}, Duration.ZERO, true);

			UpdateResult result = engine.install("v1.1", fixture.request(fixture.root.resolve("download/plugin")));

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.INSTALLED);
			requireThat(result.aotCache(), "aotCache").isEqualTo(Optional.of(false));
			requireThat(fixture.provider.installed(fixture.home).version(), "installed").
				isEqualTo(Optional.of(Releases.pluginVersion("1.1")));
		}
	}

	/**
	 * An update that needs a change that the user does not allow changes nothing, and its refusal is the question
	 * that the integration asked, so that the user can decide.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateChangesNothingWithoutConsent() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("install-no-consent-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION));
			fixture.installActive("1.0");
			fixture.integration.asking("May CAT change the shared setting?", Map.of("config#selection",
				List.of("built-in")));
			Map<String, String> before = Releases.snapshot(fixture.home);
			List<String> questions = new ArrayList<>();

			UpdateResult result = fixture.engine().install("v1.1", new InstallRequest(fixture.integration.session(),
				fixture.root.resolve("download/plugin"), List.of(), fixture.root.resolve("user/.cat/locks"),
				question ->
				{
					questions.add(question);
					return false;
				}));

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").isEqualTo("May CAT change the shared setting?");
			requireThat(questions, "questions").isEqualTo(List.of("May CAT change the shared setting?"));
			requireThat(Releases.snapshot(fixture.home), "after").isEqualTo(before);
			requireThat(fixture.integration.calls(), "calls").doesNotContain("installConsentedEntries");
		}
	}

	/**
	 * An update that needs a change that the user allows makes it before it adds the sandbox entries, records its
	 * entries, and tells the user about them in the updated message.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateMakesChangeThatUserAllows() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("install-consent-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION));
			fixture.installActive("1.0");
			fixture.integration.asking("May CAT change the shared setting?", Map.of("config#selection",
				List.of("built-in"))).describing("config#selection", "CAT changed the shared setting.");

			UpdateResult result = fixture.engine().install("v1.1", new InstallRequest(fixture.integration.session(),
				fixture.root.resolve("download/plugin"), List.of(), fixture.root.resolve("user/.cat/locks"),
				_ -> true));

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.message(), "message").isEqualTo("CAT was updated. CAT changed the shared setting.");
			List<String> calls = fixture.integration.calls();
			requireThat(calls.indexOf("installConsentedEntries"), "consented").isNotNegative().
				isLessThan(calls.lastIndexOf("installPermissions"));
			requireThat(Files.readString(InstallerState.of(fixture.data(), "harness-a").permissions()), "record").
				contains("config#selection");
		}
	}

	/**
	 * An update removes the runner entries that CAT's installation guide told the user to add before it adds its own
	 * entries, and tells the user what it removed in the updated message.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateReportsRemovedRunnerExceptions() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("install-runner-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.1", NO_MIGRATION));
			fixture.installActive("1.0");
			fixture.integration.removingRunnerExceptions(List.of("CAT removed the runner entries."));

			UpdateResult result = fixture.engine().install("v1.1", new InstallRequest(fixture.integration.session(),
				fixture.root.resolve("download/plugin"), List.of(), fixture.root.resolve("user/.cat/locks"),
				_ -> true));

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(result.message(), "message").isEqualTo("CAT was updated. CAT removed the runner entries.");
			List<String> calls = fixture.integration.calls();
			requireThat(calls.lastIndexOf("removeRunnerExceptions"), "removed").isNotNegative().
				isLessThan(calls.lastIndexOf("installPermissions"));
		}
	}

	/**
	 * A harness home that is reached through a symbolic link is installed into where the link points.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installsIntoLinkedHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("install-linked-")).toRealPath();
			Path realHome = Files.createDirectories(root.resolve("real-home"));
			Path home = Files.createSymbolicLink(root.resolve("home"), realHome);
			ReleaseCatalog catalog = new ReleaseCatalog("1.1", NO_MIGRATION);
			RecordingIntegration integration = new RecordingIntegration();
			RegisteredProvider provider = new RegisteredProvider();
			UpdateEngine engine = new UpdateEngine(provider.dataDirectory(realHome), catalog.policy(home, "1.0"),
				catalog, provider, "linux-x64", Clock.systemUTC(), interval -> () ->
			{
			}, Duration.ZERO, _ -> false, new RecordingMigrations());

			UpdateResult result = engine.install("v1.1", new InstallRequest(integration.session(),
				root.resolve("download/plugin"), List.of(), root.resolve("user/.cat/locks"), _ -> false));

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.INSTALLED);
			requireThat(provider.installed(realHome), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1")), true));
			requireThat(Files.isSymbolicLink(home), "homeLink").isTrue();
		}
	}

	/**
	 * A harness home whose CAT version predates version folders, so that it names no build, is reported as
	 * inconsistent, and install repairs it by installing the release into its version folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void repairsHomeWithoutVersionFolder() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("install-old-layout-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));
			Files.writeString(RecordingIntegration.registeredVersion(fixture.home), "1.0");
			requireThat(fixture.engine().status().consistent(), "consistentBefore").isFalse();

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			requireThat(fixture.provider.installed(fixture.home), "installed").
				isEqualTo(new InstalledState(Optional.of(Releases.pluginVersion("1.1")), true));
			requireThat(fixture.integration.registered(), "registered").
				isEqualTo(List.of(PluginVersion.parse(Releases.pluginVersion("1.1"))));
		}
	}

	/**
	 * Status reports a harness without a plugin as having no version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void statusReportsAbsentPlugin() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("status-absent-")),
				new ReleaseCatalog("1.1", NO_MIGRATION));

			UpdateResult result = fixture.engine().status();

			requireThat(result.result(), "result").isEqualTo(Result.STATUS);
			requireThat(result.version(), "version").isEmpty();
		}
	}
}
