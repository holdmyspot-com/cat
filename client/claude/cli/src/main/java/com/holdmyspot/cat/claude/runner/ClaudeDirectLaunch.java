package com.holdmyspot.cat.claude.runner;

import com.holdmyspot.cat.claude.policy.ClaudeCommandSession;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.LauncherSpelling;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Identifies the Claude Code session that runs one of the Claude plugin's runner launchers, which Claude Code runs
 * outside the session's sandbox, and says why a launcher that the session runs inside its sandbox is refused.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ClaudeDirectLaunch
{
	/**
	 * The name of Claude Code's user settings file in its configuration folder.
	 */
	private static final String SETTINGS_FILE = "settings.json";
	/**
	 * The largest settings file that CAT reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1024 * 1024;

	/**
	 * Prevents instantiation.
	 */
	private ClaudeDirectLaunch()
	{
	}

	/**
	 * Returns the finder of the Claude Code session that runs this process.
	 *
	 * @param environment the process's environment
	 * @return the finder
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static DirectLaunch.SessionFinder sessions(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String userName = System.getProperty("user.name", "");
		return () -> sessions(environment, Platform.current().homeDirectory(environment), userName,
			HarnessProcesses.system(), HarnessProcesses.ancestorsOf(ProcessHandle.current()),
			JsonMapper.builder().build()).find();
	}

	/**
	 * Returns the finder of the Claude Code session that runs a command, as {@link ClaudeCommandSession} finds it,
	 * whose policy {@link ClaudePolicyReader} reads.
	 *
	 * @param environment the command's environment
	 * @param userHome    the user's absolute home folder
	 * @param userName    the user's login name
	 * @param processes   describes the session's Claude Code process
	 * @param ancestors   the processes that run the command, from its parent up
	 * @param json        parses the session's transcript
	 * @return the finder
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static DirectLaunch.SessionFinder sessions(Map<String, String> environment, Path userHome,
		String userName, HarnessProcesses processes, List<ProcessDescription> ancestors, JsonMapper json)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(userName, "userName").isNotNull();
		requireThat(processes, "processes").isNotNull();
		requireThat(ancestors, "ancestors").isNotNull();
		requireThat(json, "json").isNotNull();
		Map<String, String> copy = Map.copyOf(environment);
		List<ProcessDescription> chain = List.copyOf(ancestors);
		return () ->
		{
			ClaudeCommandSession found = ClaudeCommandSession.find(copy, userHome, userName, chain, json);
			return new DirectLaunch.Session(ClaudeSessionLauncherCli.policySource(new ClaudePolicyReader(processes),
				found::session), found.transcript(), found.session().projectDirectory(), Optional.empty());
		};
	}

	/**
	 * Returns why a runner launcher that this process's Claude Code session runs inside its sandbox is refused.
	 *
	 * @param launcher    the absolute path of the launcher
	 * @param environment the process's environment
	 * @return the reason
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String sandboxed(Path launcher, Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		Path userHome;
		try
		{
			userHome = Platform.current().homeDirectory(environment);
		}
		catch (IOException _)
		{
			// The account's home stands in for a home that the environment does not name
			userHome = Path.of(System.getProperty("user.home")).toAbsolutePath();
		}
		return sandboxed(launcher, environment, userHome, System.getProperty("user.name", ""),
			HarnessProcesses.ancestorsOf(ProcessHandle.current()), JsonMapper.builder().build());
	}

	/**
	 * Returns why a runner launcher that a Claude Code session runs inside its sandbox is refused. The reason names
	 * each cause that it finds: the user settings name no sandbox exception for the launcher, or the session's latest
	 * Bash command that names the launcher's file name names it by another path. When it finds none, it lists the
	 * causes that keep the launcher sandboxed. Each reason names the path to run.
	 *
	 * @param launcher    the absolute path of the launcher
	 * @param environment the command's environment
	 * @param userHome    the user's absolute home folder
	 * @param userName    the user's login name
	 * @param ancestors   the processes that run the command, from its parent up
	 * @param json        parses the session's settings and transcripts
	 * @return the reason
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String sandboxed(Path launcher, Map<String, String> environment, Path userHome, String userName,
		List<ProcessDescription> ancestors, JsonMapper json)
	{
		requireThat(launcher, "launcher").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(userName, "userName").isNotNull();
		requireThat(ancestors, "ancestors").isNotNull();
		requireThat(json, "json").isNotNull();
		List<String> causes = new ArrayList<>();
		missingException(launcher, environment, userHome, json).ifPresent(causes::add);
		ClaudeCommandSession found;
		try
		{
			found = ClaudeCommandSession.find(environment, userHome, userName, ancestors, json);
		}
		catch (IOException | PolicyRefusal _)
		{
			found = null;
		}
		if (found != null)
		{
			latestCommand(found.transcript(), launcher.getFileName().toString(), json).
				flatMap(command -> LauncherSpelling.otherSpelling(command, launcher)).ifPresent(word -> causes.add(
					"The command ran the launcher as " + word + ", but the sandbox exception that CAT's install adds " +
					"matches only a command whose first word is " + launcher + ", spelled exactly so. Run " + launcher +
					" followed by the launcher's options, with nothing in front of it and no pipe or redirection."));
		}
		String start = "CAT cannot start the session: this Claude Code session runs the launcher inside its sandbox, " +
			"where no session that a harness sandboxes can start. ";
		if (!causes.isEmpty())
			return start + String.join(" ", causes);
		return start + "Claude Code runs the launcher outside its sandbox only through the sandbox exception that " +
			"CAT's install adds for " + launcher + ", and only for a command that is that absolute path followed by " +
			"the launcher's options, with nothing in front of it and no pipe or redirection. A session that started " +
			"before that install has no such exception. Run " + launcher + " as such a command, or start a new " +
			"session and run it there.";
	}

	/**
	 * Returns the cause of a sandboxed launch when the user settings name no sandbox exception for a launcher.
	 *
	 * @param launcher    the absolute path of the launcher
	 * @param environment the command's environment
	 * @param userHome    the user's absolute home folder
	 * @param json        parses the settings
	 * @return the cause and its fix, or empty if an exception names the launcher or the settings cannot be read
	 */
	private static Optional<String> missingException(Path launcher, Map<String, String> environment, Path userHome,
		JsonMapper json)
	{
		Path settings;
		try
		{
			settings = ClaudeSession.configurationDirectory(environment, userHome).resolve(SETTINGS_FILE);
			if (Files.exists(settings))
			{
				JsonNode excluded = json.readTree(BoundedInput.readUtf8(settings, MAXIMUM_FILE_BYTES,
					"Claude Code's user settings")).path("sandbox").path("excludedCommands");
				for (JsonNode entry : excluded)
				{
					if (entry.asString("").contains(launcher.toString()))
						return Optional.empty();
				}
			}
		}
		catch (IOException | PolicyRefusal | JacksonException _)
		{
			return Optional.empty();
		}
		return Optional.of("The user settings " + settings + " name no sandbox exception for " + launcher + ". Run " +
			"CAT's install again, then run " + launcher + " as a command of its own.");
	}

	/**
	 * Returns the latest Bash command whose text contains some text, among those that a session's transcript and
	 * the transcripts of its subagents record. Claude Code records a command before it runs it.
	 *
	 * @param transcript the session's transcript
	 * @param text       the text
	 * @param json       parses the transcripts' records
	 * @return the command, or empty if no transcript records one that contains {@code text}
	 */
	private static Optional<String> latestCommand(Path transcript, String text, JsonMapper json)
	{
		List<Path> transcripts = new ArrayList<>();
		transcripts.add(transcript);
		String name = transcript.getFileName().toString();
		Path subagents = transcript.resolveSibling(name.substring(0, name.length() - ".jsonl".length())).
			resolve("subagents");
		String latestTime = "";
		String latest = null;
		try
		{
			if (Files.isDirectory(subagents))
			{
				try (Stream<Path> files = Files.list(subagents))
				{
					files.filter(file -> file.getFileName().toString().endsWith(".jsonl")).forEach(transcripts::add);
				}
			}
			for (Path file : transcripts)
			{
				try (BufferedReader reader = Files.newBufferedReader(file))
				{
					for (String line = reader.readLine(); line != null; line = reader.readLine())
					{
						if (!line.contains(text))
							continue;
						JsonNode record;
						try
						{
							record = json.readTree(line);
						}
						catch (JacksonException _)
						{
							continue;
						}
						String time = record.path("timestamp").asString("");
						for (JsonNode content : record.path("message").path("content"))
						{
							String command = content.path("input").path("command").asString("");
							if (record.path("type").asString("").equals("assistant") &&
								content.path("type").asString("").equals("tool_use") &&
								content.path("name").asString("").equals("Bash") && command.contains(text) &&
								time.compareTo(latestTime) >= 0)
							{
								latestTime = time;
								latest = command;
							}
						}
					}
				}
			}
		}
		catch (IOException | UncheckedIOException _)
		{
			return Optional.empty();
		}
		return Optional.ofNullable(latest);
	}
}
