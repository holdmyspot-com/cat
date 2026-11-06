package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests the approaches assigned by a round's durable reservations.
 */
public final class RoundTest
{
	/**
	 * The approach vocabulary includes combining compatible instructions and correcting their load triggers.
	 */
	// Requirement: R-D3
	@Test
	public void includesCombinationAndLoadTrigger()
	{
		List<String> names = java.util.Arrays.stream(PromptAuthorApproach.values()).map(Enum::name).toList();
		requireThat(names, "approaches").contains("COMBINE_INSTRUCTIONS");
		requireThat(names, "approaches").contains("FIX_LOAD_TRIGGER");
	}

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

	/**
	 * A sequential round offers its cell's next attempt only once the reserved attempt's edits have been received, so
	 * that one prompt author works on a cell at a time.
	 */
	// Requirement: R-D3
	@Test
	public void sequentialWaitsForReservedEdits()
	{
		Sha256 snapshot = Builder.snapshot("candidate-2");
		Round reserved = new Round(Map.of("safety", 1), false, Map.of(12, "safety"), List.of());
		requireThat(reserved.nextCell(), "whileReserved").isEqualTo(Optional.empty());

		PromptAuthorAttempt received = new PromptAuthorAttempt(12, "safety", snapshot, List.of(),
			Minimality.start(List.of(), snapshot));
		Round submitted = new Round(Map.of("safety", 1), false, Map.of(12, "safety"), List.of(received));
		requireThat(submitted.nextCell(), "afterEditsArrive").isEqualTo(Optional.of("safety"));
	}

	/**
	 * A weak round offers each addressed cell's slots until all of them are reserved, without waiting for the edits of
	 * the sessions already reserved, so that its attempts run at the same time.
	 */
	// Requirement: R-D3
	@Test
	public void weakReservesAllSlotsAtOnce()
	{
		Map<Integer, String> reservations = new LinkedHashMap<>();
		reservations.put(7, "safety");
		reservations.put(8, "safety");
		Round twoReserved = new Round(Map.of("safety", 0), true, reservations, List.of());
		requireThat(twoReserved.nextCell(), "twoReserved").isEqualTo(Optional.of("safety"));

		reservations.put(9, "safety");
		Round allReserved = new Round(Map.of("safety", 0), true, reservations, List.of());
		requireThat(allReserved.nextCell(), "allReserved").isEqualTo(Optional.empty());
	}

	/**
	 * A round offers the first addressed cell that still has an attempt slot, skipping a cell whose slots are used.
	 */
	// Requirement: R-D3
	@Test
	public void roundSkipsCellWithoutSlots()
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		attempts.put("formatting", Round.ATTEMPTS);
		attempts.put("safety", 0);
		requireThat(new Round(attempts).nextCell(), "next").isEqualTo(Optional.of("safety"));
		attempts.put("safety", Round.ATTEMPTS);
		requireThat(new Round(attempts).nextCell(), "exhausted").isEqualTo(Optional.empty());
	}
}
