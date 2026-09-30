package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Checks evaluator attempt identity without creating a campaign or starting a call.
 */
public final class EvaluatorTransportAttemptTest
{
	/**
	 * Creates the test fixture.
	 */
	public EvaluatorTransportAttemptTest()
	{
	}

	/**
	 * An evaluator attempt accepts a semantic evaluator request and rejects a trial assignment in its place.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*identity.*")
	public void rejectsTrialAssignmentAsEvaluatorRequest()
	{
		String digest = "a".repeat(64);
		ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.EVALUATOR, "evaluate",
			new EvidenceRef("run-requests", digest), new EvidenceRef("call-slot-claims", digest),
			new EvidenceRef("prepared-lineage-calls", digest), Optional.empty());
		EvidenceRef requestRef = new EvidenceRef("evaluator-requests", digest);
		requireThat(new EvaluatorTransportAttempt(header, requestRef, 0).requestRef(), "acceptedRequest").
			isEqualTo(requestRef);
		new EvaluatorTransportAttempt(header, new EvidenceRef("trial-assignments", digest), 0);
	}
}
