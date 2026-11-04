package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.RuntimeSwap;
import com.holdmyspot.cat.common.updater.engine.RuntimeSwap.Operation;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises updates that move an installation's whole bundle, its Java runtime, updater and harness plugin, to another
 * release, and the recovery of a runtime replacement that the stable launcher did not finish.
 */
public final class BundleUpdateTest
{
	/**
	 * An update activates the plugin and the updater of the release and stages its runtime for the launcher, leaving the
	 * running runtime and the {@code current} selection for the launcher to change after the JVM exits.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateStagesBundleForLauncher() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-update-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Path runtime = Releases.installation(data, "1.0");

			UpdateResult result = engine(data, home, new Releases("1.1", "")).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of("1.1"));
			requireThat(result.previousVersion(), "previousVersion").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve("live/client/runtime-binding")), "binding").
				isEqualTo(runtime.toAbsolutePath().normalize() + "\n");
			requireThat(Files.readString(data.resolve("1.1/VERSION")), "updater").isEqualTo("1.1\n");
			requireThat(Files.readString(data.resolve(Releases.anchor("1.1"))), "anchor").
				isEqualTo(Files.readString(data.resolve(Releases.anchor("1.0"))));
			requireThat(Files.readString(data.resolve("runtime.next/release")), "stagedRuntime").
				isEqualTo("runtime 1.1");
			requireThat(Files.readString(data.resolve("runtime/release")), "runningRuntime").isEqualTo("runtime 1.0");
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.0\n");
			requireThat(engine(data, home, new Releases("1.1", "")).pendingSwap(), "pendingSwap").
				isEqualTo(Optional.of(new RuntimeSwap(Operation.UPDATE, "1.0", "1.1")));
		}
	}

	/**
	 * An update installs the release's stable launcher in place of the running one, so that a later launcher protocol
	 * arrives with the release, and leaves nothing else beside it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void updateInstallsStableLauncher() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-launcher-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");

			UpdateResult result = engine(data, home, new Releases("1.1", "")).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			Path launcher = data.resolve("bin/cat-updater");
			requireThat(Files.readString(launcher), "launcher").isEqualTo(Releases.stableLauncher("1.1"));
			if (PortableFiles.hasModes(launcher))
			{
				requireThat(Files.getPosixFilePermissions(launcher), "mode").
					isEqualTo(PosixFilePermissions.fromString("rwx------"));
			}
			try (Stream<Path> files = Files.list(data.resolve("bin")))
			{
				requireThat(files.map(file -> file.getFileName().toString()).toList(), "bin").
					isEqualTo(List.of("cat-updater"));
			}
		}
	}

	/**
	 * Once the launcher finished an update, the replaced runtime, updater and plugin stay as the previous bundle, and
	 * the next update replaces that previous bundle with the one it replaces.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void nextUpdateReplacesPreviousBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-previous-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases first = new Releases("1.1", "");
			requireThat(engine(data, home, first).update("v1.1", new RecordingIntegration().registration()).
				result(), "firstUpdate").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);
			UpdateResult status = engine(data, home, first).status();
			requireThat(status.version(), "statusVersion").isEqualTo(Optional.of("1.1"));
			requireThat(status.previousVersion(), "statusPrevious").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(data.resolve("runtime.previous/release")), "previousRuntime").
				isEqualTo("runtime 1.0");

			Releases second = new Releases("1.2", "");
			requireThat(engine(data, home, second).update("v1.2", new RecordingIntegration().registration()).
				result(), "secondUpdate").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);

			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.2\n");
			requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.2");
			requireThat(Files.readString(data.resolve("runtime.previous/release")), "replacedRuntime").
				isEqualTo("runtime 1.1");
			requireThat(Files.exists(data.resolve("1.0")), "olderUpdater").isFalse();
			requireThat(Files.isDirectory(data.resolve("1.1")), "previousUpdater").isTrue();
			requireThat(engine(data, home, second).status().previousVersion(), "previous").
				isEqualTo(Optional.of("1.1"));
		}
	}

	/**
	 * An update to the release that the whole bundle already has registers the plugin again and changes no file.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void sameVersionChangesNothing() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-same-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.1");
			Releases.installation(data, "1.1");
			RecordingIntegration integration = new RecordingIntegration();
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, new Releases("1.1", "")).update("v1.1",
				integration.registration());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("register"));
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
		}
	}

	/**
	 * An update to a release below the highest version that the harness has had is refused without changes.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void refusesVersionBelowHighest() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-highest-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Files.createDirectories(data.resolve("state/harness-a"));
			Files.writeString(data.resolve("state/harness-a/highest-version"), "1.2\n");
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, new Releases("1.1", "")).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(result.message(), "message").contains("1.2");
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
		}
	}

	/**
	 * Supplies releases whose updater archive is missing, unauthenticated or without stable launchers, or whose runtime
	 * cannot run the release's updater or plugin.
	 *
	 * @return the release defects
	 */
	@DataProvider
	public Object[][] refusedBundles()
	{
		return new Object[][]{{"noUpdater"}, {"updaterDigest"}, {"updaterRequirement"}, {"requirement"},
			{"noStableLauncher"}};
	}

	/**
	 * An update whose updater archive is missing, does not verify or ships no stable launcher, or whose new runtime does
	 * not satisfy the new updater's or plugin's requirement, is refused without changes.
	 *
	 * @param defect the release defect
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "refusedBundles")
	public void refusesUnusableBundle(String defect) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-refused-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, new Releases("1.1", defect)).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
		}
	}

	/**
	 * The plugin's requirement is checked against the runtime of the release, not the runtime being replaced: a
	 * plugin that needs a newer Java than the installed runtime is accepted with the newer runtime it comes with.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void checksPluginAgainstNewRuntime() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-newer-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");

			UpdateResult result = engine(data, home, new Releases("1.1", "newerRuntime")).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
		}
	}

	/**
	 * A failed registration restores the plugin and discards the staged runtime and updater, changing nothing; the
	 * plugin stays registered, because the restored files are where the harness registered them.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void failedActivationDiscardsBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-failed-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Map<String, String> before = Releases.snapshot(root);

			RecordingIntegration integration = new RecordingIntegration().fail("register");

			UpdateResult result = engine(data, home, new Releases("1.1", "")).update("v1.1",
				integration.registration());

			requireThat(result.result(), "result").isEqualTo(Result.RESTORED);
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
			requireThat(integration.calls(), "calls").isEqualTo(List.of("register"));
		}
	}

	/**
	 * An update whose runtime replacement the launcher abandoned is undone by the next operation before it does
	 * anything else: the plugin and the stable launcher return to the version that matches the running runtime and
	 * updater, and the staged bundle is deleted.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void abandonedUpdateIsUndone() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-undone-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().registration()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.abandon(data);
			Releases installed = new Releases("1.0", "");

			UpdateResult result = engine(data, home, installed).update("v1.0",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.0");
			requireThat(Files.readString(data.resolve("bin/cat-updater")), "launcher").
				isEqualTo(Releases.stableLauncher("1.0"));
			requireThat(Files.exists(data.resolve("runtime.next")), "stagedRuntime").isFalse();
			requireThat(Files.exists(data.resolve("1.1")), "stagedUpdater").isFalse();
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.0\n");
			requireThat(engine(data, home, installed).pendingSwap(), "pendingSwap").isEqualTo(Optional.empty());
		}
	}

	/**
	 * After the stable launcher reported {@code not_swapped} for an update, an {@code install} that the agent runs next
	 * does not finish the update, even though the staged runtime is still in place: it undoes the update, so the
	 * installation runs the previous release's plugin, runtime and updater again.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void installUndoesNotSwappedUpdate() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-not-swapped-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().registration()).
				result(), "update").isEqualTo(Result.UPDATED);
			// The launcher that reported not_swapped put the installed runtime back, kept the request and the staged
			// runtime, and exited
			LauncherSwap.abandon(data);
			// Install asks for the release that current selects
			Releases installed = new Releases("1.0", "");

			UpdateResult result = engine(data, home, installed).install(null,
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(result.version(), "version").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.0\n");
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.0");
			requireThat(Files.exists(data.resolve("runtime.next")), "stagedRuntime").isFalse();
			requireThat(engine(data, home, installed).pendingSwap(), "pendingSwap").isEqualTo(Optional.empty());
		}
	}

	/**
	 * A launcher that renamed the runtimes but stopped before it rewrote {@code current} leaves the new runtime
	 * running; the next operation completes the update by selecting the new updater.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void renamedSwapIsCompleted() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-completed-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().registration()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.renameOnly(data);
			LauncherSwap.abandon(data);

			UpdateResult result = engine(data, home, release).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.UPDATED);
			requireThat(result.version(), "version").isEqualTo(Optional.of("1.1"));
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.1\n");
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
			requireThat(engine(data, home, release).pendingSwap(), "pendingSwap").isEqualTo(Optional.empty());
			requireThat(engine(data, home, release).status().previousVersion(), "previous").
				isEqualTo(Optional.of("1.0"));
		}
	}

	/**
	 * While the launcher that is to finish a runtime replacement still runs, another operation reports the lock and
	 * changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void runningLauncherLocks() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-running-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().registration()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.claim(data);
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, release).update("v1.1",
				new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.LOCKED);
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
		}
	}

	/**
	 * Creates a harness home whose plugin has a version.
	 *
	 * @param root    the test directory
	 * @param version the plugin version
	 * @return the harness home
	 * @throws IOException if the plugin cannot be written
	 */
	static Path plugin(Path root, String version) throws IOException
	{
		Path home = Files.createDirectories(root.resolve("home/live")).getParent();
		Files.writeString(home.resolve("live/version"), version);
		return home;
	}

	/**
	 * Creates an engine for the fixture's harness that does not wait for the lock and runs as a user without
	 * administrative rights.
	 *
	 * @param data   the installation's updater data directory
	 * @param home   the harness home
	 * @param source the release source, whose key the policy trusts
	 * @return the engine
	 * @throws TrustException if the test policy is invalid
	 */
	static UpdateEngine engine(Path data, Path home, Releases source) throws TrustException
	{
		// GitHub's Windows runners run as administrators, which an installation never trains a startup cache for; these
		// tests concern what the operations train for a user without those rights
		return new UpdateEngine(data, source.policy(home), source, new LiveProvider(), "linux-x64",
			Clock.systemUTC(), interval -> () -> {}, Duration.ZERO, _ -> false);
	}
}
