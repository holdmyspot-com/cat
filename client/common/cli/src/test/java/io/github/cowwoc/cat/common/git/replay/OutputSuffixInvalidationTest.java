package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests unsealed output-suffix invalidation behavior.
 */
public final class OutputSuffixInvalidationTest
{
	/**
	 * Creates the test fixture.
	 */
	public OutputSuffixInvalidationTest()
	{
	}

	/**
	 * Verifies invalidating an output removes its dependent batch and source review while retaining the earlier witness.
	 *
	 * @throws IOException if output invalidation cannot complete
	 */
	@Test
	public void removesDependentSuffixDecisions() throws IOException
	{
		Batch batch = new Batch("batch", "source-2", "output-2", null, null);
		BatchManifest manifest = new BatchManifest(5, "base", "tree", List.of(), List.of(
			new Output("output-1", null, "feat: first"), new Output("output-2", null, "feat: second")), List.of(),
			List.of(batch), List.of(), List.of(new OutputWitness("output-1", "base-tree", "first-tree")), List.of(),
			List.of(new SourceReview("source-2", "whole", List.of("output-2"), List.of(), "counterfactual",
				"evidence")), List.of(), null);

		BatchManifest invalidated = new OutputSuffixInvalidation().invalidate(manifest, "output-2");

		requireThat(invalidated.batches(), "remainingBatches").isEmpty();
		requireThat(invalidated.sourceReviews(), "remainingReviews").isEmpty();
		requireThat(invalidated.outputWitnesses(), "remainingWitnesses").containsExactly(
			List.of(new OutputWitness("output-1", "base-tree", "first-tree")), "firstWitness");
	}
}
