package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.codex.policy.PermissionEntry.Access;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads the sandbox that Codex 0.160.0 resolved for the latest turn of a thread, from the {@code permission_profile}
 * of the last {@code turn_context} record in the thread's rollout file.
 * <p>
 * The reader refuses a record whose vocabulary it does not know, because an unknown entry could deny what the reader
 * would otherwise allow. It also reads the shell commands that the thread's model asked Codex to run.
 */
public final class CodexRollout
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String FIX = "Update CAT to a release that supports this Codex release, then start a new " +
		"Codex session";
	/**
	 * The {@code cmd} string of a {@code tools.exec_command} call in a script, as a JSON string.
	 */
	private static final Pattern SCRIPT_COMMAND = Pattern.compile("\\bcmd\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");

	/**
	 * Prevents instantiation.
	 */
	private CodexRollout()
	{
	}

	/**
	 * Returns the identifier of the session that a rollout belongs to, from its {@code session_meta} record. A
	 * subagent thread's rollout names the session of the thread that started it.
	 *
	 * @param rollout the rollout file
	 * @return the session's identifier, or empty if the rollout's first record names none
	 * @throws IOException if the file cannot be read
	 */
	public static Optional<String> sessionId(Path rollout) throws IOException
	{
		try (BufferedReader reader = Files.newBufferedReader(rollout))
		{
			String line = reader.readLine();
			if (line == null)
				return Optional.empty();
			JsonNode record = JSON.readTree(line);
			if (!record.path("type").asString("").equals("session_meta"))
				return Optional.empty();
			JsonNode id = record.path("payload").path("session_id");
			if (!id.isString())
				return Optional.empty();
			return Optional.of(id.stringValue());
		}
		catch (JacksonException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Reads the sandbox of a thread's latest turn.
	 *
	 * @param rollout     the rollout file
	 * @param environment the environment of the session's Codex process
	 * @param slashTmp    the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the machine
	 *                    has none
	 * @return the sandbox, or empty if the rollout has no turn yet
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the latest turn's record cannot be parsed or holds a value that the reader does not
	 *                       know
	 */
	public static Optional<CodexSandbox> latestTurn(Path rollout, Map<String, String> environment, Path slashTmp)
		throws IOException, PolicyRefusal
	{
		Optional<JsonNode> payload = latestTurnPayload(rollout);
		if (payload.isEmpty())
			return Optional.empty();
		return Optional.of(sandbox(rollout, payload.get(), environment, slashTmp));
	}

	/**
	 * Returns the name of the permissions profile that a thread's latest turn uses.
	 *
	 * @param rollout the rollout file
	 * @return the profile's name, or empty if the rollout has no turn yet or its latest turn names no profile
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the latest turn's record cannot be parsed
	 */
	public static Optional<String> latestProfile(Path rollout) throws IOException, PolicyRefusal
	{
		return latestTurnPayload(rollout).map(payload -> payload.path("active_permission_profile").path("id")).
			filter(JsonNode::isString).map(JsonNode::stringValue);
	}

	/**
	 * Returns the reads that a thread's latest turn denies, as its {@code permission_profile} entries name them: the
	 * path of each denied path, and the pattern of each denied glob pattern, with the project roots' prefix replaced
	 * by each workspace root. Unlike {@link #latestTurn(Path, Map, Path)}, a pattern is named whether or not it
	 * matches an existing path.
	 *
	 * @param rollout     the rollout file
	 * @param environment the environment of the session's Codex process
	 * @param slashTmp    the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the machine
	 *                    has none
	 * @return the denied paths and patterns, in the profile's order, or an empty list if the rollout has no turn yet
	 *   or its latest turn's filesystem is not restricted
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the latest turn's record cannot be parsed or holds a value that the reader does not
	 *                       know
	 */
	public static List<String> latestDeniedReads(Path rollout, Map<String, String> environment, Path slashTmp)
		throws IOException, PolicyRefusal
	{
		Optional<JsonNode> payload = latestTurnPayload(rollout);
		if (payload.isEmpty())
			return List.of();
		JsonNode fileSystem = payload.get().path("permission_profile").path("file_system");
		if (!fileSystem.path("type").asString("").equals("restricted"))
			return List.of();
		List<Path> workspaceRoots = new ArrayList<>();
		for (JsonNode root : payload.get().path("workspace_roots"))
			workspaceRoots.add(absolutePath(rollout, root.asString("")));
		List<String> denied = new ArrayList<>();
		for (JsonNode entry : fileSystem.path("entries"))
		{
			if (!entry.path("access").asString("").equals("deny"))
				continue;
			JsonNode path = entry.path("path");
			switch (path.path("type").asString(""))
			{
				case "path" -> denied.add(absolutePath(rollout, path.path("path").asString("")).toString());
				case "glob_pattern" -> denied.addAll(projectRootPatterns(path.path("pattern").asString(""),
					workspaceRoots));
				default ->
				{
					for (Path special : specialPaths(rollout, path.path("value"), workspaceRoots, environment, slashTmp))
						denied.add(special.toString());
				}
			}
		}
		return denied;
	}

	/**
	 * Returns the latest shell command that a thread's model asked Codex to run whose text contains some text. A
	 * command is the {@code cmd} or {@code command} argument of a {@code function_call}, or the {@code cmd} string of
	 * each {@code tools.exec_command} call in the script of a {@code custom_tool_call}. A {@code command} that is an
	 * argument list is the script of a shell's {@code -c} or {@code -lc}, or else its arguments joined by spaces.
	 *
	 * @param rollout the rollout file
	 * @param text    the text
	 * @return the command, or empty if no call holds one that contains {@code text}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be read
	 */
	public static Optional<String> latestCommandContaining(Path rollout, String text) throws IOException
	{
		requireThat(rollout, "rollout").isNotNull();
		requireThat(text, "text").isNotNull();
		String latest = null;
		try (BufferedReader reader = Files.newBufferedReader(rollout))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				if (!line.contains("\"response_item\"") || !line.contains(text))
					continue;
				for (String command : commands(line))
				{
					if (command.contains(text))
						latest = command;
				}
			}
		}
		return Optional.ofNullable(latest);
	}

	/**
	 * Returns the shell commands of one rollout record, as {@link #latestCommandContaining(Path, String)} reads them.
	 *
	 * @param line the record's line
	 * @return the commands, in order, or an empty list if the record is no call or cannot be parsed
	 */
	private static List<String> commands(String line)
	{
		List<String> commands = new ArrayList<>();
		try
		{
			JsonNode payload = JSON.readTree(line).path("payload");
			switch (payload.path("type").asString(""))
			{
				case "function_call" ->
				{
					JsonNode arguments = JSON.readTree(payload.path("arguments").asString("{}"));
					JsonNode command = arguments.path("cmd");
					if (command.isMissingNode())
						command = arguments.path("command");
					if (command.isString())
						commands.add(command.stringValue());
					else if (command.isArray())
						commands.add(argumentList(command));
				}
				case "custom_tool_call" ->
				{
					Matcher matcher = SCRIPT_COMMAND.matcher(payload.path("input").asString(""));
					while (matcher.find())
						commands.add(JSON.readTree(matcher.group(1)).asString(""));
				}
				default ->
				{
				}
			}
		}
		catch (JacksonException _)
		{
			// A record that cannot be parsed names no command
		}
		return commands;
	}

	/**
	 * Returns the command of an argument list.
	 *
	 * @param arguments the argument list
	 * @return the script of a shell's {@code -c} or {@code -lc}, or else the arguments joined by spaces
	 */
	private static String argumentList(JsonNode arguments)
	{
		List<String> words = new ArrayList<>();
		for (JsonNode argument : arguments)
			words.add(argument.asString(""));
		if (words.size() == 3 && (words.get(1).equals("-c") || words.get(1).equals("-lc")))
			return words.get(2);
		return String.join(" ", words);
	}

	/**
	 * Returns the payload of a thread's latest {@code turn_context} record.
	 *
	 * @param rollout the rollout file
	 * @return the payload, or empty if the rollout has no turn yet
	 * @throws IOException   if the file cannot be read
	 * @throws PolicyRefusal if the latest turn's record cannot be parsed
	 */
	private static Optional<JsonNode> latestTurnPayload(Path rollout) throws IOException, PolicyRefusal
	{
		String latest = "";
		String previous = "";
		boolean latestIsLastLine = false;
		try (BufferedReader reader = Files.newBufferedReader(rollout))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				latestIsLastLine = false;
				if (!line.contains("\"turn_context\""))
					continue;
				previous = latest;
				latest = line;
				latestIsLastLine = true;
			}
		}
		if (latest.isEmpty())
			return Optional.empty();
		JsonNode record;
		try
		{
			record = JSON.readTree(latest);
		}
		catch (JacksonException e)
		{
			// Codex may be writing the file's last line
			if (!latestIsLastLine || previous.isEmpty())
				throw unknown(rollout, "a turn_context record that is not JSON: " + e.getOriginalMessage());
			try
			{
				record = JSON.readTree(previous);
			}
			catch (JacksonException e2)
			{
				throw unknown(rollout, "a turn_context record that is not JSON: " + e2.getOriginalMessage());
			}
		}
		if (!record.path("type").asString("").equals("turn_context"))
			return Optional.empty();
		return Optional.of(record.path("payload"));
	}

	/**
	 * Reads the sandbox of a {@code turn_context} record.
	 *
	 * @param rollout     the rollout file
	 * @param payload     the record's payload
	 * @param environment the environment of the session's Codex process
	 * @param slashTmp    the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the machine
	 *                    has none
	 * @return the sandbox
	 * @throws PolicyRefusal if the payload holds a value that the reader does not know
	 */
	private static CodexSandbox sandbox(Path rollout, JsonNode payload, Map<String, String> environment,
		Path slashTmp) throws PolicyRefusal
	{
		JsonNode profile = payload.path("permission_profile");
		String type = profile.path("type").asString("");
		switch (type)
		{
			case "disabled" ->
			{
				return CodexSandbox.UNSANDBOXED;
			}
			case "managed" ->
			{
			}
			case "external" -> throw new PolicyRefusal("Codex records that an external sandbox runs this session's " +
				"commands, whose policy CAT cannot read", "Start Codex with its own sandbox, then start a new Codex " +
				"session");
			default -> throw unknown(rollout, "the permission_profile type \"" + type + "\"");
		}
		String network = profile.path("network").asString("");
		if (!CodexAssumptions.ROLLOUT_NETWORK_STATES.contains(network))
			throw unknown(rollout, "the network state \"" + network + "\"");
		List<Path> workspaceRoots = new ArrayList<>();
		for (JsonNode root : payload.path("workspace_roots"))
			workspaceRoots.add(absolutePath(rollout, root.asString("")));

		JsonNode fileSystem = profile.path("file_system");
		String fileSystemType = fileSystem.path("type").asString("");
		List<PermissionEntry> entries = new ArrayList<>();
		switch (fileSystemType)
		{
			case "unrestricted" ->
			{
				for (Path root : FileSystems.getDefault().getRootDirectories())
					entries.add(new PermissionEntry(root, Access.WRITE));
			}
			case "restricted" ->
			{
				for (JsonNode entry : fileSystem.path("entries"))
					addEntry(rollout, entry, workspaceRoots, environment, slashTmp, entries);
			}
			default -> throw unknown(rollout, "the file_system type \"" + fileSystemType + "\"");
		}
		if (network.equals("enabled"))
			return new CodexSandbox(true, entries, HostAccess.ALL, true);
		return new CodexSandbox(true, entries, HostAccess.NONE, false);
	}

	/**
	 * Adds the entries of one {@code permission_profile} entry.
	 *
	 * @param rollout        the rollout file
	 * @param entry          the entry
	 * @param workspaceRoots the turn's workspace roots
	 * @param environment    the environment of the session's Codex process
	 * @param slashTmp       the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the
	 *                       machine has none
	 * @param entries        the entries to add to
	 * @throws PolicyRefusal if the entry holds a value that the reader does not know
	 */
	private static void addEntry(Path rollout, JsonNode entry, List<Path> workspaceRoots,
		Map<String, String> environment, Path slashTmp, List<PermissionEntry> entries) throws PolicyRefusal
	{
		String accessName = entry.path("access").asString("");
		Access access = null;
		if (CodexAssumptions.ROLLOUT_ACCESS_MODES.contains(accessName))
			access = Access.ofName(accessName);
		if (access == null)
			throw unknown(rollout, "the access \"" + accessName + "\"");
		boolean skipMissing = entry.path("missing_path_behavior").asString("").equals("skip");
		JsonNode path = entry.path("path");
		String pathType = path.path("type").asString("");
		if (!CodexAssumptions.ROLLOUT_PATH_TYPES.contains(pathType))
			throw unknown(rollout, "the path type \"" + pathType + "\"");
		List<Path> targets = new ArrayList<>();
		switch (pathType)
		{
			case "path" -> targets.add(absolutePath(rollout, path.path("path").asString("")));
			case "glob_pattern" ->
			{
				String pattern = path.path("pattern").asString("");
				if (access != Access.DENY)
					return;
				for (String expanded : projectRootPatterns(pattern, workspaceRoots))
					targets.addAll(globMatches(rollout, expanded));
			}
			default -> targets.addAll(specialPaths(rollout, path.path("value"), workspaceRoots, environment, slashTmp));
		}
		for (Path target : targets)
		{
			if (skipMissing && access == Access.WRITE && !Files.exists(target))
				continue;
			entries.add(new PermissionEntry(target, access));
		}
	}

	/**
	 * Returns the paths of a special path.
	 *
	 * @param rollout        the rollout file
	 * @param value          the special path's {@code value}
	 * @param workspaceRoots the turn's workspace roots
	 * @param environment    the environment of the session's Codex process
	 * @param slashTmp       the folder that the {@code slash_tmp} kind names, or {@code null} if the machine has none
	 * @return the paths
	 * @throws PolicyRefusal if the special path's kind is unknown
	 */
	private static List<Path> specialPaths(Path rollout, JsonNode value, List<Path> workspaceRoots,
		Map<String, String> environment, Path slashTmp) throws PolicyRefusal
	{
		String kind = value.path("kind").asString("");
		if (!CodexAssumptions.ROLLOUT_SPECIAL_KINDS.contains(kind))
			throw unknown(rollout, "the special path kind \"" + kind + "\"");
		List<Path> paths = new ArrayList<>();
		switch (kind)
		{
			case "root" -> FileSystems.getDefault().getRootDirectories().forEach(paths::add);
			case "project_roots" ->
			{
				String subpath = value.path("subpath").asString(".");
				for (Path root : workspaceRoots)
					paths.add(root.resolve(subpath).normalize());
			}
			case "tmpdir" ->
			{
				String tmpdir = environment.getOrDefault("TMPDIR", "");
				if (!tmpdir.isBlank() && Path.of(tmpdir).isAbsolute())
					paths.add(Path.of(tmpdir).normalize());
			}
			case "slash_tmp" ->
			{
				if (slashTmp != null)
					paths.add(slashTmp);
			}
			default ->
			{
				// minimal: leaving it out is narrower
			}
		}
		return paths;
	}

	/**
	 * Expands a glob pattern that starts with the project roots' prefix into one pattern for each workspace root.
	 *
	 * @param pattern        the pattern
	 * @param workspaceRoots the turn's workspace roots
	 * @return the patterns
	 */
	private static List<String> projectRootPatterns(String pattern, List<Path> workspaceRoots)
	{
		String prefix = CodexAssumptions.PROJECT_ROOTS_GLOB_PREFIX;
		if (!pattern.startsWith(prefix))
			return List.of(pattern);
		String rest = pattern.substring(prefix.length()).replaceAll("^/+", "");
		List<String> patterns = new ArrayList<>();
		for (Path root : workspaceRoots)
			patterns.add(root.toString().replaceAll("/+$", "") + "/" + rest);
		return patterns;
	}

	/**
	 * Returns the existing paths that a deny glob matches.
	 *
	 * @param rollout the rollout file
	 * @param pattern the pattern
	 * @return the paths
	 * @throws PolicyRefusal if the pattern is not absolute or a folder cannot be listed
	 */
	private static List<Path> globMatches(Path rollout, String pattern) throws PolicyRefusal
	{
		if (!pattern.startsWith("/"))
			throw unknown(rollout, "the relative glob pattern \"" + pattern + "\"");
		try
		{
			return GlobDenials.expand(pattern, Integer.MAX_VALUE);
		}
		catch (IOException e)
		{
			throw new PolicyRefusal("CAT cannot list the paths that the Codex deny pattern \"" + pattern + "\" " +
				"matches: " + e.getMessage(), "Make the folders of the pattern readable, then start a new Codex " +
				"session");
		}
	}

	/**
	 * Parses an absolute path of a rollout.
	 *
	 * @param rollout the rollout file
	 * @param text    the path
	 * @return the path
	 * @throws PolicyRefusal if the text is not an absolute path
	 */
	private static Path absolutePath(Path rollout, String text) throws PolicyRefusal
	{
		try
		{
			Path path = Path.of(text);
			if (path.isAbsolute())
				return path.normalize();
		}
		catch (InvalidPathException _)
		{
			// Refused below
		}
		throw unknown(rollout, "the path \"" + text + "\", which is not absolute");
	}

	/**
	 * Returns the refusal of a rollout value that the reader does not know.
	 *
	 * @param rollout the rollout file
	 * @param value   a description of the value
	 * @return the refusal
	 */
	private static PolicyRefusal unknown(Path rollout, String value)
	{
		return new PolicyRefusal("Codex's record of the session's sandbox in " + rollout + " holds " + value +
			", which CAT does not know", FIX);
	}
}
