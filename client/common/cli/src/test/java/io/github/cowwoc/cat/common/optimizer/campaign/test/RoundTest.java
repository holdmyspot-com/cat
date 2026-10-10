package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorApproach;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
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

		requireThat(round.approach(7, 0), "firstA").isEqualTo(PromptAuthorApproach.CORRECT_INSTRUCTION);
		requireThat(round.approach(8, 0), "firstB").isEqualTo(PromptAuthorApproach.CORRECT_INSTRUCTION);
		requireThat(round.approach(9, 0), "secondA").isEqualTo(PromptAuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(round.approach(10, 0), "secondB").isEqualTo(PromptAuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(round.approach(11, 0), "thirdA").isEqualTo(PromptAuthorApproach.ADD_GUIDANCE);
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
		requireThat(restored.approach(13, 0), "second").isEqualTo(PromptAuthorApproach.REWRITE_INSTRUCTIONS);
		requireThat(restored.approach(12, 0), "first").isEqualTo(PromptAuthorApproach.CORRECT_INSTRUCTION);
	}

	/**
	 * A cell's attempts continue the approach list where its earlier rounds stopped, and wrap to its start after the
	 * last approach.
	 */
	// Requirement: R-D3
	@Test
	public void approachesContinueAcrossRounds()
	{
		Map<Integer, String> reservations = new LinkedHashMap<>();
		reservations.put(20, "safety");
		reservations.put(21, "safety");
		Round round = new Round(Map.of("safety", 0), false, reservations, List.of());
		PromptAuthorApproach[] approaches = PromptAuthorApproach.values();

		requireThat(round.approach(20, 2), "thirdOverall").isEqualTo(approaches[2]);
		requireThat(round.approach(21, 2), "fourthOverall").isEqualTo(approaches[3]);
		requireThat(round.approach(20, approaches.length - 1), "last").isEqualTo(approaches[approaches.length - 1]);
		requireThat(round.approach(21, approaches.length - 1), "wrapped").isEqualTo(approaches[0]);
	}

	/**
	 * A round rejects an attempt number absent from its reservations.
	 */
	// Requirement: R-D3
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void unknownReservationHasNoApproach()
	{
		new Round(Map.of("safety", 0)).approach(1, 0);
	}
}
