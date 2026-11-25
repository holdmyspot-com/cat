package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes a prompt test's {@code examples.json}, which lists the directories below its
 * {@value PromptTestFiles#EXAMPLES} directory that hold no entry:
 * <pre>{@code
 * {
 *   "format_version": 1,
 *   "empty_directories": ["arrival/fail_wrong_day/project", "arrival/pass_same_day/initial"]
 * }
 * }</pre>
 * A Git checkout drops a directory that holds no file, so an example's explicitly empty {@code initial/} or
 * {@code project/} would otherwise read back as omitted and take its default. Each path is relative to
 * {@value PromptTestFiles#EXAMPLES}, with {@code /} separators. Delivery writes the file, sorted, only when the list is
 * not empty; the contract author never writes it, because a draft's empty directories are themselves its record.
 * Unknown fields are refused.
 */
public final class ExamplesFile
{
	/**
	 * The file's name in a prompt test directory.
	 */
	public static final String NAME = "examples.json";
	/**
	 * The format version that this class reads and writes.
	 */
	public static final int FORMAT_VERSION = 1;
	/**
	 * What to do about a refused file, which CAT wrote when it delivered the prompt test.
	 */
	static final String CORRECTION = "CAT writes this file when it delivers the prompt test. Restore the delivered " +
		"file, or remove it and create the empty example directories that it lists.";
	private static final String FORMAT_VERSION_FIELD = "format_version";
	private static final String EMPTY_DIRECTORIES = "empty_directories";

	/**
	 * Prevents instantiation.
	 */
	private ExamplesFile()
	{
	}

	/**
	 * Returns the directories of a draft's examples that hold no entry.
	 *
	 * @param files       the example files' paths, relative to {@value PromptTestFiles#EXAMPLES} with {@code /}
	 *                    separators
	 * @param directories the explicitly recorded directories, relative to {@value PromptTestFiles#EXAMPLES} with
	 *                    {@code /} separators; their parent directories and those of {@code files} are implicit
	 * @return the directories of {@code directories} that no file or other directory lies below, sorted by path
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static SortedSet<String> emptyDirectories(Set<String> files, Set<String> directories)
	{
		requireThat(files, "files").isNotNull();
		requireThat(directories, "directories").isNotNull();
		Set<String> parents = new HashSet<>();
		for (String file : files)
			addParents(file, parents);
		for (String directory : directories)
			addParents(directory, parents);
		SortedSet<String> empty = new TreeSet<>();
		for (String directory : directories)
		{
			if (!parents.contains(directory))
				empty.add(directory);
		}
		return Collections.unmodifiableSortedSet(empty);
	}

	/**
	 * Adds the parent directories of a path.
	 *
	 * @param path    a path with {@code /} separators
	 * @param parents receives each proper prefix of {@code path} that ends before a {@code /}
	 */
	private static void addParents(String path, Set<String> parents)
	{
		for (int slash = path.indexOf('/'); slash != -1; slash = path.indexOf('/', slash + 1))
			parents.add(path.substring(0, slash));
	}

	/**
	 * Writes the file.
	 *
	 * @param json             the JSON mapper
	 * @param emptyDirectories the directories that hold no entry, relative to {@value PromptTestFiles#EXAMPLES} with
	 *                         {@code /} separators
	 * @param out              the writer that receives the file's content
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if {@code out} fails
	 */
	public static void write(JsonMapper json, Set<String> emptyDirectories, Writer out) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(emptyDirectories, "emptyDirectories").isNotNull();
		requireThat(out, "out").isNotNull();
		try (JsonGenerator generator = json.writerWithDefaultPrettyPrinter().
			without(StreamWriteFeature.AUTO_CLOSE_TARGET).createGenerator(out))
		{
			generator.writeStartObject();
			generator.writeNumberProperty(FORMAT_VERSION_FIELD, FORMAT_VERSION);
			generator.writeArrayPropertyStart(EMPTY_DIRECTORIES);
			for (String directory : new TreeSet<>(emptyDirectories))
				generator.writeString(directory);
			generator.writeEndArray();
			generator.writeEndObject();
		}
		out.write('\n');
	}

	/**
	 * Reads the listed directories, refusing a file that does not follow the format. The caller checks where each
	 * path leads.
	 *
	 * @param json     the JSON mapper
	 * @param content  the file's content
	 * @param location the file's name in a problem
	 * @return the listed directories, in file order
	 * @throws ScenarioFormatException if the content is not one object of {@link #FORMAT_VERSION} with exactly the
	 *                                 file's fields, or it lists a directory twice
	 */
	static List<String> read(JsonMapper json, byte[] content, String location) throws ScenarioFormatException
	{
		JsonNode root;
		try
		{
			root = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(content);
		}
		catch (JacksonException e)
		{
			throw problem(location, "the file is not valid JSON: " + e.getOriginalMessage());
		}
		if (root == null || !root.isObject())
			throw problem(location, "the file must hold one JSON object");
		for (String name : root.propertyNames())
		{
			if (!name.equals(FORMAT_VERSION_FIELD) && !name.equals(EMPTY_DIRECTORIES))
			{
				throw problem(location, "the field '" + name + "' is unknown. Fields: " + FORMAT_VERSION_FIELD + ", " +
					EMPTY_DIRECTORIES);
			}
		}
		JsonNode version = root.get(FORMAT_VERSION_FIELD);
		if (version == null || !version.isInt() || version.intValue() != FORMAT_VERSION)
		{
			throw problem(location, FORMAT_VERSION_FIELD + " must be " + FORMAT_VERSION + ", but it is " +
				Objects.toString(version, "missing"));
		}
		JsonNode directories = root.get(EMPTY_DIRECTORIES);
		if (directories == null || !directories.isArray())
			throw problem(location, EMPTY_DIRECTORIES + " must be an array of directory paths");
		Set<String> listed = new LinkedHashSet<>();
		for (JsonNode directory : directories)
		{
			if (!directory.isString())
				throw problem(location, EMPTY_DIRECTORIES + " must hold only strings, but it holds " + directory);
			if (!listed.add(directory.stringValue()))
				throw problem(location, EMPTY_DIRECTORIES + " lists '" + directory.stringValue() + "' twice");
		}
		return List.copyOf(listed);
	}

	/**
	 * Returns the refusal of a file.
	 *
	 * @param location the file's name
	 * @param problem  what is wrong, phrased as a clause
	 * @return the exception
	 */
	private static ScenarioFormatException problem(String location, String problem)
	{
		return new ScenarioFormatException(location + ": " + problem + ". " + CORRECTION);
	}
}
