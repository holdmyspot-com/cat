package io.github.cowwoc.cat.codex.optimizer;

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
 * Derives the {@code PROMPT_SOURCES} measurement value from what a Codex session was observed loading (design
 * &sect;5, H21).
 * <p>
 * The value lists the hooks that ran, with their source and source path, one entry per line in sorted order. A path
 * inside one of the supplied directories appears relative to that directory's placeholder, so the value does not
 * depend on where the run's files were staged. It records no file content and no content hash, so it does not change
 * when a candidate edits a prompt file. It changes only when the set of loaded sources changes.
 * <p>
 * Codex does not report the instruction files ({@code AGENTS.md}) that it loaded (H21), so they are not part of the
 * value.
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
		String method = event.path("method").asString("");
		if (!method.equals("hook/started") && !method.equals("hook/completed"))
			return;
		JsonNode run = event.path("params").path("run");
		synchronized (lock)
		{
			entries.add("hook:" + run.path("eventName").asString("none") + " " + run.path("source").asString("none") + " " +
				relativize(run.path("sourcePath").asString("none")));
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
