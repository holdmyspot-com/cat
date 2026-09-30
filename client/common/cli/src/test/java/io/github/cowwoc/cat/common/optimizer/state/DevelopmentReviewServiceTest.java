package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Checks how answered review points advance without processing individual outcomes.
 */
public final class DevelopmentReviewServiceTest
{
	/**
	 * Creates the test instance.
	 */
	public DevelopmentReviewServiceTest()
	{
	}

	/**
	 * A response extends review from 200 to 250 outcomes, then the second response authorizes more evidence.
	 *
	 * @throws IOException if the in-memory response sequence is invalid
	 */
	@Test
	public void advancesAnsweredReviewPoints() throws IOException
	{
		DevelopmentReviewService.RetainedResponse first = response('a', 250);
		DevelopmentReviewService.RetainedResponse second = response('b', 275);
		Map<Integer, DevelopmentReviewService.RetainedResponse> firstAnswer = Map.of(200, first);
		DevelopmentReviewService.ReviewCheckpoint before = DevelopmentReviewService.resolveCheckpoint(249, 200,
			point -> Optional.ofNullable(firstAnswer.get(point)));
		requireThat(before.authorizationRef(), "authorizationBeforeCheckpoint").isEqualTo(
			Optional.of(first.reference()));
		DevelopmentReviewService.ReviewCheckpoint due = DevelopmentReviewService.resolveCheckpoint(250, 200,
			point -> Optional.ofNullable(firstAnswer.get(point)));
		requireThat(due.reviewPoint(), "nextReviewPoint").isEqualTo(250);
		requireThat(due.precedingResponseRef(), "precedingResponse").isEqualTo(Optional.of(first.reference()));
		requireThat(due.authorizationRef(), "pendingAuthorization").isEmpty();

		DevelopmentReviewService.ReviewCheckpoint authorized = DevelopmentReviewService.resolveCheckpoint(250, 200,
			point -> Optional.ofNullable(Map.of(200, first, 250, second).get(point)));
		requireThat(authorized.reviewPoint(), "authorizedReviewPoint").isEqualTo(250);
		requireThat(authorized.authorizationRef(), "currentAuthorization").isEqualTo(
			Optional.of(second.reference()));
		requireThat(authorized.ended(), "samplingEnded").isFalse();
	}

	/**
	 * A response that ends representative sampling cannot authorize another draw.
	 *
	 * @throws IOException if the in-memory response cannot be read
	 */
	@Test
	public void stopsAfterTerminalReviewChoice() throws IOException
	{
		DevelopmentReviewService.RetainedResponse stopped = new DevelopmentReviewService.RetainedResponse(
			new EvidenceRef("development-review-responses", "c".repeat(64)),
			new DevelopmentReviewResponse(DevelopmentReviewResponse.SCHEMA_VERSION, "campaign",
				new LineageKey("lineage", "contract"),
				new EvidenceRef("development-review-requests", "c".repeat(64)),
				DevelopmentReviewRequest.Choice.STOP, 0));
		DevelopmentReviewService.ReviewCheckpoint result = DevelopmentReviewService.resolveCheckpoint(200, 200,
			point -> Optional.of(stopped));
		requireThat(result.ended(), "samplingEnded").isTrue();
		requireThat(result.authorizationRef(), "authorizationAbsent").isEmpty();
	}

	/**
	 * Models a response already validated by the campaign store for the count-only policy check.
	 *
	 * @param identity distinct evidence identity character
	 * @param nextPoint next review checkpoint
	 * @return in-memory response consumed by the production checkpoint resolver
	 */
	private static DevelopmentReviewService.RetainedResponse response(char identity, int nextPoint)
	{
		EvidenceRef reference = new EvidenceRef("development-review-responses", String.valueOf(identity).repeat(64));
		DevelopmentReviewResponse response = new DevelopmentReviewResponse(DevelopmentReviewResponse.SCHEMA_VERSION,
			"campaign", new LineageKey("lineage", "contract"),
			new EvidenceRef("development-review-requests", String.valueOf(identity).repeat(64)),
			DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE, nextPoint);
		return new DevelopmentReviewService.RetainedResponse(reference, response);
	}
}
