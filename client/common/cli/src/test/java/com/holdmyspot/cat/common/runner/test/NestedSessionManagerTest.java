package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.UndeletableDirectory;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.time.ZoneOffset.UTC;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionLaunch;
import com.holdmyspot.cat.common.runner.NestedSessionLock;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.NestedSessionReceipt;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import com.holdmyspot.cat.common.secrets.NestedSessionManagerSecrets;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link NestedSessionManager}.
 * <p>
 * Tests that depend on whether a nested session's owner process is alive run the real {@link ProcessHandleLiveness}
 * over a table of {@link FakeProcessHandle}s in place of the operating system's process table; only {@link
 * #readsOwnerLivenessFromProcessTable()} reads the real table, and the tests of a wall clock that was set read real
 * processes whose starts come from a process folder in place of {@code /proc}.
 */
public final class NestedSessionManagerTest
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
	public NestedSessionManagerTest()
	{
	}
	/**
	 * Verifies that a successful nested session is kept once its output is persisted, since every nested session's
	 * results are copied to its results folder.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void keepsSuccessfulSessionAfterOutput() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			Path output = root.resolve("result.json");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.complete(nestedSession, output, "{\"text\":\"done\"}");

			requireThat(Files.readString(output), "output").isEqualTo("{\"text\":\"done\"}");
			requireThat(Files.exists(nestedSession.directory()), "sessionFolder").isTrue();
		}
	}

	/**
	 * Verifies owned session folders do not inherit permissive process-umask permissions.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void createsOwnerOnlySessionFolder() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSession nestedSession = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer()).create("harness");
			if (nestedSession.directory().getFileSystem().supportedFileAttributeViews().contains("posix"))
			{
				requireThat(Files.getPosixFilePermissions(nestedSession.directory()), "permissions").isEqualTo(
					Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
			}
		}
	}

	/**
	 * Verifies that a manager with a fixed slot ID creates each nested session at the same path, once the directory of
	 * the nested session before it is gone, so that the paths of the nested session's home and project repeat.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void fixedSlotIdRepeatsSessionPaths() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("slot-1");
			NestedSession first = manager.create("harness");
			TemporaryDirectories.deleteRecursively(first.directory());
			NestedSession second = manager.create("harness");
			requireThat(second.id(), "id").isEqualTo("slot-1");
			requireThat(second.directory(), "directory").isEqualTo(first.directory()).
				isEqualTo(root.resolve("harness").resolve("slot-1"));
			requireThat(Files.isDirectory(second.directory().resolve("home")), "home").isTrue();
			requireThat(Files.isDirectory(second.directory().resolve("project")), "project").isTrue();
		}
	}

	/**
	 * Verifies that a manager with a fixed slot ID refuses to create a nested session where a nested session's files
	 * remain, so that no nested session sees the files of the one before it.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void fixedSlotIdRefusesDirectoryThatRemains() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("slot-1");
			NestedSession first = manager.create("harness");
			Files.writeString(first.directory().resolve("home/login"), "left behind");
			expectThrows(IOException.class, () -> manager.create("harness"));
			requireThat(Files.readString(first.directory().resolve("home/login")), "login").isEqualTo("left behind");
		}
	}

	/**
	 * Verifies that the copy of a nested session that ran in a slot, under the slot's identifier, is kept under the
	 * identifier that the harness gave its session, and that its manifest identifies the directory that holds it.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void namesCopyBySessionId() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.withFixedSlotId("slot-1").create("harness");
			String sessionId = "5d1c0f3e-2a4b-4c6d-8e9f-0a1b2c3d4e5f";
			Path kept = Files.createDirectories(root.resolve("kept").resolve(sessionId));

			NestedSessionManager.copyManifest(nestedSession.directory(), kept, sessionId, root.resolve("project"));

			manager.markCompleted(new NestedSession("harness", sessionId, kept));
			requireThat(Files.readString(kept.resolve("session.json")), "manifest").
				contains("\"id\": \"" + sessionId + "\"");
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			Files.writeString(nestedSession.directory().resolve("project/source.txt"), "source");
			Files.writeString(nestedSession.directory().resolve("artifacts/turn-1.events"), "events");
			Files.writeString(nestedSession.directory().resolve("home/cache.txt"), "cache");
			Files.createDirectories(nestedSession.directory().resolve("claude"));
			Files.writeString(nestedSession.directory().resolve("claude/settings.json"), "settings");
			manager.markCompleted(nestedSession);

			manager.pruneRuntime(nestedSession);

			requireThat(Files.exists(nestedSession.directory().resolve("project/source.txt")), "project").isTrue();
			requireThat(Files.exists(nestedSession.directory().resolve("artifacts/turn-1.events")), "artifacts").isTrue();
			requireThat(Files.notExists(nestedSession.directory().resolve("home")), "home").isTrue();
			requireThat(Files.notExists(nestedSession.directory().resolve("claude")), "claude").isTrue();
		}
	}

	/**
	 * Verifies bulk pruning preserves running recovery state while removing only terminal nested sessions' disposable
	 * runtime.
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession terminal = manager.create("harness");
			Files.writeString(terminal.directory().resolve("home/cache.txt"), "cache");
			Files.writeString(terminal.directory().resolve("project/source.txt"), "source");
			manager.markCompleted(terminal);
			NestedSession running = manager.create("harness");
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
	public void pruneSkipsEntriesThatAreNotSessions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession terminal = manager.create("harness");
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
	 * Verifies bulk pruning skips a session folder that has no manifest yet, as a requester's copy of a requested
	 * session's nested session does until the session ends, or never has if the session was stopped, and still prunes the
	 * terminal nested sessions beside it. The requester's own session ends and prunes while another requested session is
	 * still running.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void pruneSkipsSessionFolderWithoutManifest() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession terminal = manager.create("harness");
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
	 * Verifies failed nested sessions retain non-secret diagnostics for later investigation.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsFailedSessionDiagnostics() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.fail(nestedSession, "harness failed");

			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").contains("failed").
				contains("harness failed");
		}
	}

	/**
	 * Verifies that a failed nested session, which stays for investigation, holds no copy of either harness's login.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void failedSessionKeepsNoLogin() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			Path codexLogin = Files.writeString(nestedSession.directory().resolve("home/auth.json"), "login");
			Path claudeLogin = Files.writeString(Files.createDirectories(nestedSession.directory().resolve("home/.claude")).
				resolve(".credentials.json"), "login");

			manager.fail(nestedSession, "harness did not start");

			requireThat(Files.exists(codexLogin), "codexLogin").isFalse();
			requireThat(Files.exists(claudeLogin), "claudeLogin").isFalse();
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").
				contains("harness did not start");
		}
	}

	/**
	 * Verifies that a completed nested session kept for investigation holds no login but keeps its other evidence.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void resultsCopyKeepsNoLogin() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			Path login = Files.writeString(nestedSession.directory().resolve("home/auth.json"), "login");
			Path configuration = Files.writeString(nestedSession.directory().resolve("home/config.toml"), "model = \"m\"");
			Path output = root.resolve("output.txt");

			manager.complete(nestedSession, output, "answer");

			requireThat(Files.exists(login), "login").isFalse();
			requireThat(Files.readString(configuration), "configuration").isEqualTo("model = \"m\"");
			requireThat(Files.readString(output), "output").isEqualTo("answer");
		}
	}

	/**
	 * Verifies that removing a nested session's logins does not follow a link that the harness left in the nested
	 * session, so that a file outside the nested session survives.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void loginRemovalDoesNotFollowLinks() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path outside = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-outside-"));
			Path hostLogin = Files.writeString(outside.resolve(".credentials.json"), "host login");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			Files.createSymbolicLink(nestedSession.directory().resolve("home/.claude"), outside);

			manager.fail(nestedSession, "harness failed");

			requireThat(Files.readString(hostLogin), "hostLogin").isEqualTo("host login");
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")),
				"manifest").contains("harness failed");
		}
	}

	/**
	 * Verifies that a nested session's {@code session.json} and its receipt record the authentication that its harness
	 * uses, the source home, the names of the copied parts and the class of login, and that the record stays through the
	 * nested session's later lifecycle records.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsAuthentication() throws IOException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			AuthenticationEvidence evidence = new AuthenticationEvidence(root.resolve("source-home"),
				List.of("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN"), HostLogin.SUBSCRIPTION);
			JsonMapper json = JsonMapper.builder().build();

			manager.initialize(nestedSession, root.resolve("artifact"), root.resolve("project"), "transport",
				List.of("harness", "--model", "model-a"), evidence);
			manager.recordHarnessSession(nestedSession, "session-a");
			Path transcript = Files.writeString(Files.createDirectories(nestedSession.directory().resolve("artifacts")).
				resolve("bridge.jsonl"), "bridge");
			Path receipt = root.resolve("receipt.json");
			NestedSessionReceipt.write(json, Yielding.fileReplacer(), receipt, nestedSession, transcript, List.of());

			JsonNode manifest = json.readTree(Files.readString(nestedSession.directory().resolve("session.json")));
			requireThat(AuthenticationEvidence.fromJson(manifest.path(AuthenticationEvidence.FIELD)), "manifest").
				isEqualTo(evidence);
			JsonNode receiptDocument = json.readTree(Files.readString(receipt));
			requireThat(AuthenticationEvidence.fromJson(receiptDocument.path(AuthenticationEvidence.FIELD)), "receipt").
				isEqualTo(evidence);
		}
	}

	/**
	 * Verifies explicit cleanup removes only an owned nested session.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deletesExplicitOwnedSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.fail(nestedSession, "test cleanup");

			manager.deleteSession(nestedSession);

			requireThat(Files.exists(nestedSession.directory()), "sessionFolder").isFalse();
		}
	}

	/**
	 * Verifies that deleting a nested session removes its manifest after everything else in it: a deletion that stops
	 * partway, at an entry of the nested session that cannot be removed, leaves the nested session's manifest, which
	 * names the processes that a later cleanup must check.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deleteKeepsManifestUntilFolderIsEmpty() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.fail(nestedSession, "test cleanup");
			Path manifest = nestedSession.directory().resolve("session.json");
			IOException failure = null;
			try (UndeletableDirectory _ = UndeletableDirectory.after(nestedSession.directory(), "session.json"))
			{
				manager.deleteSession(nestedSession);
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(manifest, "manifest").isRegularFile();
		}
	}

	/**
	 * Verifies explicit cleanup rejects a malformed session manifest even beneath the configured root.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesMalformedSessionManifest() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			Files.writeString(nestedSession.directory().resolve("session.json"), "{}");

			manager.deleteSession(nestedSession);
		}
	}

	/**
	 * Verifies cleanup rejects a manifest before retaining more than the supported document size.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Session manifest exceeds the maximum supported size of .* bytes: .*")
	public void refusesOversizedSessionManifest() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			int maximumManifestBytes = NestedSessionManagerSecrets.getNestedSessionManagerAccess().maximumManifestBytes();
			int tooLarge = maximumManifestBytes + 1;
			Path manifest = Files.write(nestedSession.directory().resolve("session.json"), new byte[tooLarge]);

			try
			{
				manager.deleteSession(nestedSession);
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").isEqualTo(
					"Session manifest exceeds the maximum supported size of " + maximumManifestBytes + " bytes: " + manifest);
				requireThat(manifest, "manifest").isRegularFile();
				requireThat(Files.size(manifest), "manifestSize").isEqualTo(tooLarge);
				throw failure;
			}
		}
	}

	/**
	 * Verifies an active nested session cannot be deleted while its owner holds the exclusive lock, even when the process
	 * table no longer reports the recorded owner alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Nested session is already active: .*")
	public void refusesToDeleteLockedSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = manager(jvm, root, Clock.systemUTC());
			NestedSession nestedSession = manager.create("harness");
			try (NestedSessionLock lock = manager.acquireLock(nestedSession))
			{
				requireThat(lock.isHeld(), "lockHeld").isTrue();
				manager.deleteSession(nestedSession);
			}
		}
	}

	/**
	 * Verifies, against the operating system's real process table, that a manager created for production finds the
	 * owner of a nested session it just created, the test's own process, alive and refuses to delete the nested session.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Nested session is still owned by a live process: .*")
	public void readsOwnerLivenessFromProcessTable() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.deleteSession(nestedSession);
		}
	}

	/**
	 * Verifies explicit cleanup refuses an unlocked nested session whose recorded owner is still alive.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Nested session is still owned by a live process: .*")
	public void refusesDeletionOfSessionWithLiveOwner() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			NestedSessionManager manager = manager(jvm, root, Clock.systemUTC(),
				new FakeProcessHandle(OWNER_PID, OWNER_START, true));
			NestedSession nestedSession = manager.create("harness");
			recordOwner(nestedSession, OWNER_PID, OWNER_START);

			manager.deleteSession(nestedSession);
		}
	}

	/**
	 * Verifies a successful nested session is marked completed before cleanup starts.
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.markCompleted(nestedSession);

			requireThat(Files.readString(nestedSession.directory().resolve("session.json")),
				"manifest").contains("completed");
		}
	}

	/**
	 * Verifies a live nested session records the harness process identity used by stale-session safety checks.
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.markHarnessStarting(nestedSession);
			manager.recordHarnessProcess(nestedSession, 12_345, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t9000");

			String manifest = Files.readString(nestedSession.directory().resolve("session.json"));
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
				"cat-results-"));
			Path artifact = Files.createDirectories(cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-artifact-")).resolve("nested\\new"));
			Path project = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-project-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.initialize(nestedSession, artifact, project, "structured_protocol",
				List.of("harness", "--label", "line\r\nbreak", "--api-key", "secret"));
			manager.markCompleted(nestedSession);

			String manifest = Files.readString(nestedSession.directory().resolve("session.json"));
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.writeCommandOutput(nestedSession, "marketplace-add", "setup output", "setup error");

			Path artifacts = nestedSession.directory().resolve("artifacts");
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.writeTurnOutput(nestedSession, 1, new HarnessProcessResult(0, "harness events", "harness error"));

			Path artifacts = nestedSession.directory().resolve("artifacts");
			requireThat(Files.readString(artifacts.resolve("turn-1.events")), "events").isEqualTo("harness events");
			requireThat(Files.readString(artifacts.resolve("turn-1.stderr")), "standardError").isEqualTo("harness error");
		}
	}

	/**
	 * Verifies a retained nested session records whether its harness is starting, waiting, or processing a turn.
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			String initialManifest = Files.readString(nestedSession.directory().resolve("session.json"));
			requireThat(initialManifest, "initialManifest").contains("\"schema_version\": 6");
			requireThat(initialManifest, "initialManifest").contains("\"harness_process_state\": \"absent\"");
			requireThat(initialManifest, "initialManifest").
				contains("\"phase\": \"initializing\"");
			manager.markIdle(nestedSession);
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "idleManifest").
				contains("\"phase\": \"idle\"");
			manager.markWorking(nestedSession);
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "workingManifest").
				contains("\"phase\": \"working\"");
		}
	}

	/**
	 * Verifies a harness process cannot be recorded until the manager has persisted the prelaunch state.
	 *
	 * @throws IOException if the session manifest cannot be created
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesHarnessRecordBeforeStarting() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.recordHarnessProcess(nestedSession, 12_345, OWNER_START);
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
				"cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.appendTurnEvent(nestedSession, 1, "{\"type\":\"progress\"}");

			requireThat(Files.readString(nestedSession.directory().resolve("artifacts/turn-1.events")), "events").
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
				"cat-results-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Files.createDirectories(project.resolve(".git"));
			Files.writeString(project.resolve(".git/config"), "source-only metadata");
			Files.writeString(project.resolve("unrelated.md"), "uncommitted change");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
				root.resolve("results"), Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession);

			Path copy = nestedSession.directory().resolve("project");
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
					"cat-results-"));
				Path project = Files.createDirectories(root.resolve("project/docs"));
				Files.writeString(project.resolve("guide.md"), "project guide");
				Path workspaces = Files.createDirectories(root.resolve("workspaces/docs"));
				Files.writeString(workspaces.resolve("notes.md"), "fixture notes");
				NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
					root.resolve("results"), Clock.systemUTC(),
					Yielding.fileReplacer());
				NestedSession nestedSession = manager.create("harness");
				manager.copyProject(root.resolve("project"), nestedSession);

				manager.copyWorkspace(root.resolve("workspaces"), nestedSession);

				Path copy = nestedSession.directory().resolve("project");
				requireThat(Files.readString(copy.resolve("docs/notes.md")), "fixture").isEqualTo("fixture notes");
				requireThat(Files.readString(copy.resolve("docs/guide.md")), "projectFile").isEqualTo("project guide");

				Files.writeString(workspaces.resolve("guide.md"), "fixture guide");
				Files.delete(workspaces.resolve("notes.md"));
				try
				{
					manager.copyWorkspace(root.resolve("workspaces"), nestedSession);
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
					"cat-results-"));
				Path project = Files.createDirectories(root.resolve("project/docs"));
				Files.writeString(project.resolve("handbook.md"), "Ship small, ship often.\n");
				Path linkDirectory = Files.createDirectories(root.resolve("workspaces/draft/workspace/docs"));
				Path target = Path.of("../../../docs/handbook.md");
				Files.createSymbolicLink(linkDirectory.resolve("handbook.md"), target);
				NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
					root.resolve("results"), Clock.systemUTC(),
					Yielding.fileReplacer());
				NestedSession nestedSession = manager.create("harness");
				manager.copyProject(root.resolve("project"), nestedSession);

				manager.copyWorkspace(root.resolve("workspaces"), nestedSession);

				Path link = nestedSession.directory().resolve("project/draft/workspace/docs/handbook.md");
				requireThat(Files.isSymbolicLink(link), "isLink").isTrue();
				requireThat(Files.readSymbolicLink(link), "target").isEqualTo(target);
				requireThat(Files.readString(link), "content").isEqualTo("Ship small, ship often.\n");

				Path escaping = Files.createDirectories(root.resolve("escaping/docs"));
				Files.createSymbolicLink(escaping.resolve("secret.md"), Path.of("../../../outside.md"));
				NestedSession other = manager.create("harness");
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
	 * Verifies that the snapshot of a nested session's project holds the copied files and workspace files, and keeps them
	 * as they were when the project changes afterward.
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
				"cat-results-"));
			Path project = Files.createDirectories(root.resolve("project/docs"));
			Files.writeString(project.resolve("guide.md"), "project guide");
			Path workspaces = Files.createDirectories(root.resolve("workspaces/docs"));
			Files.writeString(workspaces.resolve("notes.md"), "fixture notes");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
				root.resolve("results"), Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.copyProject(root.resolve("project"), nestedSession);
			manager.copyWorkspace(root.resolve("workspaces"), nestedSession);

			manager.snapshotProject(nestedSession);

			Path copy = nestedSession.directory().resolve("project");
			Files.writeString(copy.resolve("docs/guide.md"), "edited guide");
			Files.delete(copy.resolve("docs/notes.md"));
			Path snapshot = nestedSession.directory().resolve("initial-project");
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
				"cat-results-"));
			if (!root.getFileSystem().supportedFileAttributeViews().contains("posix"))
				return;
			Path project = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-project-"));
			Path script = project.resolve("build.sh");
			Files.writeString(script, "#!/bin/sh\n");
			Files.setPosixFilePermissions(script, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession);

			requireThat(Files.isExecutable(nestedSession.directory().resolve("project/build.sh")), "copiedScript").isTrue();
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
			Path root = project.resolve(".cat/sessions/1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed/temp/nested-sessions");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession);

			Path copiedProject = nestedSession.directory().resolve("project");
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
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path project = root.resolve("project");
			Files.createDirectories(project.resolve("nested"));
			Files.writeString(project.resolve("README.md"), "project");
			// A sandbox mounts the null device itself over each protected file; a second name of one file stands in for
			// it, since creating a device requires administrator rights.
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Files.createLink(project.resolve(".credentials.json"), nullDevice);
			Files.createLink(project.resolve("nested/token"), nullDevice);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession, nullDevice);

			Path copy = nestedSession.directory().resolve("project");
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
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Files.createSymbolicLink(project.resolve("link"), nullDevice);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			try
			{
				manager.copyProject(project, nestedSession, nullDevice);
				throw new AssertionError("The symbolic link was copied");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("symbolic link link").
					contains(nullDevice.toRealPath().toString());
			}
		}
	}

	/**
	 * Verifies that a symbolic link whose target resolves inside the project, through a chain of links too, is copied
	 * as a link with the same relative target that resolves inside the copy, and that a link whose target leaves the
	 * project before it comes back is kept as a link to a read-only copy beside the project, since a link with its
	 * target would resolve outside the copy.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void projectCopyKeepsLinksInsideProject() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(Files.createDirectories(project.resolve("shared")).resolve("guide.md"), "guide");
			Files.createSymbolicLink(Files.createDirectories(project.resolve("docs")).resolve("guide.md"),
				Path.of("../shared/guide.md"));
			Files.createSymbolicLink(project.resolve("alias"), Path.of("shared"));
			Files.createSymbolicLink(project.resolve("chain"), Path.of("alias/guide.md"));
			Files.createSymbolicLink(project.resolve("back"), Path.of("../project/shared/guide.md"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession);

			Path copy = nestedSession.directory().resolve("project").toRealPath();
			for (String name : List.of("docs/guide.md", "alias", "chain"))
			{
				Path link = copy.resolve(name);
				requireThat(Files.readSymbolicLink(link), name).
					isEqualTo(Files.readSymbolicLink(project.resolve(name)));
				requireThat(link.toRealPath().startsWith(copy), name + "ResolvesInsideCopy").isTrue();
			}
			requireThat(Files.readString(copy.resolve("chain")), "chain").isEqualTo("guide");
			requireThat(Files.isSymbolicLink(copy.resolve("back")), "backIsLink").isTrue();
			requireThat(copy.resolve("back").toRealPath().startsWith(NestedSessionManager.linkTargets(
				nestedSession.directory()).toRealPath()), "backLeadsToLinkTargets").isTrue();
			requireThat(Files.readString(copy.resolve("back")), "back").isEqualTo("guide");
		}
	}

	/**
	 * Verifies that a symbolic link whose target lies outside the project stays a link in the project copy, with a
	 * relative target that leads to a read-only copy of the target's file or directory tree, links inside that tree
	 * included, in a folder of its own in the nested session's link-targets directory beside the project: a write to a
	 * copied file fails, adding a file to a copied directory fails where the file system records POSIX permissions, the
	 * target stays unchanged, a change to a copy is found, and the nested session still deletes.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void projectCopyLinksOutsideTargetsToCopies() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path outside = Files.createDirectories(root.resolve("outside/tree"));
			Path notes = Files.writeString(root.resolve("outside/notes.md"), "notes");
			Files.writeString(outside.resolve("a.md"), "a");
			Files.createSymbolicLink(outside.resolve("inner.md"), Path.of("../notes.md"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.createSymbolicLink(project.resolve("notes.md"), Path.of("../outside/notes.md"));
			Files.createSymbolicLink(Files.createDirectories(project.resolve("docs")).resolve("tree"),
				outside.toAbsolutePath());
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession);

			Path copy = nestedSession.directory().resolve("project");
			Path linkTargets = NestedSessionManager.linkTargets(nestedSession.directory());
			Path notesLink = copy.resolve("notes.md");
			Path treeLink = copy.resolve("docs/tree");
			for (Path link : List.of(notesLink, treeLink))
			{
				requireThat(Files.isSymbolicLink(link), link + "IsLink").isTrue();
				requireThat(Files.readSymbolicLink(link).isAbsolute(), link + "TargetIsAbsolute").isFalse();
				// Each copy lies in a folder of its own, a child of link-targets, that the link leads to
				requireThat(link.toRealPath().getParent(), link + "Folder").isEqualTo(linkTargets.toRealPath());
			}
			requireThat(notesLink.toRealPath(), "notesFolder").isNotEqualTo(treeLink.toRealPath());
			requireThat(Files.readString(notesLink), "notes").isEqualTo("notes");
			requireThat(treeLink, "tree").isDirectory();
			requireThat(Files.readString(treeLink.resolve("a.md")), "a").isEqualTo("a");
			requireThat(Files.isSymbolicLink(treeLink.resolve("inner.md")), "innerIsLink").isFalse();
			requireThat(Files.readString(treeLink.resolve("inner.md")), "inner").isEqualTo("notes");
			for (Path file : List.of(notesLink, treeLink.resolve("a.md"), treeLink.resolve("inner.md")))
			{
				try
				{
					Files.writeString(file, "changed");
					throw new AssertionError("A write to the copy of " + file + " succeeded");
				}
				catch (AccessDeniedException _)
				{
					// The copy is read-only
				}
			}
			if (PortableFiles.hasModes(copy))
			{
				try
				{
					Files.writeString(treeLink.resolve("added.md"), "added");
					throw new AssertionError("Adding a file to the copied directory succeeded");
				}
				catch (AccessDeniedException _)
				{
					// The copied directory is read-only
				}
			}
			requireThat(Files.readString(notes), "target").isEqualTo("notes");
			requireThat(Files.readString(outside.resolve("a.md")), "treeTarget").isEqualTo("a");
			requireThat(Files.exists(outside.resolve("added.md")), "addedToTarget").isFalse();

			// The copy of a link inside the copied tree belongs to the tree's copy, and a change to it is found
			requireThat(manager.changedLinkCopies(nestedSession), "unchanged").isEmpty();
			String treeCopy = copyName(nestedSession, treeLink);
			String notesCopy = copyName(nestedSession, notesLink);
			Path inner = treeLink.resolve("inner.md").toRealPath();
			PortableFiles.clearReadOnly(inner);
			Files.writeString(inner, "changed");
			requireThat(manager.changedLinkCopies(nestedSession).orElseThrow(), "changed").
				contains(treeCopy + "/inner.md (content changed, made writable)").doesNotContain(notesCopy + " (");
			if (PortableFiles.hasModes(copy))
			{
				// A mode change that leaves the copy read-only for its owner is a change too
				Path copiedNotes = notesLink.toRealPath();
				String before = PosixFilePermissions.toString(Files.getPosixFilePermissions(copiedNotes));
				Files.setPosixFilePermissions(copiedNotes, PosixFilePermissions.fromString("r-x------"));
				requireThat(manager.changedLinkCopies(nestedSession).orElseThrow(), "modeChanged").
					contains(notesCopy + " (mode changed from " + before + " to r-x------)");
				// A copy that its owner may no longer read is a change too, rather than a failure to compare
				Path copiedFile = treeLink.resolve("a.md").toRealPath();
				Files.setPosixFilePermissions(copiedFile, PosixFilePermissions.fromString("---------"));
				if (!Files.isReadable(copiedFile))
				{
					requireThat(manager.changedLinkCopies(nestedSession).orElseThrow(), "unreadable").
						contains(treeCopy + "/a.md (made unreadable, mode changed from ");
				}
			}

			manager.fail(nestedSession, "test cleanup");
			manager.deleteSession(nestedSession);
			requireThat(Files.exists(nestedSession.directory()), "sessionFolder").isFalse();
		}
	}

	/**
	 * Returns the name that the nested session's record gives the copy that a link of its project leads to.
	 *
	 * @param nestedSession the nested session
	 * @param link          the project's link
	 * @return the copy's path relative to the nested session's directory, with {@code /} between names
	 * @throws IOException if the link cannot be resolved
	 */
	private static String copyName(NestedSession nestedSession, Path link) throws IOException
	{
		Path relative = nestedSession.directory().toRealPath().relativize(link.toRealPath());
		return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Verifies that the diagnostic of changed copies of link targets outside the project names the first changed paths
	 * and counts the rest, so that a session that changes many files cannot make the nested session's diagnostic
	 * unbounded.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void countsChangedLinkCopiesBeyondThoseNamed() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Files.createDirectories(root.resolve("outside/tree"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.createSymbolicLink(project.resolve("tree"), Path.of("../outside/tree"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.copyProject(project, nestedSession);
			Path tree = nestedSession.directory().resolve("project/tree").toRealPath();
			PortableFiles.clearReadOnly(tree);
			for (int index = 10; index < 35; ++index)
				Files.writeString(tree.resolve("added-" + index + ".md"), "added");

			String diagnostic = manager.changedLinkCopies(nestedSession).orElseThrow();

			// Where directories have modes, making the copied directory writable is a change too
			int changes = 25;
			if (PortableFiles.hasModes(tree))
				++changes;
			String copyName = copyName(nestedSession, nestedSession.directory().resolve("project/tree"));
			requireThat(diagnostic, "diagnostic").contains(copyName + "/added-10.md (added)").
				doesNotContain(copyName + "/added-34.md").endsWith(", and " + (changes - 20) + " more. The nested " +
					"session fails, because its result would rest on files that differ from the targets.");
		}
	}

	/**
	 * Verifies that a workspace file whose path lies in a read-only copy of a link's target outside the project is
	 * refused, so that the nested session's check of the copies cannot take the workspace's file for a change by the
	 * session.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void refusesWorkspaceFileInLinkCopy() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Files.writeString(Files.createDirectories(root.resolve("outside/tree")).resolve("a.md"), "a");
			Path project = Files.createDirectories(root.resolve("project"));
			Files.createSymbolicLink(project.resolve("tree"), Path.of("../outside/tree"));
			Files.writeString(Files.createDirectories(root.resolve("workspace/tree")).resolve("added.md"), "added");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.copyProject(project, nestedSession);
			try
			{
				manager.copyWorkspace(root.resolve("workspace"), nestedSession);
				throw new AssertionError("A workspace file went into a copy of a link's target");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "refusal").contains("read-only copy of the target of a symbolic link");
			}
			requireThat(Files.exists(nestedSession.directory().resolve("project/tree/added.md")), "added").isFalse();
			requireThat(manager.changedLinkCopies(nestedSession), "changes").isEmpty();
		}
	}

	/**
	 * Supplies symbolic links that a project copy refuses.
	 *
	 * @return the name of the link and its target
	 */
	@DataProvider
	public Object[][] refusedProjectLinks()
	{
		return new Object[][]{{"missing", "absent.md"}, {"cycle", "cycle"}, {"up", ".."}};
	}

	/**
	 * Verifies that a project copy refuses, naming the link, a symbolic link whose target does not exist or leads back
	 * to itself, and a link to a directory that holds the project, whose copy would hold the project again.
	 *
	 * @param name   the name of the link
	 * @param target the link's target
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(dataProvider = "refusedProjectLinks")
	public void projectCopyRefusesUnusableLink(String name, String target) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.createSymbolicLink(project.resolve(name), Path.of(target));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			try
			{
				manager.copyProject(project, nestedSession);
				throw new AssertionError("The symbolic link was copied");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("symbolic link " + name + " ");
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
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Files.writeString(project.resolve("empty"), "");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");

			manager.copyProject(project, nestedSession, root.resolve("absent-null-device"));

			Path copy = nestedSession.directory().resolve("project");
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
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			Path workspace = Files.createDirectories(root.resolve("workspaces"));
			Files.writeString(workspace.resolve("fixture.md"), "fixture");
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Files.createLink(workspace.resolve(".credentials.json"), nullDevice);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.copyProject(project, nestedSession, nullDevice);

			manager.copyWorkspace(workspace, nestedSession, nullDevice);

			Path copy = nestedSession.directory().resolve("project");
			requireThat(Files.readString(copy.resolve("fixture.md")), "fixture").isEqualTo("fixture");
			requireThat(Files.exists(copy.resolve(".credentials.json")), "credentials").isFalse();
		}
	}

	/**
	 * Verifies that a session is denied reading every other entry under the results folder, including other nested
	 * sessions, workspaces and the records of its own nested session, but not the paths that it uses, the paths inside
	 * them, or the directories that contain them.
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
				jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			NestedSession other = manager.create("harness");
			NestedSession evidence = manager.create("harness");
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/src"));
			Path artifacts = Files.createDirectories(nestedSession.directory().resolve("artifacts"));
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path evidenceProject = Files.createDirectories(evidence.directory().resolve("project"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path otherHarness = Files.createDirectories(root.resolve("other-harness"));
			Path outside = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(), "cat-outside-"));

			Path home = nestedSession.directory().resolve("home");

			List<Path> deniedReads = manager.deniedReads(List.of(project.getParent(), home, evidenceArtifacts, outside));

			// Creating a nested session writes its home directory, its manifest and its lock
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(artifacts,
				nestedSession.directory().resolve("session.json"), nestedSession.directory().resolve(".lock"),
				other.directory(), evidenceProject,
				evidence.directory().resolve("home"), evidence.directory().resolve("session.json"),
				evidence.directory().resolve(".lock"),
				otherHarness, workspaces));
		}
	}

	/**
	 * Verifies stale terminal nested sessions are reclaimed after the retention period.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void deletesStaleFailedSessions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			NestedSessionManager oldManager = new NestedSessionManager(jvm.fileLocks(),
				root, oldClock, Yielding.fileReplacer());
			NestedSession nestedSession = oldManager.create("harness");
			oldManager.fail(nestedSession, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
				root, currentClock, Yielding.fileReplacer());

			manager.deleteStaleSessions(Duration.ofDays(7));

			requireThat(Files.exists(nestedSession.directory()), "sessionFolder").isFalse();
		}
	}

	/**
	 * Verifies that deleting a stale nested session removes its manifest after everything else in it: a deletion that
	 * stops partway, at an entry of the nested session that cannot be removed, leaves the nested session's manifest,
	 * which names the processes that a later cleanup must check.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void staleSessionKeepsManifestUntilEmpty() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			NestedSessionManager oldManager = new NestedSessionManager(jvm.fileLocks(), root, oldClock,
				Yielding.fileReplacer());
			NestedSession nestedSession = oldManager.create("harness");
			oldManager.fail(nestedSession, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, currentClock,
				Yielding.fileReplacer());
			Path manifest = nestedSession.directory().resolve("session.json");
			IOException failure = null;
			try (UndeletableDirectory _ = UndeletableDirectory.after(nestedSession.directory(), "session.json"))
			{
				manager.deleteStaleSessions(Duration.ofDays(7));
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(manifest, "manifest").isRegularFile();
		}
	}

	/**
	 * Verifies stale cleanup uses a terminal manifest timestamp instead of mutable directory metadata.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void keepsRecentSessionDespiteOldMtime() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			Clock clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
				root, clock, Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			manager.fail(nestedSession, "failed");
			Files.setLastModifiedTime(nestedSession.directory(), FileTime.from(
				Instant.parse("2026-07-20T00:00:00Z")));

			manager.deleteStaleSessions(Duration.ofDays(7));

			requireThat(Files.exists(nestedSession.directory()), "sessionFolder").isTrue();
		}
	}

	/**
	 * Verifies an unlocked active nested session left by a terminated orchestrator becomes a retained terminal failure
	 * immediately.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsAbandonedSessionBeforeRetention() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			// No process is alive, so the orchestrator that owns the nested session has ended
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			NestedSession nestedSession = manager(jvm, root, oldClock).create("harness");
			Path manifest = nestedSession.directory().resolve("session.json");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-20T00:00:01Z"), UTC);
			NestedSessionManager manager = manager(jvm, root, currentClock);

			manager.deleteStaleSessions(Duration.ofDays(7));

			String recovered = Files.readString(manifest);
			requireThat(recovered, "manifest").contains("\"state\": \"failed\"").
				contains("Runner process ended without recording a terminal result");
		}
	}

	/**
	 * Verifies abandoned-session recovery terminates a recorded harness child before retaining its failed diagnostic.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsAbandonedSessionFailure() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			String harnessStart = "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125";
			// Only the harness process is alive: the orchestrator that owns the nested session has ended
			FakeProcessHandle harness = new FakeProcessHandle(12_345, harnessStart, true);
			NestedSessionManager oldManager = manager(jvm, root, oldClock, harness);
			NestedSession nestedSession = oldManager.create("harness");
			oldManager.markHarnessStarting(nestedSession);
			oldManager.recordHarnessProcess(nestedSession, 12_345, harnessStart);
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			NestedSessionManager manager = manager(jvm, root, currentClock, harness);

			manager.deleteStaleSessions(Duration.ofDays(7));

			requireThat(harness.stopRequested(), "terminationRequested").isTrue();
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").contains(
				"\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies stale cleanup retains an unlocked running nested session whose recorded owner remains alive, and leaves it
	 * running.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsStaleSessionWithLiveOwner() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			FakeProcessHandle owner = new FakeProcessHandle(OWNER_PID, OWNER_START, true);
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			NestedSession nestedSession = manager(jvm, root, oldClock, owner).create("harness");
			recordOwner(nestedSession, OWNER_PID, OWNER_START);
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			NestedSessionManager manager = manager(jvm, root, currentClock, owner);

			manager.deleteStaleSessions(Duration.ofDays(7));

			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"running\"");
			requireThat(owner.stopRequested(), "ownerStopRequested").isFalse();
		}
	}

	/**
	 * Verifies stale cleanup leaves running the nested session of a live owner after the wall clock was set, which moves
	 * the boot time that Linux derives from it, between the owner's start and the cleanup. The test's own process stands
	 * for the owner.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsSessionOfLiveOwnerAfterClockSet() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"proc-")), BOOT_TIME);
			long owner = ProcessHandle.current().pid();
			folder.start(owner, "java", 5000);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			NestedSessionManager manager = manager(jvm, root, Clock.systemUTC(), processes, ProcessHandle::of);
			NestedSession nestedSession = manager.create("harness");
			recordOwner(nestedSession, owner, processes.find(owner).flatMap(ProcessDescription::start).orElseThrow());
			// The wall clock is set an hour back
			folder.setBootTime(BOOT_TIME - 3600);

			manager.deleteStaleSessions(Duration.ofDays(7));

			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"running\"");
		}
	}

	/**
	 * Verifies stale cleanup stops the recorded harness process of an abandoned nested session after the wall clock was
	 * set, which moves the boot time that Linux derives from it, between the recording of the harness process and the
	 * cleanup. The start of the test's parent process, which stands for the harness process, comes from a process folder,
	 * and a fake process with the parent's identifier records the request to stop, so that no real process stops.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void stopsAbandonedHarnessAfterClockIsSet() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"proc-")), BOOT_TIME);
			long harnessPid = ProcessHandle.current().parent().orElseThrow().pid();
			folder.start(harnessPid, "claude", 6000);
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			FakeProcessHandle harness = new FakeProcessHandle(harnessPid, "1", true);
			NestedSessionManager manager = manager(jvm, root, Clock.systemUTC(), processes,
				FakeProcessHandle.table(harness));
			NestedSession nestedSession = manager.create("harness");
			// The owner has ended, and the test's own process is a later one with its identifier
			long owner = ProcessHandle.current().pid();
			folder.start(owner, "java", 7000);
			recordOwner(nestedSession, owner, OWNER_START);
			manager.markHarnessStarting(nestedSession);
			manager.recordHarnessProcess(nestedSession, harnessPid,
				processes.find(harnessPid).flatMap(ProcessDescription::start).orElseThrow());
			// The wall clock is set an hour back
			folder.setBootTime(BOOT_TIME - 3600);

			manager.deleteStaleSessions(Duration.ofDays(7));

			requireThat(harness.stopRequested(), "terminationRequested").isTrue();
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").
				contains("\"state\": \"failed\"");
		}
	}

	/**
	 * Verifies that stale cleanup refuses to delete a nested session whose process identities are malformed.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void refusesSessionWhoseStartsAreInstants() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			// No owner process is alive
			FakeProcessHandle harness = new FakeProcessHandle(12_345, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125", true);
			NestedSessionManager manager = manager(jvm, root, Clock.systemUTC(), harness);
			NestedSession nestedSession = manager.create("harness");
			Path manifest = nestedSession.directory().resolve("session.json");
			Files.writeString(manifest, Files.readString(manifest).
				replaceFirst("\"owner_started_at\": (null|\"[^\"]+\")",
					"\"owner_started_at\": \"2026-07-20T00:00:00Z\"").
				replaceFirst("\"harness_pid\": null", "\"harness_pid\": 12345").
				replaceFirst("\"harness_started_at\": null", "\"harness_started_at\": \"2026-07-20T00:00:01Z\""));

			IOException failure = null;
			try
			{
				manager.deleteStaleSessions(Duration.ofDays(7));
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(Files.readString(manifest), "manifest").contains("\"state\": \"running\"");
			requireThat(harness.stopRequested(), "terminationRequested").isFalse();
		}
	}

	/**
	 * Verifies the stale collector skips a terminal nested session while its owner lock remains held.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsLockedStaleSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-results-"));
			Clock oldClock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), UTC);
			NestedSessionManager oldManager = new NestedSessionManager(jvm.fileLocks(),
				root, oldClock, Yielding.fileReplacer());
			NestedSession nestedSession = oldManager.create("harness");
			oldManager.fail(nestedSession, "failed");
			Clock currentClock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), UTC);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
				root, currentClock, Yielding.fileReplacer());

			try (NestedSessionLock lock = manager.acquireLock(nestedSession))
			{
				requireThat(lock.isHeld(), "lockHeld").isTrue();
				manager.deleteStaleSessions(Duration.ofDays(7));
			}

			requireThat(Files.exists(nestedSession.directory()), "sessionFolder").isTrue();
		}
	}

	/**
	 * Verifies that a nested session records the harness's own identifier of its session once, that a later report of the
	 * same identifier keeps it, and that a report of another identifier is refused.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsHarnessSessionOnce() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			requireThat(manager.harnessSessionId(nestedSession), "before").isEmpty();
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "unreported").
				contains("\"harness_session_id\": null");

			manager.recordHarnessSession(nestedSession, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
			manager.recordHarnessSession(nestedSession, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
			manager.markIdle(nestedSession);

			requireThat(manager.harnessSessionId(nestedSession), "recorded").
				isEqualTo(Optional.of("5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90"));
			requireThat(Files.readString(nestedSession.directory().resolve("session.json")), "manifest").
				contains("\"harness_session_id\": \"5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90\"");
			IOException other = expectThrows(IOException.class, () -> manager.recordHarnessSession(nestedSession,
				"019a7c3e-8d21-7f40-b6a2-5c9e1d3f8a07"));
			requireThat(other.getMessage(), "other").contains("019a7c3e-8d21-7f40-b6a2-5c9e1d3f8a07");
			requireThat(manager.harnessSessionId(nestedSession), "kept").
				isEqualTo(Optional.of("5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90"));
		}
	}

	/**
	 * Verifies that the manifest of a nested session written before nested sessions recorded their harness session and
	 * their session requests reads as a nested session whose harness reported no session and that accepted no session
	 * request.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void earlierManifestHasNoHarnessSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("harness");
			Path manifest = nestedSession.directory().resolve("session.json");
			Files.writeString(manifest, Files.readString(manifest).
				replace("\"schema_version\": 6", "\"schema_version\": 4").
				replace("\n  \"harness_session_id\": null,", "").
				replace("\n  \"requests_used\": 0,", ""));

			requireThat(manager.harnessSessionId(nestedSession), "harnessSessionId").isEmpty();
			requireThat(manager.requestsUsed(nestedSession), "requestsUsed").isEqualTo(0);
			manager.markIdle(nestedSession);
			requireThat(Files.readString(manifest), "rewritten").contains("\"schema_version\": 6").
				contains("\"harness_session_id\": null").contains("\"requests_used\": 0");
		}
	}

	/**
	 * Verifies that a runner that resumes a nested session takes the options that the nested session's start fixed,
	 * followed by its own, and that one that names an option that the start fixed is refused.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void resumedRunnerTakesLaunchFixedAtStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-results-"));
			List<String> fixed = List.of("--project", "/source", "--deny-read", "/secret");
			String harness = "codex";
			NestedSessionManager manager = new NestedSessionManager(new FileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("s-1").withLaunchArguments(fixed);
			manager.create(harness);
			JsonMapper json = JsonMapper.builder().build();
			List<String> own = List.of("--results", root.toString(), "--slot-id", "s-1", "--resume-slot",
				"5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90", "--model", "model-a", "--effort", "high");

			String[] resumed = NestedSessionLaunch.resumedArguments(json, harness, own.toArray(String[]::new));

			List<String> expected = new ArrayList<>(fixed);
			expected.addAll(own);
			requireThat(List.of(resumed), "resumed").isEqualTo(expected);

			List<String> served = new ArrayList<>(own);
			served.addFirst("serve");
			List<String> servedExpected = new ArrayList<>(expected);
			servedExpected.addFirst("serve");
			requireThat(List.of(NestedSessionLaunch.resumedArguments(json, harness, served.toArray(String[]::new))),
				"served").isEqualTo(servedExpected);

			List<String> refixed = new ArrayList<>(own);
			refixed.addAll(List.of("--deny-read", "/other"));
			InvalidArgumentsException refused = expectThrows(InvalidArgumentsException.class,
				() -> NestedSessionLaunch.resumedArguments(json, harness, refixed.toArray(String[]::new)));
			requireThat(refused.getMessage(), "refused").contains("--deny-read");
			String[] started = {"--project", "/source", "--model", "model-a"};
			requireThat(List.of(NestedSessionLaunch.resumedArguments(json, harness, started)), "started").
				isEqualTo(List.of(started));
		}
	}

	/**
	 * Verifies that a nested session in a slot records, at its creation, the launch options that its start fixed, so that
	 * a later runner can continue its session as it started, and that a nested session that names none records none.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void recordsLaunchFixedAtStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-results-"));
			List<String> fixed = NestedSessionLaunch.fixedArguments(new String[]{"--project", "/source", "--model", "haiku",
				"--deny-read", "/secret", "--install-cat", "--output", "/out.jsonl", "--session-role", "validator",
				"--agent", "cat:reviewer", "--effort", "low"});
			requireThat(fixed, "fixed").isEqualTo(List.of("--project", "/source", "--deny-read", "/secret",
				"--install-cat", "--session-role", "validator", "--agent", "cat:reviewer"));
			NestedSessionManager manager = new NestedSessionManager(new FileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("s-1").withLaunchArguments(fixed);
			NestedSession nestedSession = manager.create("claude");

			requireThat(manager.launchArguments(nestedSession), "launchArguments").isEqualTo(fixed);
			JsonNode launch = JsonMapper.builder().build().readTree(nestedSession.directory().resolve(
				"launch.json").toFile());
			requireThat(launch.path("schema_version").asInt(), "schemaVersion").isEqualTo(1);

			NestedSessionManager unrecorded = new NestedSessionManager(new FileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession other = unrecorded.create("claude");
			requireThat(Files.exists(other.directory().resolve("launch.json")), "recorded").isFalse();
			IOException missing = expectThrows(IOException.class, () -> unrecorded.launchArguments(other));
			requireThat(missing.getMessage(), "missing").contains("records no launch");
		}
	}

	/**
	 * Verifies that a launch record with a field that it does not define, or of another schema, is refused rather
	 * than read in part.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void refusesMalformedLaunchRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(new FileLocks(), root, Clock.systemUTC(),
				Yielding.fileReplacer()).withLaunchArguments(List.of("--install-cat"));
			NestedSession nestedSession = manager.create("claude");
			Path launch = nestedSession.directory().resolve("launch.json");
			for (String text : List.of("{\"schema_version\":1,\"arguments\":[\"--install-cat\"],\"extra\":1}",
				"{\"schema_version\":2,\"arguments\":[]}", "{\"schema_version\":1,\"arguments\":[1]}",
				"{\"schema_version\":1}", "[]"))
			{
				Files.writeString(launch, text, UTF_8);
				IOException failure = expectThrows(IOException.class, () -> manager.launchArguments(nestedSession));
				requireThat(failure.getMessage(), "failure").contains("launch.json");
			}
		}
	}

	/**
	 * Verifies that a nested session whose runner ended after its harness reported its session reopens for a new runner
	 * whatever its age, which records the new runner as its owner and keeps its turns, its session and its count of
	 * accepted session requests.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void reopensInterruptedSessionOfAnyAge() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			// No process is alive, so the runner that owned the nested session has ended, along with its harness
			NestedSessionManager first = manager(jvm, root, Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), UTC));
			NestedSession nestedSession = first.create("harness");
			Path checkpoint = nestedSession.directory().resolve("turn-checkpoint.json");
			String priorCheckpoint = "{\"format_version\":1,\"turn_number\":4,\"state\":\"turn_in_progress\"}";
			Files.writeString(checkpoint, priorCheckpoint);
			first.markHarnessStarting(nestedSession);
			first.recordHarnessProcess(nestedSession, 4321, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125");
			first.recordHarnessSession(nestedSession, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
			first.recordRequestsUsed(nestedSession, 2);
			first.writeTurnOutput(nestedSession, 1, new HarnessProcessResult(0, "first", ""));
			first.recordTurnEnded(nestedSession, 1);
			Files.writeString(nestedSession.directory().resolve("home/session.jsonl"), "kept");
			NestedSessionManager later = manager(jvm, root, Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), UTC));

			NestedSession reopened = later.reopen("harness", nestedSession.id());

			requireThat(reopened.directory(), "directory").isEqualTo(nestedSession.directory());
			String manifest = Files.readString(nestedSession.directory().resolve("session.json"));
			requireThat(manifest, "manifest").contains("\"state\": \"running\"").
				contains("\"owner_pid\": " + ProcessHandle.current().pid() + ",").
				contains("\"harness_process_state\": \"absent\"").
				contains("\"harness_pid\": null").
				contains("\"turn_number\": 1").
				contains("\"harness_session_id\": \"5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90\"");
			requireThat(later.requestsUsed(reopened), "requestsUsed").isEqualTo(2);
			requireThat(nestedSession.directory().resolve("home/session.jsonl"), "home").isRegularFile();
			requireThat(Files.readString(checkpoint), "checkpoint").isEqualTo(priorCheckpoint);
		}
	}

	/**
	 * Verifies that a manager of a slot resumes the slot's nested session only for the harness session that the nested
	 * session records, and that the resumed nested session's turns continue after the last turn that ended.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void resumesSlotOfItsSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			NestedSessionManager first = manager(jvm, root, Clock.systemUTC()).withFixedSlotId("s-1");
			NestedSession nestedSession = first.create("harness");
			first.recordHarnessSession(nestedSession, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
			first.writeTurnOutput(nestedSession, 2, new HarnessProcessResult(0, "second", ""));
			first.recordTurnEnded(nestedSession, 2);
			NestedSessionManager later = manager(jvm, root, Clock.systemUTC()).withFixedSlotId("s-1");

			IOException other = expectThrows(IOException.class,
				() -> later.resume("harness", "0b9e8d7c-6a5f-4e3d-8c2b-1a0f9e8d7c6b"));
			NestedSession resumed = later.resume("harness", "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");

			requireThat(other.getMessage(), "other").contains("0b9e8d7c-6a5f-4e3d-8c2b-1a0f9e8d7c6b");
			requireThat(resumed.directory(), "directory").isEqualTo(nestedSession.directory());
			requireThat(later.turnNumber(resumed), "turnNumber").isEqualTo(2);
		}
	}

	/**
	 * Verifies that a nested session does not reopen while its runner lives, while its harness launch state is unknown,
	 * before its harness reported its session, or once it has ended.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void refusesToReopenUnresumableSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), UTC);
			NestedSessionManager live = new NestedSessionManager(jvm.fileLocks(), root, clock, Yielding.fileReplacer());
			NestedSession owned = live.create("harness");
			live.recordHarnessSession(owned, "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
			IOException ownerAlive = expectThrows(IOException.class, () -> live.reopen("harness", owned.id()));
			requireThat(ownerAlive.getMessage(), "ownerAlive").contains("live");

			NestedSessionManager ended = manager(jvm, root, clock);
			NestedSession starting = ended.create("harness");
			ended.recordHarnessSession(starting, "019a7c3e-8d21-7f40-b6a2-5c9e1d3f8a07");
			ended.markHarnessStarting(starting);
			IOException launchUnknown = expectThrows(IOException.class, () -> ended.reopen("harness", starting.id()));
			requireThat(launchUnknown.getMessage(), "launchUnknown").contains("launch");

			NestedSession unreported = ended.create("harness");
			IOException noSession = expectThrows(IOException.class, () -> ended.reopen("harness", unreported.id()));
			requireThat(noSession.getMessage(), "noSession").contains("session");

			NestedSession completed = ended.create("harness");
			ended.recordHarnessSession(completed, "7d3e9b21-4c5a-4f8e-a1b6-0e2c7f9d5a13");
			ended.markCompleted(completed);
			IOException finished = expectThrows(IOException.class, () -> ended.reopen("harness", completed.id()));
			requireThat(finished.getMessage(), "finished").contains("ended");
			requireThat(Files.readString(completed.directory().resolve("session.json")), "completedManifest").
				contains("\"state\": \"completed\"");
		}
	}

	/**
	 * Returns the ways in which a runner is stopped: by its server, and by a signal to its process alone, after which
	 * the processes that it started are stopped, as the stop of its server's process tree stops them.
	 *
	 * @return the ways
	 */
	@DataProvider
	public Object[][] runnerStops()
	{
		return new Object[][]{{"server"}, {"signal"}};
	}

	/**
	 * Verifies that a runner that is stopped while its nested session runs stops its harness, but records no end for the
	 * nested session and keeps its home, so that the nested session's harness session can continue.
	 * <p>
	 * Each case starts a fixture JVM and waits for it to publish its child's process ID. Both processes are needed to
	 * check termination alongside the session files that survive it.
	 *
	 * @param stop how the runner is stopped
	 * @throws IOException          if the filesystem operation fails or the runner cannot start
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(dataProvider = "runnerStops", timeOut = 120_000)
	public void stoppedRunnerRecordsNoEnd(String stop) throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-results-"));
			String executable = "java";
			if (Platform.current().isWindows())
				executable = "java.exe";
			Path java = Path.of(System.getProperty("java.home"), "bin", executable);
			String classes = System.getProperty("jdk.module.path", System.getProperty("java.class.path"));
			try (Process runner = new ProcessBuilder(java.toString(), "-XX:+PerfDisableSharedMem",
				"-Djava.io.tmpdir=" + root, "-cp", classes, InterruptedRunnerProcess.class.getName(), "runner",
				root.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start())
			{
				String[] ready = new BufferedReader(new InputStreamReader(runner.getInputStream(), UTF_8)).readLine().
					split(" ");
				Path sessionFolder = Path.of(ready[0]);
				long harnessPid = Long.parseLong(ready[1]);
				if (stop.equals("server"))
					HarnessProcessTermination.stopRunner(runner);
				else
				{
					List<ProcessHandle> tree = runner.descendants().toList();
					runner.destroy();
					runner.waitFor();
					tree.forEach(ProcessHandle::destroy);
				}
				Optional<ProcessHandle> harness = ProcessHandle.of(harnessPid);
				if (harness.isPresent())
					harness.get().onExit().join();

				requireThat(Files.readString(sessionFolder.resolve("session.json")), "manifest").
					contains("\"state\": \"running\"").
					contains("\"harness_session_id\": \"" + InterruptedRunnerProcess.HARNESS_SESSION_ID + "\"");
				requireThat(sessionFolder.resolve("home"), "home").isDirectory();
			}
		}
	}

	/**
	 * Creates a manager whose real process checker reads a table of fake processes in place of the operating system's
	 * process table.
	 *
	 * @param jvm       the test's JVM scope, which supplies the lock coordinator
	 * @param root      the absolute owned-results folder
	 * @param clock     the clock used for generated identifiers
	 * @param processes the processes of the table; every other process identifier names no process
	 * @return the manager
	 */
	private static NestedSessionManager manager(TestJvmScope jvm, Path root, Clock clock, FakeProcessHandle... processes)
	{
		return manager(jvm, root, clock, FakeProcessHandle.processes(processes), FakeProcessHandle.table(processes));
	}

	/**
	 * Creates a manager whose real process checker reads the supplied processes in place of the operating system's.
	 *
	 * @param jvm       the test's JVM scope, which supplies the lock coordinator
	 * @param root      the absolute owned-results folder
	 * @param clock     the clock used for generated identifiers
	 * @param processes reports the starts of running processes
	 * @param handles   finds the process with an identifier
	 * @return the manager
	 */
	private static NestedSessionManager manager(TestJvmScope jvm, Path root, Clock clock, HarnessProcesses processes,
		LongFunction<Optional<ProcessHandle>> handles)
	{
		ProcessHandleLiveness liveness = ProcessHandleLivenessSecrets.getProcessHandleLivenessAccess().create(processes,
			handles);
		return NestedSessionManagerSecrets.getNestedSessionManagerAccess().newNestedSessionManager(jvm.fileLocks(), root,
			clock, Yielding.fileReplacer(), liveness);
	}

	/**
	 * Replaces the owner process that a nested session's manifest records, standing in for a nested session that another
	 * orchestrator process created.
	 *
	 * @param nestedSession       the nested session
	 * @param pid   the owner's process identifier
	 * @param start the owner's start
	 * @throws IOException if the manifest cannot be read or written
	 */
	private static void recordOwner(NestedSession nestedSession, long pid, String start) throws IOException
	{
		Path manifest = nestedSession.directory().resolve("session.json");
		Files.writeString(manifest, Files.readString(manifest).
			replaceFirst("\"owner_pid\": [0-9]+", "\"owner_pid\": " + pid).
			replaceFirst("\"owner_started_at\": (null|\"[^\"]+\")", "\"owner_started_at\": \"" + start + "\""));
	}
}
