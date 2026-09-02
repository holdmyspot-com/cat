package io.github.cowwoc.cat.common.commercial;

import io.github.cowwoc.cat.common.TemporaryDirectories;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the feedback preview and explicit submission command boundary.
 */
public final class FeedbackCommandTest
{
	/**
	 * Creates the test fixture.
	 */
	public FeedbackCommandTest()
	{
	}

	/**
	 * Verifies preview does not transmit and submit sends exactly the reviewed payload.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void submitsOnlyReviewedPayload() throws IOException
	{
		Path root = Files.createTempDirectory("cat-feedback-command-");
		Path draft = root.resolve("draft.json");
		Path preview = root.resolve("preview.json");
		Files.writeString(draft, """
			{
			  "schema_version":"cat-feedback-draft-v1",
			  "triggering_request":"request",
			  "failed_response":"response",
			  "retained_history":"history",
			  "client":{"edition":"commercial","version":"1.0"},
			  "harness":{"name":"codex","version":"1.0"},
			  "tool_events":[],
			  "prompt_files_explicitly_added_by_user":[]
			}
			""");
		JsonMapper json = new JsonMapper();
		StringBuilder previewReport = new StringBuilder();

		FeedbackCommand.preview(json, draft, preview, previewReport);

		requireThat(previewReport.toString(), "previewReport").contains("Your content will only be visible to CAT staff.");
		AtomicReference<String> transmitted = new AtomicReference<>();
		StringBuilder submitReport = new StringBuilder();
		FeedbackSubmission.Receipt receipt = FeedbackCommand.submit(json, preview, (payload, idempotencyKey) ->
		{
			transmitted.set(payload);
			return receipt();
		}, submitReport);

		requireThat(receipt.identifier(), "receiptIdentifier").isEqualTo("c842b79e-c73c-4f09-a8cd-f98e4afae221");
		requireThat(transmitted.get(), "transmittedPayload").contains("\"schema_version\":\"cat-feedback-submission-v1\"").
			contains("\"scope\":\"private-feedback-review\"").
			contains("\"triggering_request\":\"request\"");
		requireThat(submitReport.toString(), "submitReport").contains("status=submitted").contains(
			"c842b79e-c73c-4f09-a8cd-f98e4afae221");
	}

	/**
	 * Verifies a second delivery of one persisted preview reuses its original idempotency key.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reusesPreviewIdempotencyKey() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-feedback-command-"));
			Path draft = Files.writeString(root.resolve("draft.json"), """
				{
				  "schema_version":"cat-feedback-draft-v1",
				  "triggering_request":"request",
				  "failed_response":"response",
				  "retained_history":null,
				  "client":{"edition":"commercial","version":"1.0"},
				  "harness":{"name":"codex","version":"1.0"},
				  "tool_events":[],
				  "prompt_files_explicitly_added_by_user":[]
				}
				""");
			Path preview = root.resolve("preview.json");
			JsonMapper json = new JsonMapper();

			FeedbackCommand.preview(json, draft, preview, new StringBuilder());
			AtomicReference<UUID> firstKey = new AtomicReference<>();
			AtomicReference<String> firstPayload = new AtomicReference<>();
			FeedbackCommand.submit(json, preview, (payload, idempotencyKey) ->
			{
				firstKey.set(idempotencyKey);
				firstPayload.set(payload);
				return receipt();
			}, new StringBuilder());
			FeedbackCommand.submit(json, preview, (payload, idempotencyKey) ->
			{
				requireThat(idempotencyKey, "secondIdempotencyKey").isEqualTo(firstKey.get());
				requireThat(payload, "secondPayload").isEqualTo(firstPayload.get());
				return receipt();
			}, new StringBuilder());
		}
	}

	/**
	 * Creates one valid current feedback receipt fixture.
	 *
	 * @return the strict receipt JSON
	 */
	private static String receipt()
	{
		return "{\"schema_version\":\"feedback-receipt-v2\",\"feedback_id\":\"c842b79e-c73c-4f09-a8cd-" +
			"f98e4afae221\",\"received_at\":\"2026-09-02T16:00:00Z\",\"payload_sha256\":" +
			"\"d2a4f3a459c1c5c0f03661ec841b72ec7d4fb2cc8b370a4f7e454b1be35c2be0\",\"retention_until\":" +
			"\"2027-09-02T16:00:00Z\"}";
	}
}
