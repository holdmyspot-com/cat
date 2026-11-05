package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the session launcher of a harness process from CAT's SessionStart hook. The hook writes its data to the
 * launcher's folder and starts the plugin's {@value #PROGRAM} launcher on that folder, without waiting for it: the
 * launcher reads the null device, appends its output and error output to the folder's
 * {@link SessionLauncherChannel#LOG_FILE}, and runs in a session of its own when {@code setsid} is on the hook's
 * {@code PATH}, so that the hook can end while the launcher serves the harness. The hook starts no launcher on native
 * Windows, in a session that CAT's runner started, or while a live launcher holds the folder's
 * {@link SessionLauncherChannel#LOCK_FILE}.
 */
public final class SessionLauncherStart
{
	/**
	 * The launcher's file name in the plugin's {@code bin} folder.
	 */
	public static final String PROGRAM = "session-launcher";
	/**
	 * The program that starts a process in a new session.
	 */
	private static final String SETSID = "setsid";

	/**
	 * Starts a process that outlives its starter.
	 * <p>
	 * <b>Thread Safety:</b> Implementations must be thread-safe.
	 */
	@FunctionalInterface
	public interface DetachedStarter
	{
		/**
		 * Starts a process without waiting for it. The process reads the null device, and its output and error output
		 * are appended to {@code log}, so that it holds none of its starter's streams.
		 *
		 * @param command the program and its arguments
		 * @param log     the file that the process's output and error output are appended to
		 * @return the process
		 * @throws IOException if the process cannot start
		 */
		ProcessHandle start(List<String> command, Path log) throws IOException;
	}

	/**
	 * Starts operating-system processes, which inherit the starter's environment and working directory.
	 */
	public static final DetachedStarter PROCESSES = (command, log) ->
	{
		File logFile = log.toFile();
		ProcessBuilder builder = new ProcessBuilder(command).
			redirectInput(Redirect.from(new File("/dev/null"))).
			redirectOutput(Redirect.appendTo(logFile)).
			redirectError(Redirect.appendTo(logFile));
		return builder.start().toHandle();
	};

	/**
	 * Prevents instantiation.
	 */
	private SessionLauncherStart()
	{
	}

	/**
	 * Starts the launcher of a harness process, unless the platform is native Windows, {@code environment} marks a
	 * session that CAT's runner started, or a live launcher holds the folder's lock. The hook's data replaces the
	 * folder's {@link SessionLauncherChannel#LAUNCHER_FILE} before the launcher starts.
	 *
	 * @param channel     the locations that the launcher shares with its senders
	 * @param record      the hook's data, which names the harness process
	 * @param pluginRoot  the plugin's folder, whose {@code bin} folder holds the launcher
	 * @param platform    the platform that the hook runs on
	 * @param environment the hook's environment, whose {@code PATH} may name {@code setsid}
	 * @param fileLocks   the locks of this JVM
	 * @param starter     starts the launcher
	 * @return {@code true} if a launcher was started; {@code false} if none was needed
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the launcher's folder cannot be written, or the launcher cannot start
	 */
	public static boolean start(SessionLauncherChannel channel, LauncherRecord record, Path pluginRoot,
		Platform platform, Map<String, String> environment, FileLocks fileLocks, DetachedStarter starter)
		throws IOException
	{
		requireThat(channel, "channel").isNotNull();
		requireThat(record, "record").isNotNull();
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(starter, "starter").isNotNull();
		if (platform.isWindows() ||
			HarnessEnvironment.RUN_VALUE.equals(environment.get(HarnessEnvironment.RUN_VARIABLE)))
		{
			return false;
		}
		Path directory = channel.launcherDirectory(record.harness(), record.process().pid(),
			record.process().start());
		Files.createDirectories(directory);
		Optional<ExclusiveFileLock> free = fileLocks.tryLock(directory.resolve(SessionLauncherChannel.LOCK_FILE),
			file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
		if (free.isEmpty())
			return false;
		free.get().close();
		record.write(JsonMapper.builder().build(), directory.resolve(SessionLauncherChannel.LAUNCHER_FILE));
		List<String> command = new ArrayList<>();
		String setsid = platform.findProgram(SETSID, environment);
		if (!setsid.equals(SETSID))
			command.add(setsid);
		command.add(pluginRoot.resolve("bin").resolve(PROGRAM).toString());
		command.add(directory.toString());
		starter.start(command, directory.resolve(SessionLauncherChannel.LOG_FILE));
		return true;
	}
}
