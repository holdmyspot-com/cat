package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort.FakeSession;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Verifies that the port fakes behave as the port contracts describe, so that tests built on them observe the same
 * behaviour as the real adapters.
 */
public final class PortFakesTest
{
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
		return new SessionRequest(SessionRole.TRIAL, Path.of("/staged"), List.of(turn), List.of(),
			Duration.ofMinutes(1));
	}

	/**
	 * Verifies that fake sessions end in the order the test chooses.
	 */
	@Test
	public void sessionsEndInTestOrder()
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
	 * Verifies that interrupting a running fake session ends it as interrupted, and that interrupting an ended one
	 * leaves its result unchanged.
	 *
	 * @throws IOException if a fake session fails to interrupt
	 */
	@Test
	public void interruptEndsOnlyRunningSessions() throws IOException
	{
		FakeSessionPort port = new FakeSessionPort();
		SessionHandle running = port.start(request("one"));
		SessionHandle ended = port.start(request("two"));
		SessionResult failed = new SessionResult.Failed("provider error", List.of());
		port.sessions().get(1).end(failed);

		running.interrupt();
		ended.interrupt();

		requireThat(running.completion().toCompletableFuture().join(), "running").
			isInstanceOf(SessionResult.Interrupted.class);
		requireThat(ended.completion().toCompletableFuture().join(), "ended").isEqualTo(failed);
		requireThat(port.sessions().get(0).isInterruptRequested(), "isInterruptRequested").isTrue();
	}

	/**
	 * Verifies that the fake signal reports only interrupts recorded after the given instant.
	 */
	@Test
	public void interruptSignalComparesInstants()
	{
		FakeHostInterruptSignal signal = new FakeHostInterruptSignal();
		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		requireThat(signal.isInterruptedAfter(start), "beforeAnyInterrupt").isFalse();

		signal.interrupt(start);
		requireThat(signal.isInterruptedAfter(start), "atStart").isFalse();

		signal.interrupt(start.plusMillis(1));
		requireThat(signal.isInterruptedAfter(start), "afterStart").isTrue();
	}

	/**
	 * Verifies that the fake workspace tracks staged sources, authoring copies and materialized candidates.
	 */
	@Test
	public void workspaceTracksItsDirectories()
	{
		FileInventory fork = new FileInventory(Path.of("/project"), Map.of());
		FakeWorkspacePort workspace = new FakeWorkspacePort(Path.of("/workspace"), fork);
		Overlay incumbent = Overlay.of(fork);

		workspace.stage(incumbent);
		requireThat(workspace.staged(), "staged").containsExactly(List.of(incumbent.identity()));
		workspace.deleteStaged(incumbent.identity());
		requireThat(workspace.staged(), "staged").isEmpty();

		Path copy = workspace.prepareAuthoringCopy(incumbent);
		requireThat(workspace.scanAuthoringCopy(copy), "scan").isEqualTo(incumbent.snapshot());
		FileInventory edited = new FileInventory(Path.of("/edited"), Map.of());
		workspace.editAuthoringCopy(copy, edited);
		requireThat(workspace.scanAuthoringCopy(copy), "scan").isEqualTo(edited);
		workspace.deleteAuthoringCopy(copy);
		requireThat(workspace.authoringCopies(), "authoringCopies").isEmpty();

		workspace.materialize(incumbent);
		requireThat(workspace.materialized(), "materialized").isEqualTo(List.of(incumbent));
		requireThat(workspace.scanWorktree(), "worktree").isEqualTo(incumbent.snapshot());
	}
}
