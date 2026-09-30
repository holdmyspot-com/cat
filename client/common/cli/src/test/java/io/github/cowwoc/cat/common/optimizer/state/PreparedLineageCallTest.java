package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import org.testng.annotations.Test;

/** Checks the semantic request admitted for a candidate generation call. */
public final class PreparedLineageCallTest
{
	/** Creates the test fixture. */
	public PreparedLineageCallTest()
	{
	}

	/** A candidate call consumes a generation request, never a trial request. */
	@Test
	public void candidateRequiresGenerationRequest()
	{
		EvidenceRef nativeRequest = new EvidenceRef("run-requests", "a".repeat(64));
		LineageKey lineage = new LineageKey("lineage", "contract");
		EvidenceRef generation = new EvidenceRef("candidate-generation-requests", "b".repeat(64));
		PreparedLineageCall accepted = new PreparedLineageCall(1, ProducerAttemptKind.CANDIDATE,
			"campaign", lineage, "call", generation, nativeRequest);
		requireThat(accepted.semanticRequestRef(), "generationRequest").isEqualTo(generation);
		try
		{
			new PreparedLineageCall(1, ProducerAttemptKind.CANDIDATE, "campaign", lineage, "call",
				new EvidenceRef("trial-run-requests", "c".repeat(64)), nativeRequest);
			throw new AssertionError("Trial request must not authorize candidate generation");
		}
		catch (IllegalArgumentException expected)
		{
			requireThat(expected.getMessage(), "semanticCollection").contains("semantic request collection");
		}
	}
}
