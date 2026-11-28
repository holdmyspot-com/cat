package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request to say whether the session that makes it runs its commands in a sandbox, which a prompt optimizer needs
 * before it admits a trial and which the session's commands cannot tell from inside it. The receiver that serves the
 * session's requests answers from its own reading: a request server from the session's policy and its harness's
 * record of the session, and a nested session's runner from the policy that it applied to the session.
 * <p>
 * The request travels as a request archive whose name starts with {@link #PREFIX}, in the same {@code requests}
 * directory as a launch, and is answered by a {@link WorktreeResponse} in the same {@code responses} directory: done
 * when the session has a sandbox, and refused, with the reason that names the cause and the fix, when it has none.
 * Its document holds only its format version, so it chooses nothing.
 */
public final class SandboxCheckRequest
{
	/**
	 * The start of the name of a sandbox check request's archive, before its identifier.
	 */
	public static final String PREFIX = "sandbox-check-";
	private static final int FORMAT_VERSION = 1;
	private static final String VERSION_FIELD = "format_version";

	/**
	 * Prevents construction.
	 */
	private SandboxCheckRequest()
	{
	}

	/**
	 * Publishes a request into a requests directory under a new identifier.
	 *
	 * @param json     the document mapper
	 * @param requests the requests directory
	 * @return the request's identifier, which names its response directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive cannot be written
	 */
	public static String publish(JsonMapper json, Path requests) throws IOException
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
	 * Requires an extracted archive to hold a sandbox check request.
	 *
	 * @param json   the document mapper
	 * @param inputs the private directory that holds the extracted archive
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the archive holds anything besides its document, or the document is not valid
	 *                              JSON, has another format version or holds another field
	 */
	public static void read(JsonMapper json, Path inputs) throws IOException
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
					throw new IOException("A sandbox check request holds only " + RequestArchive.MANIFEST + ", not " +
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
			if (!field.equals(VERSION_FIELD))
				throw new IOException("A sandbox check request may not choose " + field);
		}
		JsonNode version = value.path(VERSION_FIELD);
		if (!version.isInt() || version.intValue() != FORMAT_VERSION)
			throw new IOException("A sandbox check request's " + VERSION_FIELD + " must be " + FORMAT_VERSION);
	}
}
