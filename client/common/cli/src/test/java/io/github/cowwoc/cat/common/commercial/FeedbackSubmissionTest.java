package io.github.cowwoc.cat.common.commercial;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests explicit feedback preview, confirmation, cancellation, and delivery.
 */
public final class FeedbackSubmissionTest
{
	/**
	 * Creates the test fixture.
	 */
	public FeedbackSubmissionTest()
	{
	}

	/**
	 * Verifies history-derived reports retain only the client and harness identity required by the submission contract.
	 */
	@Test
	public void previewsAndSubmitsHistoryOnlyPayload()
	{
		FeedbackSubmission.Draft draft = new FeedbackSubmission.Draft("request", "response", "bounded history",
			new FeedbackSubmission.Client(FeedbackSubmission.Edition.COMMERCIAL, "1.0"),
			new FeedbackSubmission.Harness(FeedbackSubmission.HarnessName.CODEX, "1.0"), List.of(),
			List.of());
		FeedbackSubmission.Preview preview = FeedbackSubmission.preview(draft);

		requireThat(preview.disclosure(), "disclosure").contains("Your content will only be visible to CAT staff.");
		requireThat(preview.payload().client().edition(), "edition").isEqualTo(FeedbackSubmission.Edition.COMMERCIAL);
		requireThat(preview.payload().client().version(), "clientVersion").isEqualTo("1.0");
		requireThat(preview.payload().harness().name(), "harnessName").isEqualTo(FeedbackSubmission.HarnessName.CODEX);
		FeedbackSubmission.Receipt receipt = FeedbackSubmission.confirm(preview).submit((payload, idempotencyKey) ->
			new FeedbackSubmission.Receipt("feedback-1"));
		requireThat(receipt.identifier(), "receiptIdentifier").isEqualTo("feedback-1");
	}

	/**
	 * Verifies a canceled preview cannot be submitted.
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = ".*Canceled feedback cannot be submitted.*")
	public void doesNotSubmitCanceledPreview()
	{
		FeedbackSubmission.Preview preview = FeedbackSubmission.preview(new FeedbackSubmission.Draft("request",
			"response", null, new FeedbackSubmission.Client(FeedbackSubmission.Edition.COMMERCIAL, "1.0"),
			new FeedbackSubmission.Harness(FeedbackSubmission.HarnessName.CODEX, "1.0"), List.of(), List.of()));

		FeedbackSubmission.cancel(preview).submit((payload, idempotencyKey) ->
			new FeedbackSubmission.Receipt("unexpected"));
	}

	/**
	 * Verifies delivery failures remain explicit and retain the confirmed payload for retry or deletion.
	 */
	@Test
	public void retainsFailedSubmissionLocally()
	{
		FeedbackSubmission.Confirmed confirmed = FeedbackSubmission.confirm(FeedbackSubmission.preview(
			new FeedbackSubmission.Draft("request", "response", null,
				new FeedbackSubmission.Client(FeedbackSubmission.Edition.COMMERCIAL, "1.0"),
				new FeedbackSubmission.Harness(FeedbackSubmission.HarnessName.CODEX, "1.0"), List.of(), List.of())));

		FeedbackSubmission.Delivery result = confirmed.trySubmit((payload, idempotencyKey) ->
			{ throw new FeedbackSubmission.DeliveryException("service unavailable"); });

		requireThat(result.status(), "status").isEqualTo(FeedbackSubmission.DeliveryStatus.FAILED_RETAINED_LOCALLY);
		requireThat(result.confirmedPayload().isPresent(), "confirmedPayload").isTrue();
		requireThat(result.failureMessage().orElseThrow(), "failureMessage").isEqualTo("service unavailable");
	}
}
