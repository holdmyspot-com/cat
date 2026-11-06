package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * The work of a harness's {@code session-launcher} program, which CAT's SessionStart hook starts on the launcher
 * folder of the harness process. The program serves the harness process that the folder's
 * {@link SessionLauncherChannel#LAUNCHER_FILE} names until the launcher ends: when that process ends, when the
 * folder's {@link SessionLauncherChannel#STOP_FILE} appears, or when the program is terminated.
 */
public final class SessionLauncherService
{
	/**
	 * The exit status of a launcher whose folder does not describe the harness process to serve.
	 */
	public static final int INVALID_FOLDER_STATUS = 2;

	/**
	 * Prevents instantiation.
	 */
	private SessionLauncherService()
	{
	}

	/**
	 * Serves the harness process of a launcher folder until the launcher ends. A launcher that finds another one
	 * serving the process exits at once.
	 *
	 * @param channel       the locations that the launcher shares with its senders
	 * @param directory     the launcher's folder, which the hook named
	 * @param harness       the identifier of the served harness
	 * @param policies      returns the sandbox policy source of the session that a record describes
	 * @param starter       starts the runner of a requested child, and of the child needs report
	 * @param processes     the operating system's processes
	 * @param fileLocks     the locks of this JVM
	 * @param clock         the clock that dates the heartbeat
	 * @param executor      runs each accepted request; shut down when the launcher ends
	 * @param pause         the wait between polls
	 * @param onTermination registers the work that ends the launcher when its program is terminated
	 * @param error         receives the reason why the launcher cannot serve
	 * @return {@code 0} once the launcher has ended, or if another launcher serves the process;
	 *   {@link #INVALID_FOLDER_STATUS} if the folder's record names another harness process
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the record cannot be read, or the launcher's state cannot be read or written
	 */
	public static int serve(SessionLauncherChannel channel, Path directory, String harness,
		Function<LauncherRecord, ParentPolicySource> policies, RequestedSessionStarter starter, ProcessTable processes,
		FileLocks fileLocks, Clock clock, ExecutorService executor, Pause pause, Consumer<Runnable> onTermination,
		PrintStream error) throws IOException
	{
		requireThat(channel, "channel").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(policies, "policies").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(executor, "executor").isNotNull();
		requireThat(pause, "pause").isNotNull();
		requireThat(onTermination, "onTermination").isNotNull();
		requireThat(error, "error").isNotNull();
		try
		{
			LauncherRecord record = LauncherRecord.read(JsonMapper.builder().build(),
				directory.resolve(SessionLauncherChannel.LAUNCHER_FILE));
			Path expected = channel.launcherDirectory(harness, record.process().pid(), record.process().start());
			if (!record.harness().equals(harness) ||
				!expected.toAbsolutePath().normalize().equals(directory.toAbsolutePath().normalize()))
			{
				error.println("CAT's session launcher cannot serve " + directory + ": its record names the " +
					record.harness() + " process " + record.process().pid() + ", whose launcher folder is " + expected);
				return INVALID_FOLDER_STATUS;
			}
			SessionLauncher launcher = new SessionLauncher(channel, harness, record.process(), processes,
				policies.apply(record), new RunnerChildNeeds(starter), starter, fileLocks, clock, executor);
			try (launcher)
			{
				if (!launcher.start())
					return 0;
				onTermination.accept(() ->
				{
					try
					{
						launcher.close();
					}
					catch (IOException e)
					{
						error.println("CAT's session launcher could not end cleanly: " + e.getMessage());
					}
				});
				launcher.serve(pause);
			}
			return 0;
		}
		finally
		{
			executor.shutdownNow();
		}
	}
}
