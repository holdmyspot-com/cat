package com.holdmyspot.cat.common.runner.launcher.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.test.FakeProcessHandle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Plays a child's runner as a session launcher starts it. It records each record that the launcher writes to its
 * standard input. When it reads the {@code complete} record, it does what a runner does at the end of a session: it
 * writes its records to the {@code --output} file, creates its run below {@code --run-root}, writes the receipt that
 * {@code --receipt} names, and exits with the status that the test chose. Like a runner whose turn is still running,
 * it keeps running when its standard input closes without that record. It has one descendant, standing in for its
 * harness, which records whether it was asked to stop.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeRunner
{
	/**
	 * The status with which the runner exits when it is stopped.
	 */
	public static final int STOPPED_STATUS = 143;
	/**
	 * The identifier of the run that the runner creates.
	 */
	public static final String RUN_ID = "run-1";
	/**
	 * The records that the runner writes to {@code --output}.
	 */
	public static final List<String> RECORDS = List.of("{\"type\":\"started\",\"run_id\":\"" + RUN_ID + "\"}",
		"{\"type\":\"completed\",\"run_id\":\"" + RUN_ID + "\"}");
	private static final Duration WAIT = Duration.ofSeconds(5);
	private final String harness;
	private final List<String> arguments;
	private final int exitStatus;
	private final FakeProcessHandle descendant = new FakeProcessHandle(Long.MAX_VALUE,
		Instant.parse("2026-11-05T12:00:00Z"), true);
	private final List<String> records = new ArrayList<>();
	private final CountDownLatch exited = new CountDownLatch(1);
	private final OutputStream input = new RunnerInput();
	private final Process process = new RunnerProcess();
	private boolean inputClosed;
	private boolean stopped;
	private int status;

	/**
	 * Creates a running runner.
	 *
	 * @param harness    the child's harness identifier
	 * @param arguments  the runner's options
	 * @param exitStatus the status with which it exits after the {@code complete} record
	 */
	public FakeRunner(String harness, List<String> arguments, int exitStatus)
	{
		this.harness = harness;
		this.arguments = List.copyOf(arguments);
		this.exitStatus = exitStatus;
	}

	/**
	 * Returns the child's harness identifier.
	 *
	 * @return the identifier
	 */
	public String harness()
	{
		return harness;
	}

	/**
	 * Returns the runner's options.
	 *
	 * @return the options
	 */
	public List<String> arguments()
	{
		return arguments;
	}

	/**
	 * Returns the value of one of the runner's options.
	 *
	 * @param option the option
	 * @return the value that follows it, or empty if the runner has no such option
	 */
	public Optional<String> option(String option)
	{
		int index = arguments.indexOf(option);
		if (index < 0 || index + 1 == arguments.size())
			return Optional.empty();
		return Optional.of(arguments.get(index + 1));
	}

	/**
	 * Waits until the runner has read some records.
	 *
	 * @param count how many records to wait for
	 * @return the records that it has read
	 * @throws InterruptedException if the thread is interrupted while waiting
	 * @throws AssertionError       if the records do not arrive in time
	 */
	public List<String> awaitRecords(int count) throws InterruptedException
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		synchronized (this)
		{
			while (records.size() < count)
			{
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
					throw new AssertionError("The runner read " + records + ", not " + count + " records");
				TimeUnit.NANOSECONDS.timedWait(this, remaining);
			}
			return List.copyOf(records);
		}
	}

	/**
	 * Returns the records that the runner has read.
	 *
	 * @return the records
	 */
	public List<String> records()
	{
		synchronized (this)
		{
			return List.copyOf(records);
		}
	}

	/**
	 * Indicates whether the runner was stopped, together with its harness.
	 *
	 * @return {@code true} if the runner and its descendant were both asked to stop
	 */
	public boolean treeStopped()
	{
		synchronized (this)
		{
			return stopped && descendant.stopRequested();
		}
	}

	/**
	 * Indicates whether the runner's standard input is closed.
	 *
	 * @return {@code true} if it is closed
	 */
	public boolean inputClosed()
	{
		synchronized (this)
		{
			return inputClosed;
		}
	}

	/**
	 * Returns the runner's process, as the launcher sees it.
	 *
	 * @return the process
	 */
	public Process process()
	{
		return process;
	}

	/**
	 * Ends the runner.
	 *
	 * @param exit its exit status
	 */
	private void exit(int exit)
	{
		synchronized (this)
		{
			if (exited.getCount() == 0)
				return;
			status = exit;
			exited.countDown();
		}
	}

	/**
	 * Ends the session as a runner does after the {@code complete} record.
	 *
	 * @throws IOException if the runner's files cannot be written
	 */
	private void complete() throws IOException
	{
		Optional<String> output = option("--output");
		if (output.isPresent())
			Files.writeString(Path.of(output.get()), String.join("\n", RECORDS) + "\n", UTF_8);
		Optional<String> runRoot = option("--run-root");
		if (runRoot.isPresent())
			Files.createDirectories(Path.of(runRoot.get()).resolve(RUN_ID).resolve("artifacts"));
		Optional<String> receipt = option("--receipt");
		if (receipt.isPresent())
			Files.writeString(Path.of(receipt.get()), "{\"run_id\":\"" + RUN_ID + "\"}");
		exit(exitStatus);
	}

	/**
	 * The runner's standard input: each complete line is one record.
	 */
	private final class RunnerInput extends OutputStream
	{
		private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

		@Override
		public void write(int value) throws IOException
		{
			synchronized (FakeRunner.this)
			{
				if (inputClosed || exited.getCount() == 0)
					throw new IOException("Broken pipe");
				if (value != '\n')
				{
					pending.write(value);
					return;
				}
				String record = pending.toString(UTF_8);
				pending.reset();
				records.add(record);
				FakeRunner.this.notifyAll();
				if (record.contains("\"complete\""))
					complete();
			}
		}

		@Override
		public void close()
		{
			synchronized (FakeRunner.this)
			{
				inputClosed = true;
			}
		}
	}

	/**
	 * The runner's process.
	 */
	private final class RunnerProcess extends Process
	{
		@Override
		public OutputStream getOutputStream()
		{
			return input;
		}

		@Override
		public InputStream getInputStream()
		{
			return new ByteArrayInputStream(new byte[0]);
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
			return exitValue();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			return exited.await(timeout, unit);
		}

		@Override
		public int exitValue()
		{
			synchronized (FakeRunner.this)
			{
				if (exited.getCount() != 0)
					throw new IllegalThreadStateException("The runner has not exited");
				return status;
			}
		}

		@Override
		public boolean isAlive()
		{
			return exited.getCount() != 0;
		}

		@Override
		public void destroy()
		{
			synchronized (FakeRunner.this)
			{
				stopped = true;
				exit(STOPPED_STATUS);
			}
		}

		@Override
		public Process destroyForcibly()
		{
			destroy();
			return process;
		}

		@Override
		public ProcessHandle toHandle()
		{
			return new FakeProcessHandle(Long.MAX_VALUE - 1, Instant.parse("2026-11-05T12:00:00Z"), true, descendant);
		}

		@Override
		public CompletableFuture<Process> onExit()
		{
			return CompletableFuture.supplyAsync(() ->
			{
				try
				{
					exited.await();
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
				return process;
			}, runnable -> Thread.ofVirtual().start(runnable));
		}
	}
}
