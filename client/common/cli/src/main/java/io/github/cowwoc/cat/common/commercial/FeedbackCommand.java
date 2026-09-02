package io.github.cowwoc.cat.common.commercial;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates an exact private-feedback preview and submits it only through a separate explicit command.
 */
public final class FeedbackCommand
{
	private static final int MAXIMUM_ACCESS_TOKEN_BYTES = 16 * 1024;
	private static final int MAXIMUM_RECEIPT_BYTES = 64 * 1024;
	private static final String RECEIPT_SCHEMA = "feedback-receipt-v2";
	private static final Set<String> RECEIPT_FIELDS = Set.of("schema_version", "feedback_id", "received_at",
		"payload_sha256", "retention_until");

	/**
	 * Runs one feedback preview, submit, or cancel action.
	 *
	 * @param args the action-specific arguments
	 * @throws IOException if a local document or explicit delivery action fails
	 */
	public static void main(String[] args) throws IOException
	{
		Objects.requireNonNull(args, "args must not be null");
		if (args.length == 3 && args[0].equals("preview"))
		{
			try (ToolScope scope = new ToolScope())
			{
				preview(scope.json(), Path.of(args[1]), Path.of(args[2]), System.out);
			}
			return;
		}
		if (args.length == 4 && args[0].equals("submit"))
		{
			try (ToolScope scope = new ToolScope(); FeedbackHttpClient client = new FeedbackHttpClient(
				Duration.ofSeconds(10), Duration.ofSeconds(30), MAXIMUM_RECEIPT_BYTES))
			{
				String token = BoundedInput.readUtf8(Path.of(args[3]), MAXIMUM_ACCESS_TOKEN_BYTES,
					"CAT account access token").strip();
				URI endpoint = URI.create(args[2]);
				submit(scope.json(), Path.of(args[1]),
					(payload, idempotencyKey) -> client.submit(endpoint, payload, token, idempotencyKey), System.out);
			}
			return;
		}
		if (args.length == 2 && args[0].equals("cancel"))
		{
			cancel(Path.of(args[1]), System.out);
			return;
		}
		throw new IllegalArgumentException("Usage: feedback preview <draft.json> <preview.json> | " +
			"feedback submit <preview.json> <https-endpoint> <access-token-file> | " +
			"feedback cancel <preview.json>");
	}

	/**
	 * Creates a reviewable preview without invoking a transport.
	 *
	 * @param json the JSON codec
	 * @param draftPath the history-derived draft document
	 * @param previewPath the exact preview destination
	 * @param report the status destination
	 * @return the created preview
	 * @throws IOException if the draft or preview operation fails
	 */
	public static FeedbackSubmission.Preview preview(JsonMapper json, Path draftPath, Path previewPath,
		Appendable report) throws IOException
	{
		Objects.requireNonNull(report, "report must not be null");
		FeedbackSubmission.Preview preview = FeedbackSubmission.preview(FeedbackDocument.readDraft(json, draftPath));
		FeedbackDocument.writePreview(json, previewPath, preview);
		report.append("status=preview-ready\n");
		report.append("preview=").append(previewPath.toAbsolutePath().normalize().toString()).append('\n');
		report.append(preview.disclosure()).append('\n');
		report.append("Review the preview, remove any field you do not want to share, then explicitly submit or " +
			"cancel it.\n");
		return preview;
	}

	/**
	 * Submits exactly one previously written and reviewed preview.
	 *
	 * @param json the JSON codec
	 * @param previewPath the reviewed preview document
	 * @param transport the authenticated private endpoint
	 * @param report the status destination
	 * @return the backend receipt
	 * @throws IOException if the preview changed, delivery fails, or the receipt is invalid
	 */
	public static FeedbackSubmission.Receipt submit(JsonMapper json, Path previewPath, Transport transport,
		Appendable report) throws IOException
	{
		Objects.requireNonNull(transport, "transport must not be null");
		Objects.requireNonNull(report, "report must not be null");
		FeedbackSubmission.Preview preview = FeedbackDocument.readPreview(json, previewPath);
		FeedbackSubmission.Confirmed confirmed = FeedbackSubmission.confirm(preview);
		FeedbackDocument.writePreview(json, previewPath, confirmed.preview());
		FeedbackSubmission.Receipt receipt;
		try
		{
			receipt = confirmed.submit((payload, idempotencyKey) ->
			{
				try
				{
					String response = transport.submit(FeedbackDocument.encodePayload(json, confirmed), idempotencyKey);
					return readReceipt(json, response);
				}
				catch (IOException e)
				{
					throw new FeedbackSubmission.DeliveryException(e.getMessage());
				}
			});
		}
		catch (FeedbackSubmission.DeliveryException e)
		{
			throw new IOException("Feedback delivery failed; the confirmed preview remains local at " + previewPath +
				": " + e.getMessage(), e);
		}
		report.append("status=submitted\n");
		report.append("receipt=").append(receipt.identifier()).append('\n');
		return receipt;
	}

	/**
	 * Cancels and deletes one local preview without transmitting it.
	 *
	 * @param previewPath the preview to delete
	 * @param report the status destination
	 * @throws IOException if deletion or report writing fails
	 */
	public static void cancel(Path previewPath, Appendable report) throws IOException
	{
		Objects.requireNonNull(previewPath, "previewPath must not be null");
		Objects.requireNonNull(report, "report must not be null");
		Files.deleteIfExists(previewPath);
		report.append("status=canceled\n");
	}

	/**
	 * Parses one strict bounded backend receipt body.
	 *
	 * @param json the JSON codec
	 * @param response the bounded response body
	 * @return the typed receipt
	 * @throws IOException if the receipt is malformed or unsupported
	 */
	private static FeedbackSubmission.Receipt readReceipt(JsonMapper json, String response) throws IOException
	{
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strict.readTree(response);
			if (!(parsed instanceof ObjectNode document) ||
				!new HashSet<>(document.propertyNames()).equals(RECEIPT_FIELDS))
			{
				throw new IOException("Feedback receipt has unsupported or missing fields");
			}
			String schema = requiredText(document, "schema_version");
			if (!schema.equals(RECEIPT_SCHEMA))
				throw new IOException("Unsupported feedback receipt schema: " + schema);
			String feedbackId = requiredText(document, "feedback_id");
			UUID.fromString(feedbackId);
			Instant.parse(requiredText(document, "received_at"));
			String payloadHash = requiredText(document, "payload_sha256");
			if (!payloadHash.matches("[0-9a-f]{64}"))
				throw new IOException("Feedback receipt has invalid payload_sha256");
			Instant.parse(requiredText(document, "retention_until"));
			return new FeedbackSubmission.Receipt(feedbackId);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid feedback receipt: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads one required receipt text field.
	 *
	 * @param document the receipt object
	 * @param field the field name
	 * @return the field value
	 * @throws IOException if the field is invalid
	 */
	private static String requiredText(ObjectNode document, String field) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Feedback receipt has invalid " + field);
		return value.asString();
	}

	/**
	 * Submits one exact confirmed payload and its stable idempotency key, then returns the bounded backend receipt body.
	 */
	@FunctionalInterface
	public interface Transport
	{
		/**
		 * Sends the exact JSON payload.
		 *
		 * @param payload the confirmed JSON payload
		 * @param idempotencyKey the stable UUID for the confirmed preview
		 * @return the bounded receipt JSON
		 * @throws IOException if delivery fails
		 */
		String submit(String payload, UUID idempotencyKey) throws IOException;
	}

	/**
	 * Prevents construction of this static command owner.
	 */
	private FeedbackCommand()
	{
	}
}
