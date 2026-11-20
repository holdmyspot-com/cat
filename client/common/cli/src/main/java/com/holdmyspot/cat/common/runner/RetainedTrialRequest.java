package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request to inspect or discard the retained slot of one original trial launch. The broker uses the authenticated
 * request route to select the requesting session's slots. The discard uses the identity observed by the broker to
 * guard against a change of slot occupancy between status and discard.
 *
 * @param operation the recovery operation
 * @param requestId the original launch's request identifier
 * @param identity  the slot that a status request observed, or empty when none was observed
 */
public record RetainedTrialRequest(Operation operation, UUID requestId, Optional<Identity> identity)
{
	/**
	 * The prefix of a retained trial request's archive and response directory.
	 */
	public static final String PREFIX = "retained-trial-";
	static final int FORMAT_VERSION = 1;
	static final String VERSION_FIELD = "format_version";
	static final String REQUEST_ID_FIELD = "request_id";
	static final String IDENTITY_FIELD = "identity";
	private static final String OPERATION_FIELD = "operation";
	private static final Set<String> FIELDS = Set.of(VERSION_FIELD, OPERATION_FIELD, REQUEST_ID_FIELD, IDENTITY_FIELD);

	/**
	 * Creates a request to inspect an original trial launch.
	 *
	 * @param requestId the original launch's request identifier
	 * @return the status request
	 * @throws NullPointerException if {@code requestId} is null
	 */
	public static RetainedTrialRequest status(UUID requestId)
	{
		return new RetainedTrialRequest(Operation.STATUS, requestId, Optional.empty());
	}

	/**
	 * Creates a request to discard the original trial launch's retained slot.
	 *
	 * @param requestId the original launch's request identifier
	 * @param identity  the slot that the broker observed, or empty when it returned no identity
	 * @return the discard request
	 * @throws NullPointerException if either argument is null
	 */
	public static RetainedTrialRequest discard(UUID requestId, Optional<Identity> identity)
	{
		return new RetainedTrialRequest(Operation.DISCARD, requestId, identity);
	}

	/**
	 * Reads a retained trial request from its extracted archive.
	 *
	 * @param json   the document mapper
	 * @param inputs the private directory holding the extracted archive
	 * @return the request
	 * @throws NullPointerException if either argument is null
	 * @throws IOException          if the request document cannot be read, is invalid, or has another archive entry
	 */
	public static RetainedTrialRequest read(JsonMapper json, Path inputs) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(inputs, "inputs").isNotNull();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(inputs))
		{
			for (Path entry : entries)
			{
				if (!entry.getFileName().toString().equals(RequestArchive.MANIFEST))
					throw new IOException("A retained trial request archive must contain only " + RequestArchive.MANIFEST);
			}
		}

		JsonNode value = readDocument(json, inputs.resolve(RequestArchive.MANIFEST), FIELDS);
		Operation operation = Operation.parse(value.path(OPERATION_FIELD));
		UUID requestId = readUuid(value.path(REQUEST_ID_FIELD), REQUEST_ID_FIELD);
		Optional<Identity> identity = Identity.readOptional(value);
		try
		{
			return new RetainedTrialRequest(operation, requestId, identity);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Checks that a status operation carries no observed identity.
	 *
	 * @param operation the recovery operation
	 * @param requestId the original launch's request identifier
	 * @param identity  the slot that a status request observed, or empty when none was observed
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a status request carries an identity
	 */
	public RetainedTrialRequest
	{
		requireThat(operation, "operation").isNotNull();
		requireThat(requestId, "requestId").isNotNull();
		requireThat(identity, "identity").isNotNull();
		if (operation == Operation.STATUS && identity.isPresent())
			throw new IllegalArgumentException("A retained trial status request cannot carry a slot identity");
	}

	/**
	 * Publishes the request into the authenticated requests directory. Republishing a discard uses a new envelope with
	 * the same original launch identifier and observed slot identity.
	 *
	 * @param json     the document mapper
	 * @param requests the requests directory
	 * @return the request envelope's identifier, which names its response directory
	 * @throws NullPointerException if either argument is null
	 * @throws IOException          if the archive cannot be written
	 */
	public String publish(JsonMapper json, Path requests) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(requests, "requests").isNotNull();

		String id = PREFIX + UUID.randomUUID();
		ObjectNode manifest = json.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION).
			put(OPERATION_FIELD, operation.name().toLowerCase(Locale.ROOT)).put(REQUEST_ID_FIELD, requestId.toString());
		identity.ifPresent(value -> manifest.set(IDENTITY_FIELD, value.document(json)));
		RequestArchive.packDocument(json, requests.resolve(id + RequestArchive.EXTENSION), manifest,
			RequestLimits.IN_NESTED_SESSION);
		return id;
	}

	/**
	 * Reads one bounded, versioned retained trial document.
	 *
	 * @param json   the document mapper
	 * @param file   the manifest or response file
	 * @param fields the allowed top-level fields
	 * @return the validated document object
	 * @throws IOException if the file cannot be read or the document structure or version is invalid
	 */
	static JsonNode readDocument(JsonMapper json, Path file, Set<String> fields) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The retained trial document must be a regular file");

		byte[] bytes = BoundedInput.readBytes(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), file.getFileName(),
			RequestArchive.MAXIMUM_MANIFEST_BYTES, "The retained trial document");

		JsonNode document;
		try
		{
			document = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(bytes);
		}
		catch (JacksonException e)
		{
			throw new IOException("The retained trial document is not valid JSON", e);
		}
		validateFields(document, fields);
		JsonNode version = document.path(VERSION_FIELD);
		if (!version.isInt() || version.intValue() != FORMAT_VERSION)
			throw new IOException("The retained trial document requires " + VERSION_FIELD + " " + FORMAT_VERSION);
		return document;
	}

	/**
	 * Rejects values other than objects containing the permitted fields.
	 *
	 * @param value  the document or nested identity
	 * @param fields the permitted fields
	 * @throws IOException if the value is not an object or has an unknown field
	 */
	static void validateFields(JsonNode value, Set<String> fields) throws IOException
	{
		if (!value.isObject())
			throw new IOException("The retained trial document or identity must be a JSON object");
		for (String field : value.propertyNames())
		{
			if (!fields.contains(field))
				throw new IOException("The retained trial document contains an unexpected field");
		}
	}

	/**
	 * Reads a UUID in its canonical lowercase form.
	 *
	 * @param value the JSON value of the field
	 * @param field the field whose UUID is read
	 * @return the UUID
	 * @throws IOException if the value is not a canonical UUID
	 */
	static UUID readUuid(JsonNode value, String field) throws IOException
	{
		String message = "The retained trial document requires a canonical UUID in " + field;
		if (!value.isString())
			throw new IOException(message);
		String text = value.asString();
		UUID id;
		try
		{
			id = UUID.fromString(text);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(message, e);
		}
		if (!id.toString().equals(text))
			throw new IOException(message);
		return id;
	}

	/**
	 * The recovery operation inside the request archive.
	 */
	public enum Operation
	{
		/**
		 * Inspect an original launch's ownership and retained slot.
		 */
		STATUS,
		/**
		 * Discard the original launch's retained slot.
		 */
		DISCARD;

		/**
		 * Reads the operation's lowercase wire label.
		 *
		 * @param value the operation field
		 * @return the operation
		 * @throws IOException if the field does not name a supported operation
		 */
		private static Operation parse(JsonNode value) throws IOException
		{
			if (value.isString())
			{
				for (Operation candidate : values())
				{
					if (candidate.name().toLowerCase(Locale.ROOT).equals(value.asString()))
						return candidate;
				}
			}
			throw new IOException("The retained trial request requires operation status or discard");
		}
	}
}
