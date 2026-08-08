package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests origin-candidate selection from immutable topic-replay history facts.
 */
public final class OriginCandidatesTest
{
	/**
	 * Creates the test fixture.
	 */
	public OriginCandidatesTest()
	{
	}

	/**
	 * Verifies a reviewed changed path identifies only earlier same-path source commits.
	 */
	@Test
	public void selectsEarlierSamePathSources()
	{
		List<History.InventoryEntry> inventory = List.of(
			new History.InventoryEntry("source-1:1", "source-1", "rules/example.md", "000000",
				"0000000000000000000000000000000000000000", "100644", "one"),
			new History.InventoryEntry("source-2:1", "source-2", "rules/example.md", "100644", "one",
				"100644", "two"),
			new History.InventoryEntry("source-3:1", "source-3", "rules/other.md", "000000",
				"0000000000000000000000000000000000000000", "100644", "three"));

		List<OriginCandidates.Candidate> candidates = new OriginCandidates().select(
			List.of("source-1", "source-2", "source-3"), inventory, Set.of("source-2", "source-3"));

		requireThat(candidates, "candidates").isEqualTo(List.of(new OriginCandidates.Candidate(
			"source-2:1", "rules/example.md", List.of("source-1"))));
	}
}
