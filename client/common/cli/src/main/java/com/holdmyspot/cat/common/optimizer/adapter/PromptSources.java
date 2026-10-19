package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Derives the {@code PROMPT_SOURCES} measurement value from what a session was observed loading at session start.
 * <p>
 * The value lists the sources that the harness's events report, one entry per line in sorted order. A subclass
 * reads one harness's event format and adds an entry for each source. A path inside one of the supplied directories
 * appears relative to that directory's placeholder, so the value does not depend on where the run's files were
 * staged. The value records no file content and no content hash, so it does not change when a candidate edits a
 * prompt file. It changes only when the set of loaded sources changes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. {@link #read(JsonNode)} runs while the observation holds its
 * lock.
 */
public abstract class PromptSources
{
	private final JsonMapper json;
	private final List<Map.Entry<String, String>> roots;
	/**
	 * The separator of the directories' file system.
	 */
	private final char separator;
	private final Object lock = new Object();
	private final SortedSet<String> entries = new TreeSet<>();

	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @throws NullPointerException if any argument is null
	 */
	protected PromptSources(JsonMapper json, Map<String, Path> directories)
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
		this.separator = FileSystems.getDefault().getSeparator().charAt(0);
	}

	/**
	 * Absorbs one harness event line.
	 *
	 * @param line the line. A line that is not a JSON object is ignored.
	 * @throws NullPointerException if {@code line} is null
	 */
	public final void accept(String line)
	{
		JsonNode event = EventLine.parse(json, line);
		if (event == null)
			return;
		synchronized (lock)
		{
			read(event);
		}
	}

	/**
	 * Reads the sources that one harness event reports.
	 *
	 * @param event a JSON object
	 */
	protected abstract void read(JsonNode event);

	/**
	 * Adds a source.
	 *
	 * @param entry the source's entry
	 * @throws NullPointerException if {@code entry} is null
	 */
	protected final void add(String entry)
	{
		requireThat(entry, "entry").isNotNull();
		entries.add(entry);
	}

	/**
	 * Returns the observed value.
	 *
	 * @return the entries, one per line in sorted order, or {@code none} if the events showed nothing loading
	 */
	public final String value()
	{
		synchronized (lock)
		{
			if (entries.isEmpty())
				return "none";
			return String.join("\n", entries);
		}
	}

	/**
	 * Replaces a leading staged directory with its placeholder. The harness may write the directory with the
	 * platform's separator or with {@code /}, and either may separate it from the rest of the path.
	 *
	 * @param path the path or name that the event reported
	 * @return the normalized text
	 */
	protected final String relativize(String path)
	{
		for (Map.Entry<String, String> root : roots)
		{
			String directory = root.getValue();
			for (String form : List.of(directory, directory.replace(separator, '/')))
			{
				if (path.equals(form))
					return root.getKey();
				if (path.startsWith(form) && isSeparator(path.charAt(form.length())))
					return root.getKey() + path.substring(form.length());
			}
		}
		return path;
	}

	/**
	 * Indicates whether a character separates the names of a path.
	 *
	 * @param character a character
	 * @return true if it is {@code /} or the platform's separator
	 */
	private boolean isSeparator(char character)
	{
		return character == '/' || character == separator;
	}
}
