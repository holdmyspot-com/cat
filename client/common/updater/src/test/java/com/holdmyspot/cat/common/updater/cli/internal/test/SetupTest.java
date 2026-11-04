package com.holdmyspot.cat.common.updater.cli.internal.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.cli.internal.DirectoryMover;
import com.holdmyspot.cat.common.updater.cli.internal.Locations;
import com.holdmyspot.cat.common.updater.cli.internal.Setup;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.engine.test.LauncherSwap;
import com.holdmyspot.cat.common.updater.engine.test.LiveProvider;
import com.holdmyspot.cat.common.updater.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.updater.engine.test.Releases;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests setup from an extracted updater archive: the installed runtime, updater, policy and launcher, the plugin step,
 * and the permission entries.
 */
public final class SetupTest
{
	/**
	 * Creates the test.
	 */
	public SetupTest()
	{
	}

	/**
	 * Setup installs the runtime, the updater, its policy, the stable launcher and the current selection, then
	 * installs and registers the plugin and records the permission entries that it added.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void installsFromArchive() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-archive-")));
			fixture.integration.adding(Map.of("settings.json", List.of("entry")));

			Setup.Outcome outcome = fixture.setUp(null);

			Path data = fixture.data();
			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.INSTALLED);
			requireThat(outcome.version(), "version").isEqualTo("1.2");
			requireThat(Files.readString(data.resolve("runtime/cat-runtime.json")), "runtime").
				isEqualTo(Files.readString(fixture.archive.resolve("runtime/cat-runtime.json")));
			requireThat(Files.readString(data.resolve("1.2/VERSION")), "app").isEqualTo("1.2\n");
			requireThat(Files.readString(data.resolve("1.2").resolve(Locations.POLICY_ANCHOR)), "anchor").
				isEqualTo(fixture.policy() + "\n");
			TrustPolicy policy = TrustPolicy.read(fixture.policy());
			requireThat(policy.format(), "format").isEqualTo(2);
			requireThat(policy.harnesses(), "harnesses").isEqualTo(Map.of("harness-a", fixture.home));
			requireThat(policy.command("harness-a").orElseThrow(), "command").isEqualTo(fixture.command);
			requireThat(Files.isExecutable(data.resolve("bin/cat-updater")), "launcher").isTrue();
			requireThat(outcome.launcher(), "outcomeLauncher").isEqualTo(data.resolve("bin/cat-updater"));
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.2\n");
			requireThat(Files.readString(fixture.home.resolve("live/version")), "plugin").isEqualTo("1.2");
			requireThat(Files.readString(fixture.home.resolve("live/client/runtime-binding")), "binding").
				isEqualTo(data.resolve("runtime") + "\n");
			requireThat(fixture.integration.calls(), "calls").isEqualTo(List.of("discover", "validateConfiguration",
				"register", "installPermissions"));
			requireThat(outcome.permissions(), "permissions").isEqualTo(Map.of("settings.json", List.of("entry")));
			requireThat(Files.readString(data.resolve("state/permissions/harness-a.json")), "record").
				contains("settings.json").contains("entry");
			requireThat(Files.readString(data.resolve("state/harness-a/highest-version")), "highest").
				isEqualTo("1.2\n");
		}
	}

	/**
	 * Returns setup conditions that must be refused before anything changes.
	 *
	 * @return each condition's name
	 */
	@DataProvider
	public static Object[][] refusals()
	{
		return new Object[][]{{"configuration"}, {"discovery"}, {"runtime"}, {"keyless"}, {"otherHarness"},
			{"insideProject"}};
	}

	/**
	 * Setup refuses, changing nothing, when the harness configuration is invalid, the harness program is not found,
	 * the archive's runtime cannot run the installed plugin, the release has no key and no local plugin is given, the
	 * existing policy names another harness, or the data directory lies inside the working directory.
	 *
	 * @param condition the refused condition
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the fixture's policies are invalid
	 */
	@Test(dataProvider = "refusals")
	public void refusesWithoutChange(String condition) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-refused-")));
			Files.createDirectories(fixture.home.resolve("live/client"));
			Files.writeString(fixture.home.resolve("live/version"), "1.0");
			switch (condition)
			{
				case "configuration" -> fixture.integration.fail("validateConfiguration");
				case "discovery" -> fixture.integration.discovering(null);
				case "runtime" -> Files.writeString(fixture.home.resolve("live/client/runtime-requirement.json"),
					"{\"format\": 1, \"java_feature\": 99, \"modules\": [\"java.base\"]}");
				case "keyless" -> fixture.writeDefaultPolicy(false);
				case "otherHarness" ->
				{
					Files.createDirectories(fixture.policy().getParent());
					TrustPolicy.of(List.of(), URI.create("https://releases.example/cat"), ReleaseVersion.parse("1.0"),
						1, Map.of("harness-b", fixture.home)).write(fixture.policy(), replacer());
				}
				case "insideProject" -> fixture.environment.put("XDG_DATA_HOME", fixture.cwd.resolve("data").toString());
				default -> throw new AssertionError(condition);
			}
			String before = "";
			if (Files.exists(fixture.policy()))
				before = Files.readString(fixture.policy());
			try
			{
				fixture.setUp(null);
				throw new AssertionError("Setup accepted " + condition);
			}
			catch (TrustException | IOException e)
			{
				requireThat(e.getMessage(), "message").isNotBlank();
			}
			requireThat(Files.exists(fixture.data(), NOFOLLOW_LINKS), "data").isFalse();
			requireThat(Files.exists(fixture.cwd.resolve("data"), NOFOLLOW_LINKS), "projectData").isFalse();
			requireThat(Files.readString(fixture.home.resolve("live/version")), "plugin").isEqualTo("1.0");
			if (!before.isEmpty())
				requireThat(Files.readString(fixture.policy()), "policy").isEqualTo(before);
		}
	}

	/**
	 * Setup replaces the installed runtime in place; when moving the new runtime into place fails, the previous
	 * runtime is restored, no staged or set-aside copy remains, and setup fails with the cause.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses unexpectedly
	 */
	@Test
	public void replacesRuntimeAndRestoresOnFailure() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-runtime-")));
			fixture.setUp(null);
			Path installed = fixture.data().resolve("runtime/bin/java");
			Files.writeString(fixture.archive.resolve("runtime/bin/java"), "newer java");
			boolean[] failed = {false};
			fixture.mover = (source, target) ->
			{
				if (target.getFileName().toString().equals("runtime") && !failed[0])
				{
					failed[0] = true;
					throw new IOException("The runtime is in use");
				}
				DirectoryMover.ATOMIC.move(source, target);
			};
			try
			{
				fixture.setUp(null);
				throw new AssertionError("Setup replaced a runtime that was in use");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("in use");
			}
			requireThat(Files.readString(installed), "restored").isEqualTo("java");
			try (Stream<Path> entries = Files.list(fixture.data()))
			{
				requireThat(entries.map(path -> path.getFileName().toString()).filter(name -> name.contains("runtime")).
					toList(), "runtimeEntries").isEqualTo(List.of("runtime"));
			}

			fixture.mover = DirectoryMover.ATOMIC;
			fixture.setUp(null);

			requireThat(Files.readString(installed), "replaced").isEqualTo("newer java");
		}
	}

	/**
	 * Setup deletes every other updater version directory, and activates and registers the plugin again when the
	 * harness already has this version, without making the replaced plugin a rollback.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void replacesOtherVersionsAndReactivates() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-versions-")));
			Files.createDirectories(fixture.data().resolve("1.0/bin"));
			Files.createDirectories(fixture.data().resolve("1.1"));
			Files.createDirectories(fixture.home.resolve("live"));
			Files.writeString(fixture.home.resolve("live/version"), "1.2");

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.UPDATED);
			requireThat(Files.exists(fixture.data().resolve("1.0"), NOFOLLOW_LINKS), "1.0").isFalse();
			requireThat(Files.exists(fixture.data().resolve("1.1"), NOFOLLOW_LINKS), "1.1").isFalse();
			requireThat(Files.isDirectory(fixture.data().resolve("1.2")), "1.2").isTrue();
			requireThat(Files.isRegularFile(fixture.home.resolve("live/.harness-a/plugin.json")), "activated").isTrue();
			requireThat(Files.exists(fixture.home.resolve(".cat-previous-live"), NOFOLLOW_LINKS), "previous").
				isFalse();
			requireThat(fixture.integration.calls(), "calls").contains("register");
		}
	}

	/**
	 * Setup from another release moves the whole bundle of an installation whose runtime replacement the stable
	 * launcher abandoned: the runtime, the updater and the plugin are at the setup's release, no replacement or previous
	 * bundle remains, the next operation succeeds, and the other harness's installation is unchanged.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void movesBundleOverPendingReplacement() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-pending-")));
			Path data = fixture.data();
			Files.createDirectories(fixture.home.resolve("live"));
			Files.writeString(fixture.home.resolve("live/version"), "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, release.policy(fixture.home), release).update("v1.1",
				new RecordingIntegration().registration()).result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.abandon(data);
			Path other = data.resolveSibling("harness-b");
			Releases.installation(other, "1.0");
			LauncherSwap.requestRollback(other, "1.0", "0.9");
			Map<String, String> otherBefore = Releases.snapshot(other);

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.UPDATED);
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.2\n");
			requireThat(Files.readString(data.resolve("runtime/cat-runtime.json")), "runtime").
				isEqualTo(Files.readString(fixture.archive.resolve("runtime/cat-runtime.json")));
			requireThat(Files.readString(fixture.home.resolve("live/version")), "pluginVersion").isEqualTo("1.2");
			requireThat(leftovers(data), "leftovers").isEmpty();
			UpdateEngine engine = engine(data, outcome.policy(), fixture.source);
			requireThat(engine.pendingSwap(), "pendingSwap").isEqualTo(Optional.empty());
			requireThat(engine.status().previousVersion(), "previous").isEqualTo(Optional.empty());
			requireThat(engine.update("v1.2", new RecordingIntegration().registration()).result(), "next").
				isEqualTo(Result.UP_TO_DATE);
			requireThat(Releases.snapshot(other), "otherInstallation").isEqualTo(otherBefore);
		}
	}

	/**
	 * Setup from an older release discards the previous bundle that the installation kept, because rollback would
	 * return past the release that the user chose.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void olderReleaseLeavesNoPreviousBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-older-")));
			Path data = fixture.data();
			Files.createDirectories(fixture.home.resolve("live"));
			Files.writeString(fixture.home.resolve("live/version"), "1.3");
			Releases.updated(data, "1.1", "1.3");

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.UPDATED);
			requireThat(leftovers(data), "leftovers").isEmpty();
			requireThat(Files.exists(data.resolve("1.3"), NOFOLLOW_LINKS), "1.3").isFalse();
			requireThat(engine(data, outcome.policy(), fixture.source).status().previousVersion(), "previous").
				isEqualTo(Optional.empty());
		}
	}

	/**
	 * Setup from a newer release keeps the installed bundle as the previous bundle: status reports it, and rollback
	 * restores its Java runtime, updater and plugin.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void newerReleaseKeepsReplacedBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-newer-")));
			Path data = fixture.data();
			Files.createDirectories(fixture.home.resolve("live"));
			Files.writeString(fixture.home.resolve("live/version"), "1.1");
			Releases.installation(data, "1.1");

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.UPDATED);
			UpdateEngine engine = engine(data, outcome.policy(), fixture.source);
			requireThat(engine.status().previousVersion(), "previous").isEqualTo(Optional.of("1.1"));
			requireRollbackTo11(engine, fixture);
		}
	}

	/**
	 * Setup from the installed release keeps the previous bundle that an update left, also though it activates the
	 * plugin again: status reports it, and rollback restores it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void repairKeepsPreviousBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = updatedFixture(cleanup.add(testDirectory("setup-repair-")));

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.UPDATED);
			requireThat(fixture.integration.calls(), "calls").contains("register");
			UpdateEngine engine = engine(fixture.data(), outcome.policy(), fixture.source);
			requireThat(engine.status().previousVersion(), "previous").isEqualTo(Optional.of("1.1"));
			requireRollbackTo11(engine, fixture);
		}
	}

	/**
	 * Setup from the installed release that stops while it registers the plugin again leaves the previous bundle that
	 * an update left: the next operation recovers the plugin, and rollback restores the previous bundle.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void interruptedRepairKeepsPreviousBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = updatedFixture(cleanup.add(testDirectory("setup-interrupted-")));
			fixture.integration.crash("register");

			try
			{
				fixture.setUp(null);
				throw new AssertionError("Setup completed although registration stopped");
			}
			catch (IllegalStateException _)
			{
				// The process ended during registration
			}

			UpdateEngine engine = engine(fixture.data(), TrustPolicy.read(fixture.policy()), fixture.source);
			requireRollbackTo11(engine, fixture);
		}
	}

	/**
	 * Creates a fixture whose installation an update moved from release 1.1 to release 1.2, so that it keeps the
	 * bundle of release 1.1 as its previous bundle.
	 *
	 * @param directory the test directory
	 * @return the fixture
	 * @throws IOException    if the fixture cannot be created
	 * @throws TrustException if the release's policy is invalid
	 */
	private static Fixture updatedFixture(Path directory) throws IOException, TrustException
	{
		Fixture fixture = new Fixture(directory);
		Path data = fixture.data();
		Files.createDirectories(fixture.home.resolve("live"));
		Files.writeString(fixture.home.resolve("live/version"), "1.1");
		Releases.installation(data, "1.1");
		requireThat(engine(data, fixture.source.policy(fixture.home), fixture.source).update("v1.2",
			new RecordingIntegration().registration()).result(), "update").isEqualTo(Result.UPDATED);
		LauncherSwap.finish(data);
		return fixture;
	}

	/**
	 * Rolls an installation back and requires that release 1.1's Java runtime, updater and plugin are restored once
	 * the stable launcher finishes.
	 *
	 * @param engine  the installation's engine
	 * @param fixture the fixture
	 * @throws IOException if the installation cannot be read
	 */
	private static void requireRollbackTo11(UpdateEngine engine, Fixture fixture) throws IOException
	{
		Path data = fixture.data();
		requireThat(engine.rollback().result(), "rollback").isEqualTo(Result.ROLLED_BACK);
		LauncherSwap.finish(data);
		requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.1\n");
		requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.1");
		requireThat(Files.readString(data.resolve("1.1/VERSION")), "updater").isEqualTo("1.1\n");
		requireThat(Files.readString(fixture.home.resolve("live/version")), "plugin").isEqualTo("1.1");
	}

	/**
	 * Returns what an installation keeps of a runtime replacement or a previous bundle.
	 *
	 * @param data the installation's updater data directory
	 * @return the names of the staged and replaced runtimes, the swap request and result, and the updater version
	 *         directories other than release 1.2's
	 */
	private static List<String> leftovers(Path data)
	{
		return Stream.of("runtime.next", "runtime.previous", "state/runtime-swap", "state/runtime-swap-result.json",
			"1.0", "1.1").filter(name -> Files.exists(data.resolve(name), NOFOLLOW_LINKS)).toList();
	}

	/**
	 * Creates an engine for the fixture's harness that does not wait for the lock.
	 *
	 * @param data   the installation's updater data directory
	 * @param policy the installation's policy
	 * @param source the release source
	 * @return the engine
	 */
	private static UpdateEngine engine(Path data, TrustPolicy policy, Releases source)
	{
		return new UpdateEngine(data, policy, source, new LiveProvider(), "linux-x64", Clock.systemUTC(),
			interval -> () -> {}, Duration.ZERO);
	}

	/**
	 * Setup installs, and installs again over its own installation, when the data and configuration directories are
	 * reached through symbolic links, and deletes a link inside an older updater version as an entry, leaving what it
	 * points to.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void installsThroughLinkedDirectories() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-linked-")));
			Path realData = Files.createDirectories(fixture.root.resolve("real-data"));
			Path realConfig = Files.createDirectories(fixture.root.resolve("real-config"));
			Files.createSymbolicLink(fixture.root.resolve("xdg-data"), realData);
			Files.createSymbolicLink(fixture.root.resolve("xdg-config"), realConfig);
			fixture.setUp(null);
			Path older = Files.createDirectories(realData.resolve("cat-updater/harness-a/1.1"));
			Path outside = Files.writeString(fixture.root.resolve("outside"), "kept");
			Files.createSymbolicLink(older.resolve("escape"), outside);

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.version(), "version").isEqualTo("1.2");
			requireThat(Files.isDirectory(realData.resolve("cat-updater/harness-a/1.2")), "1.2").isTrue();
			requireThat(Files.exists(older, NOFOLLOW_LINKS), "1.1").isFalse();
			requireThat(Files.isRegularFile(realConfig.resolve("cat-updater/harness-a/policy.json")), "policy").
				isTrue();
			requireThat(Files.readString(outside), "outside").isEqualTo("kept");
		}
	}

	/**
	 * Setup removes its harness from the single updater installation that earlier builds kept directly in the
	 * directory that holds every installation and, when no harness is left in that installation's policy, deletes the
	 * installation and its policy without following a link inside it and without touching another harness's own
	 * installation.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void retiresSharedLayout() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-shared-")));
			Path base = fixture.data().getParent();
			fixture.writeSharedLayout(Map.of("harness-a", fixture.home));
			Path outside = Files.createDirectories(fixture.root.resolve("outside"));
			Files.writeString(outside.resolve("file"), "kept");
			Files.createSymbolicLink(base.resolve("state/link"), outside);
			Files.createDirectories(base.resolve("harness-b"));
			Files.writeString(base.resolve("harness-b/current"), "1.1\n");

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.INSTALLED);
			for (String name : List.of("current", "bin", "1.0", "state"))
				requireThat(Files.exists(base.resolve(name), NOFOLLOW_LINKS), name).isFalse();
			requireThat(Files.exists(fixture.sharedPolicy(), NOFOLLOW_LINKS), "sharedPolicy").isFalse();
			requireThat(Files.readString(outside.resolve("file")), "linkTarget").isEqualTo("kept");
			requireThat(Files.readString(base.resolve("harness-b/current")), "otherInstallation").isEqualTo("1.1\n");
			requireThat(Files.readString(fixture.data().resolve("current")), "current").isEqualTo("1.2\n");
			requireThat(TrustPolicy.read(fixture.policy()).harnesses(), "policy").
				isEqualTo(Map.of("harness-a", fixture.home));
		}
	}

	/**
	 * Setup removes only its own harness from the single updater installation of earlier builds while that
	 * installation's policy still names another harness, keeping the installation for that harness.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void keepsSharedLayoutForOthers() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-shared-other-")));
			Path base = fixture.data().getParent();
			Path otherHome = Files.createDirectories(fixture.root.resolve("other-home"));
			fixture.writeSharedLayout(Map.of("harness-a", fixture.home, "harness-b", otherHome));
			Files.createDirectories(base.resolve("state/harness-b"));
			Files.writeString(base.resolve("state/harness-b/lock"), "");

			Setup.Outcome outcome = fixture.setUp(null);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.INSTALLED);
			requireThat(Files.exists(base.resolve("state/harness-a"), NOFOLLOW_LINKS), "ownState").isFalse();
			requireThat(Files.exists(base.resolve("state/harness-b/lock"), NOFOLLOW_LINKS), "otherState").isTrue();
			requireThat(Files.readString(base.resolve("current")), "sharedCurrent").isEqualTo("1.0\n");
			requireThat(base.resolve("1.0/bin/java"), "sharedImage").isRegularFile();
			requireThat(TrustPolicy.read(fixture.sharedPolicy()).harnesses(), "sharedPolicy").
				isEqualTo(Map.of("harness-b", otherHome));
		}
	}

	/**
	 * Setup with a local plugin tree installs a copy of it, bound to the runtime, also when the release has no key;
	 * the user's tree is not changed.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void installsLocalPluginTree() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-local-")));
			fixture.writeDefaultPolicy(false);
			Path tree = SetupArchive.pluginTree(fixture.root.resolve("plugin-tree"));

			Setup.Outcome outcome = fixture.setUp(tree);

			requireThat(outcome.plugin().result(), "plugin").isEqualTo(Result.INSTALLED);
			requireThat(Files.readString(fixture.home.resolve("live/bin/plugin-info")), "copied").isEqualTo("local");
			requireThat(Files.exists(fixture.home.resolve("live/client/runtime-binding")), "binding").isTrue();
			requireThat(Files.exists(tree.resolve("client/runtime-binding"), NOFOLLOW_LINKS), "sourceBinding").
				isFalse();
		}
	}

	/**
	 * A repeated setup whose permission installation adds no new entry keeps the record of the entries that the earlier
	 * setup added.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void keepsPermissionRecordWhenNothingIsAdded() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-permissions-")));
			fixture.integration.adding(Map.of("settings.json", List.of("entry")));
			fixture.setUp(null);
			Path record = fixture.data().resolve("state/permissions/harness-a.json");
			String first = Files.readString(record);

			fixture.setUp(null);

			requireThat(Files.readString(record), "record").isEqualTo(first);
		}
	}

	/**
	 * Setup removes the updater's own directories from the search path before the harness program is looked up, so
	 * that no updater file can stand in for the harness program.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if setup refuses
	 */
	@Test
	public void filtersUpdaterDirectoriesFromSearchPath() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("setup-path-")));
			Path base = fixture.data().getParent();
			String kept = fixture.root.resolve("tools").toString();
			fixture.environment.put("PATH", String.join(File.pathSeparator, base.resolve("harness-b/bin").toString(),
				kept, fixture.data().resolve("bin").toString()));

			fixture.setUp(null);

			requireThat(fixture.integration.discoveredEnvironment().get("PATH"), "path").isEqualTo(kept);
		}
	}

	/**
	 * Relative XDG values are ignored when explicit setup resolves its destinations.
	 */
	@Test
	public void ignoresRelativeXdgValues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-xdg-"));
			Path image = Files.createDirectories(root.resolve("image"));
			Locations actual = Locations.forSetup(image, Platform.LINUX_X64, Map.of("HOME", root.toString(),
				"XDG_DATA_HOME", "relative-data", "XDG_CONFIG_HOME", "relative-config"), "harness-a");
			requireThat(actual.data(), "data").isEqualTo(root.resolve(".local/share/cat-updater/harness-a"));
			requireThat(actual.policy(), "policy").isEqualTo(root.resolve(".config/cat-updater/harness-a/policy.json"));
		}
	}

	/**
	 * A relative protected policy anchor is refused rather than resolved against the calling project.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*absolute and normalized.*")
	public void refusesRelativePolicyAnchor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-anchor-"));
			Path image = Files.createDirectory(root.resolve("image"));
			Path anchor = image.resolve(Locations.POLICY_ANCHOR);
			Files.createDirectories(anchor.getParent());
			Files.writeString(anchor, "project-policy.json\n");
			Locations.installed(image);
		}
	}

	/**
	 * Complete current-file validation refuses content a first-line-only batch reader cannot distinguish.
	 *
	 * @param content the protected current file's complete contents
	 */
	@Test(dataProvider = "invalidCurrentDataProvider", expectedExceptions = IOException.class)
	public void refusesMalformedCurrent(String content) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-current-"));
			Path image = Files.createDirectory(root.resolve("1.0"));
			Files.writeString(root.resolve("current"), content);
			Locations.validateCurrent(image);
		}
	}

	/**
	 * Supplies full-file boundary cases, including blank lines skipped by batch FOR /F.
	 *
	 * @return the malformed selectors
	 */
	@DataProvider
	public Object[][] invalidCurrentDataProvider()
	{
		return new Object[][]{
			{""}, {"1.0"}, {"1.0\n\n"}, {"1.0\n2.0\n"}, {"1.0\r\n"},
			{"../1.0\n"}, {"1.1\n"}, {"1.0\n" + "x".repeat(128)}
		};
	}

	/**
	 * The exact setup-written selector passes validation against the actual image path.
	 */
	@Test
	public void acceptsExactCurrent() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("setup-valid-current-"));
			Path image = Files.createDirectory(root.resolve("1.0"));
			Files.writeString(root.resolve("current"), "1.0\n");
			Locations.validateCurrent(image);
		}
	}

	/**
	 * Returns an atomic replacer for isolated temporary files, which never need a retry pause.
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
	 * An extracted updater archive, the user's environment and one harness home.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path archive;
		private final Path home;
		private final Path cwd;
		private final Map<String, String> environment = new HashMap<>();
		private final RecordingIntegration integration = new RecordingIntegration();
		private final HarnessCommand command;
		private final Releases source = new Releases("1.2", "");
		private DirectoryMover mover = DirectoryMover.ATOMIC;

		/**
		 * Creates the archive and the user's directories.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the fixture cannot be created
		 * @throws TrustException if the release's policy is invalid
		 */
		private Fixture(Path directory) throws IOException, TrustException
		{
			this.root = directory.toRealPath();
			this.archive = SetupArchive.create(root.resolve("archive"), source.policy(home(root)).keys());
			this.home = Files.createDirectories(home(root));
			this.cwd = Files.createDirectories(root.resolve("project"));
			Path user = Files.createDirectories(root.resolve("user"));
			environment.put("HOME", user.toString());
			environment.put("XDG_DATA_HOME", root.resolve("xdg-data").toString());
			environment.put("XDG_CONFIG_HOME", root.resolve("xdg-config").toString());
			this.command = new HarnessCommand(List.of(root.resolve("tools/harness-a").toString()),
				Map.of("HOME", user.toString()));
			integration.discovering(command);
		}

		/**
		 * Returns the harness home.
		 *
		 * @param root the test directory
		 * @return the home
		 */
		private static Path home(Path root)
		{
			return root.resolve("harness-home");
		}

		/**
		 * Replaces the archive's default policy.
		 *
		 * @param keyed true to trust the fixture release's key, false for a development build without a key
		 * @throws IOException if the policy cannot be written
		 */
		private void writeDefaultPolicy(boolean keyed) throws IOException
		{
			List<TrustedKey> keys = List.of();
			if (keyed)
			{
				try
				{
					keys = source.policy(home).keys();
				}
				catch (TrustException e)
				{
					throw new AssertionError(e);
				}
			}
			SetupArchive.writeDefaultPolicy(archive, keys);
		}

		/**
		 * Returns the installation's data directory.
		 *
		 * @return the directory
		 */
		private Path data()
		{
			return root.resolve("xdg-data/cat-updater/harness-a");
		}

		/**
		 * Returns the installation's policy file.
		 *
		 * @return the file
		 */
		private Path policy()
		{
			return root.resolve("xdg-config/cat-updater/harness-a/policy.json");
		}

		/**
		 * Returns the policy file of the single updater installation that earlier builds kept.
		 *
		 * @return the file
		 */
		private Path sharedPolicy()
		{
			return root.resolve("xdg-config/cat-updater/policy.json");
		}

		/**
		 * Creates the single updater installation that earlier builds kept directly in the directory that holds every
		 * installation: its current selection, stable launcher, updater image with its own Java, the state of its first
		 * harness and its policy.
		 *
		 * @param harnesses the harnesses that the installation's policy records, mapped to their homes
		 * @throws IOException    if the installation cannot be written
		 * @throws TrustException if the policy is invalid
		 */
		private void writeSharedLayout(Map<String, Path> harnesses) throws IOException, TrustException
		{
			Path base = data().getParent();
			Files.createDirectories(base.resolve("bin"));
			Files.writeString(base.resolve("bin/cat-updater"), "#!/bin/sh\n");
			Files.writeString(base.resolve("current"), "1.0\n");
			Files.createDirectories(base.resolve("1.0/bin"));
			Files.writeString(base.resolve("1.0/bin/java"), "java");
			Files.createDirectories(base.resolve("state/harness-a"));
			Files.writeString(base.resolve("state/harness-a/lock"), "");
			TrustPolicy release = source.policy(home);
			Files.createDirectories(sharedPolicy().getParent());
			TrustPolicy.of(release.keys(), release.releaseBaseUrl(), release.minimum(), release.maximumMajor(),
				harnesses).write(sharedPolicy(), replacer());
		}

		/**
		 * Runs setup from the archive.
		 *
		 * @param plugin the local plugin tree, or null for the signed release
		 * @return the outcome
		 * @throws IOException    if setup fails
		 * @throws TrustException if setup refuses
		 */
		private Setup.Outcome setUp(Path plugin) throws IOException, TrustException
		{
			Locations locations = Locations.forSetup(archive.resolve("1.2"), Platform.LINUX_X64, environment,
				"harness-a");
			Setup setup = new Setup(locations, Platform.LINUX_X64, replacer(), mover,
				(recorded, recordedHome) -> RecordingIntegration.cli(), Clock.systemUTC(), interval -> () -> {});
			return setup.setUp("harness-a", home, plugin, cwd, environment, new LiveProvider(), integration, source);
		}
	}
}
