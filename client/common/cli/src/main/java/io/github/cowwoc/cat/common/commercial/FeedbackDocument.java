package io.github.cowwoc.cat.common.commercial;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Reads strict local feedback drafts and writes the exact reviewable submission preview.
 */
public final class FeedbackDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 4 * 1024 * 1024;
	private static final String DRAFT_SCHEMA = "cat-feedback-draft-v1";
	private static final String PREVIEW_SCHEMA = "feedback-preview-v1";
	private static final String SUBMISSION_SCHEMA = "cat-feedback-submission-v1";
	private static final String CONSENT_SCHEMA = "cat-feedback-consent-v1";
	private static final String CONSENT_SCOPE = "private-feedback-review";
	private static final Set<String> DRAFT_FIELDS = Set.of("client", "harness", "schema_version", "triggering_request",
		"failed_response", "retained_history",
		"tool_events", "prompt_files_explicitly_added_by_user");
	private static final Set<String> PREVIEW_FIELDS = Set.of("schema_version", "confirmed_at", "disclosure",
		"idempotency_key", "payload");
	private static final Set<String> PAYLOAD_FIELDS = Set.of("client", "harness",
		"prompt_files_explicitly_added_by_user", "report_id", "retained_context_snapshot", "selected_response",
		"tool_events", "triggering_request");
	private static final Set<String> CLIENT_FIELDS = Set.of("edition", "version");
	private static final Set<String> HARNESS_FIELDS = Set.of("name", "version");

	/**
	 * Reads one history-derived feedback draft without inspecting another local file.
	 *
	 * @param json the JSON codec
	 * @param source the bounded draft document
	 * @return the typed feedback draft
	 * @throws IOException if the draft is malformed, unsupported, or exceeds its bound
	 */
	public static FeedbackSubmission.Draft readDraft(JsonMapper json, Path source) throws IOException
	{
		ObjectNode draft = readObject(json, source, "Feedback draft");
		verifyFields(draft, DRAFT_FIELDS, "Feedback draft", source);
		verifySchema(draft, DRAFT_SCHEMA, "Feedback draft", source);
		return new FeedbackSubmission.Draft(requiredText(draft, "triggering_request", source),
			requiredText(draft, "failed_response", source), optionalText(draft, "retained_history", source),
			client(draft, "client", source), harness(draft, "harness", source),
			textList(draft, "tool_events", source), promptFiles(draft, "prompt_files_explicitly_added_by_user", source));
	}

	/**
	 * Writes the exact preview payload in deterministic field order.
	 *
	 * @param json the JSON codec
	 * @param destination the preview destination
	 * @param preview the payload and disclosure shown to the user
	 * @throws IOException if the preview cannot be encoded or written atomically
	 */
	public static void writePreview(JsonMapper json, Path destination, FeedbackSubmission.Preview preview)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(preview, "preview must not be null");
		if (!preview.disclosure().equals(FeedbackSubmission.expectedDisclosure()))
			throw new IllegalArgumentException("preview uses an unsupported disclosure");
		ObjectNode document = json.createObjectNode();
		document.put("schema_version", PREVIEW_SCHEMA);
		if (preview.confirmedAt() == null)
			document.putNull("confirmed_at");
		else
			document.put("confirmed_at", preview.confirmedAt().toString());
		document.put("disclosure", preview.disclosure());
		document.put("idempotency_key", preview.idempotencyKey().toString());
		document.set("payload", payload(json, preview.payload()));
		try
		{
			RecordStore.writeText(destination,
				json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize feedback preview: " + destination, e);
		}
	}

	/**
	 * Reads an exact preview before confirmation or submission.
	 *
	 * @param json the JSON codec
	 * @param source the preview document
	 * @return the typed preview
	 * @throws IOException if the preview is malformed, changed, or exceeds its bound
	 */
	public static FeedbackSubmission.Preview readPreview(JsonMapper json, Path source) throws IOException
	{
		ObjectNode document = readObject(json, source, "Feedback preview");
		verifyFields(document, PREVIEW_FIELDS, "Feedback preview", source);
		verifySchema(document, PREVIEW_SCHEMA, "Feedback preview", source);
		String disclosure = requiredText(document, "disclosure", source);
		if (!disclosure.equals(FeedbackSubmission.expectedDisclosure()))
			throw new IOException("Feedback preview disclosure changed: " + source);
		JsonNode payloadNode = document.path("payload");
		if (!(payloadNode instanceof ObjectNode encodedPayload))
			throw new IOException("Feedback preview payload is not a JSON object: " + source);
		verifyFields(encodedPayload, PAYLOAD_FIELDS, "Feedback preview payload", source);
		List<FeedbackSubmission.PromptFile> promptFiles = promptFiles(encodedPayload,
			"prompt_files_explicitly_added_by_user", source);
		List<String> toolEvents = textList(encodedPayload, "tool_events", source);
		FeedbackSubmission.Payload payload = new FeedbackSubmission.Payload(
			reportId(requiredText(encodedPayload, "report_id", source), source),
			requiredText(encodedPayload, "triggering_request", source),
			requiredText(encodedPayload, "selected_response", source),
			nullWhenUnavailable(requiredText(encodedPayload, "retained_context_snapshot", source)),
			client(encodedPayload, "client", source), harness(encodedPayload, "harness", source), toolEvents,
			promptFiles);
		return new FeedbackSubmission.Preview(payload, disclosure,
			idempotencyKey(requiredText(document, "idempotency_key", source), source),
			optionalInstant(document, "confirmed_at", source));
	}

	/**
	 * Encodes the reviewed payload with its payload-bound private-feedback consent.
	 *
	 * @param json the JSON codec
	 * @param payload the confirmed payload
	 * @return the exact submission JSON
	 * @throws IOException if encoding fails
	 */
	public static String encodePayload(JsonMapper json, FeedbackSubmission.Payload payload) throws IOException
	{
		return encodePayload(json, payload, Instant.now());
	}

	/**
	 * Encodes one confirmed payload using its persisted confirmation time.
	 *
	 * @param json the JSON codec
	 * @param confirmed the confirmed feedback submission
	 * @return the exact submission JSON
	 * @throws IOException if encoding fails
	 */
	public static String encodePayload(JsonMapper json, FeedbackSubmission.Confirmed confirmed) throws IOException
	{
		Objects.requireNonNull(confirmed, "confirmed must not be null");
		FeedbackSubmission.Preview preview = confirmed.preview();
		return encodePayload(json, preview.payload(), preview.confirmedAt());
	}

	/**
	 * Encodes one payload and its immutable confirmation time.
	 *
	 * @param json the JSON codec
	 * @param payload the confirmed payload
	 * @param confirmedAt the persisted confirmation time
	 * @return the exact submission JSON
	 * @throws IOException if encoding fails
	 */
	private static String encodePayload(JsonMapper json, FeedbackSubmission.Payload payload, Instant confirmedAt)
		throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(payload, "payload must not be null");
		Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
		try
		{
			ObjectNode encodedPayload = payload(json, payload);
			String canonicalPayload = json.writeValueAsString(encodedPayload);
			ObjectNode submission = json.createObjectNode();
			ObjectNode consent = submission.putObject("consent");
			consent.put("confirmed_at", confirmedAt.toString());
			consent.put("disclosure_version", "1");
			consent.put("payload_sha256", sha256(canonicalPayload));
			consent.put("schema_version", CONSENT_SCHEMA);
			consent.put("scope", CONSENT_SCOPE);
			submission.set("payload", encodedPayload);
			submission.put("schema_version", SUBMISSION_SCHEMA);
			return json.writeValueAsString(submission);
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize confirmed feedback payload", e);
		}
	}

	/**
	 * Returns the lowercase SHA-256 fingerprint for one canonical UTF-8 payload.
	 *
	 * @param canonicalPayload the canonical JSON payload
	 * @return the payload fingerprint
	 */
	private static String sha256(String canonicalPayload)
	{
		try
		{
			byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(
				canonicalPayload.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(fingerprint);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("The Java runtime does not provide SHA-256", e);
		}
	}

	/**
	 * Reads one bounded strict JSON object.
	 *
	 * @param json the JSON codec
	 * @param source the document
	 * @param description the diagnostic description
	 * @return the root object
	 * @throws IOException if the document is malformed
	 */
	private static ObjectNode readObject(JsonMapper json, Path source, String description) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, description);
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strictJson.readTree(contents);
			if (parsed instanceof ObjectNode object)
				return object;
			throw new IOException(description + " is not a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid " + description.toLowerCase(Locale.ROOT) + " JSON at " + source + ": " +
				e.getMessage(), e);
		}
	}

	/**
	 * Encodes one exact payload.
	 *
	 * @param json the JSON codec
	 * @param payload the payload
	 * @return the encoded payload
	 */
	private static ObjectNode payload(JsonMapper json, FeedbackSubmission.Payload payload)
	{
		ObjectNode result = json.createObjectNode();
		ObjectNode client = result.putObject("client");
		client.put("edition", payload.client().edition().wireValue());
		client.put("version", payload.client().version());
		ObjectNode harness = result.putObject("harness");
		harness.put("name", payload.harness().name().wireValue());
		harness.put("version", payload.harness().version());
		ArrayNode promptFiles = result.putArray("prompt_files_explicitly_added_by_user");
		for (FeedbackSubmission.PromptFile promptFile : payload.promptFilesExplicitlyAddedByUser())
		{
			ObjectNode encodedPromptFile = promptFiles.addObject();
			encodedPromptFile.put("content", promptFile.content());
			encodedPromptFile.put("path", promptFile.path());
		}
		result.put("report_id", payload.reportId().toString());
		result.put("retained_context_snapshot", unavailableWhenNull(payload.retainedContextSnapshot()));
		result.put("selected_response", payload.selectedResponse());
		ArrayNode toolEvents = result.putArray("tool_events");
		for (String toolEvent : payload.toolEvents())
			toolEvents.add(toolEvent);
		result.put("triggering_request", payload.triggeringRequest());
		return result;
	}

	/**
	 * Maps absent retained content to the contract's literal unavailable marker.
	 *
	 * @param value the nullable retained content
	 * @return the content or {@code unavailable}
	 */
	private static String unavailableWhenNull(String value)
	{
		if (value == null)
			return "unavailable";
		return value;
	}

	/**
	 * Maps the contract's unavailable marker back to absent local retained content.
	 *
	 * @param value the required contract value
	 * @return {@code null} for unavailable, otherwise the retained content
	 */
	private static String nullWhenUnavailable(String value)
	{
		if (value.equals("unavailable"))
			return null;
		return value;
	}

	/**
	 * Verifies the exact field set.
	 *
	 * @param object the source object
	 * @param expected the complete field set
	 * @param description the diagnostic description
	 * @param source the source path
	 * @throws IOException if fields are missing or unsupported
	 */
	private static void verifyFields(ObjectNode object, Set<String> expected, String description, Path source)
		throws IOException
	{
		if (!new HashSet<>(object.propertyNames()).equals(expected))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Verifies one schema identifier.
	 *
	 * @param object the source object
	 * @param expected the supported schema
	 * @param description the diagnostic description
	 * @param source the source path
	 * @throws IOException if the schema is unsupported
	 */
	private static void verifySchema(ObjectNode object, String expected, String description, Path source)
		throws IOException
	{
		String actual = requiredText(object, "schema_version", source);
		if (!actual.equals(expected))
			throw new IOException("Unsupported " + description.toLowerCase(Locale.ROOT) + " schema " + actual + ": " +
				source);
	}

	/**
	 * Reads required nonblank text.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the text
	 * @throws IOException if the field is not nonblank text
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Feedback document has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads nullable text.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the source path
	 * @return the text or {@code null}
	 * @throws IOException if a non-null value is not text
	 */
	private static String optionalText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (value.isNull())
			return null;
		if (!value.isString())
			throw new IOException("Feedback document has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads one constrained client identity.
	 *
	 * @param object the containing document
	 * @param field the identity field name
	 * @param source the document path
	 * @return the supported client identity
	 * @throws IOException if the identity has an unsupported edition or invalid shape
	 */
	private static FeedbackSubmission.Client client(ObjectNode object, String field, Path source) throws IOException
	{
		ObjectNode client = requiredObject(object, field, source);
		verifyFields(client, CLIENT_FIELDS, "Feedback client", source);
		try
		{
			return new FeedbackSubmission.Client(FeedbackSubmission.Edition.fromWireValue(
				requiredText(client, "edition", source)), requiredText(client, "version", source));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Feedback document has unsupported client edition: " + source, e);
		}
	}

	/**
	 * Reads one constrained harness identity.
	 *
	 * @param object the containing document
	 * @param field the identity field name
	 * @param source the document path
	 * @return the supported harness identity
	 * @throws IOException if the identity has an unsupported harness or invalid shape
	 */
	private static FeedbackSubmission.Harness harness(ObjectNode object, String field, Path source) throws IOException
	{
		ObjectNode harness = requiredObject(object, field, source);
		verifyFields(harness, HARNESS_FIELDS, "Feedback harness", source);
		try
		{
			return new FeedbackSubmission.Harness(FeedbackSubmission.HarnessName.fromWireValue(
				requiredText(harness, "name", source)), requiredText(harness, "version", source));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Feedback document has unsupported harness: " + source, e);
		}
	}

	/**
	 * Reads one required object field.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the document path
	 * @return the object value
	 * @throws IOException if the field is not an object
	 */
	private static ObjectNode requiredObject(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (value instanceof ObjectNode result)
			return result;
		throw new IOException("Feedback document has invalid " + field + ": " + source);
	}

	/**
	 * Reads a required list of nonblank text values.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the document path
	 * @return the validated text list
	 * @throws IOException if the field has another shape or contains invalid values
	 */
	private static List<String> textList(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isArray())
			throw new IOException("Feedback document has invalid " + field + ": " + source);
		List<String> result = new ArrayList<>();
		for (JsonNode entry : value)
		{
			if (!entry.isString() || entry.asString().isBlank())
				throw new IOException("Feedback document has invalid " + field + " entry: " + source);
			result.add(entry.asString());
		}
		return result;
	}

	/**
	 * Reads user-selected project-relative prompt files.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the document path
	 * @return the validated prompt-file list
	 * @throws IOException if the field has another shape or contains an invalid file
	 */
	private static List<FeedbackSubmission.PromptFile> promptFiles(ObjectNode object, String field, Path source)
		throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isArray())
			throw new IOException("Feedback document has invalid " + field + ": " + source);
		List<FeedbackSubmission.PromptFile> result = new ArrayList<>();
		for (JsonNode entry : value)
		{
			if (!(entry instanceof ObjectNode promptFile) ||
				!new HashSet<>(promptFile.propertyNames()).equals(Set.of("content", "path")))
			{
				throw new IOException("Feedback document has invalid " + field + " entry: " + source);
			}
			try
			{
				result.add(new FeedbackSubmission.PromptFile(requiredText(promptFile, "path", source),
					requiredText(promptFile, "content", source)));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("Feedback document has invalid " + field + " entry: " + source, e);
			}
		}
		return result;
	}

	/**
	 * Parses the delivery key retained with one reviewable preview.
	 *
	 * @param value the exact key text
	 * @param source the preview path
	 * @return the validated UUID
	 * @throws IOException if the key is not a UUID
	 */
	private static UUID idempotencyKey(String value, Path source) throws IOException
	{
		try
		{
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Feedback preview has invalid idempotency_key: " + source, e);
		}
	}

	/**
	 * Reads a nullable RFC 3339 instant.
	 *
	 * @param object the source object
	 * @param field the field name
	 * @param source the document path
	 * @return the parsed instant, or {@code null}
	 * @throws IOException if a non-null value is not an instant
	 */
	private static Instant optionalInstant(ObjectNode object, String field, Path source) throws IOException
	{
		String value = optionalText(object, field, source);
		if (value == null)
			return null;
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException e)
		{
			throw new IOException("Feedback preview has invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Parses the client-generated report identifier retained in a preview.
	 *
	 * @param value the identifier text
	 * @param source the preview path
	 * @return the validated UUID
	 * @throws IOException if the identifier is invalid
	 */
	private static UUID reportId(String value, Path source) throws IOException
	{
		try
		{
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Feedback preview has invalid report_id: " + source, e);
		}
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private FeedbackDocument()
	{
	}
}
