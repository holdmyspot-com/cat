package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives one retained bridge conversation while allowing its caller to interrupt an active turn.
 * <p>
 * <b>Thread Safety:</b> A caller may interrupt or close the call concurrently with bridge execution; completion has
 * one stable result.
 */
public final class HarnessBridgeCall implements AutoCloseable
{
	private static final int BRIDGE_FINISHED = -1;

	/**
	 * Starts one bridge conversation under a preallocated native run identity.
	 *
	 * @param starter starts the native session
	 * @param manager owns the retained native run
	 * @param runId the identity retained before dispatch
	 * @param transcript the exclusively created bridge transcript
	 * @param turns the nonempty ordered user turns
	 * @param finalizer retains native artifacts after the session closes
	 * @return the live call controller
	 * @throws NullPointerException if a required argument or turn is null
	 * @throws IllegalArgumentException if the run ID is invalid or there are no turns
	 */
	public static HarnessBridgeCall start(HarnessRunStarter starter, HarnessRunManager manager, String runId,
		Path transcript, List<String> turns, HarnessRunFinalizer finalizer)
	{
		Objects.requireNonNull(starter, "starter");
		Objects.requireNonNull(manager, "manager");
		HarnessRun.requireValidId(runId);
		Objects.requireNonNull(transcript, "transcript");
		turns = List.copyOf(turns);
		if (turns.isEmpty())
			throw new IllegalArgumentException("Bridge call requires at least one turn");
		Objects.requireNonNull(finalizer, "finalizer");

		HarnessBridgeCall call = new HarnessBridgeCall(runId, transcript, turns);
		Thread.ofVirtual().name("cat-bridge-" + runId).start(() -> call.serve(starter, manager, finalizer));
		Thread.ofVirtual().name("cat-bridge-input-" + runId).start(call::sendTurns);
		return call;
	}

	private final String runId;
	private final Path transcript;
	private final List<String> turns;
	private final JsonMapper json = JsonMapper.builder().build();
	private final JsonlInput input = new JsonlInput();
	private final BlockingQueue<Integer> finishedTurns = new LinkedBlockingQueue<>();
	private final CountDownLatch sessionReady = new CountDownLatch(1);
	private final CompletableFuture<HarnessRun> completion = new CompletableFuture<>();
	private boolean stopRequested;
	private boolean turnPending;
	private boolean closed;

	/**
	 * Creates the controller before its workers begin.
	 *
	 * @param runId exact native run identity
	 * @param transcript retained bridge output
	 * @param turns ordered native prompts
	 */
	private HarnessBridgeCall(String runId, Path transcript, List<String> turns)
	{
		this.runId = runId;
		this.transcript = transcript;
		this.turns = turns;
	}

	/**
	 * Returns the one stable terminal bridge result.
	 *
	 * @return the completed run or its native or protocol failure
	 */
	public CompletionStage<HarnessRun> completion()
	{
		return completion.minimalCompletionStage();
	}

	/**
	 * Requests cancellation of the active native turn and prevents later turns.
	 */
	public void interrupt()
	{
		synchronized (this)
		{
			if (stopRequested || completion.isDone())
				return;
			stopRequested = true;
			if (turnPending)
				input.offer(message("interrupt", null));
		}
	}

	@Override
	public void close()
	{
		synchronized (this)
		{
			if (closed)
				return;
			closed = true;
			interrupt();
		}
	}

	/**
	 * Owns the native bridge lifecycle and publishes its terminal result.
	 *
	 * @param starter native session factory
	 * @param manager owned-run manager
	 * @param finalizer native artifact finalizer
	 */
	private void serve(HarnessRunStarter starter, HarnessRunManager manager, HarnessRunFinalizer finalizer)
	{
		try (input)
		{
			HarnessRun run = HarnessRunnerBridge.serveNewTranscript(() ->
			{
				try
				{
					HarnessRunSession session = starter.start();
					if (runId.equals(session.run().id()))
						return session;
					try (session)
					{
						throw new IOException("Native run identity differs from the retained call ID");
					}
				}
				finally
				{
					sessionReady.countDown();
				}
			}, manager, true, input, transcript, finalizer, finishedTurns::offer);
			completion.complete(run);
		}
		catch (Exception failure)
		{
			completion.completeExceptionally(failure);
		}
		finally
		{
			sessionReady.countDown();
			finishedTurns.offer(BRIDGE_FINISHED);
		}
	}

	/**
	 * Sends each turn only after the previous terminal outcome is visible in the retained transcript.
	 */
	private void sendTurns()
	{
		try
		{
			sessionReady.await();
			for (int index = 0; index < turns.size(); ++index)
			{
				synchronized (this)
				{
					if (stopRequested || completion.isDone())
						break;
					turnPending = true;
					input.offer(message("turn", turns.get(index)));
				}
				int finished = finishedTurns.take();
				if (finished == BRIDGE_FINISHED)
					return;
				if (finished != index + 1)
					throw new IOException("Bridge reported an unexpected terminal turn number");
				HarnessTranscriptObservation observation = HarnessTranscriptObservation.read(json, transcript, runId).
					orElseThrow(() -> new IOException("Retained bridge transcript is missing"));
				synchronized (this)
				{
					turnPending = false;
					if (observation.turns().size() != finished ||
						observation.turns().getLast().state() == HarnessTranscriptObservation.TurnState.INTERRUPTED)
						stopRequested = true;
				}
			}
			input.offer(message("complete", null));
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			completion.completeExceptionally(failure);
			input.close();
		}
		catch (IOException failure)
		{
			completion.completeExceptionally(failure);
			input.close();
		}
	}

	/**
	 * Encodes one bridge request without interpreting prompt text as JSON.
	 *
	 * @param type the bridge request kind
	 * @param prompt the turn prompt, or null for a control request
	 * @return one complete JSONL request
	 */
	private String message(String type, String prompt)
	{
		ObjectNode request = json.createObjectNode().put("type", type);
		if (prompt != null)
			request.put("prompt", prompt);
		return json.writeValueAsString(request) + '\n';
	}

	/**
	 * Presents queued complete JSONL requests as the bridge's one live input stream.
	 */
	private static final class JsonlInput extends Reader
	{
		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		private final Object writes = new Object();
		private String current = "";
		private int offset;
		private boolean ended;
		private boolean closed;

		/**
		 * Queues one caller request while the bridge may be executing a native turn.
		 *
		 * @param line one complete JSONL request
		 */
		private void offer(String line)
		{
			synchronized (writes)
			{
				if (!closed)
					lines.offer(line);
			}
		}

		@Override
		public int read(char[] target, int start, int length) throws IOException
		{
			Objects.checkFromIndexSize(start, length, target.length);
			if (length == 0)
				return 0;
			if (ended)
				return -1;
			while (offset == current.length())
			{
				try
				{
					current = lines.take();
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while reading bridge input", failure);
				}
				offset = 0;
				if (current.isEmpty())
				{
					ended = true;
					return -1;
				}
			}
			int count = Math.min(length, current.length() - offset);
			current.getChars(offset, offset + count, target, start);
			offset += count;
			return count;
		}

		@Override
		public void close()
		{
			synchronized (writes)
			{
				if (closed)
					return;
				closed = true;
				lines.offer("");
			}
		}
	}
}
