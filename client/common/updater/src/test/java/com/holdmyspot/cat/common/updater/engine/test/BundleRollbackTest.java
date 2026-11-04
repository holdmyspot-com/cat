package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.engine.test.BundleUpdateTest.engine;
import static com.holdmyspot.cat.common.updater.engine.test.BundleUpdateTest.plugin;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.RuntimeSwap;
import com.holdmyspot.cat.common.updater.engine.RuntimeSwap.Operation;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Exercises the rollback of an installation's whole bundle to the one that its last update replaced.
 */
public final class BundleRollbackTest
{
	/**
	 * Rollback restores the previous plugin and stable launcher and requests the previous runtime and updater from the
	 * launcher; afterwards no previous bundle remains.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void rollbackRestoresPreviousBundle() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-rollback-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().session()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);

			UpdateResult result = engine(data, home, release).rollback();

			requireThat(result.result(), "result").isEqualTo(Result.ROLLED_BACK);
			requireThat(result.version(), "version").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.0");
			requireThat(Files.readString(data.resolve("bin/cat-updater")), "launcher").
				isEqualTo(Releases.stableLauncher("1.0"));
			requireThat(engine(data, home, release).pendingSwap(), "pendingSwap").
				isEqualTo(Optional.of(new RuntimeSwap(Operation.ROLLBACK, "1.1", "1.0")));
			LauncherSwap.finish(data);
			requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.0");
			requireThat(Files.readString(data.resolve("current")), "current").isEqualTo("1.0\n");
			requireThat(engine(data, home, release).status().previousVersion(), "previous").
				isEqualTo(Optional.empty());
			Map<String, String> before = Releases.snapshot(root);
			requireThat(engine(data, home, release).rollback().result(), "secondRollback").
				isEqualTo(Result.REFUSED);
			requireThat(Releases.snapshot(root), "afterSecondRollback").isEqualTo(before);
		}
	}

	/**
	 * Without a previous bundle, rollback refuses and changes nothing.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void rollbackWithoutPreviousRefuses() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-no-previous-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Map<String, String> before = Releases.snapshot(root);

			UpdateResult result = engine(data, home, new Releases("1.0", "")).rollback();

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Releases.snapshot(root), "after").isEqualTo(before);
		}
	}

	/**
	 * A rollback whose runtime replacement the launcher abandoned after the plugin was restored is completed by the
	 * next operation, which installs the previous release's stable launcher and requests the replacement from the
	 * launcher again.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void abandonedRollbackIsRequestedAgain() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-rollback-again-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().session()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);
			requireThat(engine(data, home, release).rollback().result(), "rollback").isEqualTo(Result.ROLLED_BACK);
			LauncherSwap.abandon(data);
			// A rollback that stopped after it restored the plugin leaves the replaced release's launcher in place
			Files.writeString(data.resolve("bin/cat-updater"), Releases.stableLauncher("1.1"));

			UpdateResult result = engine(data, home, release).update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.ROLLED_BACK);
			requireThat(result.version(), "version").isEqualTo(Optional.of("1.0"));
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.0");
			requireThat(Files.readString(data.resolve("bin/cat-updater")), "launcher").
				isEqualTo(Releases.stableLauncher("1.0"));
			requireThat(engine(data, home, release).pendingSwap(), "pendingSwap").
				isEqualTo(Optional.of(new RuntimeSwap(Operation.ROLLBACK, "1.1", "1.0")));
			LauncherSwap.finish(data);
			requireThat(Files.readString(data.resolve("runtime/release")), "runtime").isEqualTo("runtime 1.0");
		}
	}

	/**
	 * A rollback request that an updater left before it restored the previous plugin is cancelled by the next
	 * operation, which keeps the previous bundle.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void unstartedRollbackIsCancelled() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("bundle-rollback-cancel-"));
			Path data = root.resolve("data");
			Path home = plugin(root, "1.0");
			Releases.installation(data, "1.0");
			Releases release = new Releases("1.1", "");
			requireThat(engine(data, home, release).update("v1.1", new RecordingIntegration().session()).
				result(), "update").isEqualTo(Result.UPDATED);
			LauncherSwap.finish(data);
			LauncherSwap.requestRollback(data, "1.1", "1.0");

			UpdateResult result = engine(data, home, release).update("v1.1",
				new RecordingIntegration().session());

			requireThat(result.result(), "result").isEqualTo(Result.UP_TO_DATE);
			requireThat(Files.readString(home.resolve("live/version")), "plugin").isEqualTo("1.1");
			requireThat(engine(data, home, release).pendingSwap(), "pendingSwap").isEqualTo(Optional.empty());
			requireThat(engine(data, home, release).status().previousVersion(), "previous").
				isEqualTo(Optional.of("1.0"));
		}
	}
}
