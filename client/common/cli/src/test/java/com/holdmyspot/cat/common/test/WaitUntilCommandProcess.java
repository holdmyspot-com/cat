package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.holdmyspot.cat.common.Pause;

/**
 * A child JVM that waits for an owned completion file before writing its output and exiting.
 */
public final class WaitUntilCommandProcess
{
	/**
	 * The exact line the child prints when it is ready for fixture control.
	 */
	public static final String READY = "wait-until-command-ready";
	/**
	 * The maximum time the child waits for its test-owned completion file.
	 */
	private static final Duration COMPLETION_WAIT = Duration.ofSeconds(30);
	/**
	 * The interval between checks for the completion file.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

	/**
	 * Waits for fixture control, writes the selected payloads and exits with the selected status.
	 *
	 * @param args the completion-file path, stdout payload, stderr payload and exit status
	 * @throws IOException if the completion file is not created before the wait ends, or the poll is interrupted
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args.length, "argumentCount").isEqualTo(4);
		Path completionFile = Path.of(args[0]);
		int status = Integer.parseInt(args[3]);
		System.out.println(READY);
		System.out.flush();

		long completionDeadline = System.nanoTime() + COMPLETION_WAIT.toNanos();
		boolean waitingForCompletion = !Files.exists(completionFile) && System.nanoTime() < completionDeadline;
		Pause poll = Pause.sleeping(POLL_INTERVAL);
		while (waitingForCompletion)
		{
			poll.pause();
			waitingForCompletion = !Files.exists(completionFile) && System.nanoTime() < completionDeadline;
		}
		if (!Files.exists(completionFile))
			throw new IOException("The process fixture did not receive completion control");

		String stdout = args[1];
		System.out.print(stdout);
		System.out.flush();
		String stderr = args[2];
		System.err.print(stderr);
		System.err.flush();
		System.exit(status);
	}

	/**
	 * Prevents instantiation.
	 */
	private WaitUntilCommandProcess()
	{
	}
}
