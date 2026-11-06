package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
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
	 * Indicates whether a hook starts a session launcher.
	 *
	 * @param platform    the platform that the hook runs on
	 * @param environment the hook's environment
	 * @return {@code false} on native Windows, which has no session launcher, and in a session that CAT's runner
	 *   started, whose requests the requesting session's launcher serves; {@code true} otherwise
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean isWanted(Platform platform, Map<String, String> environment)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		return !platform.isWindows() &&
			!HarnessEnvironment.RUN_VALUE.equals(environment.get(HarnessEnvironment.RUN_VARIABLE));
	}

	/**
	 * Returns the identity of a harness process.
	 *
	 * @param harnessProcess the harness process, as the operating system reports it
	 * @return the identity
	 * @throws NullPointerException if {@code harnessProcess} is null
	 * @throws IOException          if the operating system does not report when the process started
	 */
	public static ProcessIdentity identity(ProcessDescription harnessProcess) throws IOException
	{
		requireThat(harnessProcess, "harnessProcess").isNotNull();
		if (harnessProcess.start().isEmpty())
		{
			throw new IOException("The operating system does not report when the harness process " +
				harnessProcess.processId() + " started");
		}
		return new ProcessIdentity(harnessProcess.processId(), harnessProcess.start().get());
	}

	/**
	 * Starts the launcher of a harness process from a SessionStart hook's input, as {@link #start} does. The input
	 * supplies the session's {@code session_id}, {@code cwd}, {@code transcript_path} and {@code source}; input
	 * that is not a JSON object with a {@code session_id} and an absolute {@code cwd} starts no launcher, and is left
	 * to the hook's other work to report.
	 *
	 * @param event       the hook's input
	 * @param harness     the identifier of the harness
	 * @param process     the harness process
	 * @param harnessHome the harness's absolute configuration folder
	 * @param pluginRoot  the plugin's folder, whose {@code bin} folder holds the launcher
	 * @param platform    the platform that the hook runs on
	 * @param environment the hook's environment, which names the user's home folder
	 * @param fileLocks   the locks of this JVM
	 * @param starter     starts the launcher
	 * @return {@code true} if a launcher was started
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code transcript_path} is not an absolute path
	 * @throws IOException              if the user's home folder is unknown, the launcher's folder cannot be written,
	 *                                  or the launcher cannot start
	 */
	public static boolean startFromHook(byte[] event, String harness, ProcessIdentity process, Path harnessHome,
		Path pluginRoot, Platform platform, Map<String, String> environment, FileLocks fileLocks,
		DetachedStarter starter) throws IOException
	{
		requireThat(event, "event").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		JsonNode input;
		try
		{
			input = JsonMapper.builder().build().readTree(event);
		}
		catch (JacksonException _)
		{
			return false;
		}
		if (input == null || !input.isObject())
			return false;
		String sessionId = input.path("session_id").asString("");
		String cwd = input.path("cwd").asString("");
		if (sessionId.isBlank() || cwd.isBlank() || !Path.of(cwd).isAbsolute())
			return false;
		Optional<Path> transcript = Optional.empty();
		String transcriptPath = input.path("transcript_path").asString("");
		if (!transcriptPath.isBlank())
			transcript = Optional.of(Path.of(transcriptPath));
		LauncherRecord record = new LauncherRecord(harness, process, sessionId, Path.of(cwd), transcript,
			input.path("source").asString(""), harnessHome, PluginInfo.CAT_VERSION);
		return start(new SessionLauncherChannel(platform.homeDirectory(environment)), record, pluginRoot, platform,
			environment, fileLocks, starter);
	}

	/**
	 * Asks the launcher of a harness process to stop, by creating its folder's
	 * {@link SessionLauncherChannel#STOP_FILE}. A harness process without a launcher folder is left alone.
	 *
	 * @param channel the locations that the launcher shares with its senders
	 * @param harness the identifier of the harness
	 * @param process the harness process
	 * @return {@code true} if the stop file was written
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is not a launcher folder's harness identifier
	 * @throws IOException              if the stop file cannot be written
	 */
	public static boolean requestStop(SessionLauncherChannel channel, String harness, ProcessIdentity process)
		throws IOException
	{
		requireThat(channel, "channel").isNotNull();
		requireThat(process, "process").isNotNull();
		Path directory = channel.launcherDirectory(harness, process.pid(), process.start());
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			return false;
		Files.writeString(directory.resolve(SessionLauncherChannel.STOP_FILE), "");
		return true;
	}

	/**
	 * Starts the launcher of a harness process, unless the platform is native Windows, {@code environment} marks a
	 * session that CAT's runner started, or a live launcher holds the folder's lock. The hook's data replaces the
	 * folder's {@link SessionLauncherChannel#LAUNCHER_FILE} before the launcher starts. The
	 * {@link SessionLauncherChannel#inbox() inbox} exists when this method returns, since a sandbox that can let a
	 * command write only folders that exist when the command starts would leave the session's commands unable to
	 * create it.
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
	 * @throws IOException          if the launcher's folder or the inbox cannot be written, or the launcher cannot
	 *                              start
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
		if (!isWanted(platform, environment))
			return false;
		Path directory = channel.launcherDirectory(record.harness(), record.process().pid(),
			record.process().start());
		Files.createDirectories(directory);
		Optional<ExclusiveFileLock> free = fileLocks.tryLock(directory.resolve(SessionLauncherChannel.LOCK_FILE),
			file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
		if (free.isEmpty())
			return false;
		free.get().close();
		Files.createDirectories(channel.inbox());
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
