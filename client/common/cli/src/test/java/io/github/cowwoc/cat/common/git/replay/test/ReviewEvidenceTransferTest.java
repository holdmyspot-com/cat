package io.github.cowwoc.cat.common.git.replay.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.git.replay.ReviewEvidenceTransfer;
import io.github.cowwoc.cat.common.git.replay.SourceSnapshot;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests immutable review-evidence transfer policy.
 */
public final class ReviewEvidenceTransferTest
{
	/**
	 * Creates the test fixture.
	 */
	public ReviewEvidenceTransferTest()
	{
	}

	/**
	 * Verifies matching source transitions translate both source and source-scoped evidence identifiers.
	 *
	 * @throws IOException if equivalent source transitions cannot be mapped
	 */
	@Test
	public void mapsMatchingSourceScopedEvidence() throws IOException
	{
		Map<String, String> sources = ReviewEvidenceTransfer.sourceTransitionMap(List.of(
			new SourceSnapshot("previous", "parent-tree", "result-tree")), List.of(
			new SourceSnapshot("replacement", "parent-tree", "result-tree")), Set.of());

		requireThat(ReviewEvidenceTransfer.mapSourceScopedId(sources, "previous:unit", "source review"),
			"mappedIdentifier").isEqualTo("replacement:unit");
	}

	/**
	 * Verifies a source outside the verified transition prefix cannot contribute reused evidence.
	 *
	 * @throws IOException if source mapping reports its required failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*not in the verified replacement prefix.*")
	public void rejectsSourceOutsideVerifiedPrefix() throws IOException
	{
		ReviewEvidenceTransfer.mapSourceScopedId(Map.of(), "previous:unit", "source review");
	}
}
