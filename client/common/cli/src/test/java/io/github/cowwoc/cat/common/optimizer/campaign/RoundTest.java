package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the approaches assigned by a round's durable reservations.
 */
public final class RoundTest
{
	/**
	 * Each cell receives its own approach sequence, independent of attempt numbers and other cells' reservations.
	 */
	// Requirement: R-D3
	@Test
	public void approachesFollowReservationsPerCell()
	{
		Map<Integer, String> reservations = new LinkedHashMap<>();
		reservations.put(7, "safety@a");
		reservations.put(8, "safety@b");
		reservations.put(9, "safety@a");
		reservations.put(10, "safety@b");
		reservations.put(11, "safety@a");
		Round round = new Round(Map.of("safety@a", 0, "safety@b", 0), true, reservations, List.of());

		requireThat(round.approach(7), "firstA").isEqualTo(AuthorApproach.CORRECT_INSTRUCTION);
		requireThat(round.approach(8), "firstB").isEqualTo(AuthorApproach.CORRECT_INSTRUCTION);
		requireThat(round.approach(9), "secondA").isEqualTo(AuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(round.approach(10), "secondB").isEqualTo(AuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(round.approach(11), "thirdA").isEqualTo(AuthorApproach.ADD_GUIDANCE);
	}

	/**
	 * A sequential reservation retains the rewrite approach after completed counts change and state is restored.
	 */
	// Requirement: R-D3
	@Test
	public void sequentialApproachUsesDurablePosition()
	{
		Map<Integer, String> reservations = new LinkedHashMap<>();
		reservations.put(12, "safety");
		reservations.put(13, "safety");
		Round round = new Round(Map.of("safety", 1), false, reservations, List.of());
		Round restored = new Round(round.attempts(), round.weak(), round.reservations(), round.validationAttempts());
		requireThat(restored.approach(13), "second").isEqualTo(AuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(restored.approach(12), "first").isEqualTo(AuthorApproach.CORRECT_INSTRUCTION);
	}

	/**
	 * A round rejects an attempt number absent from its reservations.
	 */
	// Requirement: R-D3
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void unknownReservationHasNoApproach()
	{
		new Round(Map.of("safety", 0)).approach(1);
	}
}
