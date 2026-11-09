package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.time.ZoneOffset.UTC;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunLock;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import com.holdmyspot.cat.common.secrets.HarnessRunManagerSecrets;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link HarnessRunManager}.
 * <p>
 * Tests that depend on whether a run's owner process is alive run the real {@link ProcessHandleLiveness} over a table
 * of {@link FakeProcessHandle}s in place of the operating system's process table; only
 * {@link #readsOwnerLivenessFromProcessTable()} reads the real table, and the tests of a wall clock that was set read
 * real processes whose starts come from a process folder in place of {@code /proc}.
 */
public final class HarnessRunManagerTest
{
	/**
	 * The identifier of the recorded owner process that a test's process table may report alive.
	 */
	private static final long OWNER_PID = 4001;
	/**
	 * The start of the recorded owner process that a test's process table may report alive.
	 */
	private static final String OWNER_START = "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5000";
	/**
	 * The boot time, in seconds since the epoch, that a process folder shows until a test sets the wall clock.
	 */
	private static final long BOOT_TIME = 1_794_069_622;

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Path output = root.resolve("result.json");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRun run = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
	 * Verifies bulk pruning skips a run folder that has no manifest yet, as a requester's copy of a requested session's
	 * run does until the session ends, or never has if the session was stopped, and still prunes the terminal runs
	 * beside it. The requester's own session ends and prunes while another requested session is still running.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void pruneSkipsRunFolderWithoutManifest() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun terminal = manager.create("harness");
			Files.writeString(terminal.directory().resolve("home/cache.txt"), "cache");
			manager.markCompleted(terminal);
			Path requested = Files.createDirectories(root.resolve("harness/mw9qj3hn-1x2ce9sid1/artifacts"));
			Files.writeString(requested.resolve("transcript.jsonl"), "{}");

			manager.pruneTerminalRuntimes("harness");

			requireThat(Files.notExists(terminal.directory().resolve("home")), "terminalHome").isTrue();
			requireThat(Files.isRegularFile(requested.resolve("transcript.jsonl")), "requestedTranscript").isTrue();
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.fail(run, "harness failed");

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("failed").
				contains("harness failed");
		}
	}

	/**
	 * Verifies that a failed run, which stays for investigation, holds no copy of either harness's login.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void failedRunKeepsNoLogin() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path codexLogin = Files.writeString(run.directory().resolve("home/auth.json"), "login");
			Path claudeLogin = Files.writeString(Files.createDirectories(run.directory().resolve("home/.claude")).
				resolve(".credentials.json"), "login");

			manager.fail(run, "harness did not start");

			requireThat(Files.exists(codexLogin), "codexLogin").isFalse();
			requireThat(Files.exists(claudeLogin), "claudeLogin").isFalse();
			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("harness did not start");
		}
	}

	/**
	 * Verifies that a completed run kept for investigation holds no login but keeps its other evidence.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void keptRunKeepsNoLogin() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path login = Files.writeString(run.directory().resolve("home/auth.json"), "login");
			Path configuration = Files.writeString(run.directory().resolve("home/config.toml"), "model = \"m\"");
			Path output = root.resolve("output.txt");

			manager.complete(run, output, "answer", true);

			requireThat(Files.exists(login), "login").isFalse();
			requireThat(Files.readString(configuration), "configuration").isEqualTo("model = \"m\"");
			requireThat(Files.readString(output), "output").isEqualTo("answer");
		}
	}

	/**
	 * Verifies that removing a run's logins does not follow a link that the harness left in the run, so that a file
	 * outside the run survives.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void loginRemovalDoesNotFollowLinks() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			Path outside = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-outside-"));
			Path hostLogin = Files.writeString(outside.resolve(".credentials.json"), "host login");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.createSymbolicLink(run.directory().resolve("home/.claude"), outside);

			manager.fail(run, "harness failed");

			requireThat(Files.readString(hostLogin), "hostLogin").isEqualTo("host login");
			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains("harness failed");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.fail(run, "test cleanup");

			manager.deleteRun(run);

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Files.writeString(run.directory().resolve("run.json"), "{}");

			manager.deleteRun(run);
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			int tooLarge = HarnessRunManagerSecrets.getHarnessRunManagerAccess().maximumManifestBytes() + 1;
			Files.write(run.directory().resolve("run.json"), new byte[tooLarge]);

			manager.deleteRun(run);
		}
	}

	/**
	 * Verifies an active run cannot be deleted while its owner holds the exclusive lock, even when the process table
	 * no longer reports the recorded owner alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "Run is already active: .*")
	public void refusesToDeleteLockedRun() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = manager(jvm, root, Clock.systemUTC());
			HarnessRun run = manager.create("harness");
			try (HarnessRunLock lock = manager.acquireLock(run))
			{
				requireThat(lock.isHeld(), "lockHeld").isTrue();
				manager.deleteRun(run);
			}
		}
	}

	/**
	 * Verifies, against the operating system's real process table, that a manager created for production finds the
	 * owner of a run it just created, the test's own process, alive and refuses to delete the run.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Run is still owned by a live process: .*")
	public void readsOwnerLivenessFromProcessTable() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.deleteRun(run);
		}
	}

	/**
	 * Verifies explicit cleanup refuses an unlocked run whose recorded owner is still alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Run is still owned by a live process: .*")
	public void refusesDirectDeletionOfRunWithLiveOwner() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = manager(jvm, root, Clock.systemUTC(),
				new FakeProcessHandle(OWNER_PID, OWNER_START, true));
			HarnessRun run = manager.create("harness");
			recordOwner(run, OWNER_PID, OWNER_START);

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.recordHarnessProcess(run, 12_345, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t9000");

			String manifest = Files.readString(run.directory().resolve("run.json"));
			requireThat(manifest, "manifest").contains("\"harness_pid\": 12345").
				contains("\"harness_started_at\": \"0f1e2d3c4b5a69788796a5b4c3d2e1f0t9000\"");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Path artifact = Files.createDirectories(cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-artifact-")).resolve("nested\\new"));
			Path project = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-project-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.initialize(run, artifact, project, "structured_protocol",
				List.of("harness", "--label", "line\r\nbreak", "--api-key", "secret"));
			manager.markCompleted(run);

			String manifest = Files.readString(run.directory().resolve("run.json"));
			JsonNode fields = JsonMapper.builder().build().readTree(manifest);
			requireThat(fields.get("artifact_path").asString(), "artifactPath").isEqualTo(artifact.toString());
			requireThat(fields.get("project_path").asString(), "projectPath").isEqualTo(project.toString());
			requireThat(fields.get("transport").asString(), "transport").isEqualTo("structured_protocol");
			requireThat(fields.get("harness_command").asString(), "harnessCommand").
				isEqualTo("harness --label line\r\nbreak --api-key <redacted>");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Files.createDirectories(project.resolve(".git"));
			Files.writeString(project.resolve(".git/config"), "source-only metadata");
			Files.writeString(project.resolve("unrelated.md"), "uncommitted change");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
				root.resolve("runs"), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-C21
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(
					jvm.temporaryDirectory(),
					"cat-run-root-"));
				Path project = Files.createDirectories(root.resolve("project/docs"));
				Files.writeString(project.resolve("guide.md"), "project guide");
				Path workspaces = Files.createDirectories(root.resolve("workspaces/docs"));
				Files.writeString(workspaces.resolve("notes.md"), "fixture notes");
				HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
					root.resolve("runs"), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-C21
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(
					jvm.temporaryDirectory(),
					"cat-run-root-"));
				Path project = Files.createDirectories(root.resolve("project/docs"));
				Files.writeString(project.resolve("handbook.md"), "Ship small, ship often.\n");
				Path linkDirectory = Files.createDirectories(root.resolve("workspaces/draft/workspace/docs"));
				Path target = Path.of("../../../docs/handbook.md");
				Files.createSymbolicLink(linkDirectory.resolve("handbook.md"), target);
				HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
					root.resolve("runs"), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project/docs"));
			Files.writeString(project.resolve("guide.md"), "project guide");
			Path workspaces = Files.createDirectories(root.resolve("workspaces/docs"));
			Files.writeString(workspaces.resolve("notes.md"), "fixture notes");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
				root.resolve("runs"), Clock.systemUTC(),
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			if (!root.getFileSystem().supportedFileAttributeViews().contains("posix"))
				return;
			Path project = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-project-"));
			Path script = project.resolve("build.sh");
			Files.writeString(script, "#!/bin/sh\n");
			Files.setPosixFilePermissions(script, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-project-"));
			Files.writeString(project.resolve("README.md"), "project");
			Path root = project.resolve(".cat/sessions/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed/temp/runner-runs");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.copyProject(project, run);

			Path copiedProject = run.directory().resolve("project");
			requireThat(Files.readString(copiedProject.resolve("README.md")), "projectContents").isEqualTo("project");
			requireThat(Files.exists(copiedProject.resolve(project.relativize(root))), "nestedRunnerRoot").isFalse();
		}
	}

	/**
	 * Verifies that a project file that the sandbox has replaced with the null device is left out of the copy, and the
	 * copy still succeeds with the other files.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void projectCopySkipsPlaceholders() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			Path project = root.resolve("project");
			Files.createDirectories(project.resolve("nested"));
			Files.writeString(project.resolve("README.md"), "project");
			// A sandbox mounts the null device itself over each protected file; a second name of one file stands in for
			// it, since creating a device requires administrator rights.
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Files.createLink(project.resolve(".credentials.json"), nullDevice);
			Files.createLink(project.resolve("nested/token"), nullDevice);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.copyProject(project, run, nullDevice);

			Path copy = run.directory().resolve("project");
			requireThat(Files.readString(copy.resolve("README.md")), "readme").isEqualTo("project");
			requireThat(Files.exists(copy.resolve(".credentials.json")), "credentials").isFalse();
			requireThat(Files.exists(copy.resolve("nested/token")), "token").isFalse();
			requireThat(Files.isDirectory(copy.resolve("nested")), "nestedDirectory").isTrue();
		}
	}

	/**
	 * Verifies that a symbolic link to the null device is still refused, not skipped as a placeholder.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void projectCopyRefusesNullDeviceLink() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Files.createSymbolicLink(project.resolve("link"), nullDevice);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			try
			{
				manager.copyProject(project, run, nullDevice);
				throw new AssertionError("The symbolic link was copied");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("symbolic-link");
			}
		}
	}

	/**
	 * Verifies that where the null device does not exist, as on Windows, the copy leaves out no file, including an
	 * empty one.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void projectCopyKeepsAllWithoutDevice() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Files.writeString(project.resolve("empty"), "");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");

			manager.copyProject(project, run, root.resolve("absent-null-device"));

			Path copy = run.directory().resolve("project");
			requireThat(Files.readString(copy.resolve("README.md")), "readme").isEqualTo("project");
			requireThat(Files.exists(copy.resolve("empty")), "empty").isTrue();
		}
	}

	/**
	 * Verifies that a workspace file that the sandbox has replaced with the null device is left out of the copy.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void workspaceCopySkipsPlaceholders() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Path workspace = Files.createDirectories(root.resolve("workspaces"));
			Files.writeString(workspace.resolve("fixture.md"), "fixture");
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Files.createLink(workspace.resolve(".credentials.json"), nullDevice);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.copyProject(project, run, nullDevice);

			manager.copyWorkspace(workspace, run, nullDevice);

			Path copy = run.directory().resolve("project");
			requireThat(Files.readString(copy.resolve("fixture.md")), "fixture").isEqualTo("fixture");
			requireThat(Files.exists(copy.resolve(".credentials.json")), "credentials").isFalse();
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(), "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
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
				jvm.temporaryDirectory(), "cat-outside-"));

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRunManager oldManager = new HarnessRunManager(jvm.fileLocks(),
				root, oldClock, Yielding.fileReplacer());
			HarnessRun run = oldManager.create("harness");
			oldManager.fail(run, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
				root, currentClock, Yielding.fileReplacer());

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Clock clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
				root, clock, Yielding.fileReplacer());
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			// No process is alive, so the orchestrator that owns the run has ended
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRun run = manager(jvm, root, oldClock).create("harness");
			Path manifest = run.directory().resolve("run.json");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-20T00:00:01Z"), UTC);
			HarnessRunManager manager = manager(jvm, root, currentClock);

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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			String harnessStart = "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125";
			// Only the harness process is alive: the orchestrator that owns the run has ended
			FakeProcessHandle harness = new FakeProcessHandle(12_345, harnessStart, true);
			HarnessRunManager oldManager = manager(jvm, root, oldClock, harness);
			HarnessRun run = oldManager.create("harness");
			oldManager.recordHarnessProcess(run, 12_345, harnessStart);
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = manager(jvm, root, currentClock, harness);

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(harness.stopRequested(), "terminationRequested").isTrue();
			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").contains(
				"\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies stale cleanup retains an unlocked running run whose recorded owner remains alive, and leaves it running.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsStaleRunWithLiveOwner() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			FakeProcessHandle owner = new FakeProcessHandle(OWNER_PID, OWNER_START, true);
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRun run = manager(jvm, root, oldClock, owner).create("harness");
			recordOwner(run, OWNER_PID, OWNER_START);
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = manager(jvm, root, currentClock, owner);

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").
				contains("\"state\": \"running\"");
			requireThat(owner.stopRequested(), "ownerStopRequested").isFalse();
		}
	}

	/**
	 * Verifies stale cleanup leaves running the run of a live owner after the wall clock was set, which moves the boot
	 * time that Linux derives from it, between the owner's start and the cleanup. The test's own process stands for the
	 * owner.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsRunOfLiveOwnerAfterClockIsSet() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"proc-")), BOOT_TIME);
			long owner = ProcessHandle.current().pid();
			folder.start(owner, "java", 5000);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			HarnessRunManager manager = manager(jvm, root, Clock.systemUTC(), processes, ProcessHandle::of);
			HarnessRun run = manager.create("harness");
			recordOwner(run, owner, processes.find(owner).flatMap(ProcessDescription::start).orElseThrow());
			// The wall clock is set an hour back
			folder.setBootTime(BOOT_TIME - 3600);

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").
				contains("\"state\": \"running\"");
		}
	}

	/**
	 * Verifies stale cleanup stops the recorded harness process of an abandoned run after the wall clock was set, which
	 * moves the boot time that Linux derives from it, between the recording of the harness process and the cleanup.
	 * The start of the test's parent process, which stands for the harness process, comes from a process folder, and a
	 * fake process with the parent's identifier records the request to stop, so that no real process stops.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void stopsAbandonedHarnessAfterClockIsSet() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"proc-")), BOOT_TIME);
			long harnessPid = ProcessHandle.current().parent().orElseThrow().pid();
			folder.start(harnessPid, "claude", 6000);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			FakeProcessHandle harness = new FakeProcessHandle(harnessPid, "1", true);
			HarnessRunManager manager = manager(jvm, root, Clock.systemUTC(), processes,
				FakeProcessHandle.table(harness));
			HarnessRun run = manager.create("harness");
			// The owner has ended, and the test's own process is a later one with its identifier
			long owner = ProcessHandle.current().pid();
			folder.start(owner, "java", 7000);
			recordOwner(run, owner, OWNER_START);
			manager.recordHarnessProcess(run, harnessPid,
				processes.find(harnessPid).flatMap(ProcessDescription::start).orElseThrow());
			// The wall clock is set an hour back
			folder.setBootTime(BOOT_TIME - 3600);

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(harness.stopRequested(), "terminationRequested").isTrue();
			requireThat(Files.readString(run.directory().resolve("run.json")), "manifest").
				contains("\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies that stale cleanup leaves running, and leaves its harness process alone, a run whose manifest records its
	 * owner's and its harness process's starts as instants, which identify no process: the owner might still be alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsRunWhoseStartsAreInstants() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-run-root-"));
			// No owner process is alive
			FakeProcessHandle harness = new FakeProcessHandle(12_345, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125", true);
			HarnessRunManager manager = manager(jvm, root, Clock.systemUTC(), harness);
			HarnessRun run = manager.create("harness");
			Path manifest = run.directory().resolve("run.json");
			Files.writeString(manifest, Files.readString(manifest).
				replaceFirst("\"owner_started_at\": (null|\"[^\"]+\")",
					"\"owner_started_at\": \"2026-07-20T00:00:00Z\"").
				replaceFirst("\"harness_pid\": null", "\"harness_pid\": 12345").
				replaceFirst("\"harness_started_at\": null", "\"harness_started_at\": \"2026-07-20T00:00:01Z\""));

			manager.deleteStaleRuns(Duration.ofDays(7));

			requireThat(Files.readString(manifest), "manifest").contains("\"state\": \"running\"");
			requireThat(harness.stopRequested(), "terminationRequested").isFalse();
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-run-root-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			HarnessRunManager oldManager = new HarnessRunManager(jvm.fileLocks(),
				root, oldClock, Yielding.fileReplacer());
			HarnessRun run = oldManager.create("harness");
			oldManager.fail(run, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
				root, currentClock, Yielding.fileReplacer());

			try (HarnessRunLock lock = manager.acquireLock(run))
			{
				requireThat(lock.isHeld(), "lockHeld").isTrue();
				manager.deleteStaleRuns(Duration.ofDays(7));
			}

			requireThat(Files.exists(run.directory()), "runDirectory").isTrue();
		}
	}

	/**
	 * Creates a manager whose real process checker reads a table of fake processes in place of the operating system's
	 * process table.
	 *
	 * @param jvm       the test's JVM scope, which supplies the lock coordinator
	 * @param root      the absolute owned-run root
	 * @param clock     the clock used for generated identifiers
	 * @param processes the processes of the table; every other process identifier names no process
	 * @return the manager
	 */
	private static HarnessRunManager manager(TestJvmScope jvm, Path root, Clock clock, FakeProcessHandle... processes)
	{
		return manager(jvm, root, clock, FakeProcessHandle.processes(processes), FakeProcessHandle.table(processes));
	}

	/**
	 * Creates a manager whose real process checker reads the supplied processes in place of the operating system's.
	 *
	 * @param jvm       the test's JVM scope, which supplies the lock coordinator
	 * @param root      the absolute owned-run root
	 * @param clock     the clock used for generated identifiers
	 * @param processes reports the starts of running processes
	 * @param handles   finds the process with an identifier
	 * @return the manager
	 */
	private static HarnessRunManager manager(TestJvmScope jvm, Path root, Clock clock, HarnessProcesses processes,
		LongFunction<Optional<ProcessHandle>> handles)
	{
		ProcessHandleLiveness liveness = ProcessHandleLivenessSecrets.getProcessHandleLivenessAccess().create(processes,
			handles);
		return HarnessRunManagerSecrets.getHarnessRunManagerAccess().newHarnessRunManager(jvm.fileLocks(), root, clock,
			Yielding.fileReplacer(), liveness);
	}

	/**
	 * Replaces the owner process that a run's manifest records, standing in for a run that another orchestrator
	 * process created.
	 *
	 * @param run       the run
	 * @param pid   the owner's process identifier
	 * @param start the owner's start
	 * @throws IOException if the manifest cannot be read or written
	 */
	private static void recordOwner(HarnessRun run, long pid, String start) throws IOException
	{
		Path manifest = run.directory().resolve("run.json");
		Files.writeString(manifest, Files.readString(manifest).
			replaceFirst("\"owner_pid\": [0-9]+", "\"owner_pid\": " + pid).
			replaceFirst("\"owner_started_at\": (null|\"[^\"]+\")", "\"owner_started_at\": \"" + start + "\""));
	}
}
