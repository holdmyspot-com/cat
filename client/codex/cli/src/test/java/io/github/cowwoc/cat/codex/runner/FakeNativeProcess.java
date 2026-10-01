package io.github.cowwoc.cat.codex.runner;

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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Stands in for a native harness child process in memory. The test plays the child: it writes the lines the child
 * prints on standard output with {@link #emit(String...)} and ends the child with {@link #exit()}. Each line the
 * runner writes to the child's standard input is recorded and passed to a test-supplied handler, and closing that
 * input ends the child. The child has no operating-system process, so {@link #pid()} fails, which makes any attempt
 * to signal it fail the test.
 */
final class FakeNativeProcess extends Process
{
	/**
	 * Chunks of the child's standard output; an empty chunk marks the end of the stream.
	 */
	private final BlockingQueue<byte[]> standardOutput = new LinkedBlockingQueue<>();
	private final List<String> requests = new ArrayList<>();
	private final Consumer<String> requestHandler;
	private final CountDownLatch exited = new CountDownLatch(1);
	private final OutputStream standardInput = new ChildInput();
	private final InputStream outputStream = new ChildOutput();

	/**
	 * Creates a running child.
	 *
	 * @param requestHandler receives each line, without its line terminator, that the runner writes to the child's
	 *   standard input; it may call {@link #emit(String...)} to answer
	 */
	FakeNativeProcess(Consumer<String> requestHandler)
	{
		this.requestHandler = requestHandler;
	}

	/**
	 * Prints lines on the child's standard output.
	 *
	 * @param lines the lines, without line terminators
	 */
	void emit(String... lines)
	{
		for (String line : lines)
			standardOutput.add((line + "\n").getBytes(UTF_8));
	}

	/**
	 * Ends the child: closes its standard output and marks it as exited with status 0.
	 */
	void exit()
	{
		standardOutput.add(new byte[0]);
		exited.countDown();
	}

	/**
	 * Returns the lines the runner has written to the child's standard input.
	 *
	 * @return a snapshot of the request lines in write order
	 */
	List<String> requests()
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
		return new ByteArrayInputStream(new byte[0]);
	}

	@Override
	public int waitFor() throws InterruptedException
	{
		exited.await();
		return 0;
	}

	@Override
	public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
	{
		return exited.await(timeout, unit);
	}

	@Override
	public int exitValue()
	{
		if (exited.getCount() != 0)
			throw new IllegalThreadStateException("The child has not exited");
		return 0;
	}

	@Override
	public boolean isAlive()
	{
		return exited.getCount() != 0;
	}

	@Override
	public void destroy()
	{
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
	 * Closing it ends the child, as a harness CLI exits once its input ends.
	 */
	private final class ChildInput extends OutputStream
	{
		private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

		@Override
		public void close()
		{
			destroy();
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
				try
				{
					current = standardOutput.take();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for child output", e);
				}
				position = 0;
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
			FakeNativeProcess.this.destroy();
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
			return FakeNativeProcess.this.isAlive();
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
