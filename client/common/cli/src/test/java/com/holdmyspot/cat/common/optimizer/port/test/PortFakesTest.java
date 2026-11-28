package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies that the port fakes behave as the port contracts describe, so that tests built on them observe the same
 * behaviour as the real adapters.
 */
public final class PortFakesTest
{
	/**
	 * The model and reasoning effort of a session whose profile the test does not examine.
	 */
	private static final TrialProfile SESSION_PROFILE = new TrialProfile("model-a", "low");
	/**
	 * Creates the test.
	 */
	public PortFakesTest()
	{
	}

	/**
	 * Returns a trial request.
	 *
	 * @param turn the only turn
	 * @return the request
	 */
	private static SessionRequest request(String turn)
	{
		return new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, Path.of("/staged").toAbsolutePath(), List.of(turn),
			List.of(), Duration.ofMinutes(1), SESSION_PROFILE);
	}

	/**
	 * Verifies that fake sessions end in the order the test chooses.
	 *
	 * @throws IOException if a fake session fails to start
	 */
	@Test
	public void sessionsEndInTestOrder() throws IOException
	{
		FakeSessionPort port = new FakeSessionPort();
		SessionHandle first = port.start(request("one"));
		SessionHandle second = port.start(request("two"));
		List<FakeSession> sessions = port.sessions();
		requireThat(sessions.size(), "sessions.size()").isEqualTo(2);
		requireThat(sessions.get(0).request().turns(), "turns").isEqualTo(List.of("one"));

		sessions.get(1).end(new SessionResult.Failed("provider error", List.of()));
		requireThat(second.completion().toCompletableFuture().isDone(), "second.isDone").isTrue();
		requireThat(first.completion().toCompletableFuture().isDone(), "first.isDone").isFalse();
	}

	/**
	 * Verifies that interrupting a fake session records the request, which tests of the code that interrupts
	 * sessions read. Whether the interrupt ends the session's result is a shared port check.
	 *
	 * @throws IOException if a fake session fails to interrupt
	 */
	@Test
	public void recordsInterruptPerSession() throws IOException
	{
		FakeSessionPort port = new FakeSessionPort();
		SessionHandle interrupted = port.start(request("one"));
		port.start(request("two"));

		interrupted.interrupt();

		requireThat(port.sessions().get(0).isInterruptRequested(), "interruptedRequested").isTrue();
		requireThat(port.sessions().get(1).isInterruptRequested(), "otherRequested").isFalse();
	}

	/**
	 * Verifies the fake workspace's staged and authoring-copy directory state and its materialized worktree.
	 * Shared workspace checks establish the corresponding adapter behavior.
	 *
	 * @throws IOException if the fake fails
	 */
	@Test
	public void workspaceTracksItsDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fake-workspace-"));
			FileInventory fork = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of());
			FakeWorkspacePort workspace = new FakeWorkspacePort(root.resolve("workspace"), fork);
			Overlay incumbent = Overlay.of(fork);

			Path staged = workspace.stage(incumbent, Set.of());
			requireThat(workspace.staged(), "staged").containsExactly(List.of(incumbent.identity()));
			requireThat(Files.isDirectory(staged), "stagedDirectory").isTrue();
			workspace.deleteStaged(incumbent.identity());
			requireThat(workspace.staged(), "staged").isEmpty();
			requireThat(Files.exists(staged), "stagedExists").isFalse();

			Path copy = workspace.prepareAuthoringCopy(incumbent);
			requireThat(workspace.authoringCopies(), "preparedCopies").containsExactly(List.of(copy));
			workspace.deleteAuthoringCopy(copy);
			requireThat(workspace.authoringCopies(), "authoringCopies").isEmpty();

			Overlay edited = incumbent.write(Path.of("prompt.md"), new FileContent("edited".getBytes(UTF_8)),
				PosixFilePermissions.fromString("rw-r--r--"));
			workspace.materialize(edited);
			requireThat(workspace.scanWorktree(), "worktree").isEqualTo(edited.snapshot());
		}
	}

	/**
	 * Verifies that the fake reports the login it was given, and that a fake without a login reports none.
	 */
	@Test
	public void reportsTheConfiguredHostLogin()
	{
		requireThat(new FakeSessionPort(HostLogin.API_KEY).hostLogin(), "configured").isEqualTo(HostLogin.API_KEY);
		requireThat(new FakeSessionPort().hostLogin(), "default").isEqualTo(HostLogin.UNKNOWN);
	}

	/**
	 * Verifies that a completed result must carry the observed prompt sources.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void completedRequiresPromptSources()
	{
		new SessionResult.Completed(Path.of("transcript.jsonl"), List.of(List.of("ok")), Path.of("effects"),
			Path.of("initial-project"), "model", null,
			null, List.of(), " ", List.of());
	}
}
