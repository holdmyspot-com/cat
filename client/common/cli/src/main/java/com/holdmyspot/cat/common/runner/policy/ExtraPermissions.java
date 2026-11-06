package com.holdmyspot.cat.common.runner.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
 * The writes and hosts that a caller asks for a child session beyond what the child's harness needs and the request
 * denies, such as the host of a package registry or a cache outside the project. They join the child's
 * {@link ChildNeeds}, so the child gets them when its parent's sandbox allows them and is refused, naming them,
 * when it does not.
 * <p>
 * The caller names them in a JSON file whose path follows {@link #OPTION}. The file is a JSON object whose
 * {@code format_version} is {@value #VERSION}, whose {@code writes} is an array of absolute paths that the child's
 * commands may write, and whose {@code hosts} is an array of host names or literal IP addresses that they may reach.
 * Either array may be left out and then asks for nothing of its kind. The file has no other field. For example:
 *
 * <pre>{@code
 * {"format_version": 1, "writes": ["/home/me/.cache/pip"], "hosts": ["pypi.org", "files.pythonhosted.org"]}
 * }</pre>
 *
 * @param writes the absolute paths outside the child's run folder that its commands may write
 * @param hosts  the hosts that its commands may reach, as host names or literal IP addresses, in lower case
 */
public record ExtraPermissions(List<Path> writes, Set<String> hosts)
{
	/**
	 * The runner option that names the file of extra permissions.
	 */
	public static final String OPTION = "--extra-permissions";
	/**
	 * The name of a runner's copy of the file in the folder of a session that it starts.
	 */
	public static final String FILE_NAME = "extra-permissions.json";
	/**
	 * The version of the file's format.
	 */
	public static final int VERSION = 1;
	/**
	 * The size of the largest file that is read.
	 */
	public static final int MAXIMUM_BYTES = 65_536;
	/**
	 * The permissions of a caller that asks for none.
	 */
	public static final ExtraPermissions NONE = new ExtraPermissions(List.of(), Set.of());
	private static final Set<String> FIELDS = Set.of("format_version", "writes", "hosts");

	/**
	 * Creates extra permissions.
	 *
	 * @param writes the absolute paths outside the child's run folder that its commands may write
	 * @param hosts  the hosts that its commands may reach, as host names or literal IP addresses
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if a path is not absolute, or a host is blank or is a pattern that starts with
	 *                                  {@code *}
	 */
	public ExtraPermissions
	{
		writes = ParentPolicy.absolutePaths(writes, "writes");
		hosts = ChildNeeds.lowerCaseHosts(hosts);
	}

	/**
	 * Indicates whether these permissions ask for nothing.
	 *
	 * @return {@code true} if there are no writes and no hosts
	 */
	public boolean isEmpty()
	{
		return writes.isEmpty() && hosts.isEmpty();
	}

	/**
	 * Writes these permissions as a file that {@link #read} reads.
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
		Files.writeString(destination, json.writeValueAsString(toDocument(json)));
	}

	/**
	 * Returns these permissions as the document that the file holds, which a request's manifest and a campaign's
	 * header also hold.
	 *
	 * @param json the document mapper
	 * @return the document, whose hosts are in ascending order
	 * @throws NullPointerException if {@code json} is null
	 */
	public ObjectNode toDocument(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		ObjectNode document = json.createObjectNode().put("format_version", VERSION);
		ArrayNode writeArray = document.putArray("writes");
		for (Path write : writes)
			writeArray.add(write.toString());
		ArrayNode hostArray = document.putArray("hosts");
		for (String host : new TreeSet<>(hosts))
			hostArray.add(host);
		return document;
	}

	/**
	 * Reads a file of extra permissions.
	 *
	 * @param json   the document mapper
	 * @param source the file
	 * @return the permissions
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file does not exist, cannot be read, is larger than {@value #MAXIMUM_BYTES}
	 *                              bytes, is not JSON, or is not a version {@value #VERSION} document with the fields
	 *                              above and absolute paths and host names
	 */
	public static ExtraPermissions read(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		JsonNode document;
		try
		{
			document = json.readTree(BoundedInput.readUtf8(source, MAXIMUM_BYTES, "extra permissions"));
		}
		catch (NoSuchFileException e)
		{
			throw new IOException("The extra permissions file " + source + " does not exist", e);
		}
		catch (JacksonException e)
		{
			throw new IOException("The extra permissions file " + source + " is not valid JSON", e);
		}
		return fromDocument(document, "The extra permissions file " + source);
	}

	/**
	 * Returns the permissions that a document holds.
	 *
	 * @param document the document, as {@link #toDocument} returns it
	 * @param origin   names the document in a failure message, such as "The extra permissions file /a/b.json"
	 * @return the permissions
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the document is not a version {@value #VERSION} object with the fields above
	 *                              and absolute paths and host names
	 */
	public static ExtraPermissions fromDocument(JsonNode document, String origin) throws IOException
	{
		requireThat(document, "document").isNotNull();
		requireThat(origin, "origin").isNotBlank();
		if (!document.isObject())
			throw new IOException(origin + " is not a JSON object");
		for (String field : document.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException(origin + " has an unknown field: " + field);
		}
		JsonNode version = document.path("format_version");
		if (!version.isInt() || version.intValue() != VERSION)
			throw new IOException(origin + " has an unsupported format_version: " + version);
		List<String> writeTexts = strings(document, "writes", origin);
		List<String> hostTexts = strings(document, "hosts", origin);
		try
		{
			List<Path> writes = new ArrayList<>();
			for (String write : writeTexts)
				writes.add(Path.of(write));
			return new ExtraPermissions(writes, new LinkedHashSet<>(hostTexts));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(origin + " is not valid: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads an optional array of text from a document.
	 *
	 * @param document the document
	 * @param field    the array's field
	 * @param origin   names the document in a failure message
	 * @return the text, in order, or an empty list if the document has no such field
	 * @throws IOException if the field is not an array of text
	 */
	private static List<String> strings(JsonNode document, String field, String origin) throws IOException
	{
		if (!document.has(field))
			return List.of();
		JsonNode array = document.get(field);
		if (!array.isArray())
			throw new IOException(origin + " does not hold an array in " + field);
		List<String> values = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException(origin + " has a non-text element in " + field);
			values.add(element.stringValue());
		}
		return values;
	}
}
