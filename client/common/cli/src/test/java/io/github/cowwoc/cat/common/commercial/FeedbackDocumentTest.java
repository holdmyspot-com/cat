package io.github.cowwoc.cat.common.commercial;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests strict local feedback drafts and exact preview documents.
 */
public final class FeedbackDocumentTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FeedbackDocumentTest()
	{
	}

	/**
	 * Closes the JSON scope after tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a draft preserves the reviewed client and harness identity in its encoded submission.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesAndReadsExactPreview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("feedback-document-"));
			Path draft = Files.writeString(root.resolve("draft.json"), """
				{
					"schema_version":"cat-feedback-draft-v1",
					"triggering_request":"Apply the rule",
					"failed_response":"I ignored it",
					"retained_history":"bounded context",
					"client":{"edition":"commercial","version":"1.0"},
					"harness":{"name":"codex","version":"1.0"},
				  "tool_events":["edited source"],
				  "prompt_files_explicitly_added_by_user":[{"path":"AGENTS.md","content":"Use clear prose."}]
				}
				""");
			FeedbackSubmission.Preview preview = FeedbackSubmission.preview(
				FeedbackDocument.readDraft(scope.json(), draft));
			Path previewPath = root.resolve("preview.json");

			FeedbackDocument.writePreview(scope.json(), previewPath, preview);
			FeedbackSubmission.Preview retained = FeedbackDocument.readPreview(scope.json(), previewPath);

			requireThat(retained.idempotencyKey(), "retainedIdempotencyKey").isEqualTo(preview.idempotencyKey());
			String idempotencyKey = scope.json().readTree(Files.readString(previewPath)).path("idempotency_key").asString();
			requireThat(idempotencyKey, "idempotencyKey").isEqualTo(UUID.fromString(idempotencyKey).toString());
			requireThat(retained.disclosure(), "disclosure").
				contains("Your content will only be visible to CAT staff.");
			requireThat(retained.payload().client().edition(), "clientEdition").isEqualTo(
				FeedbackSubmission.Edition.COMMERCIAL);
			requireThat(retained.payload().harness().name(), "harnessName").isEqualTo(
				FeedbackSubmission.HarnessName.CODEX);
			JsonNode encodedPayload = scope.json().readTree(FeedbackDocument.encodePayload(scope.json(),
				retained.payload())).path("payload");
			requireThat(encodedPayload.path("tool_events").get(0).asString(), "toolEvent").isEqualTo("edited source");
			requireThat(encodedPayload.path("prompt_files_explicitly_added_by_user").get(0).path("content").asString(),
				"promptFileContent").isEqualTo("Use clear prose.");
		}
	}

	/**
	 * Verifies an unknown draft field is rejected instead of entering the submission payload.
	 *
	 * @throws IOException for the expected schema failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Feedback draft has unsupported or missing fields.*")
	public void rejectsUnknownDraftField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("feedback-document-"));
			Path draft = Files.writeString(root.resolve("draft.json"), """
				{
					"schema_version":"cat-feedback-draft-v1",
					"triggering_request":"request",
					"failed_response":"response",
					"retained_history":null,
					"client":{"edition":"commercial","version":"1.0"},
					"harness":{"name":"codex","version":"1.0"},
				  "tool_events":[],
				  "prompt_files_explicitly_added_by_user":[],
				  "file_scan":"/private/project"
				}
				""");

			FeedbackDocument.readDraft(scope.json(), draft);
		}
	}

	/**
	 * Verifies the draft boundary rejects a harness outside the supported feedback contract.
	 *
	 * @throws IOException for the expected unsupported-harness failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*unsupported harness.*")
	public void rejectsUnsupportedHarness() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("feedback-document-"));
			Path draft = Files.writeString(root.resolve("draft.json"), """
				{
					"schema_version":"cat-feedback-draft-v1",
					"triggering_request":"request",
					"failed_response":"response",
					"retained_history":null,
					"client":{"edition":"commercial","version":"1.0"},
					"harness":{"name":"unsupported","version":"1.0"},
					"tool_events":[],
					"prompt_files_explicitly_added_by_user":[]
				}
				""");

			FeedbackDocument.readDraft(scope.json(), draft);
		}
	}

	/**
	 * Verifies a confirmed submission binds the reviewed payload to explicit private-feedback consent.
	 *
	 * @throws IOException if JSON encoding fails
	 */
	@Test
	public void encodesPayloadBoundConsent() throws IOException
	{
		FeedbackSubmission.Preview preview = FeedbackSubmission.preview(new FeedbackSubmission.Draft("request",
			"response", "retained context", new FeedbackSubmission.Client(FeedbackSubmission.Edition.COMMERCIAL, "1.0"),
			new FeedbackSubmission.Harness(FeedbackSubmission.HarnessName.CODEX, "1.0"), java.util.List.of(),
			java.util.List.of()));

		String encoded = FeedbackDocument.encodePayload(scope.json(), preview.payload());
		JsonNode document = scope.json().readTree(encoded);
		requireThat(document.path("schema_version").asString(), "schemaVersion").isEqualTo(
			"cat-feedback-submission-v1");
		requireThat(document.path("consent").path("schema_version").asString(), "consentSchemaVersion").isEqualTo(
			"cat-feedback-consent-v1");
		requireThat(document.path("consent").path("payload_sha256").asString(), "payloadHash").matches(
			"[0-9a-f]{64}");
		requireThat(document.path("payload").path("selected_response").asString(), "selectedResponse").isEqualTo(
			"response");
		requireThat(document.path("payload").has("failed_response"), "legacyFailedResponse").isFalse();
	}
}
