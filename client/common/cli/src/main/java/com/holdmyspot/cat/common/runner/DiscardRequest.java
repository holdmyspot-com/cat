package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request to discard the interrupted run whose retained slot records a harness session, which a request server or a
 * run's runner carries out among the slots of the sessions that it started. Its document names the harness session and
 * nothing else, so it can only discard a run that the requester's own server retains.
 * <p>
 * The request travels as a request archive whose name starts with {@link #PREFIX}, in the same {@code requests}
 * directory as a launch, and is answered by a {@link WorktreeResponse} in the same {@code responses} directory, whose
 * result is {@link #DISCARDED} or {@link #ABSENT}.
 *
 * @param sessionId the harness session that the interrupted run records
 */
public record DiscardRequest(String sessionId)
{
	/**
	 * The start of the name of a discard request's archive, before its identifier.
	 */
	public static final String PREFIX = "discard-";
	/**
	 * The result of a request whose run was discarded.
	 */
	public static final String DISCARDED = "discarded";
	/**
	 * The result of a request that named a session that no retained run records.
	 */
	public static final String ABSENT = "absent";
	private static final int FORMAT_VERSION = 1;
	private static final String VERSION_FIELD = "format_version";
	private static final String SESSION_FIELD = "session_id";
	private static final Set<String> FIELDS = Set.of(VERSION_FIELD, SESSION_FIELD);

	/**
	 * Creates a request.
	 *
	 * @param sessionId the harness session that the interrupted run records
	 * @throws NullPointerException     if {@code sessionId} is null
	 * @throws IllegalArgumentException if {@code sessionId} is not shaped like a harness session identifier
	 */
	public DiscardRequest
	{
		requireThat(sessionId, "sessionId").isNotNull();
		if (!NestedSessionManager.HARNESS_SESSION_ID.matcher(sessionId).matches())
			throw new IllegalArgumentException("Not a harness session identifier: " + sessionId);
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
		ObjectNode manifest = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION).put(SESSION_FIELD, sessionId);
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
	 *                              JSON, has another format version, holds another field, or names no harness session
	 */
	public static DiscardRequest read(JsonMapper json, Path inputs) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(inputs, "inputs").isNotNull();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(inputs))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!name.equals(RequestArchive.MANIFEST))
					throw new IOException("A discard request holds only " + RequestArchive.MANIFEST + ", not " + name);
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
				throw new IOException("A discard request may not choose " + field);
		}
		JsonNode version = value.path(VERSION_FIELD);
		if (!version.isInt() || version.intValue() != FORMAT_VERSION)
			throw new IOException("A discard request's " + VERSION_FIELD + " must be " + FORMAT_VERSION);
		JsonNode session = value.path(SESSION_FIELD);
		if (!session.isString() || !NestedSessionManager.HARNESS_SESSION_ID.matcher(session.asString()).matches())
			throw new IOException("A discard request requires the identifier of a harness session");
		return new DiscardRequest(session.asString());
	}
}
