package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The Claude Code session that runs a command, which a runner launcher identifies from sources that the session's
 * commands cannot change: the Claude Code process among the launcher's ancestors, which {@code CLAUDE_PID} names, and
 * the session's transcript, which {@value #SESSION_VARIABLE} names. The transcript's first record that names a working
 * directory gives the session's project folder.
 *
 * @param session    the session
 * @param transcript the absolute path of the session's transcript
 */
public record ClaudeCommandSession(ClaudeSession session, Path transcript)
{
	/**
	 * The variable in which Claude Code gives a command the identifier of the session that runs it.
	 */
	public static final String SESSION_VARIABLE = "CLAUDE_CODE_SESSION_ID";
	/**
	 * The variable in which Claude Code gives a command the identifier of its process.
	 */
	private static final String PROCESS_VARIABLE = "CLAUDE_PID";
	/**
	 * The form of a session identifier, which names the session's transcript file.
	 */
	private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]+");
	/**
	 * The fix of a refusal whose sources a newer Claude Code gives.
	 */
	private static final String UPDATE_FIX = "Run the launcher from a session of Claude Code " + ClaudeRelease.VERSION +
		" or later";

	/**
	 * Creates a session that runs a command.
	 *
	 * @param session    the session
	 * @param transcript the absolute path of the session's transcript
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code transcript} is not absolute
	 */
	public ClaudeCommandSession
	{
		requireThat(session, "session").isNotNull();
		requireThat(transcript, "transcript").isNotNull().isAbsolute();
	}

	/**
	 * Finds the session that runs a command.
	 *
	 * @param environment the command's environment variables
	 * @param userHome    the user's absolute home folder
	 * @param userName    the user's login name
	 * @param ancestors   the processes that run the command, from its parent up
	 * @param json        parses the transcript's records
	 * @return the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is not absolute
	 * @throws IOException              if the session's transcripts cannot be listed or read
	 * @throws PolicyRefusal            if the sources do not identify exactly one session: the environment names no
	 *                                  Claude Code process among {@code ancestors} or no session, no transcript or
	 *                                  more than one has the session's identifier, or the transcript names no project
	 *                                  folder
	 */
	public static ClaudeCommandSession find(Map<String, String> environment, Path userHome, String userName,
		List<ProcessDescription> ancestors, JsonMapper json) throws IOException, PolicyRefusal
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(userHome, "userHome").isNotNull().isAbsolute();
		requireThat(userName, "userName").isNotNull();
		requireThat(ancestors, "ancestors").isNotNull();
		requireThat(json, "json").isNotNull();
		long processId = processId(environment.get(PROCESS_VARIABLE), ancestors);
		String sessionId = environment.get(SESSION_VARIABLE);
		if (sessionId == null || sessionId.isBlank())
		{
			throw new PolicyRefusal("the command's environment does not name the session in " + SESSION_VARIABLE,
				UPDATE_FIX);
		}
		if (!SESSION_ID.matcher(sessionId).matches())
		{
			throw new PolicyRefusal("the command's " + SESSION_VARIABLE + " \"" + sessionId + "\" is not a " +
				"session identifier", UPDATE_FIX);
		}
		Path configuration = ClaudeSession.configurationDirectory(environment, userHome);
		Path transcript = transcript(configuration, sessionId);
		Path project = project(transcript, json);
		return new ClaudeCommandSession(ClaudeSession.of(environment, processId, project, configuration, userHome,
			userName), transcript);
	}

	/**
	 * Returns the Claude Code process that runs the command.
	 *
	 * @param value     the value of {@value #PROCESS_VARIABLE}, or null
	 * @param ancestors the processes that run the command
	 * @return the process's identifier
	 * @throws PolicyRefusal if the value names no process among {@code ancestors}
	 */
	private static long processId(String value, List<ProcessDescription> ancestors) throws PolicyRefusal
	{
		if (value == null || value.isBlank())
		{
			throw new PolicyRefusal("the command's environment does not name its Claude Code process in " +
				PROCESS_VARIABLE, UPDATE_FIX);
		}
		long processId;
		try
		{
			processId = Long.parseLong(value.strip());
		}
		catch (NumberFormatException _)
		{
			processId = 0;
		}
		long id = processId;
		if (id <= 0 || ancestors.stream().noneMatch(ancestor -> ancestor.processId() == id))
		{
			throw new PolicyRefusal("the Claude Code process \"" + value + "\" that the command's " +
				PROCESS_VARIABLE + " names does not run the launcher", "Run the launcher from a Claude Code session");
		}
		return id;
	}

	/**
	 * Returns the one transcript of a session.
	 *
	 * @param configuration the session's configuration folder
	 * @param sessionId     the session's identifier
	 * @return the transcript
	 * @throws IOException   if the transcripts cannot be listed
	 * @throws PolicyRefusal if no project folder or more than one holds a transcript of the session
	 */
	private static Path transcript(Path configuration, String sessionId) throws IOException, PolicyRefusal
	{
		Path projects = configuration.resolve("projects");
		String name = sessionId + ".jsonl";
		List<Path> transcripts = new ArrayList<>();
		if (Files.isDirectory(projects, LinkOption.NOFOLLOW_LINKS))
		{
			try (DirectoryStream<Path> folders = Files.newDirectoryStream(projects))
			{
				for (Path folder : folders)
				{
					Path candidate = folder.resolve(name);
					if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) &&
						Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
						transcripts.add(candidate.toAbsolutePath().normalize());
				}
			}
		}
		if (transcripts.isEmpty())
		{
			throw new PolicyRefusal("no transcript of the session " + sessionId + " is in " + projects,
				"Run the launcher from a Claude Code session that keeps its transcript");
		}
		if (transcripts.size() > 1)
		{
			transcripts.sort(null);
			throw new PolicyRefusal("more than one transcript of the session " + sessionId + " is in " + projects +
				": " + transcripts, "Remove the transcripts that do not belong to the session, then start a new " +
				"session");
		}
		return transcripts.getFirst();
	}

	/**
	 * Returns the project folder that a transcript names: the working directory of its first record that names one.
	 *
	 * @param transcript the transcript
	 * @param json       parses the transcript's records
	 * @return the absolute project folder
	 * @throws IOException   if the transcript cannot be read
	 * @throws PolicyRefusal if no record names an absolute working directory before a record that cannot be parsed
	 */
	private static Path project(Path transcript, JsonMapper json) throws IOException, PolicyRefusal
	{
		Optional<String> cwd = firstWorkingDirectory(transcript, json);
		if (cwd.isPresent())
		{
			try
			{
				Path project = Path.of(cwd.get());
				if (project.isAbsolute())
					return project.normalize();
			}
			catch (InvalidPathException _)
			{
				// Refused below
			}
		}
		throw new PolicyRefusal("the transcript " + transcript + " does not name the session's project folder as " +
			"the absolute working directory of its first records", "Run the launcher from a Claude Code session");
	}

	/**
	 * Returns the working directory that a transcript's first record that names one gives.
	 *
	 * @param transcript the transcript
	 * @param json       parses the transcript's records
	 * @return the working directory, or empty if no record names one before a record that cannot be parsed
	 * @throws IOException if the transcript cannot be read
	 */
	private static Optional<String> firstWorkingDirectory(Path transcript, JsonMapper json) throws IOException
	{
		try (BufferedReader reader = Files.newBufferedReader(transcript, StandardCharsets.UTF_8))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (line.isBlank())
					continue;
				JsonNode cwd;
				try
				{
					cwd = json.readTree(line).path("cwd");
				}
				catch (JacksonException _)
				{
					return Optional.empty();
				}
				if (cwd.isString())
					return Optional.of(cwd.stringValue());
			}
		}
		return Optional.empty();
	}
}
