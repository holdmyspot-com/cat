package com.holdmyspot.cat.common.runner.launcher.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plays a child's runner as a session launcher starts it, with the files that a runner writes. When it starts, it
 * creates its run at {@code <--run-root>/<harness>/}{@value #RUN_ID}, with a project that holds the files that the test
 * chose, a home folder, a manifest and a link, and writes its {@code started} record to the {@code --output} file. It
 * records each record that the launcher writes to its standard input and answers each {@code turn} record with a
 * {@code turn_completed} record whose assistant message is {@code reply <n>}, after a {@code session_transcript}
 * record that names a file of its run for the first turn. When it reads the {@code complete} record, it does what a
 * runner does at the end of a session: it writes its result and its {@code completed} record, writes the receipt that
 * {@code --receipt} names, writes {@link #reason(int)} to its standard error unless it completed, and exits with the
 * status that the test chose. Like a runner whose turn is still running, it keeps running when its standard input
 * closes without that record. It has one descendant, standing in for its harness, which records whether it was asked
 * to stop.
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
	 * The file of its run that the runner names in its {@code session_transcript} record.
	 */
	public static final String SESSION_TRANSCRIPT = "artifacts/session.jsonl";
	private static final Duration WAIT = Duration.ofSeconds(5);
	private final JsonMapper json = JsonMapper.builder().build();
	private final String harness;
	private final List<String> arguments;
	private final Path directory;
	private final int exitStatus;
	private final Optional<Path> run;
	private final List<String> output = new ArrayList<>();
	private final FakeProcessHandle descendant = new FakeProcessHandle(Long.MAX_VALUE,
		Instant.parse("2026-11-05T12:00:00Z"), true);
	private final List<String> records = new ArrayList<>();
	private final CountDownLatch exited = new CountDownLatch(1);
	private final OutputStream input = new RunnerInput();
	private final Process process = new RunnerProcess();
	private boolean inputClosed;
	private boolean stopped;
	private int status;
	private int turns;

	/**
	 * Starts a runner: creates its run and writes its {@code started} record.
	 *
	 * @param harness    the child's harness identifier
	 * @param arguments  the runner's options
	 * @param directory  the request's private folder, which receives the runner's standard error
	 * @param exitStatus the status with which it exits after the {@code complete} record
	 * @param runFiles   the files, relative to the run, that the run holds; each file holds its relative path
	 * @throws IOException if the run or the record cannot be written
	 */
	public FakeRunner(String harness, List<String> arguments, Path directory, int exitStatus, List<String> runFiles)
		throws IOException
	{
		this.harness = harness;
		this.arguments = List.copyOf(arguments);
		this.directory = directory;
		this.exitStatus = exitStatus;
		this.run = option("--run-root").map(root -> Path.of(root).resolve(harness).resolve(RUN_ID));
		if (run.isPresent())
		{
			Path folder = run.get();
			Files.createDirectories(folder.resolve("artifacts"));
			Files.createDirectories(folder.resolve("project"));
			Files.createDirectories(folder.resolve("home"));
			Files.writeString(folder.resolve("home/credentials"), "secret");
			Files.writeString(folder.resolve("run.json"), "{}");
			Files.createSymbolicLink(folder.resolve("link"), folder.resolve("run.json"));
			for (String relative : runFiles)
			{
				Path file = folder.resolve(relative);
				Files.createDirectories(file.getParent());
				Files.writeString(file, relative);
			}
		}
		write(record("started"));
	}

	/**
	 * Returns what the runner writes to its standard error when it exits with a status other than {@code 0}.
	 *
	 * @param exitStatus the exit status
	 * @return the text
	 */
	public static String reason(int exitStatus)
	{
		return "the runner's reason for exit status " + exitStatus;
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
	 * Returns the folder that the runner was started in, which receives its standard error.
	 *
	 * @return the folder
	 */
	public Path directory()
	{
		return directory;
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
	 * Returns the records that the runner has written to {@code --output}.
	 *
	 * @return the records
	 */
	public List<String> output()
	{
		synchronized (this)
		{
			return List.copyOf(output);
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
	 * Returns a new record of the runner's run.
	 *
	 * @param type the record's type
	 * @return the record
	 */
	private ObjectNode record(String type)
	{
		ObjectNode record = json.createObjectNode();
		record.put("type", type);
		record.put("run_id", RUN_ID);
		return record;
	}

	/**
	 * Appends a record to the {@code --output} file.
	 *
	 * @param record the record
	 * @throws IOException if the file cannot be written
	 */
	private void write(ObjectNode record) throws IOException
	{
		synchronized (this)
		{
			String line = json.writeValueAsString(record);
			output.add(line);
			Optional<String> file = option("--output");
			if (file.isPresent())
			{
				Files.writeString(Path.of(file.get()), line + "\n", UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
			}
		}
	}

	/**
	 * Answers a {@code turn} record as a runner does once the turn ends.
	 *
	 * @throws IOException if the runner's files cannot be written
	 */
	private void turn() throws IOException
	{
		turns += 1;
		if (turns == 1 && run.isPresent())
		{
			Path transcript = Files.writeString(run.get().resolve(SESSION_TRANSCRIPT), "{}\n");
			ObjectNode named = record("session_transcript");
			named.put("path", transcript.toString());
			write(named);
		}
		ObjectNode completed = record("turn_completed");
		completed.put("exit_code", 0);
		completed.putArray("assistant_messages").add("reply " + turns);
		write(completed);
	}

	/**
	 * Ends the session as a runner does after the {@code complete} record.
	 *
	 * @throws IOException if the runner's files cannot be written
	 */
	private void complete() throws IOException
	{
		if (run.isPresent())
			Files.writeString(run.get().resolve("artifacts/result.txt"), "result");
		write(record("completed"));
		Optional<String> receipt = option("--receipt");
		if (receipt.isPresent())
		{
			ObjectNode document = json.createObjectNode();
			document.put("run_id", RUN_ID);
			run.ifPresent(folder ->
			{
				document.put("retained_project", folder.resolve("project").toString());
				document.put("raw_result", folder.resolve("artifacts/result.txt").toString());
			});
			Files.writeString(Path.of(receipt.get()), json.writeValueAsString(document));
		}
		if (exitStatus != 0)
			Files.writeString(directory.resolve(SessionLauncherChannel.RUNNER_STDERR_FILE), reason(exitStatus) + "\n");
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
				else if (record.contains("\"turn\""))
					turn();
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
