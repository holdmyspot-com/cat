package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.BareValue;

import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the expected outcome classification of {@link BareValue}.
 */
public final class BareValueTest
{
	/**
	 * Creates the test.
	 */
	public BareValueTest()
	{
	}

	/**
	 * Returns bare expected outcomes, including dates, times and dotted names.
	 *
	 * @return the outcomes, each in an array
	 */
	@DataProvider
	public Object[][] bareValues()
	{
		return new Object[][]
			{
				{"00:00"},
				{"12:00 AM"},
				{"midnight"},
				{"42"},
				{"Paris"},
				{"3.5"},
				{"2025-03-04"},
				{"Ivy route"},
				{"  Ivy \t  route\n"},
				{"the Ivy route"},
				{"v1.2.3"}
			};
	}

	/**
	 * Returns expected outcomes outside the token or punctuation limits, or without a value.
	 *
	 * @return the outcomes, each in an array
	 */
	@DataProvider
	public Object[][] otherOutcomes()
	{
		return new Object[][]
			{
				{"It arrives at midnight."},
				{"Yes, because the file is missing"},
				{"00:00 the next day."},
				{"The reply lists three colors"},
				{"It arrives at midnight. It leaves at noon."},
				{"Is it midnight?"},
				{"Done!"},
				{"midnight; noon"},
				{"Paris."},
				{"Paris. Rome"},
				{"Paris.\tRome"},
				{""},
				{"   "}
			};
	}

	/**
	 * Verifies that a normalized outcome within the token and punctuation limits is a bare value.
	 *
	 * @param outcome an expected outcome
	 */
	@Test(dataProvider = "bareValues")
	public void recognizesBareValue(String outcome)
	{
		// Requirement: R-L14
		requireThat(BareValue.isBareValue(outcome), "isBareValue").isTrue();
	}

	/**
	 * Verifies that an outcome is classified outside bare values when it is blank or exceeds either limit.
	 *
	 * @param outcome an expected outcome
	 */
	@Test(dataProvider = "otherOutcomes")
	public void rejectsOtherOutcome(String outcome)
	{
		// Requirement: R-L14
		requireThat(BareValue.isBareValue(outcome), "isBareValue").isFalse();
	}

	/**
	 * Verifies that testing for all bare outcomes requires at least one outcome, and that any non-bare outcome makes
	 * the check false.
	 */
	@Test
	public void oneNonBareOutcomeAcceptsTest()
	{
		// Requirement: R-L14
		requireThat(BareValue.areAllBareValues(List.of("00:00", "12:00 AM", "midnight")), "allBare").isTrue();
		requireThat(BareValue.areAllBareValues(List.of("00:00", "It arrives at midnight.", "42")), "oneSentence").
			isFalse();
		requireThat(BareValue.areAllBareValues(List.of("00:00 the next day.")), "onlySentence").isFalse();
		requireThat(BareValue.areAllBareValues(List.of()), "none").isFalse();
	}
}
