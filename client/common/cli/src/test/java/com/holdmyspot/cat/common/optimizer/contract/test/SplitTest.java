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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	/**
	 * Verifies that the split puts every boundary class with at least 2 new distinct outcomes into both parts, with
	 * part sizes that differ by at most one outcome, whatever the salt.
	 */
	@Test
	public void everyClassReachesBothParts()
	{
		// Requirement: R-L11
		List<TestCase> testCases = List.of(
			ContractFixtures.testCase("Tuesday 14:00", "same_day", Map.of("version", "1")),
			ContractFixtures.testCase("Tuesday 15:30", "same_day", Map.of("version", "2")),
			ContractFixtures.testCase("Tuesday 18:45", "same_day", Map.of("version", "3")),
			ContractFixtures.testCase("Tuesday 20:10", "same_day", Map.of("version", "4")),
			ContractFixtures.testCase("Wednesday 01:00", "crosses_midnight", Map.of("version", "5")),
			ContractFixtures.testCase("Wednesday 02:30", "crosses_midnight", Map.of("version", "6")),
			ContractFixtures.testCase("Thursday 03:15", "crosses_midnight", Map.of("version", "7")),
			ContractFixtures.testCase("Wednesday 00:00", "arrives_exactly_at_midnight", Map.of("version", "8")),
			ContractFixtures.testCase("Thursday 00:00", "arrives_exactly_at_midnight", Map.of("version", "9")));
		PromptTest test = test(List.of(), testCases);
		for (long salt = 0; salt < 200; ++salt)
		{
			Map<String, Map<Part, Set<String>>> outcomes = outcomesByClass(new Split(salt), test);
			for (Map.Entry<String, Map<Part, Set<String>>> entry : outcomes.entrySet())
			{
				int development = entry.getValue().get(Part.DEVELOPMENT).size();
				int heldOut = entry.getValue().get(Part.HELD_OUT).size();
				String name = entry.getKey() + "[" + salt + "]";
				requireThat(development, name + ".development").isPositive();
				requireThat(heldOut, name + ".heldOut").isPositive();
				requireThat(Math.abs(development - heldOut), name + ".difference").isLessThanOrEqualTo(1);
			}
		}
	}

	/**
	 * Verifies that a committed outcome counts for development in its boundary class, so a class with one committed
	 * outcome and one new outcome holds the new one in the held-out part, whatever the salt.
	 */
	@Test
	public void committedOutcomeCountsForItsClass()
	{
		// Requirement: R-L11
		TestCase committed = ContractFixtures.testCase("Wednesday 00:00", "arrives_exactly_at_midnight",
			Map.of("version", "1"));
		TestCase added = ContractFixtures.testCase("Thursday 00:00", "arrives_exactly_at_midnight",
			Map.of("version", "2"));
		PromptTest test = test(List.of(committed), List.of(added));
		for (long salt = 0; salt < 200; ++salt)
		{
			Split split = new Split(salt);
			requireThat(split.partOf(test, committed), "committed[" + salt + "]").isEqualTo(Part.DEVELOPMENT);
			requireThat(split.partOf(test, added), "added[" + salt + "]").isEqualTo(Part.HELD_OUT);
		}
	}

	/**
	 * Verifies that the parts depend only on the prompt test's test cases and the salt, not on the test cases' order,
	 * and that test cases whose outcomes differ only in spelling share a part.
	 */
	@Test
	public void partsIgnoreOrder()
	{
		// Requirement: R-L11
		List<TestCase> testCases = new ArrayList<>();
		for (int i = 0; i < 12; ++i)
		{
			String boundaryClass = List.of("same_day", "crosses_midnight", "leap_day").get(i % 3);
			testCases.add(ContractFixtures.testCase("Outcome " + i, boundaryClass, Map.of("version", "v" + i)));
		}
		TestCase respelled = ContractFixtures.testCase("  OUTCOME\t5 ", "leap_day",
			Map.of("version", "respelled"));
		testCases.add(respelled);
		List<TestCase> reversed = new ArrayList<>(testCases.reversed());
		PromptTest test = test(List.of(), testCases);
		PromptTest reversedTest = test(List.of(), reversed);
		for (long salt = 0; salt < 50; ++salt)
		{
			Split split = new Split(salt);
			for (int i = 0; i < testCases.size(); ++i)
			{
				TestCase testCase = testCases.get(i);
				requireThat(split.partOf(reversedTest, testCase), "reversed[" + salt + "][" + i + "]").
					isEqualTo(split.partOf(test, testCase));
			}
			requireThat(split.partOf(test, respelled), "respelled[" + salt + "]").
				isEqualTo(split.partOf(test, testCases.get(5)));
		}
	}

	/**
	 * Verifies that the outcomes of untagged test cases, which a values file of format version 1 holds, keep the part
	 * that the salted hash assigns them, so a campaign confirmed before boundary classes keeps its held-out part.
	 */
	@Test
	public void untaggedOutcomesKeepTheirHashPart()
	{
		// Requirement: R-L11
		List<TestCase> testCases = new ArrayList<>();
		for (int i = 0; i < 20; ++i)
			testCases.add(ContractFixtures.testCase("release " + i, Map.of("version", String.valueOf(i))));
		PromptTest test = test(List.of(), testCases);
		for (long salt = 0; salt < 20; ++salt)
		{
			Split split = new Split(salt);
			for (int i = 0; i < testCases.size(); ++i)
			{
				TestCase testCase = testCases.get(i);
				requireThat(split.partOf(test, testCase), "untagged[" + salt + "][" + i + "]").
					isEqualTo(split.partOf(testCase.expectedOutcome()));
			}
		}
	}

	/**
	 * Returns the distinct normalized outcomes of each boundary class in each part.
	 *
	 * @param split the split
	 * @param test  the prompt test
	 * @return the outcomes of each part, by class
	 */
	private static Map<String, Map<Part, Set<String>>> outcomesByClass(Split split, PromptTest test)
	{
		Map<String, Map<Part, Set<String>>> outcomes = new LinkedHashMap<>();
		for (Part part : Part.values())
		{
			for (TestCase testCase : split.testCases(test, part))
			{
				outcomes.computeIfAbsent(testCase.boundaryClass(), _ ->
				{
					Map<Part, Set<String>> byPart = new EnumMap<>(Part.class);
					for (Part each : Part.values())
						byPart.put(each, new HashSet<>());
					return byPart;
				}).get(part).add(Split.normalize(testCase.expectedOutcome()));
			}
		}
		return outcomes;
	}

	/**
	 * Returns a prompt test with one variable.
	 *
	 * @param committed the committed test cases
	 * @param added     the new test cases
	 * @return the prompt test
	 */
	private static PromptTest test(List<TestCase> committed, List<TestCase> added)
	{
		return new PromptTest("shipment", new RiskRank(1), List.of("r1"), List.of(),
			"The user asks when a shipment arrives.", List.of("When does shipment {{version}} arrive?"),
			Duration.ofMinutes(1), "The arrival is named.", List.of(),
			List.of(new VariableDeclaration("version", "The shipment.")), committed, added,
			List.of(ContractFixtures.judged("names_arrival", AssertionKind.EFFECT, "The arrival is named.", "r1")),
			List.of(), List.of());
	}
}
