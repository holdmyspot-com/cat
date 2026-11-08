package com.holdmyspot.cat.common.runner.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * The behavior that a runner sees from every {@link HarnessProcess}. A test class extends this class for each
 * implementation and starts it over a harness that follows a {@link Turn} script: {@link ScriptedProcess}, which tests
 * use in place of a harness, and each harness module's adapter over an in-memory stand-in for the harness program
 * that prints the harness's recorded protocol lines. A case that passes for the double must pass for every adapter, so
 * the double has only behavior that the adapters show. Where harnesses differ, a subclass says which way its harness
 * behaves and the case checks that way.
 */
public abstract class HarnessProcessContract
{
	/**
	 * Creates the contract.
	 */
	protected HarnessProcessContract()
	{
	}

	/**
	 * A started harness process and what its harness did.
	 */
	protected interface Harness
	{
		/**
		 * Returns the process under test.
		 *
		 * @return the process
		 */
		HarnessProcess process();

		/**
		 * Returns the lines that the harness printed during a turn, which the process must relay. A harness that speaks
		 * a protocol prints its protocol lines in place of the turn's scripted events.
		 *
		 * @param turn the turn's index in the script, starting at 0
		 * @return the lines in the order the harness printed them
		 */
		List<String> printed(int turn);

		/**
		 * Returns what the user told the harness: each turn's prompt and each steering direction.
		 *
		 * @return the texts in the order the harness received them
		 */
		List<String> received();

		/**
		 * Returns the file in which the harness writes the session's transcript.
		 *
		 * @return the file, or {@code null} if the process keeps no session transcripts
		 */
		Path transcript();
	}

	/**
	 * Starts a process over a harness that follows a script.
	 *
	 * @param script    what the harness does for each turn, in turn order
	 * @param directory a new directory below which the harness writes the session's transcript, in {@code home}, and
	 *                  closing the process copies it, into {@code copies}; or {@code null} if the process keeps no
	 *                  session transcripts
	 * @return the started harness
	 * @throws IOException if the process cannot start
	 */
	protected abstract Harness start(List<Turn> script, Path directory) throws IOException;

	/**
	 * Indicates whether the harness accepts a steering direction while a turn is in progress. A harness that does not
	 * makes {@link HarnessProcess#steer(String)} fail and the turn go on.
	 *
	 * @return {@code true} if it does
	 */
	protected abstract boolean steers();

	/**
	 * Indicates whether a turn that the caller interrupts completes, with the reply so far, instead of failing.
	 *
	 * @return {@code true} if it completes
	 */
	protected abstract boolean completesInterruptedTurn();

	/**
	 * Verifies that a turn the harness replies to completes with exit status 0, no diagnostic, every assistant message
	 * in order and the last one as its response, and that the process relays each line the harness printed.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void completesTurnWithReply() throws IOException
	{
		Harness harness = start(List.of(Turn.replies(List.of("interim with a \"quote\"", "final"), "harness line")),
			null);
		try (HarnessProcess process = harness.process())
		{
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("first", relayed::add);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.exitCode(), "exitCode").isEqualTo(0);
			requireThat(result.standardError(), "standardError").isEmpty();
			requireThat(result.assistantMessages(), "assistantMessages").
				isEqualTo(List.of("interim with a \"quote\"", "final"));
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("final");
			requireThat(relayed, "relayed").isEqualTo(harness.printed(0));
			requireThat(result.standardOutput(), "standardOutput").isEqualTo(lines(harness.printed(0)));
		}
	}

	/**
	 * Verifies that one process serves every turn in order until it is closed, and is alive until then.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void servesTurnsUntilClosed() throws IOException
	{
		Harness harness = start(List.of(Turn.replies("one"), Turn.replies("two")), null);
		HarnessProcess process = harness.process();
		try (process)
		{
			requireThat(process.sendTurn("first").assistantResponse(), "firstResponse").isEqualTo("one");
			requireThat(process.isAlive(), "aliveAfterFirstTurn").isTrue();
			requireThat(process.sendTurn("second").assistantResponse(), "secondResponse").isEqualTo("two");
			requireThat(process.isAlive(), "aliveAfterSecondTurn").isTrue();
		}
		requireThat(process.isAlive(), "aliveAfterClose").isFalse();
		requireThat(harness.received(), "received").isEqualTo(List.of("first", "second"));
	}

	/**
	 * Returns exit statuses of a harness that exits during a turn.
	 *
	 * @return a clean exit and a failure status
	 */
	@DataProvider
	public Object[][] exitStatuses()
	{
		return new Object[][]{{0}, {143}};
	}

	/**
	 * Verifies that a turn during which the harness exits without a result fails with the harness's exit status and a
	 * diagnostic, and that the process is no longer alive.
	 *
	 * @param exitStatus the harness's exit status
	 * @throws IOException if the turn cannot be sent
	 */
	@Test(dataProvider = "exitStatuses", timeOut = 10_000)
	public void reportsTurnEndedWithoutResult(int exitStatus) throws IOException
	{
		Harness harness = start(List.of(Turn.exits(exitStatus, "partial harness event")), null);
		try (HarnessProcess process = harness.process())
		{
			HarnessProcessResult result = process.sendTurn("first");

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.exitCode(), "exitCode").isEqualTo(exitStatus);
			requireThat(result.standardError(), "standardError").isNotBlank();
			requireThat(process.isAlive(), "alive").isFalse();
		}
	}

	/**
	 * Verifies that a turn the harness rejects fails with an exception.
	 *
	 * @throws IOException if the turn fails as expected
	 */
	@Test(expectedExceptions = IOException.class, timeOut = 10_000)
	public void failsRejectedTurn() throws IOException
	{
		Harness harness = start(List.of(Turn.rejected()), null);
		try (HarnessProcess process = harness.process())
		{
			process.sendTurn("first");
		}
	}

	/**
	 * Verifies that interrupting a turn in progress ends it: it completes without a reply where the harness completes
	 * an interrupted turn, and fails otherwise.
	 *
	 * @throws IOException if the interrupt cannot be sent
	 */
	@Test(timeOut = 10_000)
	public void endsInterruptedTurn() throws IOException
	{
		Harness harness = start(List.of(Turn.blocksUntilInterrupted("harness line")), null);
		try (HarnessProcess process = harness.process())
		{
			AtomicBoolean interrupted = new AtomicBoolean();
			HarnessProcessResult result;
			try
			{
				result = process.sendTurn("first", _ ->
				{
					if (interrupted.compareAndSet(false, true))
						process.interrupt();
				});
			}
			catch (IOException e)
			{
				requireThat(completesInterruptedTurn(), "completesInterruptedTurn").withContext(e, "failure").isFalse();
				return;
			}
			requireThat(completesInterruptedTurn(), "completesInterruptedTurn").isTrue();
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEmpty();
			requireThat(result.assistantMessages(), "assistantMessages").isEmpty();
		}
	}

	/**
	 * Verifies that an interrupt ends only the turn in progress, whether the harness completes or fails that turn: the
	 * process stays alive and its next turn completes with its reply.
	 *
	 * @throws IOException if the next turn fails
	 */
	@Test(timeOut = 10_000)
	public void servesTurnAfterInterruptedTurn() throws IOException
	{
		Harness harness = start(List.of(Turn.blocksUntilInterrupted("harness line"), Turn.replies("two")), null);
		try (HarnessProcess process = harness.process())
		{
			AtomicBoolean interrupted = new AtomicBoolean();
			try
			{
				process.sendTurn("first", _ ->
				{
					if (interrupted.compareAndSet(false, true))
						process.interrupt();
				});
			}
			catch (IOException e)
			{
				requireThat(completesInterruptedTurn(), "completesInterruptedTurn").withContext(e, "failure").isFalse();
			}
			requireThat(process.isAlive(), "isAlive").isTrue();

			HarnessProcessResult result = process.sendTurn("second");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("two");
		}
	}

	/**
	 * Verifies that an interrupt between turns changes nothing: the next turn completes with its reply.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptBetweenTurns() throws IOException
	{
		Harness harness = start(List.of(Turn.replies("one"), Turn.replies("two")), null);
		try (HarnessProcess process = harness.process())
		{
			process.sendTurn("first");
			process.interrupt();

			HarnessProcessResult result = process.sendTurn("second");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("two");
		}
	}

	/**
	 * Verifies that sending a prepared turn ends its preparation: an interrupt after the turn ends changes nothing, and
	 * the next turn completes with its reply.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterPreparedTurnEnds() throws IOException
	{
		Harness harness = start(List.of(Turn.replies("one"), Turn.replies("two")), null);
		try (HarnessProcess process = harness.process())
		{
			process.prepareTurn();
			process.sendTurn("first");
			process.interrupt();

			HarnessProcessResult result = process.sendTurn("second");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("two");
		}
	}

	/**
	 * Verifies that an interrupt requested once the caller has prepared a turn, before the turn is sent, stops that
	 * turn once it is sent: it completes without a reply where the harness completes an interrupted turn, and fails
	 * otherwise. The turn runs on its own thread so that a process that drops the interrupt fails the test instead of
	 * leaving the test's thread waiting.
	 *
	 * @throws IOException          if the interrupt cannot be sent or the process cannot close
	 * @throws InterruptedException if the test is interrupted while it waits for the turn
	 */
	@Test(timeOut = 10_000)
	public void stopsPreparedTurnInterruptedEarly() throws IOException, InterruptedException
	{
		Harness harness = start(List.of(Turn.blocksUntilInterrupted("harness line")), null);
		try (HarnessProcess process = harness.process())
		{
			process.prepareTurn();
			process.interrupt();
			CompletableFuture<HarnessProcessResult> turn = new CompletableFuture<>();
			Thread.ofPlatform().daemon().name("turn-under-test").start(() ->
			{
				try
				{
					turn.complete(process.sendTurn("first"));
				}
				catch (IOException | RuntimeException e)
				{
					turn.completeExceptionally(e);
				}
			});
			HarnessProcessResult result;
			try
			{
				result = turn.get(5, TimeUnit.SECONDS);
			}
			catch (ExecutionException e)
			{
				requireThat(e.getCause(), "failure").isInstanceOf(IOException.class);
				requireThat(completesInterruptedTurn(), "completesInterruptedTurn").withContext(e.getCause(), "failure").
					isFalse();
				return;
			}
			catch (TimeoutException e)
			{
				// Closing the process ends the harness, which ends the turn that dropped the interrupt
				throw new AssertionError("The interrupt requested before the prepared turn was sent did not stop it", e);
			}
			requireThat(completesInterruptedTurn(), "completesInterruptedTurn").isTrue();
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEmpty();
			requireThat(result.assistantMessages(), "assistantMessages").isEmpty();
		}
	}

	/**
	 * Verifies that a turn in progress fails once the thread that runs it is interrupted, so that a caller can stop a
	 * turn that would otherwise not end, and that the process restores the thread's interrupt status. The turn runs on
	 * its own thread, which interrupts itself once the harness has printed a line, so that a process whose turn ignores
	 * the interrupt fails the test instead of leaving the test's thread waiting.
	 *
	 * @throws IOException          if the process cannot close
	 * @throws InterruptedException if the test is interrupted while it waits for the turn
	 */
	@Test(timeOut = 10_000)
	public void failsTurnWhoseThreadIsInterrupted() throws IOException, InterruptedException
	{
		Harness harness = start(List.of(Turn.blocksUntilInterrupted("harness line")), null);
		try (HarnessProcess process = harness.process())
		{
			// Completes with whether the failed turn left its thread's interrupt status set
			CompletableFuture<Boolean> failed = new CompletableFuture<>();
			Thread.ofPlatform().daemon().name("turn-under-test").start(() ->
			{
				try
				{
					process.sendTurn("first", _ -> Thread.currentThread().interrupt());
					failed.completeExceptionally(new AssertionError("The turn did not fail"));
				}
				catch (IOException _)
				{
					failed.complete(Thread.interrupted());
				}
			});
			boolean interruptRestored;
			try
			{
				interruptRestored = failed.get(5, TimeUnit.SECONDS);
			}
			catch (ExecutionException e)
			{
				throw new AssertionError(e.getCause());
			}
			catch (TimeoutException e)
			{
				// Closing the process ends the harness, which ends the turn that ignored the interrupt
				throw new AssertionError("The turn did not fail once its thread was interrupted", e);
			}
			requireThat(interruptRestored, "interruptRestored").isTrue();
		}
	}

	/**
	 * Verifies that a steering direction sent while a turn is in progress reaches a harness that accepts steering and
	 * fails for one that does not, and that the turn completes either way.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void steersActiveTurn() throws IOException
	{
		Harness harness = start(List.of(Turn.replies("one", "harness line")), null);
		try (HarnessProcess process = harness.process())
		{
			List<IOException> refusals = new ArrayList<>();
			AtomicBoolean steered = new AtomicBoolean();

			HarnessProcessResult result = process.sendTurn("first", _ ->
			{
				if (!steered.compareAndSet(false, true))
					return;
				try
				{
					process.steer("change course");
				}
				catch (IOException e)
				{
					refusals.add(e);
				}
			});

			requireThat(result.completed(), "completed").isTrue();
			if (steers())
			{
				requireThat(refusals, "refusals").isEmpty();
				requireThat(harness.received(), "received").isEqualTo(List.of("first", "change course"));
			}
			else
			{
				requireThat(refusals, "refusals").size().isEqualTo(1);
				requireThat(harness.received(), "received").isEqualTo(List.of("first"));
			}
		}
	}

	/**
	 * Verifies that a process that keeps no session transcripts names none.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void namesNoTranscriptUnlessKept() throws IOException
	{
		Harness harness = start(List.of(Turn.replies("one")), null);
		try (HarnessProcess process = harness.process())
		{
			process.sendTurn("first");

			requireThat(process.sessionTranscript(), "sessionTranscript").isEmpty();
		}
	}

	/**
	 * Verifies that a process that keeps session transcripts names the session's transcript once a turn has run, and
	 * copies it when it closes.
	 *
	 * @throws IOException if the turn fails or the transcript cannot be copied
	 */
	@Test(timeOut = 10_000)
	public void copiesTranscriptOnClose() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("harness-process-"));
			Harness harness = start(List.of(Turn.replies("one")), directory);
			Path transcript = harness.transcript();
			try (HarnessProcess process = harness.process())
			{
				Optional<Path> beforeTurn = process.sessionTranscript();
				if (beforeTurn.isPresent())
					requireThat(beforeTurn.get(), "beforeTurn").isEqualTo(transcript);

				process.sendTurn("first");

				requireThat(process.sessionTranscript(), "sessionTranscript").isEqualTo(Optional.of(transcript));
				// The harness writes the transcript as it works on the session
				Files.createDirectories(transcript.getParent());
				Files.writeString(transcript, "{\"session\":\"one\"}\n");
			}

			List<Path> copies;
			try (Stream<Path> files = Files.walk(directory.resolve("copies")))
			{
				copies = files.filter(Files::isRegularFile).toList();
			}
			requireThat(copies, "copies").isEqualTo(List.of(directory.resolve("copies").
				resolve(transcript.getFileName())));
			requireThat(Files.readString(copies.getFirst()), "copy").isEqualTo("{\"session\":\"one\"}\n");
		}
	}

	/**
	 * Verifies that closing a process that keeps session transcripts fails if it cannot copy them.
	 *
	 * @throws IOException if closing fails as expected
	 */
	@Test(expectedExceptions = IOException.class, timeOut = 10_000)
	public void reportsTranscriptCopyFailureOnClose() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("harness-process-"));
			// A file where the copies' directory belongs makes the copy fail
			Files.writeString(directory.resolve("copies"), "");
			Harness harness = start(List.of(Turn.replies("one")), directory);
			try (HarnessProcess process = harness.process())
			{
				process.sendTurn("first");
			}
		}
	}

	/**
	 * Returns lines as one text, each followed by a line terminator.
	 *
	 * @param lines the lines
	 * @return the text
	 */
	private static String lines(List<String> lines)
	{
		StringBuilder text = new StringBuilder();
		for (String line : lines)
			text.append(line).append('\n');
		return text.toString();
	}
}
