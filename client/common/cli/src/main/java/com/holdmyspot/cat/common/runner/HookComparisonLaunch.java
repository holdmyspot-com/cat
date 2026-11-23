package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A comparison launch prepared by the owner request server and passed directly to its selected runner.
 *
 * @param project the authenticated owner's original project
 * @param inputs the broker's extracted copy of the comparison archive
 * @param nativeHome the owner-approved Codex configuration directory selected by the broker
 * @param executable the protected Codex executable selected by the broker
 * @param sandbox the protected sandbox executable selected by the broker
 * @param policy the policy authenticated by the broker
 */
public record HookComparisonLaunch(Path project, Path inputs, Path nativeHome, Path executable, Path sandbox,
	ParentPolicy policy)
{
	/**
	 * The internal runner option for this fixed operation.
	 */
	public static final String OPTION = "--compare-hooks";
	private static final int MAXIMUM_BYTES = 1_048_576;
	private static final Set<String> FIELDS = Set.of("format_version", "project", "inputs", "native_home",
		"executable", "sandbox", "sandboxed", "write_roots", "write_denials", "read_denials", "read_reallowances",
		"protected_sources", "host_access", "hosts", "allow_local_binding");

	/**
	 * Creates a launch for known broker-owned inputs.
	 *
	 * @param project the authenticated owner's absolute project
	 * @param inputs the absolute extracted inputs directory
	 * @param nativeHome the absolute native configuration directory
	 * @param executable the absolute native executable
	 * @param sandbox the absolute sandbox executable
	 * @param policy the authenticated owner policy
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if a path is relative
	 */
	public HookComparisonLaunch
	{
		if (!project.isAbsolute() || !inputs.isAbsolute() || !nativeHome.isAbsolute() || !executable.isAbsolute() ||
			!sandbox.isAbsolute())
			throw new IllegalArgumentException("A hook comparison launch requires absolute paths");
		Objects.requireNonNull(policy);
	}

	/**
	 * Sends the broker's authority over the runner's private standard input.
	 *
	 * @param json the document mapper
	 * @param stream the runner's input pipe, closed by the caller
	 * @throws IOException if the launch is too large or cannot be sent
	 */
	public void write(JsonMapper json, OutputStream stream) throws IOException
	{
		ObjectNode root = json.createObjectNode().put("format_version", 1).put("project", project.toString()).
			put("inputs", inputs.toString()).put("native_home", nativeHome.toString()).
			put("executable", executable.toString()).put("sandbox", sandbox.toString()).
			put("sandboxed", policy.sandboxed()).
			put("allow_local_binding", policy.allowLocalBinding());
		writePaths(root.putArray("write_roots"), policy.writeRoots());
		writePaths(root.putArray("write_denials"), policy.writeDenials());
		writePaths(root.putArray("read_denials"), policy.readDenials());
		writePaths(root.putArray("read_reallowances"), policy.readReallowances());
		writePaths(root.putArray("protected_sources"), policy.protectedSources());
		ArrayNode hosts = root.putArray("hosts");
		switch (policy.hosts())
		{
			case HostAccess.NoHosts _ -> root.put("host_access", "none");
			case HostAccess.AllHosts _ -> root.put("host_access", "all");
			case HostAccess.ListedHosts listed ->
			{
				root.put("host_access", "listed");
				new TreeSet<>(listed.patterns()).forEach(hosts::add);
			}
		}
		byte[] document = json.writeValueAsBytes(root);
		if (document.length > MAXIMUM_BYTES)
			throw new IOException("The hook comparison launch exceeds the input size limit");
		stream.write(document);
	}

	/**
	 * Reads and validates the broker's launch from the runner's standard input.
	 *
	 * @param json the document mapper
	 * @param stream the input pipe
	 * @return the launch
	 * @throws IOException if its version, fields, paths or permissions are invalid
	 */
	public static HookComparisonLaunch read(JsonMapper json, InputStream stream) throws IOException
	{
		try
		{
			JsonNode root = json.readTree(BoundedInput.readUtf8(stream, "broker standard input", MAXIMUM_BYTES,
				"Hook comparison launch"));
			JsonNode version = root.path("format_version");
			if (!root.isObject() || !Set.copyOf(root.propertyNames()).equals(FIELDS) || !version.isInt() ||
				version.intValue() != 1 ||
				!root.path("sandboxed").isBoolean() || !root.path("allow_local_binding").isBoolean())
			{
				throw new IOException("Invalid hook comparison launch");
			}
			ParentPolicy policy = new ParentPolicy(root.path("sandboxed").booleanValue(),
				paths(root.path("write_roots")), paths(root.path("write_denials")), paths(root.path("read_denials")),
				paths(root.path("read_reallowances")), hosts(root), root.path("allow_local_binding").booleanValue(),
				paths(root.path("protected_sources")));
			return new HookComparisonLaunch(path(root.path("project")), path(root.path("inputs")),
				path(root.path("native_home")), path(root.path("executable")), path(root.path("sandbox")), policy);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid hook comparison launch", e);
		}
	}

	/**
	 * Reads a required path without coercion.
	 *
	 * @param field the path value
	 * @return its path
	 * @throws IOException if it is not a string
	 */
	private static Path path(JsonNode field) throws IOException
	{
		if (!field.isString())
			throw new IOException("Expected a path in the hook comparison launch");
		return Path.of(field.asString());
	}

	/**
	 * Renders the paths of one policy field.
	 *
	 * @param field the destination array
	 * @param paths the paths to write
	 */
	private static void writePaths(ArrayNode field, List<Path> paths)
	{
		paths.forEach(path -> field.add(path.toString()));
	}

	/**
	 * Reads paths without treating absent or malformed fields as a permission grant.
	 *
	 * @param field the array of paths
	 * @return the paths
	 * @throws IOException if the array or an element is invalid
	 */
	private static List<Path> paths(JsonNode field) throws IOException
	{
		return strings(field).stream().map(Path::of).toList();
	}

	/**
	 * Reads the exact network mode and host patterns.
	 *
	 * @param root the launch document
	 * @return the host access
	 * @throws IOException if the network fields contradict each other or are invalid
	 */
	private static HostAccess hosts(JsonNode root) throws IOException
	{
		List<String> names = strings(root.path("hosts"));
		String mode = root.path("host_access").asString("");
		if (mode.equals("listed"))
			return HostAccess.listed(Set.copyOf(names));
		if (!names.isEmpty())
			throw new IOException("Unexpected host patterns in the hook comparison launch");
		return switch (mode)
		{
			case "none" -> HostAccess.NONE;
			case "all" -> HostAccess.ALL;
			default -> throw new IOException("Invalid network mode in the hook comparison launch");
		};
	}

	/**
	 * Reads a required array of strings without coercion.
	 *
	 * @param field the array
	 * @return its text values
	 * @throws IOException if the field is absent or invalid
	 */
	private static List<String> strings(JsonNode field) throws IOException
	{
		if (!field.isArray() || field.valueStream().anyMatch(value -> !value.isString()))
			throw new IOException("Invalid text array in the hook comparison launch");
		return field.valueStream().map(JsonNode::stringValue).toList();
	}
}
