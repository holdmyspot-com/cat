package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The permission entries that setup added to a harness's configuration, which uninstall removes, and which setup
 * removes once the harness integration no longer maintains them. The record is a JSON
 * object that maps each entry key that the harness integration reported to the list of entries added under it.
 */
public final class PermissionRecord
{
	/**
	 * The largest supported record.
	 */
	private static final int MAXIMUM_BYTES = 1024 * 1024;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Prevents construction.
	 */
	private PermissionRecord()
	{
	}

	/**
	 * Reads a record.
	 *
	 * @param file the record
	 * @return the recorded entries, empty when the record does not exist
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException          if the record is a link, cannot be read or is malformed
	 */
	public static Map<String, List<String>> read(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		StateFiles.requireNoLinks(file);
		if (!Files.exists(file, NOFOLLOW_LINKS))
			return Map.of();
		String text = BoundedInput.readUtf8(file, MAXIMUM_BYTES, "The permission record");
		JsonNode root;
		try
		{
			root = MAPPER.readTree(text);
		}
		catch (JacksonException e)
		{
			throw new IOException("The permission record is not valid JSON: " + file, e);
		}
		if (root == null || !root.isObject())
			throw new IOException("The permission record must contain a JSON object: " + file);
		Map<String, List<String>> entries = new TreeMap<>();
		for (Entry<String, JsonNode> property : root.properties())
		{
			if (!property.getValue().isArray())
				throw new IOException("The permission record's " + property.getKey() + " must be an array: " + file);
			List<String> values = new ArrayList<>();
			for (JsonNode value : property.getValue())
			{
				if (!value.isString())
					throw new IOException("The permission record's " + property.getKey() + " must list strings: " + file);
				values.add(value.stringValue());
			}
			entries.put(property.getKey(), List.copyOf(values));
		}
		return entries;
	}

	/**
	 * Adds newly added entries to a record. A record is never replaced when nothing was added, so the entries of an
	 * earlier setup stay recorded.
	 *
	 * @param file  the record
	 * @param added the entries that the harness integration just added
	 * @return the complete record after the addition
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the record cannot be read or written
	 */
	public static Map<String, List<String>> add(Path file, Map<String, List<String>> added) throws IOException
	{
		requireThat(added, "added").isNotNull();
		Map<String, List<String>> recorded = read(file);
		if (added.values().stream().allMatch(List::isEmpty))
			return recorded;
		Map<String, List<String>> merged = new TreeMap<>(recorded);
		for (Entry<String, List<String>> entry : added.entrySet())
		{
			Set<String> values = new LinkedHashSet<>(merged.getOrDefault(entry.getKey(), List.of()));
			values.addAll(entry.getValue());
			merged.put(entry.getKey(), List.copyOf(values));
		}
		write(file, merged);
		return merged;
	}

	/**
	 * Returns the recorded entries that the harness integration no longer maintains, such as the entries of an
	 * operation that the updater no longer has.
	 *
	 * @param file       the record
	 * @param maintained every entry that the harness integration maintains, by entry key
	 * @return the obsolete entries by entry key; empty when every recorded entry is maintained
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the record cannot be read
	 */
	public static Map<String, List<String>> obsolete(Path file, Map<String, List<String>> maintained)
		throws IOException
	{
		requireThat(maintained, "maintained").isNotNull();
		Map<String, List<String>> obsolete = new TreeMap<>();
		for (Entry<String, List<String>> entry : read(file).entrySet())
		{
			List<String> kept = maintained.getOrDefault(entry.getKey(), List.of());
			List<String> retired = entry.getValue().stream().filter(value -> !kept.contains(value)).toList();
			if (!retired.isEmpty())
				obsolete.put(entry.getKey(), retired);
		}
		return obsolete;
	}

	/**
	 * Removes entries from a record once the harness integration removed them from the harness configuration.
	 *
	 * @param file    the record
	 * @param removed the removed entries, by entry key
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the record cannot be read or written
	 */
	public static void remove(Path file, Map<String, List<String>> removed) throws IOException
	{
		requireThat(removed, "removed").isNotNull();
		Map<String, List<String>> remaining = new TreeMap<>();
		for (Entry<String, List<String>> entry : read(file).entrySet())
		{
			List<String> gone = removed.getOrDefault(entry.getKey(), List.of());
			List<String> values = entry.getValue().stream().filter(value -> !gone.contains(value)).toList();
			if (!values.isEmpty())
				remaining.put(entry.getKey(), values);
		}
		write(file, remaining);
	}

	/**
	 * Writes a record.
	 *
	 * @param file    the record
	 * @param entries the entries by entry key
	 * @throws IOException if the record cannot be written
	 */
	private static void write(Path file, Map<String, List<String>> entries) throws IOException
	{
		ObjectNode root = MAPPER.createObjectNode();
		for (Entry<String, List<String>> entry : entries.entrySet())
		{
			ArrayNode values = root.putArray(entry.getKey());
			entry.getValue().forEach(values::add);
		}
		StateFiles.write(file, (MAPPER.writeValueAsString(root) + "\n").getBytes(UTF_8));
	}
}
