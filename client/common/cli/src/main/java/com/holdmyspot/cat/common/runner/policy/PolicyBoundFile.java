package com.holdmyspot.cat.common.runner.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The file that a request server gives a child's runner with {@link #OPTION}: the child's {@link ChildPolicy}, which
 * the runner renders into the child harness's sandbox settings and which bounds every session that the child requests
 * in turn. The server writes it and the runner reads it. It is a JSON object whose {@code format_version} is
 * {@value #VERSION}, whose {@code sandboxed} is a boolean that says whether the parent, and so the child, has a
 * sandbox, whose {@code write_roots}, {@code write_denials}, {@code read_denials} and {@code read_reallowances} are
 * arrays of absolute paths, whose {@code hosts} is an array of host names in ascending order, whose
 * {@code allow_local_binding} is a boolean, and whose {@code sandbox_selection} is an object of string values, the
 * parent's settings that select its harness's sandbox implementation. It has no other field.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class PolicyBoundFile
{
	/**
	 * The runner option that names the file.
	 */
	public static final String OPTION = "--policy-bound";
	/**
	 * The file's name in a request's directory.
	 */
	public static final String FILE_NAME = "policy-bound.json";
	/**
	 * The version of the file's format.
	 */
	public static final int VERSION = 2;
	/**
	 * The size of the largest file that is read.
	 */
	public static final int MAXIMUM_BYTES = 1_048_576;
	private static final Set<String> FIELDS = Set.of("format_version", "sandboxed", "write_roots",
		"write_denials", "read_denials", "read_reallowances", "hosts", "allow_local_binding", "sandbox_selection");

	/**
	 * Prevents construction.
	 */
	private PolicyBoundFile()
	{
	}

	/**
	 * Writes a child's policy.
	 *
	 * @param json        the document mapper
	 * @param destination the new file
	 * @param policy      the child's policy
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written
	 */
	public static void write(JsonMapper json, Path destination, ChildPolicy policy) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(policy, "policy").isNotNull();
		ObjectNode document = json.createObjectNode().put("format_version", VERSION).
			put("sandboxed", policy.sandboxed());
		putPaths(document, "write_roots", policy.writeRoots());
		putPaths(document, "write_denials", policy.writeDenials());
		putPaths(document, "read_denials", policy.readDenials());
		putPaths(document, "read_reallowances", policy.readReallowances());
		ArrayNode hosts = document.putArray("hosts");
		for (String host : new TreeSet<>(policy.hosts()))
			hosts.add(host);
		document.put("allow_local_binding", policy.allowLocalBinding());
		ObjectNode selection = document.putObject("sandbox_selection");
		for (Map.Entry<String, String> setting : new TreeMap<>(policy.sandboxSelection()).entrySet())
			selection.put(setting.getKey(), setting.getValue());
		Files.writeString(destination, json.writeValueAsString(document));
	}

	/**
	 * Reads a child's policy.
	 *
	 * @param json   the document mapper
	 * @param source the file
	 * @return the policy
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be read, is larger than {@value #MAXIMUM_BYTES} bytes, or is
	 *                              not a version {@value #VERSION} document with exactly the fields above
	 */
	public static ChildPolicy read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		JsonNode document;
		try
		{
			document = json.readTree(BoundedInput.readUtf8(source, MAXIMUM_BYTES, "policy bound"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The policy bound " + source + " is not valid JSON", e);
		}
		if (!document.isObject())
			throw new IOException("The policy bound " + source + " is not a JSON object");
		for (String field : document.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("The policy bound " + source + " has an unknown field: " + field);
		}
		for (String field : FIELDS)
		{
			if (!document.has(field))
				throw new IOException("The policy bound " + source + " lacks the field " + field);
		}
		JsonNode version = document.path("format_version");
		if (!version.isInt() || version.intValue() != VERSION)
			throw new IOException("The policy bound " + source + " has an unsupported format_version: " + version);
		if (!document.path("sandboxed").isBoolean())
			throw new IOException("The policy bound " + source + " has a non-boolean sandboxed");
		if (!document.path("allow_local_binding").isBoolean())
			throw new IOException("The policy bound " + source + " has a non-boolean allow_local_binding");
		JsonNode selectionNode = document.path("sandbox_selection");
		if (!selectionNode.isObject())
			throw new IOException("The policy bound " + source + " has a sandbox_selection that is not an object");
		Map<String, String> selection = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> setting : selectionNode.properties())
		{
			if (!setting.getValue().isString())
			{
				throw new IOException("The policy bound " + source + " has a sandbox_selection whose " +
					setting.getKey() + " is not a string");
			}
			selection.put(setting.getKey(), setting.getValue().stringValue());
		}
		try
		{
			return new ChildPolicy(document.path("sandboxed").booleanValue(), paths(document, "write_roots", source),
				paths(document, "write_denials", source), paths(document, "read_denials", source),
				paths(document, "read_reallowances", source), new LinkedHashSet<>(strings(document, "hosts", source)),
				document.path("allow_local_binding").booleanValue(), selection);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("The policy bound " + source + " is not a valid policy: " + e.getMessage(), e);
		}
	}

	/**
	 * Returns the arguments of a runner that a bounded session's runner starts for a session that the bounded session
	 * requests, with the requested session's bound: the bounded session's policy, so that the requested session's
	 * runner intersects its own needs with it and the requested session is never wider than the bounded session. The
	 * bound is written to {@value #FILE_NAME} in the requested session's directory.
	 *
	 * @param json      the document mapper
	 * @param arguments the runner's arguments
	 * @param directory the requested session's directory, which receives the bound's file
	 * @param policy    the bounded session's policy, or {@code null} if the bounded session has not started yet
	 * @return {@code arguments} followed by {@value #OPTION} and the bound's file, or {@code arguments} unchanged if
	 *         {@code policy} is null
	 * @throws NullPointerException if {@code json}, {@code arguments} or {@code directory} is null
	 * @throws IOException          if the bound's file cannot be written
	 */
	public static List<String> boundArguments(JsonMapper json, List<String> arguments, Path directory,
		ChildPolicy policy) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		requireThat(directory, "directory").isNotNull();
		if (policy == null)
			return arguments;
		Path destination = Files.createDirectories(directory).resolve(FILE_NAME);
		write(json, destination, policy);
		List<String> result = new ArrayList<>(arguments);
		result.add(OPTION);
		result.add(destination.toString());
		return result;
	}

	/**
	 * Reads an array of paths from a document.
	 *
	 * @param document the document
	 * @param field    the array's field
	 * @param source   the document's file
	 * @return the paths, in order
	 * @throws IOException if the field is not an array of text
	 */
	private static List<Path> paths(JsonNode document, String field, Path source) throws IOException
	{
		List<Path> paths = new ArrayList<>();
		for (String value : strings(document, field, source))
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
	 * @throws IOException if the field is not an array of text
	 */
	private static List<String> strings(JsonNode document, String field, Path source) throws IOException
	{
		JsonNode array = document.path(field);
		if (!array.isArray())
			throw new IOException("The policy bound " + source + " has a non-array " + field);
		List<String> values = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException("The policy bound " + source + " has a non-text element in " + field);
			values.add(element.stringValue());
		}
		return values;
	}

	/**
	 * Adds an array of paths to a document.
	 *
	 * @param document the document
	 * @param field    the array's field
	 * @param paths    the paths, in order
	 */
	private static void putPaths(ObjectNode document, String field, List<Path> paths)
	{
		ArrayNode array = document.putArray(field);
		for (Path path : paths)
			array.add(path.toString());
	}
}
