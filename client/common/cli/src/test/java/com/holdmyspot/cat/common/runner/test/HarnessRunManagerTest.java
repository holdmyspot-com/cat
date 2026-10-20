package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.time.ZoneOffset.UTC;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunLock;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.secrets.HarnessRunManagerSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link HarnessRunManager}.
 */
public final class HarnessRunManagerTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunManagerTest()
	{
	}
	/**
	 * Verifies successful runs are deleted only after their output is persisted.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deletesSuccessfulRunAfterOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Path output = root.resolve("result.json");
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.complete(run, output, "{\"text\":\"done\"}");

			requireThat(Files.readString(output), "output").isEqualTo("{\"text\":\"done\"}");
			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
		}
	}

	/**
	 * Verifies owned run directories do not inherit permissive process-umask permissions.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void createsOwnerOnlyRunDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRun run = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			if (run.directory().getFileSystem().supportedFileAttributeViews().contains("posix"))
			{
				requireThat(Files.getPosixFilePermissions(run.directory()), "permissions").isEqualTo(
					Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
			}
		}
	}

	/**
	 * Verifies receipt-retained evidence keeps the project and event artifacts while pruning disposable isolated runtime
	 * state.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void prunesOnlyDisposableRuntimeState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.writeString(run.directory().resolve("project/source.txt"), "source");
			Files.writeString(run.directory().resolve("artifacts/turn-1.events"), "events");
			Files.writeString(run.directory().resolve("home/cache.txt"), "cache");
			Files.createDirectories(run.directory().resolve("claude"));
			Files.writeString(run.directory().resolve("claude/settings.json"), "settings");
			manager.markCompleted(run);

			manager.pruneRuntime(run);

			requireThat(Files.exists(run.directory().resolve("project/source.txt")), "project").isTrue();
			requireThat(Files.exists(run.directory().resolve("artifacts/turn-1.events")), "artifacts").isTrue();
			requireThat(Files.notExists(run.directory().resolve("home")), "home").isTrue();
			requireThat(Files.notExists(run.directory().resolve("claude")), "claude").isTrue();
		}
	}

	/**
	 * Verifies bulk pruning preserves running recovery state while removing only terminal runs' disposable runtime.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void prunesOnlyTerminalRuntimes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun terminal = manager.create("harness");
			Files.writeString(terminal.directory().resolve("home/cache.txt"), "cache");
			Files.writeString(terminal.directory().resolve("project/source.txt"), "source");
			manager.markCompleted(terminal);
			HarnessRun running = manager.create("harness");
			Files.writeString(running.directory().resolve("home/cache.txt"), "cache");

			manager.pruneTerminalRuntimes("harness");

			requireThat(Files.notExists(terminal.directory().resolve("home")), "terminalHome").isTrue();
			requireThat(Files.exists(terminal.directory().resolve("project/source.txt")), "terminalProject").isTrue();
			requireThat(Files.exists(running.directory().resolve("home/cache.txt")), "runningHome").isTrue();
		}
	}

	/**
	 * Verifies bulk pruning leaves alone the entries of a harness root that are not runs, such as the empty
	 * {@code .claude} directories that a harness sandbox creates in the directories above its working directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void pruneSkipsEntriesThatAreNotRuns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun terminal = manager.create("harness");
			Files.writeString(terminal.directory().resolve("home/cache.txt"), "cache");
			manager.markCompleted(terminal);
			Path directory = Files.createDirectory(root.resolve("harness/.claude"));
			Path file = Files.writeString(root.resolve("harness/.mcp.json"), "{}");

			manager.pruneTerminalRuntimes("harness");

			requireThat(Files.notExists(terminal.directory().resolve("home")), "terminalHome").isTrue();
			requireThat(Files.isDirectory(directory), "directory").isTrue();
			requireThat(Files.isRegularFile(file), "file").isTrue();
		}
	}

	/**
	 * Verifies failed runs retain non-secret diagnostics for later investigation.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsFailedRunDiagnostics() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.fail(run, "harness failed");

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("failed").
				contains("harness failed");
		}
	}

	/**
	 * Verifies a supervisor atomically records the observed exit status when its runner has ended before completing a
	 * run.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsExternallyObservedRunFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path manifestPath = run.directory().resolve("run.json");
			String inactiveOwnerManifest = Files.readString(manifestPath).replaceFirst(
				"\\\"owner_started_at\\\": \\\"[^\\\"]+\\\"", "\\\"owner_started_at\\\": \\\"1970-01-01T00:00:00Z\\\"");
			Files.writeString(manifestPath, inactiveOwnerManifest);

			manager.fail("harness", run.id(), "capture timed out after 600 seconds", 143);

			requireThat(Files.readString(manifestPath), "manifest").contains("\"state\": \"failed\"").
				contains("\"exit_code\": 143").contains("capture timed out after 600 seconds");
		}
	}

	/**
	 * Verifies a late supervisor report preserves the already recorded harness failure rather than replacing it with the
	 * runner's exit status.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void preservesRecordedHarnessFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path manifestPath = run.directory().resolve("run.json");
			manager.fail(run, "harness exited with exit status 2");
			String inactiveOwnerManifest = Files.readString(manifestPath).replaceFirst(
				"\\\"owner_started_at\\\": \\\"[^\\\"]+\\\"", "\\\"owner_started_at\\\": \\\"1970-01-01T00:00:00Z\\\"");
			Files.writeString(manifestPath, inactiveOwnerManifest);

			manager.fail("harness", run.id(), "runner ended with exit status 1", 1);

			requireThat(Files.readString(manifestPath), "manifest").contains("harness exited with exit status 2").
				doesNotContain("runner ended with exit status 1");
		}
	}

	/**
	 * Verifies explicit cleanup removes only an owned run.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deletesExplicitOwnedRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.fail(run, "test cleanup");

			manager.deleteRun("harness", run.id());

			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
		}
	}

	/**
	 * Verifies explicit cleanup rejects a malformed run manifest even beneath the configured root.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesMalformedRunManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.writeString(run.directory().resolve("run.json"), "{}");

			manager.deleteRun("harness", run.id());
		}
	}

	/**
	 * Verifies cleanup rejects a manifest before retaining more than the supported document size.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesOversizedRunManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			int tooLarge = HarnessRunManagerSecrets.access().maximumManifestBytes() + 1;
			Files.write(run.directory().resolve("run.json"), new byte[tooLarge]);

			manager.deleteRun("harness", run.id());
		}
	}

	/**
	 * Verifies an active run cannot be deleted while its owner holds the exclusive lock.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesToDeleteLockedRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			try (HarnessRunLock lock = manager.acquireLock(run))
			{
				requireThat(lock.isHeld(), "lockHeld").isTrue();
				manager.deleteRun(run);
			}
		}
	}

	/**
	 * Verifies explicit cleanup refuses an unlocked run whose recorded owner is still alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesToDeleteRunWithLiveOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.deleteRun("harness", run.id());
		}
	}

	/**
	 * Verifies every deletion entry point refuses an unlocked run whose recorded owner is still alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesDirectDeletionOfRunWithLiveOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.deleteRun(run);
		}
	}

	/**
	 * Verifies a successful run is marked completed before cleanup starts.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsCompletedStateBeforeCleanup() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.markCompleted(run);

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("completed");
		}
	}

	/**
	 * Verifies a live run records the harness process identity used by stale-run safety checks.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsHarnessProcessIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.recordHarnessProcess(run, 12_345, Instant.parse("2026-07-29T00:00:00Z"));

			String manifest = Files.readString(run.directory().resolve("run.json"));
			requireThat(manifest, "manifest").contains("\"harness_pid\": 12345").
				contains("\"harness_started_at\": \"2026-07-29T00:00:00Z\"");
		}
	}

	/**
	 * Verifies retained manifests record reproducible harness-process metadata without command secrets.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsRedactedHarnessProcessMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Path artifact = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-artifact-"));
			Path project = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-project-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.initialize(run, artifact, project, "structured_protocol", List.of("harness", "--api-key", "secret"));

			String manifest = Files.readString(run.directory().resolve("run.json"));
			JsonNode fields = JsonMapper.builder().build().readTree(manifest);
			requireThat(fields.get("artifact_path").asString(), "artifactPath").isEqualTo(artifact.toString());
			requireThat(fields.get("project_path").asString(), "projectPath").isEqualTo(project.toString());
			requireThat(fields.get("transport").asString(), "transport").isEqualTo("structured_protocol");
			requireThat(fields.get("harness_command").asString(), "harnessCommand").
				isEqualTo("harness --api-key <redacted>");
			requireThat(manifest, "manifest").doesNotContain("secret");
		}
	}

	/**
	 * Verifies harness command streams are retained in the owned diagnostics directory.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsHarnessCommandStreams() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.writeCommandOutput(run, "marketplace-add", "setup output", "setup error");

			Path artifacts = run.directory().resolve("artifacts");
			requireThat(Files.readString(artifacts.resolve("marketplace-add.stdout")), "standardOutput").
				isEqualTo("setup output");
			requireThat(Files.readString(artifacts.resolve("marketplace-add.stderr")), "standardError").
				isEqualTo("setup error");
		}
	}

	/**
	 * Verifies harness turn streams are retained in the owned diagnostics directory.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsHarnessTurnStreams() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.writeTurnOutput(run, 1, new HarnessProcessResult(0, "harness events", "harness error"));

			Path artifacts = run.directory().resolve("artifacts");
			requireThat(Files.readString(artifacts.resolve("turn-1.events")), "events").isEqualTo("harness events");
			requireThat(Files.readString(artifacts.resolve("turn-1.stderr")), "standardError").isEqualTo("harness error");
		}
	}

	/**
	 * Verifies a retained run records whether its harness is starting, waiting, or processing a turn.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsCurrentHarnessPhase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			requireThat(Files.readString(run.directory().resolve("run.json")), "initialManifest").
				contains("\"phase\": \"initializing\"");
			manager.markIdle(run);
			requireThat(Files.readString(run.directory().resolve("run.json")), "idleManifest").
				contains("\"phase\": \"idle\"");
			manager.markWorking(run);
			requireThat(Files.readString(run.directory().resolve("run.json")), "workingManifest").
				contains("\"phase\": \"working\"");
		}
	}

	/**
	 * Verifies harness events remain inspectable while a turn is still active.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void appendsLiveHarnessTurnEvents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.appendTurnEvent(run, 1, "{\"type\":\"progress\"}");

			requireThat(Files.readString(run.directory().resolve("artifacts/turn-1.events")), "events").
				isEqualTo("{\"type\":\"progress\"}\n");
		}
	}

	/**
	 * Verifies copying excludes source Git metadata and retains uncommitted project files.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void excludesSourceGitMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Files.createDirectories(project.resolve(".git"));
			Files.writeString(project.resolve(".git/config"), "source-only metadata");
			Files.writeString(project.resolve("unrelated.md"), "uncommitted change");
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.copyProject(project, run);

			Path copy = run.directory().resolve("project");
			requireThat(Files.readString(copy.resolve("README.md")), "project").isEqualTo("project");
			requireThat(Files.readString(copy.resolve("unrelated.md")), "uncommittedFile").
				isEqualTo("uncommitted change");
			requireThat(Files.exists(copy.resolve(".git")), "sourceGitMetadata").isFalse();
		}
	}

	/**
	 * Verifies that fixture files are copied into the project beside its files, and that a fixture whose path the
	 * project holds already is refused without replacing the project's file.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void workspaceKeepsProjectFiles() throws IOException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project/docs"));
			Files.writeString(project.resolve("guide.md"), "project guide");
			Path workspaces = Files.createDirectories(root.resolve("workspaces/docs"));
			Files.writeString(workspaces.resolve("notes.md"), "fixture notes");
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.copyProject(root.resolve("project"), run);

			manager.copyWorkspace(root.resolve("workspaces"), run);

			Path copy = run.directory().resolve("project");
			requireThat(Files.readString(copy.resolve("docs/notes.md")), "fixture").isEqualTo("fixture notes");
			requireThat(Files.readString(copy.resolve("docs/guide.md")), "projectFile").isEqualTo("project guide");

			Files.writeString(workspaces.resolve("guide.md"), "fixture guide");
			Files.delete(workspaces.resolve("notes.md"));
			try
			{
				manager.copyWorkspace(root.resolve("workspaces"), run);
				throw new AssertionError("A fixture replaced a project file");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("guide.md");
			}
			requireThat(Files.readString(copy.resolve("docs/guide.md")), "kept").isEqualTo("project guide");
		}
	}

	/**
	 * Verifies that a symbolic link among the workspace files is copied as a link with the same target, so that a
	 * draft's link reaches the project file it names, and that a link whose target lies outside the project is refused.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void workspaceKeepsLinksInsideProject() throws IOException
	{
		// Requirement: R-C21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project/docs"));
			Files.writeString(project.resolve("handbook.md"), "Ship small, ship often.\n");
			Path linkDirectory = Files.createDirectories(root.resolve("workspaces/draft/workspace/docs"));
			Path target = Path.of("../../../docs/handbook.md");
			Files.createSymbolicLink(linkDirectory.resolve("handbook.md"), target);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.copyProject(root.resolve("project"), run);

			manager.copyWorkspace(root.resolve("workspaces"), run);

			Path link = run.directory().resolve("project/draft/workspace/docs/handbook.md");
			requireThat(Files.isSymbolicLink(link), "isLink").isTrue();
			requireThat(Files.readSymbolicLink(link), "target").isEqualTo(target);
			requireThat(Files.readString(link), "content").isEqualTo("Ship small, ship often.\n");

			Path escaping = Files.createDirectories(root.resolve("escaping/docs"));
			Files.createSymbolicLink(escaping.resolve("secret.md"), Path.of("../../../outside.md"));
			HarnessRun other = manager.create("harness");
			manager.copyProject(root.resolve("project"), other);
			try
			{
				manager.copyWorkspace(root.resolve("escaping"), other);
				throw new AssertionError("A link that leaves the project was copied");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("secret.md");
			}
		}
	}

	/**
	 * Verifies that the snapshot of a run's project holds the copied files and workspace files, and keeps them as they
	 * were when the project changes afterward.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void snapshotKeepsInitialProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project/docs"));
			Files.writeString(project.resolve("guide.md"), "project guide");
			Path workspaces = Files.createDirectories(root.resolve("workspaces/docs"));
			Files.writeString(workspaces.resolve("notes.md"), "fixture notes");
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.copyProject(root.resolve("project"), run);
			manager.copyWorkspace(root.resolve("workspaces"), run);

			manager.snapshotProject(run);

			Path copy = run.directory().resolve("project");
			Files.writeString(copy.resolve("docs/guide.md"), "edited guide");
			Files.delete(copy.resolve("docs/notes.md"));
			Path snapshot = run.directory().resolve("initial-project");
			requireThat(Files.readString(snapshot.resolve("docs/guide.md")), "projectFile").isEqualTo("project guide");
			requireThat(Files.readString(snapshot.resolve("docs/notes.md")), "fixture").isEqualTo("fixture notes");
		}
	}

	/**
	 * Verifies copied project files retain their executable permission on POSIX filesystems.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsExecutableProjectFiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			if (!root.getFileSystem().supportedFileAttributeViews().contains("posix"))
				return;
			Path project = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-project-"));
			Path script = project.resolve("build.sh");
			Files.writeString(script, "#!/bin/sh\n");
			Files.setPosixFilePermissions(script, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.copyProject(project, run);

			requireThat(Files.isExecutable(run.directory().resolve("project/build.sh")), "copiedScript").isTrue();
		}
	}

	/**
	 * Verifies a runner root nested in the source project is excluded from the copied project tree.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void excludesNestedRunnerRootFromProjectCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-project-"));
			Files.writeString(project.resolve("README.md"), "project");
			Path root = project.resolve(".cat/sessions/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed/temp/runner-runs");
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.copyProject(project, run);

			Path copiedProject = run.directory().resolve("project");
			requireThat(Files.readString(copiedProject.resolve("README.md")), "projectContents").isEqualTo("project");
			requireThat(Files.exists(copiedProject.resolve(project.relativize(root))), "nestedRunnerRoot").isFalse();
		}
	}

	/**
	 * Verifies that a session is denied reading every other entry under the run root, including other runs, workspaces
	 * and the records of its own run, but not the paths that it uses, the paths inside them, or the directories that
	 * contain them.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deniesReadingAllButAccessiblePaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()), "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			HarnessRun other = manager.create("harness");
			HarnessRun evidence = manager.create("harness");
			Path project = Files.createDirectories(run.directory().resolve("project/src"));
			Path artifacts = Files.createDirectories(run.directory().resolve("artifacts"));
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path evidenceProject = Files.createDirectories(evidence.directory().resolve("project"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path otherHarness = Files.createDirectories(root.resolve("other-harness"));
			Path outside = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()), "cat-outside-"));

			Path home = run.directory().resolve("home");

			List<Path> deniedReads = manager.deniedReads(List.of(project.getParent(), home, evidenceArtifacts, outside));

			// Creating a run writes its home directory, its manifest and its lock
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(artifacts,
				run.directory().resolve("run.json"), run.directory().resolve(".lock"), other.directory(), evidenceProject,
				evidence.directory().resolve("home"), evidence.directory().resolve("run.json"),
				evidence.directory().resolve(".lock"), workspaces, otherHarness));
		}
	}

	/**
	 * Verifies stale terminal runs are reclaimed after the retention period.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deletesStaleFailedRuns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRunManager oldManager = new HarnessRunManager(root, oldClock, Yielding.fileReplacer());
			HarnessRun run = oldManager.create("harness");
			oldManager.fail(run, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(root, currentClock, Yielding.fileReplacer());

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
		}
	}

	/**
	 * Verifies stale cleanup uses a terminal manifest timestamp instead of mutable directory metadata.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void keepsRecentRunDespiteOldMtime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Clock clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(root, clock, Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.fail(run, "failed");
			Files.setLastModifiedTime(run.directory(), FileTime.from(
				Instant.parse("2026-07-20T00:00:00Z")));

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
		}
	}

	/**
	 * Verifies an unlocked active run left by a terminated orchestrator becomes a retained terminal failure immediately.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsAbandonedRunBeforeRetention() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRun run = new HarnessRunManager(root, oldClock, Yielding.fileReplacer()).create("harness");
			Path manifest = run.directory().resolve("run.json");
			String content = Files.readString(manifest).replaceFirst("\"owner_pid\": [0-9]+", "\"owner_pid\": 999999");
			Files.writeString(manifest, content);
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-20T00:00:01Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(root, currentClock, Yielding.fileReplacer());

			manager.deleteStaleRuns(Duration.ofDays(7));

			String recovered = Files.readString(manifest);
			requireThat(recovered, "manifest").contains("\"state\": \"failed\"").
				contains("Runner process ended without recording a terminal result");
		}
	}

	/**
	 * Verifies abandoned-run recovery terminates a recorded harness child before retaining its failed diagnostic.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsAbandonedHarnessRunFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			FakeProcessLiveness processLiveness = new FakeProcessLiveness(12_345,
				Instant.parse("2026-07-20T00:00:01Z"));
			HarnessRunManager oldManager = HarnessRunManagerSecrets.access().newHarnessRunManager(root, oldClock,
				Yielding.fileReplacer(), processLiveness);
			HarnessRun run = oldManager.create("harness");
			oldManager.recordHarnessProcess(run, 12_345, Instant.parse("2026-07-20T00:00:01Z"));
			Path manifest = run.directory().resolve("run.json");
			String orphanedManifest = Files.readString(manifest).replaceFirst("\"owner_pid\": [0-9]+",
				"\"owner_pid\": 999999");
			Files.writeString(manifest, orphanedManifest);
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = HarnessRunManagerSecrets.access().newHarnessRunManager(root, currentClock,
				Yielding.fileReplacer(), processLiveness);

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(processLiveness.terminationRequested, "terminationRequested").isTrue();
			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains(
				"\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies stale cleanup retains an unlocked running run whose recorded owner remains alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsStaleRunWithLiveOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRun run = new HarnessRunManager(root, oldClock, Yielding.fileReplacer()).create("harness");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(root, currentClock, Yielding.fileReplacer());

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
		}
	}

	/**
	 * Verifies the stale collector skips a terminal run while its owner lock remains held.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsLockedStaleRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRunManager oldManager = new HarnessRunManager(root, oldClock, Yielding.fileReplacer());
			HarnessRun run = oldManager.create("harness");
			oldManager.fail(run, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(root, currentClock, Yielding.fileReplacer());

			try (HarnessRunLock lock = manager.acquireLock(run))
			{
				requireThat(lock.isHeld(), "lockHeld").isTrue();
				manager.deleteStaleRuns(Duration.ofDays(7));
			}

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
		}
	}

	/**
	 * A controllable recorded-harness-process liveness service.
	 */
	private static final class FakeProcessLiveness implements HarnessProcessLiveness
	{
		private final long pid;
		private final Instant startedAt;
		private boolean alive = true;
		private boolean terminationRequested;

		/**
		 * Creates a live fake process.
		 *
		 * @param pid the positive process identifier
		 * @param startedAt the process start marker
		 */
		private FakeProcessLiveness(long pid, Instant startedAt)
		{
			assert pid > 0;
			assert startedAt != null;
			this.pid = pid;
			this.startedAt = startedAt;
		}

		@Override
		public boolean isAlive(long pid, Instant startedAt)
		{
			assert pid > 0;
			assert startedAt != null;
			return alive && this.pid == pid && this.startedAt.equals(startedAt);
		}

		@Override
		public boolean terminate(long pid, Instant startedAt)
		{
			assert pid > 0;
			assert startedAt != null;
			if (!isAlive(pid, startedAt))
				return false;
			terminationRequested = true;
			alive = false;
			return true;
		}
	}
}
