package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.RiskRank;
import com.holdmyspot.cat.common.optimizer.contract.Split;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests {@link Split}.
 */
public final class SplitTest
{
	/**
	 * The number of distinct outcomes whose parts are counted.
	 */
	private static final int OUTCOMES = 10_000;

	/**
	 * Creates the test fixture.
	 */
	public SplitTest()
	{
	}

	/**
	 * Verifies that an outcome's part depends only on the salt and the outcome: the same salt always gives the same
	 * part, and another salt reassigns some outcomes.
	 */
	@Test
	public void partIsStableUnderSalt()
	{
		// Requirement: R-C16
		Split split = new Split(42);
		Split again = new Split(42);
		Split other = new Split(43);
		int moved = 0;
		for (int i = 0; i < 200; ++i)
		{
			String outcome = "release " + i;
			requireThat(again.partOf(outcome), "again.partOf(" + i + ")").isEqualTo(split.partOf(outcome));
			if (other.partOf(outcome) != split.partOf(outcome))
				++moved;
		}
		requireThat(moved, "moved").isGreaterThan(0);
	}

	/**
	 * Verifies that each part receives about half of many distinct outcomes.
	 */
	@Test
	public void eachPartHoldsAboutHalf()
	{
		// Requirement: R-C16
		Split split = new Split(-7);
		int development = 0;
		for (int i = 0; i < OUTCOMES; ++i)
		{
			if (split.partOf("2025-03-" + i) == Part.DEVELOPMENT)
				++development;
		}
		// A fair coin's count lies within 4 standard deviations (4 * 50 = 200) of 5000 with probability above 0.9999
		requireThat(development, "development").isBetween(OUTCOMES / 2 - 200, OUTCOMES / 2 + 200);
	}

	/**
	 * Verifies that outcomes that differ only in Unicode composition, surrounding or repeated whitespace, or case
	 * normalize to the same text and so share a part.
	 */
	@Test
	public void normalizesSpelling()
	{
		// Requirement: R-C16
		String composed = "Café opened";
		requireThat(Split.normalize("  Café\t\n OPENED "), "normalize").isEqualTo(Split.normalize(composed));
		requireThat(Split.normalize(composed), "normalized").isEqualTo("café opened");
		requireThat(Split.normalize("a  b"), "unicodeSpace").isEqualTo("a b");
		Split split = new Split(5);
		for (int i = 0; i < 50; ++i)
		{
			requireThat(split.partOf("  Release\t" + i + " "), "partOf(" + i + ")").
				isEqualTo(split.partOf("release " + i));
		}
	}

	/**
	 * Verifies that committed test cases, and new test cases that share a committed test case's expected outcome in
	 * another spelling, belong to the development part even where the salted hash assigns their outcome to the
	 * held-out part, while other new test cases keep the part of their outcome.
	 */
	@Test
	public void committedOutcomesStayInDevelopment()
	{
		// Requirement: R-C16
		Split split = new Split(11);
		List<String> heldOut = new ArrayList<>();
		for (int i = 0; heldOut.size() < 2; ++i)
		{
			String outcome = "release " + i;
			if (split.partOf(outcome) == Part.HELD_OUT)
				heldOut.add(outcome);
		}
		TestCase committed = ContractFixtures.testCase(heldOut.get(0), Map.of("version", "1"));
		TestCase sharing = ContractFixtures.testCase("  RELEASE\t" + heldOut.get(0).substring("release ".length()),
			Map.of("version", "one"));
		TestCase other = ContractFixtures.testCase(heldOut.get(1), Map.of("version", "2"));
		PromptTest test = new PromptTest("release", new RiskRank(1), List.of("r1"), List.of(),
			"The user asks for a release.", List.of("Release version {{version}}."), Duration.ofMinutes(1),
			"The release is named.", List.of(), List.of(new VariableDeclaration("version", "The version to release.")),
			List.of(committed), List.of(sharing, other),
			List.of(ContractFixtures.judged("names_release", AssertionKind.EFFECT, "The release is named.", "r1")),
			List.of(), List.of());

		requireThat(split.partOf(test, committed), "committed").isEqualTo(Part.DEVELOPMENT);
		requireThat(split.partOf(test, sharing), "sharing").isEqualTo(Part.DEVELOPMENT);
		requireThat(split.partOf(test, other), "other").isEqualTo(Part.HELD_OUT);
		requireThat(split.testCases(test, Part.DEVELOPMENT), "development").isEqualTo(List.of(committed, sharing));
		requireThat(split.testCases(test, Part.HELD_OUT), "heldOut").isEqualTo(List.of(other));
		requireThat(split.outcomeCount(test, Part.DEVELOPMENT), "developmentOutcomes").isEqualTo(1);
		requireThat(split.outcomeCount(test, Part.HELD_OUT), "heldOutOutcomes").isEqualTo(1);
	}
}
