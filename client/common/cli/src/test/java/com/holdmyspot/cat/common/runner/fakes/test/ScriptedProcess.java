package com.holdmyspot.cat.common.runner.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessEventListener;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.contract.test.HarnessProcessContract;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * A harness process that follows a script instead of running a harness. Each turn prints the turn's event lines and
 * then ends the way its {@link Turn} says.
 * <p>
 * {@link HarnessProcessContract} checks this behavior on this class and on each harness module's real adapter over a
 * stand-in for the harness program, so the double has only behavior that the adapters show. The adapters differ where
 * the contract says so: one harness fails a turn that the caller interrupts while this process completes it, and one
 * harness rejects mid-turn steering while this process accepts it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ScriptedProcess implements HarnessProcess
{
	private final List<Turn> script;
	private final List<String> received;
	private final Path transcript;
	private final HarnessSessionTranscripts transcripts;
	private final Object lock = new Object();
	/**
	 * The number of turns that have started; guarded by {@link #lock}.
	 */
	private int turns;
	/**
	 * Opens when the caller interrupts the turn in progress, or {@code null} between turns; guarded by {@link #lock}.
	 */
	private CountDownLatch activeTurn;
	/**
	 * Indicates whether the harness ended during a turn; guarded by {@link #lock}.
	 */
	private boolean ended;
	private volatile boolean closed;

	/**
	 * Creates a process that keeps no session transcript.
	 *
	 * @param script   what the harness does for each turn, in turn order
	 * @param received receives each turn prompt and each steering direction, in the order the harness receives them
	 * @throws NullPointerException if any argument is null
	 */
	public ScriptedProcess(List<Turn> script, List<String> received)
	{
		requireThat(script, "script").isNotNull();
		requireThat(received, "received").isNotNull();
		this.script = List.copyOf(script);
		this.received = received;
		this.transcript = null;
		this.transcripts = null;
	}

	/**
	 * Creates a process that keeps a session transcript.
	 *
	 * @param script      what the harness does for each turn, in turn order
	 * @param received    receives each turn prompt and each steering direction, in the order the harness receives
	 *                    them
	 * @param transcript  the file in which the harness writes the session's transcript
	 * @param transcripts the transcripts that closing the process copies
	 * @throws NullPointerException if any argument is null
	 */
	public ScriptedProcess(List<Turn> script, List<String> received, Path transcript,
		HarnessSessionTranscripts transcripts)
	{
		requireThat(script, "script").isNotNull();
		requireThat(received, "received").isNotNull();
		requireThat(transcript, "transcript").isNotNull();
		requireThat(transcripts, "transcripts").isNotNull();
		this.script = List.copyOf(script);
		this.received = received;
		this.transcript = transcript;
		this.transcripts = transcripts;
	}

	/**
	 * Plays the script's next turn.
	 * <p>
	 * The turn gives each of its event lines to the listener, then ends by its kind: a reply completes the turn with
	 * exit status 0 and the turn's messages, the last of which is its response; a harness that exits fails the turn
	 * with the turn's exit status and a description on standard error, and stays ended; a rejected turn throws; and a
	 * turn that blocks waits until the caller interrupts it and then completes with no messages.
	 *
	 * @param request  the non-blank user prompt
	 * @param listener receives each harness event line
	 * @return the turn's result
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code request} is blank
	 * @throws IOException              if the script has no turn left, the harness rejects the turn, the thread is
	 *                                  interrupted while the turn blocks, or the turn's checkpoint fails
	 */
	@Override
	public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
	{
		requireThat(request, "request").isNotBlank();
		requireThat(listener, "listener").isNotNull();
		Turn turn;
		CountDownLatch interrupt = new CountDownLatch(1);
		synchronized (lock)
		{
			if (turns >= script.size())
				throw new IOException("The script has no turn " + (turns + 1));
			received.add(request);
			turn = script.get(turns);
			turns += 1;
			activeTurn = interrupt;
		}
		try
		{
			StringBuilder output = new StringBuilder();
			int printed = 0;
			for (String event : turn.events())
			{
				turn.checkpoint().reached(printed);
				output.append(event).append('\n');
				listener.accept(event);
				printed += 1;
			}
			turn.checkpoint().reached(printed);
			return switch (turn.kind())
			{
				case REPLIES -> new HarnessProcessResult(0, output.toString(), "", true, turn.messages().getLast(),
					turn.messages());
				case EXITS ->
				{
					synchronized (lock)
					{
						ended = true;
					}
					yield new HarnessProcessResult(turn.exitStatus(), output.toString(),
						"The harness ended before the turn's result", false);
				}
				case REJECTED -> throw new IOException("The harness rejected the turn");
				case BLOCKS_UNTIL_INTERRUPTED ->
				{
					await(interrupt);
					yield new HarnessProcessResult(0, output.toString(), "", true, "", List.of());
				}
			};
		}
		finally
		{
			synchronized (lock)
			{
				activeTurn = null;
			}
		}
	}

	@Override
	public HarnessProcessResult sendTurn(String request) throws IOException
	{
		return sendTurn(request, _ ->
		{
		});
	}

	/**
	 * Receives a steering direction.
	 *
	 * @param request the non-blank user direction
	 * @throws NullPointerException     if {@code request} is null
	 * @throws IllegalArgumentException if {@code request} is blank
	 */
	@Override
	public void steer(String request)
	{
		requireThat(request, "request").isNotBlank();
		synchronized (lock)
		{
			received.add(request);
		}
	}

	/**
	 * Ends the turn in progress if it blocks until it is interrupted. Does nothing between turns.
	 */
	@Override
	public void interrupt()
	{
		synchronized (lock)
		{
			if (activeTurn != null)
				activeTurn.countDown();
		}
	}

	/**
	 * Returns the session's transcript.
	 *
	 * @return the transcript, or none if the process keeps no session transcript
	 */
	@Override
	public Optional<Path> sessionTranscript()
	{
		return Optional.ofNullable(transcript);
	}

	/**
	 * Indicates whether the harness is still running.
	 *
	 * @return {@code false} once the process is closed or the harness ended during a turn
	 */
	@Override
	public boolean isAlive()
	{
		synchronized (lock)
		{
			return !closed && !ended;
		}
	}

	/**
	 * Stops the harness and then copies the session's transcripts if the process keeps them.
	 *
	 * @throws IOException if the transcripts cannot be copied
	 */
	@Override
	public void close() throws IOException
	{
		closed = true;
		if (transcripts != null)
			transcripts.copy();
	}

	/**
	 * Waits until the caller interrupts the turn.
	 *
	 * @param interrupt opens when the caller interrupts the turn
	 * @throws IOException if the thread is interrupted
	 */
	private static void await(CountDownLatch interrupt) throws IOException
	{
		try
		{
			interrupt.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while the scripted turn was running", e);
		}
	}
}
