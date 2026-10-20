package com.holdmyspot.cat.claude.optimizer;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.claude.secrets.StreamJsonPromptSourcesAccess;
import com.holdmyspot.cat.claude.secrets.StreamJsonPromptSourcesSecrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Observes the prompt sources that a Claude session's {@code stream-json} records report.
 * <p>
 * The value lists the skills, agents and plugins that the {@code init} record names and that come from files, and the
 * hooks that ran on {@code SessionStart}. A plugin comes from a file unless the record gives it the path
 * {@code builtin}. A skill or agent comes from a file when its namespace is the name of such a plugin, or when a file
 * below the {@code .claude/skills}, {@code .claude/commands} or {@code .claude/agents} directory of the record's
 * working directory declares its name. The value leaves out the other skills and agents, which come with Claude Code
 * or with the account and which a session may or may not list depending on what Claude Code fetched before it wrote
 * the record. Hooks triggered by other events contribute no entries. Claude does not report the instruction files
 * ({@code CLAUDE.md}) that it loaded, so they are not part of the value.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class StreamJsonPromptSources extends PromptSources
{
	/**
	 * The directories below a project's {@code .claude} directory whose files declare skills, commands and agents.
	 */
	private static final List<String> DEFINITIONS = List.of("skills", "commands", "agents");
	/**
	 * The file that declares a skill, whose name is the name of the directory that holds it.
	 */
	private static final String SKILL = "SKILL.md";
	/**
	 * The YAML frontmatter at the start of a definition file.
	 */
	private static final Pattern FRONTMATTER = Pattern.compile("(?s)\\A---\\R(.*?)\\R---");
	/**
	 * The {@code name} field of a definition's frontmatter, which replaces the name that the file's path gives.
	 */
	private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*[\"']?([^\"'\\r\\n]+?)[\"']?\\s*$");
	/**
	 * The path that the {@code init} record gives a plugin that comes with Claude Code.
	 */
	private static final String BUILT_IN = "builtin";

	static
	{
		StreamJsonPromptSourcesSecrets.setAccess(new StreamJsonPromptSourcesAccess()
		{
			@Override
			public StreamJsonPromptSources newStreamJsonPromptSources(JsonMapper json, Map<String, Path> directories)
			{
				return new StreamJsonPromptSources(json, directories);
			}
		});
	}

	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @throws NullPointerException if any argument is null
	 */
	StreamJsonPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		super(json, directories);
	}

	@Override
	protected void read(JsonNode event)
	{
		if (!event.path("type").asString("").equals("system"))
			return;
		String subtype = event.path("subtype").asString("");
		if (subtype.equals("init"))
			readInit(event);
		else if ((subtype.equals("hook_started") || subtype.equals("hook_response")) &&
			event.path("hook_event").asString("").equals("SessionStart"))
		{
			add("hook:" + event.path("hook_event").asString("none") + " " +
				event.path("hook_name").asString("none"));
		}
	}

	/**
	 * Reads the sources that an {@code init} record lists.
	 *
	 * @param init the record
	 * @throws UncheckedIOException if a definition file of the record's working directory cannot be read
	 */
	private void readInit(JsonNode init)
	{
		Set<String> plugins = new HashSet<>();
		for (JsonNode plugin : init.path("plugins"))
		{
			String path = plugin.path("path").asString("none");
			if (path.equals(BUILT_IN))
				continue;
			String name = plugin.path("name").asString("none");
			plugins.add(name);
			add("plugin:" + name + " " + plugin.path("source").asString("none") + " " + relativize(path));
		}

		Set<String> declared = declaredNames(init.path("cwd").asString(""));
		for (JsonNode skill : init.path("skills"))
		{
			if (skill.isString() && isFromFile(skill.stringValue(), plugins, declared))
				add("skill:" + skill.stringValue());
		}
		for (JsonNode agent : init.path("agents"))
		{
			if (agent.isString() && isFromFile(agent.stringValue(), plugins, declared))
				add("agent:" + agent.stringValue());
		}
	}

	/**
	 * Indicates whether a skill or agent comes from a file.
	 *
	 * @param name     the name that the {@code init} record lists
	 * @param plugins  the names of the plugins that come from files
	 * @param declared the names that the project's definition files declare
	 * @return {@code true} if {@code declared} contains the name or the name's namespace is in {@code plugins}
	 */
	private static boolean isFromFile(String name, Set<String> plugins, Set<String> declared)
	{
		if (declared.contains(name))
			return true;
		int separator = name.indexOf(':');
		return separator >= 0 && plugins.contains(name.substring(0, separator));
	}

	/**
	 * Returns the names that the skill, command and agent files of a project declare. A file declares the name of the
	 * directory that holds a {@code SKILL.md}, or else its file name without {@code .md} and its path below the
	 * definition directory with {@code :} between the names, and the {@code name} of its frontmatter.
	 *
	 * @param project the project directory, or an empty string if the record names none
	 * @return the names
	 * @throws UncheckedIOException if a definition file cannot be read
	 */
	private static Set<String> declaredNames(String project)
	{
		Set<String> names = new HashSet<>();
		if (project.isEmpty())
			return names;
		Path claude = Path.of(project, ".claude");
		for (String kind : DEFINITIONS)
		{
			Path directory = claude.resolve(kind);
			if (!Files.isDirectory(directory))
				continue;
			try (Stream<Path> files = Files.walk(directory))
			{
				for (Path file : files.filter(path -> path.toString().endsWith(".md") && Files.isRegularFile(path)).
					toList())
				{
					names.addAll(pathNames(directory, file));
					Matcher frontmatter = FRONTMATTER.matcher(new String(Files.readAllBytes(file), UTF_8));
					if (frontmatter.find())
					{
						Matcher name = NAME.matcher(frontmatter.group(1));
						if (name.find())
							names.add(name.group(1));
					}
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}
		return names;
	}

	/**
	 * Returns the names that a definition file's path declares.
	 *
	 * @param directory the definition directory
	 * @param file      a {@code .md} file below it
	 * @return the name of the directory that holds a {@code SKILL.md}, or else the file name without {@code .md} and
	 *   the path below {@code directory} without {@code .md} with {@code :} between the names
	 */
	private static List<String> pathNames(Path directory, Path file)
	{
		Path relative = directory.relativize(file);
		if (file.getFileName().toString().equals(SKILL))
		{
			if (relative.getNameCount() < 2)
				return List.of();
			return List.of(relative.getName(relative.getNameCount() - 2).toString());
		}
		String stem = file.getFileName().toString();
		stem = stem.substring(0, stem.length() - ".md".length());
		Path parent = relative.getParent();
		if (parent == null)
			return List.of(stem);
		StringBuilder namespaced = new StringBuilder();
		for (Path name : parent)
			namespaced.append(name).append(':');
		return List.of(stem, namespaced.append(stem).toString());
	}
}
