package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request for the policy that bounds the commands of the session that makes it, which the receiver that serves the
 * session's requests answers. A command that runs in the session's sandbox cannot read the policy itself, because the
 * harness process that holds it is not among the processes that the sandbox shows the command. A request server reads
 * the policy of the host session that it serves, and a nested session's runner states the policy that it applied to
 * the nested session's sandbox, so only the session's own receiver can say what the session may do.
 * <p>
 * The request travels as a request archive whose name starts with {@link #PREFIX}, in the same {@code requests}
 * directory as a launch, and is answered by a {@link WorktreeResponse} in the same {@code responses} directory, whose
 * result is the policies' document, which {@link #encode(JsonMapper, List)} writes and
 * {@link #decode(JsonMapper, String)} reads. The request holds no field, so a session can ask only for its own policy.
 */
public final class OwnerPolicyRequest
{
	/**
	 * The start of the name of an owner policy request's archive, before its identifier.
	 */
	public static final String PREFIX = "owner-policy-";
	private static final int FORMAT_VERSION = 1;
	private static final String VERSION_FIELD = "format_version";
	private static final String POLICIES_FIELD = "policies";
	private static final String SANDBOXED_FIELD = "sandboxed";
	private static final String WRITE_ROOTS_FIELD = "write_roots";
	private static final String WRITE_DENIALS_FIELD = "write_denials";
	private static final String READ_DENIALS_FIELD = "read_denials";
	private static final String READ_REALLOWANCES_FIELD = "read_reallowances";
	private static final String HOSTS_FIELD = "hosts";
	private static final String LOCAL_BINDING_FIELD = "allow_local_binding";
	private static final String PROTECTED_SOURCES_FIELD = "protected_sources";
	private static final String NO_HOSTS = "none";
	private static final String ALL_HOSTS = "all";
	private static final Set<String> FIELDS = Set.of(VERSION_FIELD);

	/**
	 * Creates a request.
	 */
	public OwnerPolicyRequest()
	{
	}

	/**
	 * Publishes the request into a requests directory under a new identifier.
	 *
	 * @param json     the document mapper
	 * @param requests the requests directory
	 * @return the request's identifier, which names its response directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive cannot be written
	 */
	public String publish(JsonMapper json, Path requests) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(requests, "requests").isNotNull();
		String id = PREFIX + UUID.randomUUID();
		ObjectNode manifest = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION);
		RequestArchive.packDocument(json, requests.resolve(id + RequestArchive.EXTENSION), manifest,
			RequestLimits.IN_NESTED_SESSION);
		return id;
	}

	/**
	 * Reads the request that an extracted archive holds.
	 *
	 * @param json   the document mapper
	 * @param inputs the private directory that holds the extracted archive
	 * @return the request
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive holds anything besides its document, or the document is not valid
	 *                              JSON, has another format version or holds another field
	 */
	public static OwnerPolicyRequest read(JsonMapper json, Path inputs) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(inputs, "inputs").isNotNull();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(inputs))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!name.equals(RequestArchive.MANIFEST))
				{
					throw new IOException("An owner policy request holds only " + RequestArchive.MANIFEST + ", not " +
						name);
				}
			}
		}
		Path file = inputs.resolve(RequestArchive.MANIFEST);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Request archive requires " + RequestArchive.MANIFEST);
		JsonNode value;
		try
		{
			value = json.readTree(Files.readAllBytes(file));
		}
		catch (JacksonException e)
		{
			throw new IOException("Request manifest is not valid JSON: " + e.getOriginalMessage(), e);
		}
		if (!value.isObject())
			throw new IOException("Request manifest must be a JSON object");
		for (String field : value.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("An owner policy request may not choose " + field);
		}
		JsonNode version = value.path(VERSION_FIELD);
		if (!version.isInt() || version.intValue() != FORMAT_VERSION)
			throw new IOException("An owner policy request's " + VERSION_FIELD + " must be " + FORMAT_VERSION);
		return new OwnerPolicyRequest();
	}

	/**
	 * Writes policies as the result of the answer to a request.
	 *
	 * @param json     the document mapper
	 * @param policies the policies
	 * @return the document
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String encode(JsonMapper json, List<ParentPolicy> policies)
	{
		requireThat(json, "json").isNotNull();
		requireThat(policies, "policies").isNotNull();
		ObjectNode document = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION);
		ArrayNode array = document.putArray(POLICIES_FIELD);
		for (ParentPolicy policy : policies)
		{
			ObjectNode node = array.addObject();
			node.put(SANDBOXED_FIELD, policy.sandboxed());
			paths(node.putArray(WRITE_ROOTS_FIELD), policy.writeRoots());
			paths(node.putArray(WRITE_DENIALS_FIELD), policy.writeDenials());
			paths(node.putArray(READ_DENIALS_FIELD), policy.readDenials());
			paths(node.putArray(READ_REALLOWANCES_FIELD), policy.readReallowances());
			ObjectNode hosts = node.putObject(HOSTS_FIELD);
			switch (policy.hosts())
			{
				case HostAccess.NoHosts _ -> hosts.put("kind", NO_HOSTS);
				case HostAccess.AllHosts _ -> hosts.put("kind", ALL_HOSTS);
				case HostAccess.ListedHosts listed ->
				{
					hosts.put("kind", "listed");
					ArrayNode patterns = hosts.putArray("patterns");
					for (String pattern : new TreeSet<>(listed.patterns()))
						patterns.add(pattern);
				}
			}
			node.put(LOCAL_BINDING_FIELD, policy.allowLocalBinding());
			paths(node.putArray(PROTECTED_SOURCES_FIELD), policy.protectedSources());
		}
		return document.toString();
	}

	/**
	 * Adds paths to an array.
	 *
	 * @param array the array
	 * @param paths the paths
	 */
	private static void paths(ArrayNode array, List<Path> paths)
	{
		for (Path path : paths)
			array.add(path.toString());
	}

	/**
	 * Reads the policies that {@link #encode(JsonMapper, List)} wrote.
	 *
	 * @param json     the document mapper
	 * @param document the document
	 * @return the policies
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the document is not valid JSON, has another format version, lacks a field, or holds
	 *                              a path that is not absolute
	 */
	public static List<ParentPolicy> decode(JsonMapper json, String document) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(document, "document").isNotNull();
		try
		{
			JsonNode value = json.readTree(document);
			JsonNode version = value.path(VERSION_FIELD);
			if (!version.isInt() || version.intValue() != FORMAT_VERSION)
				throw new IOException("The owner policy document's " + VERSION_FIELD + " must be " + FORMAT_VERSION);
			JsonNode array = value.path(POLICIES_FIELD);
			if (!array.isArray())
				throw new IOException("The owner policy document holds no " + POLICIES_FIELD);
			List<ParentPolicy> policies = new ArrayList<>();
			for (JsonNode node : array)
			{
				if (!node.path(SANDBOXED_FIELD).isBoolean() || !node.path(LOCAL_BINDING_FIELD).isBoolean())
					throw new IOException("An owner policy must state " + SANDBOXED_FIELD + " and " + LOCAL_BINDING_FIELD);
				policies.add(new ParentPolicy(node.path(SANDBOXED_FIELD).booleanValue(),
					paths(node, WRITE_ROOTS_FIELD), paths(node, WRITE_DENIALS_FIELD), paths(node, READ_DENIALS_FIELD),
					paths(node, READ_REALLOWANCES_FIELD), hosts(node.path(HOSTS_FIELD)),
					node.path(LOCAL_BINDING_FIELD).booleanValue(), paths(node, PROTECTED_SOURCES_FIELD)));
			}
			return List.copyOf(policies);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("The owner policy document is malformed: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads a policy's paths.
	 *
	 * @param node  the policy
	 * @param field the name of the paths' field
	 * @return the paths
	 * @throws IOException if the field is not an array of strings
	 */
	private static List<Path> paths(JsonNode node, String field) throws IOException
	{
		JsonNode array = node.path(field);
		if (!array.isArray())
			throw new IOException("An owner policy must list " + field);
		List<Path> paths = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException("An owner policy's " + field + " must hold only paths");
			paths.add(Path.of(element.asString()));
		}
		return paths;
	}

	/**
	 * Reads a policy's host access.
	 *
	 * @param node the access
	 * @return the access
	 * @throws IOException if the access is not one that {@link #encode(JsonMapper, List)} writes
	 */
	private static HostAccess hosts(JsonNode node) throws IOException
	{
		String kind = node.path("kind").asString("");
		return switch (kind)
		{
			case NO_HOSTS -> HostAccess.NONE;
			case ALL_HOSTS -> HostAccess.ALL;
			case "listed" ->
			{
				Set<String> patterns = new LinkedHashSet<>();
				for (JsonNode pattern : node.path("patterns"))
				{
					if (!pattern.isString())
						throw new IOException("An owner policy's host patterns must be text");
					patterns.add(pattern.asString());
				}
				yield HostAccess.listed(patterns);
			}
			default -> throw new IOException("An owner policy's hosts have an unknown kind: " + kind);
		};
	}
}
