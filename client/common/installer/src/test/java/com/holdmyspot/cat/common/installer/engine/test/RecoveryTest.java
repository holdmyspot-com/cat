package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.AddDirectory;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises interruption recovery through the public installer operations with isolated installation directories.
 */
public final class RecoveryTest
{
	/**
	 * Creates the test.
	 */
	public RecoveryTest()
	{
	}

	/**
	 * The next operation recovers an interrupted replacement without normal planning, which requires its absent live
	 * tree, and restores the exact earlier migration marker before it goes on.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 * @throws TrustException if the test policy cannot be created
	 */
	@Test
	public void interruptedRenameIsRestored() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("recovery-")).toAbsolutePath().normalize();
			Fixture fixture = new Fixture(root);
			fixture.record(fixture.live(), "staged", false, true);
			Files.writeString(fixture.state().previousFiles().resolve("recovery/migration"), "0.9\n");
			Files.writeString(fixture.marker(), "1.1\n");

			UpdateResult result = fixture.operate();

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.readString(fixture.live().resolve("version")), "restoredVersion").isEqualTo("1.0");
			requireThat(Files.readString(fixture.marker()), "migrationMarker").isEqualTo("0.9\n");
			requireThat(Files.exists(fixture.state().activation()), "pendingJournal").isFalse();
		}
	}

	/**
	 * Recovery rejects a journal that redirects the provider's live path and preserves both the unrelated directory
	 * and the pending journal for diagnosis.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 * @throws TrustException if the test policy cannot be created
	 */
	@Test
	public void redirectedJournalChangesNothing() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("recovery-")).toAbsolutePath().normalize();
			Fixture fixture = new Fixture(root);
			Path unrelated = Files.createDirectory(root.resolve("unrelated"));
			Files.writeString(unrelated.resolve("keep"), "protected");
			fixture.record(unrelated, "swapped", false, false);
			byte[] before = Files.readAllBytes(fixture.state().activation());

			UpdateResult result = fixture.operate();

			requireThat(result.result(), "result").isEqualTo(Result.NOT_RESTORED);
			requireThat(result.result().exitStatus(), "exitStatus").isEqualTo(6);
			requireThat(Files.readString(unrelated.resolve("keep")), "protectedContent").isEqualTo("protected");
			requireThat(Files.readAllBytes(fixture.state().activation()), "journal").isEqualTo(before);
			requireThat(Files.readString(fixture.previous().resolve("version")), "previousContent").isEqualTo("1.0");
		}
	}

	/**
	 * An interrupted undo of the last successful activation, as an update whose runtime replacement the stable
	 * launcher abandoned leaves it, and as an earlier build's rollback left it, is finished by the next operation from
	 * its recorded plan despite the active version changing normal planning. It reverses the directory replacement,
	 * restores an absent marker as absent and consumes the activation record.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 * @throws TrustException if the test policy cannot be created
	 */
	@Test
	public void interruptedUndoConsumesLastActivation() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("recovery-")).toAbsolutePath().normalize();
			Fixture fixture = new Fixture(root);
			fixture.record(fixture.live(), "done", true, false);
			Files.copy(fixture.state().lastActivation(), fixture.state().activation());
			fixture.undoing();
			Files.createDirectory(fixture.live());
			Files.writeString(fixture.live().resolve("version"), "1.1");
			Files.writeString(fixture.marker(), "1.1\n");

			UpdateResult result = fixture.operate();

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.readString(fixture.live().resolve("version")), "liveVersion").isEqualTo("1.0");
			requireThat(Files.exists(fixture.marker()), "migrationMarker").isFalse();
			requireThat(Files.exists(fixture.state().lastActivation()), "lastActivation").isFalse();
			requireThat(Files.exists(fixture.state().activation()), "pendingJournal").isFalse();
		}
	}

	/**
	 * Recovery retains its journal and before-image root when the old installation is missing.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 * @throws TrustException if the test policy cannot be created
	 */
	@Test
	public void missingBeforeImageRetainsRecoveryState() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("recovery-")).toAbsolutePath().normalize();
			Fixture fixture = new Fixture(root);
			fixture.record(fixture.live(), "swapped", false, false);
			Files.delete(fixture.previous().resolve("version"));
			Files.delete(fixture.previous());

			UpdateResult result = fixture.operate();

			requireThat(result.result(), "result").isEqualTo(Result.NOT_RESTORED);
			requireThat(result.consistent(), "consistent").isFalse();
			requireThat(result.paths().isEmpty(), "missingPaths").isFalse();
			requireThat(Files.exists(fixture.state().activation()), "pendingJournal").isTrue();
			requireThat(Files.exists(fixture.state().previousFiles().resolve("recovery")), "beforeImages").isTrue();
		}
	}

	/**
	 * Recovery reverses a completed second rename when the stopped installer still recorded the swapped state.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 * @throws TrustException if the test policy cannot be created
	 */
	@Test
	public void completedSecondRenameIsRestored() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("recovery-")).toAbsolutePath().normalize();
			Fixture fixture = new Fixture(root);
			fixture.record(fixture.live(), "swapped", false, false);
			Files.createDirectory(fixture.live());
			Files.writeString(fixture.live().resolve("version"), "1.1");

			UpdateResult result = fixture.operate();

			requireThat(result.result(), "result").isEqualTo(Result.REFUSED);
			requireThat(Files.readString(fixture.live().resolve("version")), "restoredVersion").isEqualTo("1.0");
			requireThat(Files.exists(fixture.state().activation()), "pendingJournal").isFalse();
		}
	}

	/**
	 * Owns a fake provider whose documented plan authorizes one isolated directory replacement. The source refuses all
	 * access because recovery must use retained before-images without downloading a release. The installation records
	 * no installer version, so that an install refuses once recovery has run.
	 *
	 * @param root the test-owned installation root
	 */
	private record Fixture(Path root)
	{
		/**
		 * Creates an isolated installation description.
		 *
		 * @param root the absolute test-owned root
		 */
		private Fixture
		{
			requireThat(root, "root").isAbsolute();
		}

		/**
		 * Locates the directory that the fake provider loads.
		 *
		 * @return the live path
		 */
		private Path live()
		{
			return root.resolve("live");
		}

		/**
		 * Locates the retained old installation.
		 *
		 * @return the previous path
		 */
		private Path previous()
		{
			return root.resolve(".cat-previous-live");
		}

		/**
		 * Locates the migration marker.
		 *
		 * @return the marker path
		 */
		private Path marker()
		{
			return root.resolve("marker");
		}

		/**
		 * Locates the isolated installer state.
		 *
		 * @return the state value
		 */
		private InstallerState state()
		{
			return InstallerState.of(root.resolve("data"), "harness-a");
		}

		/**
		 * Writes the retained journal and old directory that a stopped installer would leave at the selected transition.
		 *
		 * @param recordedLive     the live destination recorded by the stopped operation
		 * @param progress         its forward state
		 * @param successful       indicates whether the journal is the last successful activation
		 * @param migrationExisted indicates whether the old marker had content
		 * @throws IOException if writing the fixture fails
		 */
		private void record(Path recordedLive, String progress, boolean successful, boolean migrationExisted)
			throws IOException
		{
			Files.createDirectories(previous());
			Files.writeString(previous().resolve("version"), "1.0");
			InstallerState state = state();
			Files.createDirectories(state.previousFiles().resolve("recovery"));
			JsonMapper mapper = JsonMapper.builder().build();
			ObjectNode journal = mapper.createObjectNode();
			journal.put("format_version", 1);
			journal.put("previous_version", "1.0");
			journal.put("new_version", "1.1");
			journal.put("migration_file", marker().toString());
			journal.put("backup_root", state.previousFiles().resolve("recovery").toString());
			journal.put("migration_existed", migrationExisted);
			journal.put("prepared", true);
			journal.put("rollback", false);
			ArrayNode entries = journal.putArray("entries");
			ObjectNode entry = entries.addObject();
			entry.put("kind", "replace_directory");
			entry.put("live", recordedLive.toString());
			entry.put("staging", root.resolve(".cat-staging-recovery").toString());
			entry.put("previous", previous().toString());
			entry.put("older", root.resolve(".cat-older-recovery").toString());
			entry.put("discard", root.resolve(".cat-discard-recovery").toString());
			entry.put("state", progress);
			entry.put("undo", "live");
			entry.put("had_previous", false);
			entry.putArray("files");
			Path destination;
			if (successful)
				destination = state.lastActivation();
			else
				destination = state.activation();
			Files.write(destination, mapper.writeValueAsBytes(journal));
		}

		/**
		 * Marks the pending journal as an undo of the last successful activation, as the installer records it before it
		 * starts undoing.
		 *
		 * @throws IOException if the journal cannot be rewritten
		 */
		private void undoing() throws IOException
		{
			JsonMapper mapper = JsonMapper.builder().build();
			ObjectNode journal = (ObjectNode) mapper.readTree(Files.readAllBytes(state().activation()));
			journal.put("rollback", true);
			Files.write(state().activation(), mapper.writeValueAsBytes(journal));
		}

		/**
		 * Runs an operation, which recovers an interrupted activation before it does anything else.
		 *
		 * @return the outcome
		 * @throws TrustException if the policy cannot be created
		 */
		private UpdateResult operate() throws TrustException
		{
			return engine().install(null, new RecordingIntegration().session());
		}

		/**
		 * Constructs the production coordinator over the fake provider's isolated home.
		 *
		 * @return the engine
		 * @throws TrustException if the policy cannot be created
		 */
		private UpdateEngine engine() throws TrustException
		{
			TrustPolicy policy = TrustPolicy.of(List.of(), URI.create("https://example.invalid/releases/download"),
				ReleaseVersion.parse("1.0"), 1, Map.of("harness-a", root));
			PluginActivation provider = new PluginActivation()
			{
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
					if (!Files.exists(live().resolve("version")))
						return new InstalledState(Optional.empty(), false);
					return new InstalledState(Optional.of(Files.readString(live().resolve("version"), UTF_8)), true);
				}

				@Override
				public List<ActivationStep> plan(Path home, String newVersion) throws IOException
				{
					if (!Files.isDirectory(live()))
						throw new IOException("Normal planning requires the live installation");
					String observed = Files.readString(live().resolve("version"));
					if (observed.equals(newVersion))
						return List.of(new AddDirectory(live(), root));
					return List.of(new ReplaceDirectory(live(), root));
				}

				@Override
				public List<ActivationStep> recoveryPlan(Path home, String previousVersion, String newVersion)
				{
					requireThat(previousVersion, "previousVersion").isEqualTo("1.0");
					requireThat(newVersion, "newVersion").isEqualTo("1.1");
					return List.of(new ReplaceDirectory(live(), root));
				}

				@Override
				public Path migrationVersionFile(Path home)
				{
					return marker();
				}

				@Override
				public List<ActivationStep> installPlan(Path home, String newVersion)
				{
					return List.of(new AddDirectory(live(), root));
				}

				@Override
				public List<Path> removalPaths(Path home)
				{
					return List.of(live());
				}

				@Override
				public Path dataDirectory(Path home)
				{
					return root.resolve("plugin-data");
				}
			};
			return new UpdateEngine(root.resolve("data"), policy, new NoDownloads(), provider, "linux-x64",
				Clock.systemUTC(), interval -> () -> {});
		}
	}

	/**
	 * Rejects transport calls in operations that must restore only installer-retained content.
	 */
	private static final class NoDownloads implements ReleaseSource
	{
		@Override
		public String latestTag()
		{
			throw new AssertionError("Recovery requested a release tag");
		}

		@Override
		public byte[] manifest(String tag)
		{
			throw new AssertionError("Recovery requested a manifest");
		}

		@Override
		public byte[] signature(String tag)
		{
			throw new AssertionError("Recovery requested a signature");
		}

		@Override
		public void download(String tag, String assetName, long expectedSize, Path destination)
		{
			throw new AssertionError("Recovery requested an asset");
		}
	}
}
