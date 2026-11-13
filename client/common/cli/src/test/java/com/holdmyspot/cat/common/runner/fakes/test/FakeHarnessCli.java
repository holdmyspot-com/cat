package com.holdmyspot.cat.common.runner.fakes.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Stands in for a harness child process in memory. The test plays the child: it writes the lines the child
 * prints on standard output with {@link #emit(String...)} and ends the child with {@link #exit()}. Each line the
 * runner writes to the child's standard input is recorded and passed to a test-supplied handler, and closing that
 * input ends the child. The child has no operating-system process, so {@link #pid()} fails, which makes any attempt
 * to signal it fail the test.
 * <p>
 * Protocol lines come from caller-owned recordings: Claude Code 2.1.286 timelines under
 * {@code client/claude/cli/src/test/resources/runner/turn-boundary} and
 * {@code client/claude/cli/src/test/resources/optimizer/claude-single-turn.jsonl}, Claude Code 2.1.287 recordings
 * described in {@code client/claude/cli/src/test/resources/optimizer/tool-calls.md}, codex-cli 0.159.1's
 * {@code client/codex/cli/src/test/resources/optimizer/codex-children.jsonl}, and codex-cli 0.147.0/0.159.1+23
 * recordings described in {@code client/codex/cli/src/test/resources/optimizer/codex-tool-calls.md}.
 * This class invents no protocol events
 * or interrupt responses; its handler records requests or supplies the documented responses named by its caller.
 * <p>
 * Closing input models codex-cli 0.147.0's stdio EOF shutdown: OpenAI's
 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server-transport/src/transport/stdio.rs">
 * stdio transport</a> reports a closed connection on EOF, and
 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/src/lib.rs">app-server</a>
 * ends its single-client event loop. A Claude session exits on stdin EOF too, after saving its record; a test of a
 * child that does not exit on EOF uses {@link #keepRunningWhenInputCloses()}.
 * Explicit exit, output EOF, waiting, and termination model the generic JDK
 * <a href="https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/lang/Process.html">Process contract</a>:
 * output EOF and process termination are separate observations, and exit status is unavailable while alive.
 * {@link #failOutput(String)} models the I/O error that the JDK's {@code InputStream.read} reports.
 * The delayed-exit option makes waiting observable without asserting a harness-specific delay. Successful exit status
 * 0 also appears in {@code docs/changelog/1.0/captures/base-codex-live/run.json} (codex-cli 0.147.0) and
 * {@code base-claude-live/run.json} (Claude Code 2.1.220), with versions named in the captures' README.
 */
public final class FakeHarnessCli extends Process
{
	/**
	 * Chunks of the child's standard output; an empty chunk marks the end of the stream.
	 */
	private final BlockingQueue<byte[]> standardOutput = new LinkedBlockingQueue<>();
	/**
	 * The chunk that marks where reading the child's standard output fails; compared by identity.
	 */
	private final byte[] readFailure = new byte[1];
	private volatile String readFailureMessage = "";
	private final List<String> requests = new ArrayList<>();
	private final Consumer<String> requestHandler;
	private final int exitStatus;
	private final CountDownLatch exited = new CountDownLatch(1);
	/**
	 * The actions to run when the child exits, guarded by this list's monitor, which also guards the exit.
	 */
	private final List<Runnable> exitActions = new ArrayList<>();
	private volatile boolean exitsWhenAwaited;
	private volatile boolean keepsRunningWhenInputCloses;
	private final AtomicInteger terminationRequests = new AtomicInteger();
	private volatile byte[] standardError = new byte[0];
	private final OutputStream standardInput = new ChildInput();
	private final InputStream outputStream = new ChildOutput();

	/**
	 * Creates a running child.
	 *
	 * @param requestHandler receives each line, without its line terminator, that the runner writes to the child's
	 *   standard input; it may call {@link #emit(String...)} to answer
	 */
	public FakeHarnessCli(Consumer<String> requestHandler)
	{
		this(requestHandler, 0);
	}

	/**
	 * Creates a child with a caller-selected exit status, modeling the JDK process exit contract.
	 *
	 * @param requestHandler receives each input line
	 * @param exitStatus the terminal process status
	 */
	public FakeHarnessCli(Consumer<String> requestHandler, int exitStatus)
	{
		this.requestHandler = requestHandler;
		this.exitStatus = exitStatus;
	}

	/**
	 * Prints lines on the child's standard output.
	 *
	 * @param lines the lines, without line terminators
	 */
	public void emit(String... lines)
	{
		for (String line : lines)
			standardOutput.add((line + "\n").getBytes(UTF_8));
	}

	/**
	 * Sets the text that the child prints on its standard error, which a caller reads from the stream that
	 * {@link #getErrorStream()} returns after this call.
	 *
	 * @param text the text
	 */
	public void printError(String text)
	{
		standardError = text.getBytes(UTF_8);
	}

	/**
	 * Makes reading the child's standard output fail with an {@link IOException} that carries a message, once the
	 * caller has read the lines emitted before this call. Reads after that failure report the end of the stream. The
	 * child stays alive.
	 *
	 * @param message the failure's message
	 */
	public void failOutput(String message)
	{
		readFailureMessage = message;
		standardOutput.add(readFailure);
	}

	/**
	 * Ends the child: closes its standard output and marks it as exited with status 0.
	 */
	public void exit()
	{
		standardOutput.add(new byte[0]);
		markExited();
	}

	/**
	 * Runs an action when the child exits, or at once if it has. The action runs in the thread that marks the child as
	 * exited, before any call that waits for the exit returns, so it is complete when a caller that waited for the exit
	 * goes on. {@link Process#onExit()} cannot give that order, because it completes in another thread.
	 *
	 * @param action the action
	 */
	public void whenExited(Runnable action)
	{
		synchronized (exitActions)
		{
			if (exited.getCount() == 0)
				action.run();
			else
				exitActions.add(action);
		}
	}

	/**
	 * Marks the child as exited, which runs the actions that {@link #whenExited(Runnable)} registered and then
	 * releases the callers that wait for the exit. Does nothing if the child has exited.
	 */
	private void markExited()
	{
		synchronized (exitActions)
		{
			if (exited.getCount() == 0)
				return;
			for (Runnable action : exitActions)
				action.run();
			exitActions.clear();
			exited.countDown();
		}
	}

	/**
	 * Makes the child keep running when its standard input closes, as a child that has not finished its cleanup does.
	 * The child then ends only through {@link #exit()} or termination.
	 */
	public void keepRunningWhenInputCloses()
	{
		keepsRunningWhenInputCloses = true;
	}

	/**
	 * Returns how many times the runner asked the child to terminate, through {@link #destroy()} or its process handle.
	 * Closing the child's standard input is not a termination request.
	 *
	 * @return the number of termination requests
	 */
	public int terminationRequests()
	{
		return terminationRequests.get();
	}

	/**
	 * Closes the child's standard output without marking the child as exited. The child is marked as exited, with
	 * status 0, when a caller waits for it. This makes the JDK contract's separate output-EOF and exit observations
	 * testable; it does not prescribe the ordering or delay of a real harness. Reading the exit status while the
	 * child is alive fails.
	 */
	public void closeOutputThenExitWhenAwaited()
	{
		exitsWhenAwaited = true;
		standardOutput.add(new byte[0]);
	}

	/**
	 * Returns the lines the runner has written to the child's standard input.
	 *
	 * @return a snapshot of the request lines in write order
	 */
	public List<String> requests()
	{
		synchronized (requests)
		{
			return List.copyOf(requests);
		}
	}

	@Override
	public OutputStream getOutputStream()
	{
		return standardInput;
	}

	@Override
	public InputStream getInputStream()
	{
		return outputStream;
	}

	@Override
	public InputStream getErrorStream()
	{
		return new ByteArrayInputStream(standardError);
	}

	@Override
	public int waitFor() throws InterruptedException
	{
		exitIfAwaited();
		exited.await();
		return exitStatus;
	}

	@Override
	public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
	{
		exitIfAwaited();
		return exited.await(timeout, unit);
	}

	/**
	 * Marks the child as exited if it was set to exit when awaited.
	 */
	private void exitIfAwaited()
	{
		if (exitsWhenAwaited)
			markExited();
	}

	@Override
	public int exitValue()
	{
		if (exited.getCount() != 0)
			throw new IllegalThreadStateException("The child has not exited");
		return exitStatus;
	}

	@Override
	public boolean isAlive()
	{
		return exited.getCount() != 0;
	}

	@Override
	public void destroy()
	{
		terminationRequests.incrementAndGet();
		if (isAlive())
			exit();
	}

	@Override
	public ProcessHandle toHandle()
	{
		return new Handle();
	}

	/**
	 * The child's standard input as the runner sees it: each complete line is recorded and passed to the handler.
	 * Closing it models the documented Codex stdio EOF shutdown described on this class.
	 */
	private final class ChildInput extends OutputStream
	{
		private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

		@Override
		public void close()
		{
			if (!keepsRunningWhenInputCloses && isAlive())
				exit();
		}

		@Override
		public void write(int value)
		{
			if (value != '\n')
			{
				pending.write(value);
				return;
			}
			String line = pending.toString(UTF_8);
			pending.reset();
			synchronized (requests)
			{
				requests.add(line);
			}
			requestHandler.accept(line);
		}
	}

	/**
	 * The child's standard output as the runner reads it: blocks until the test emits a line or ends the child.
	 * <p>
	 * Interrupting the reading thread does not end the wait, just as {@code Thread.interrupt} does not unblock a read
	 * of a real child's pipe; the thread's interrupt status stays set.
	 */
	private final class ChildOutput extends InputStream
	{
		private byte[] current = new byte[0];
		private int position;
		private boolean ended;

		@Override
		public int read() throws IOException
		{
			byte[] single = new byte[1];
			if (read(single, 0, 1) == -1)
				return -1;
			return single[0] & 0xFF;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException
		{
			if (length == 0)
				return 0;
			while (!ended && position == current.length)
			{
				current = takeChunk();
				position = 0;
				if (current == readFailure)
				{
					current = new byte[0];
					ended = true;
					throw new IOException(readFailureMessage);
				}
				if (current.length == 0)
					ended = true;
			}
			if (ended)
				return -1;
			int count = Math.min(length, current.length - position);
			System.arraycopy(current, position, buffer, offset, count);
			position += count;
			return count;
		}

		/**
		 * Waits for the next chunk of the child's standard output, ignoring interrupts of the reading thread until the
		 * chunk arrives.
		 *
		 * @return the chunk
		 */
		private byte[] takeChunk()
		{
			boolean interrupted = false;
			try
			{
				while (true)
				{
					try
					{
						return standardOutput.take();
					}
					catch (InterruptedException _)
					{
						interrupted = true;
					}
				}
			}
			finally
			{
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * The child's process handle. The child has no descendants and no operating-system identity.
	 */
	private final class Handle implements ProcessHandle
	{
		@Override
		public long pid()
		{
			throw new UnsupportedOperationException("The in-memory child has no operating-system process");
		}

		@Override
		public Optional<ProcessHandle> parent()
		{
			return Optional.empty();
		}

		@Override
		public Stream<ProcessHandle> children()
		{
			return Stream.empty();
		}

		@Override
		public Stream<ProcessHandle> descendants()
		{
			return Stream.empty();
		}

		@Override
		public Info info()
		{
			throw new UnsupportedOperationException("The in-memory child has no operating-system process");
		}

		@Override
		public CompletableFuture<ProcessHandle> onExit()
		{
			throw new UnsupportedOperationException("The in-memory child has no operating-system process");
		}

		@Override
		public boolean supportsNormalTermination()
		{
			return true;
		}

		@Override
		public boolean destroy()
		{
			FakeHarnessCli.this.destroy();
			return true;
		}

		@Override
		public boolean destroyForcibly()
		{
			return destroy();
		}

		@Override
		public boolean isAlive()
		{
			return FakeHarnessCli.this.isAlive();
		}

		@Override
		public int compareTo(ProcessHandle other)
		{
			return Integer.compare(hashCode(), other.hashCode());
		}

		@Override
		public boolean equals(Object other)
		{
			return this == other;
		}

		@Override
		public int hashCode()
		{
			return System.identityHashCode(this);
		}
	}
}
