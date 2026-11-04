package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.engine.UpdaterState;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Exercises activation and reversal of provider-planned installation changes through the public engine.
 */
public final class ActivationTest
{
	/**
	 * Creates the tests.
	 */
	public ActivationTest()
	{
	}

	/**
	 * An update replaces its directory, adds a version copy and replaces only manifest-owned files. Rollback restores
	 * all three changes and the exact earlier migration marker while preserving unrelated files.
	 *
	 * @throws IOException if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void ownedFilesAndAddedTreeRollBack() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory("activation-")).toAbsolutePath().normalize();
			Files.createDirectory(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.createDirectory(home.resolve("agents"));
			Files.writeString(home.resolve("agents/cat-old.toml"), "old agent");
			Files.writeString(home.resolve("agents/user.toml"), "user agent");
			Files.writeString(home.resolve("agents/ownership"), "cat-old.toml\n");
			Files.writeString(home.resolve("marker"), "0.9\n");
			Releases source = new Releases("1.1", "");
			List<ActivationStep> plan = List.of(new ReplaceDirectory(home.resolve("live"), home),
				new AddDirectory(home.resolve("cache/1.1"), home), ownedStep(home));
			Releases.runtime(home.resolve("data"));
			UpdateEngine engine = new UpdateEngine(home.resolve("data"), source.policy(home), source,
				new Provider(plan), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult update = engine.update("v1.1", new RecordingIntegration().registration());

			requireThat(update.result(), "update").isEqualTo(Result.UPDATED);
			requireThat(Files.readString(home.resolve("cache/1.1/version")), "addedVersion").isEqualTo("1.1");
			requireThat(Files.readString(home.resolve("agents/cat-reviewer.toml")), "newAgent").isEqualTo("name = 'new'");
			requireThat(Files.exists(home.resolve("agents/cat-old.toml")), "oldAgent").isFalse();
			requireThat(Files.readString(home.resolve("agents/ownership")), "newOwnership").
				isEqualTo("cat-reviewer.toml\n");

			UpdateResult rollback = engine.rollback();

			requireThat(rollback.result(), "rollback").isEqualTo(Result.ROLLED_BACK);
			requireThat(Files.readString(home.resolve("live/version")), "restoredVersion").isEqualTo("1.0");
			requireThat(Files.exists(home.resolve("cache/1.1")), "addedTree").isFalse();
			requireThat(Files.readString(home.resolve("agents/cat-old.toml")), "oldAgent").isEqualTo("old agent");
			requireThat(Files.exists(home.resolve("agents/cat-reviewer.toml")), "newAgent").isFalse();
			requireThat(Files.readString(home.resolve("agents/ownership")), "restoredOwnership").
				isEqualTo("cat-old.toml\n");
			requireThat(Files.readString(home.resolve("agents/user.toml")), "userAgent").isEqualTo("user agent");
			requireThat(Files.readString(home.resolve("marker")), "migrationMarker").isEqualTo("0.9\n");
		}
	}

	/**
	 * An unowned destination collision refuses the complete plan before the first directory replacement.
	 *
	 * @throws IOException if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void unownedCollisionPreventsAllChanges() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory("activation-")).toAbsolutePath().normalize();
			Files.createDirectory(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.createDirectory(home.resolve("agents"));
			Files.writeString(home.resolve("agents/cat-reviewer.toml"), "unowned");
			Releases source = new Releases("1.1", "");
			Releases.runtime(home.resolve("data"));
			UpdateEngine engine = new UpdateEngine(home.resolve("data"), source.policy(home), source,
				new Provider(List.of(new ReplaceDirectory(home.resolve("live"), home), ownedStep(home))),
				"linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.update("v1.1", new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("agents/cat-reviewer.toml")), "unownedContent").isEqualTo("unowned");
			requireThat(Files.exists(home.resolve(".cat-previous-live")), "previousSlot").isFalse();
			requireThat(Files.exists(UpdaterState.of(home.resolve("data"), "harness-a").activation()), "journal").isFalse();
		}
	}

	/**
	 * Supplies names that cannot identify one prefixed owned file.
	 *
	 * @return hostile manifest entries
	 */
	@DataProvider
	public Object[][] hostileNames()
	{
		return new Object[][]{{"../x"}, {"cat-a/b"}, {"foreign.toml"}};
	}

	/**
	 * A hostile ownership manifest is refused before any installation path changes.
	 *
	 * @param name the hostile owned filename
	 * @throws IOException if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test(dataProvider = "hostileNames")
	public void hostileOwnershipIsRefused(String name) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory("activation-")).toAbsolutePath().normalize();
			Files.createDirectory(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.createDirectory(home.resolve("agents"));
			Files.writeString(home.resolve("agents/ownership"), name + "\n");
			Releases source = new Releases("1.1", "");
			Releases.runtime(home.resolve("data"));
			UpdateEngine engine = new UpdateEngine(home.resolve("data"), source.policy(home), source,
				new Provider(List.of(new ReplaceDirectory(home.resolve("live"), home), ownedStep(home))),
				"linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.update("v1.1", new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.readString(home.resolve("live/version")), "liveVersion").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("agents/ownership")), "ownership").isEqualTo(name + "\n");
		}
	}

	/**
	 * A blocked parent of the second target makes its rename fail after the first replacement. The engine reverses the
	 * completed first step and keeps the unrelated blocking file intact.
	 *
	 * @throws IOException if fixture creation or inspection fails
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void secondStepFailureRestoresFirst() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory("activation-")).toAbsolutePath().normalize();
			Files.createDirectory(home.resolve("live"));
			Files.writeString(home.resolve("live/version"), "1.0");
			Files.writeString(home.resolve("blocked"), "keep");
			Files.writeString(home.resolve("marker"), "1.0\n");
			Releases source = new Releases("1.1", "");
			List<ActivationStep> plan = List.of(new ReplaceDirectory(home.resolve("live"), home),
				new AddDirectory(home.resolve("blocked/new"), home));
			Releases.runtime(home.resolve("data"));
			UpdateEngine engine = new UpdateEngine(home.resolve("data"), source.policy(home), source,
				new Provider(plan), "linux-x64", Clock.systemUTC(), interval -> () -> {});

			UpdateResult result = engine.update("v1.1", new RecordingIntegration().registration());

			requireThat(result.result(), "result").isEqualTo(Result.RESTORED);
			requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(5);
			requireThat(Files.readString(home.resolve("live/version")), "restoredVersion").isEqualTo("1.0");
			requireThat(Files.readString(home.resolve("blocked")), "unrelatedFile").isEqualTo("keep");
			requireThat(Files.readString(home.resolve("marker")), "migrationMarker").isEqualTo("1.0\n");
			requireThat(Files.exists(UpdaterState.of(home.resolve("data"), "harness-a").activation()), "journal").isFalse();
		}
	}

	/**
	 * Defines ownership of the fake provider's agent directory.
	 *
	 * @param home the isolated harness home
	 * @return the owned-file step
	 */
	private static ReplaceOwnedFiles ownedStep(Path home)
	{
		return new ReplaceOwnedFiles(home.resolve("agents"), home.resolve("agents/ownership"), "cat-", "agents",
			"*.toml");
	}

	/**
	 * Supplies a fixed supported plan over the test-owned home; production performs every filesystem mutation.
	 *
	 * @param steps the planned activation steps
	 */
	private record Provider(List<ActivationStep> steps) implements PluginActivation
	{
		/**
		 * Keeps a stable plan for the duration of one engine operation.
		 *
		 * @param steps the planned changes
		 */
		private Provider
		{
			steps = List.copyOf(steps);
		}

		@Override
		public String harness()
		{
			return "harness-a";
		}

		@Override
		public String pluginManifest()
		{
			return ".harness-a/plugin.json";
		}

		@Override
		public InstalledState installed(Path home) throws IOException
		{
			return new InstalledState(Optional.of(Files.readString(home.resolve("live/version"))), true);
		}

		@Override
		public List<ActivationStep> plan(Path home, String newVersion)
		{
			return steps;
		}

		@Override
		public Path migrationVersionFile(Path home)
		{
			return home.resolve("marker");
		}

		@Override
		public List<ActivationStep> installPlan(Path home, String newVersion)
		{
			return steps;
		}

		@Override
		public List<Path> removalPaths(Path home)
		{
			return List.of(home.resolve("live"));
		}

		@Override
		public Path dataDirectory(Path home)
		{
			return home.resolve("plugin-data");
		}
	}
}
