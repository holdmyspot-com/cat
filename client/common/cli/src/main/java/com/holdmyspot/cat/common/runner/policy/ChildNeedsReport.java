package com.holdmyspot.cat.common.runner.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a child session of one harness needs beyond its run folder, as that harness's runner reports it to a session
 * launcher with {@link #OPTION}: the folders that it writes outside the run folder, the hosts that its commands reach,
 * and the login files that its isolated home copies. The runner writes the report and the launcher reads it. Its file
 * is a JSON object whose {@code format_version} is {@value #VERSION}, whose {@code writes} and {@code logins} are
 * arrays of absolute paths, and whose {@code hosts} is an array of host names in ascending order. It has no other
 * field.
 *
 * @param writes the absolute folders that the child writes outside its run folder, such as dependency caches
 * @param hosts  the hosts that the child's commands reach, as host names or literal IP addresses
 * @param logins the absolute login files that the child's isolated home copies
 */
public record ChildNeedsReport(List<Path> writes, Set<String> hosts, List<Path> logins)
{
	/**
	 * The runner option that asks for the report, followed by the file that receives it.
	 */
	public static final String OPTION = "--child-needs";
	/**
	 * The report's file name in a request's directory.
	 */
	public static final String FILE_NAME = "child-needs.json";
	/**
	 * The version of the file's format.
	 */
	public static final int VERSION = 1;
	/**
	 * The size of the largest file that is read.
	 */
	public static final int MAXIMUM_BYTES = 1_048_576;
	private static final Set<String> FIELDS = Set.of("format_version", "writes", "hosts", "logins");

	/**
	 * Creates a report.
	 *
	 * @param writes the absolute folders that the child writes outside its run folder, such as dependency caches
	 * @param hosts  the hosts that the child's commands reach, as host names or literal IP addresses
	 * @param logins the absolute login files that the child's isolated home copies
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if a path is not absolute, or a host is blank
	 */
	public ChildNeedsReport
	{
		writes = absolute(writes, "writes");
		logins = absolute(logins, "logins");
		requireThat(hosts, "hosts").isNotNull();
		for (String host : hosts)
			requireThat(host, "host").isNotBlank();
		hosts = Set.copyOf(hosts);
	}

	/**
	 * Copies a list of absolute paths.
	 *
	 * @param paths the paths
	 * @param name  the name of the list
	 * @return the copy
	 * @throws NullPointerException     if {@code paths} is null or contains null
	 * @throws IllegalArgumentException if a path is not absolute
	 */
	private static List<Path> absolute(List<Path> paths, String name)
	{
		List<Path> copy = List.copyOf(paths);
		for (Path path : copy)
		{
			if (!path.isAbsolute())
				throw new IllegalArgumentException(name + " must hold absolute paths: " + path);
		}
		return copy;
	}

	/**
	 * Writes the report.
	 *
	 * @param json        the document mapper
	 * @param destination the file
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written
	 */
	public void write(JsonMapper json, Path destination) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(destination, "destination").isNotNull();
		ObjectNode document = json.createObjectNode().put("format_version", VERSION);
		ArrayNode writeArray = document.putArray("writes");
		for (Path write : writes)
			writeArray.add(write.toString());
		ArrayNode hostArray = document.putArray("hosts");
		for (String host : new TreeSet<>(hosts))
			hostArray.add(host);
		ArrayNode loginArray = document.putArray("logins");
		for (Path login : logins)
			loginArray.add(login.toString());
		Files.writeString(destination, json.writeValueAsString(document));
	}

	/**
	 * Reads a report.
	 *
	 * @param json   the document mapper
	 * @param source the file
	 * @return the report
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be read, is larger than {@value #MAXIMUM_BYTES} bytes, or is
	 *                              not a version {@value #VERSION} document with exactly the fields above
	 */
	public static ChildNeedsReport read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		JsonNode document;
		try
		{
			document = json.readTree(BoundedInput.readUtf8(source, MAXIMUM_BYTES, "child needs report"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The child needs report " + source + " is not valid JSON", e);
		}
		if (!document.isObject())
			throw new IOException("The child needs report " + source + " is not a JSON object");
		for (String field : document.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("The child needs report " + source + " has an unknown field: " + field);
		}
		JsonNode version = document.path("format_version");
		if (!version.isInt() || version.intValue() != VERSION)
		{
			throw new IOException("The child needs report " + source + " has an unsupported format_version: " +
				version);
		}
		try
		{
			return new ChildNeedsReport(paths(strings(document, "writes", source)),
				new LinkedHashSet<>(strings(document, "hosts", source)), paths(strings(document, "logins", source)));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("The child needs report " + source + " is not valid: " + e.getMessage(), e);
		}
	}

	/**
	 * Converts text to paths.
	 *
	 * @param values the text
	 * @return the paths, in order
	 */
	private static List<Path> paths(List<String> values)
	{
		List<Path> paths = new ArrayList<>();
		for (String value : values)
			paths.add(Path.of(value));
		return paths;
	}

	/**
	 * Reads an array of text from a document.
	 *
	 * @param document the document
	 * @param field    the array's field
	 * @param source   the document's file
	 * @return the text, in order
	 * @throws IOException if the field is absent or is not an array of text
	 */
	private static List<String> strings(JsonNode document, String field, Path source) throws IOException
	{
		JsonNode array = document.path(field);
		if (!array.isArray())
			throw new IOException("The child needs report " + source + " lacks the array " + field);
		List<String> values = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException("The child needs report " + source + " has a non-text element in " + field);
			values.add(element.stringValue());
		}
		return values;
	}
}
