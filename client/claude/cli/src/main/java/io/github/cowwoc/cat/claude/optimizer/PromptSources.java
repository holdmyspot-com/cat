package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Derives the {@code PROMPT_SOURCES} measurement value from what a Claude session was observed loading (design
 * &sect;5, H21).
 * <p>
 * The value lists the skills, agents and plugins that the {@code init} record names and the hooks that ran, one entry
 * per line in sorted order. A path inside one of the supplied directories appears relative to that directory's
 * placeholder, so the value does not depend on where the run's files were staged. It records no file content and no
 * content hash, so it does not change when a candidate edits a prompt file. It changes only when the set of loaded
 * sources changes.
 * <p>
 * Claude does not report the instruction files ({@code CLAUDE.md}) that it loaded (H21), so they are not part of
 * the value.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class PromptSources
{
	private final JsonMapper json;
	private final List<Map.Entry<String, String>> roots;
	private final Object lock = new Object();
	private final SortedSet<String> entries = new TreeSet<>();

	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @throws NullPointerException if any argument is null
	 */
	PromptSources(JsonMapper json, Map<String, Path> directories)
	{
		requireThat(json, "json").isNotNull();
		requireThat(directories, "directories").isNotNull();
		this.json = json;
		List<Map.Entry<String, String>> sorted = new ArrayList<>();
		for (Map.Entry<String, Path> directory : directories.entrySet())
			sorted.add(Map.entry(directory.getKey(), directory.getValue().toString()));
		// The longest directory first, so that a nested directory wins over its parent.
		sorted.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getValue().length()).reversed());
		this.roots = List.copyOf(sorted);
	}

	/**
	 * Absorbs one harness event line.
	 *
	 * @param line the line. A line that is not a JSON object is ignored.
	 * @throws NullPointerException if {@code line} is null
	 */
	void accept(String line)
	{
		requireThat(line, "line").isNotNull();
		if (!line.startsWith("{"))
			return;
		JsonNode event;
		try
		{
			event = json.readTree(line);
		}
		catch (JacksonException _)
		{
			return;
		}
		if (!event.isObject() || !event.path("type").asString("").equals("system"))
			return;
		String subtype = event.path("subtype").asString("");
		synchronized (lock)
		{
			if (subtype.equals("init"))
				readInit(event);
			else if (subtype.equals("hook_started") || subtype.equals("hook_response"))
				entries.add("hook:" + event.path("hook_event").asString("none") + " " +
					event.path("hook_name").asString("none"));
		}
	}

	/**
	 * Returns the observed value.
	 *
	 * @return the entries, one per line in sorted order, or {@code none} if the events showed nothing loading
	 */
	String value()
	{
		synchronized (lock)
		{
			if (entries.isEmpty())
				return "none";
			return String.join("\n", entries);
		}
	}

	/**
	 * Reads the sources that an {@code init} record lists.
	 *
	 * @param init the record
	 */
	private void readInit(JsonNode init)
	{
		for (JsonNode skill : init.path("skills"))
		{
			if (skill.isString())
				entries.add("skill:" + skill.stringValue());
		}
		for (JsonNode agent : init.path("agents"))
		{
			if (agent.isString())
				entries.add("agent:" + agent.stringValue());
		}
		for (JsonNode plugin : init.path("plugins"))
		{
			entries.add("plugin:" + plugin.path("name").asString("none") + " " + plugin.path("source").asString("none") +
				" " + relativize(plugin.path("path").asString("none")));
		}
	}

	/**
	 * Replaces a leading staged directory with its placeholder.
	 *
	 * @param path the path or name that the event reported
	 * @return the normalized text
	 */
	private String relativize(String path)
	{
		for (Map.Entry<String, String> root : roots)
		{
			String directory = root.getValue();
			if (path.equals(directory))
				return root.getKey();
			if (path.startsWith(directory + "/"))
				return root.getKey() + path.substring(directory.length());
		}
		return path;
	}
}
