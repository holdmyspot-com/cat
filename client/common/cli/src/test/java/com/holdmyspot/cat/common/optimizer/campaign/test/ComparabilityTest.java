package com.holdmyspot.cat.common.optimizer.campaign.test;

import static com.holdmyspot.cat.common.optimizer.campaign.test.MeasurementIdentityTest.identity;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Comparability;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link Comparability}.
 */
public final class ComparabilityTest
{
	/**
	 * Creates the test.
	 */
	public ComparabilityTest()
	{
	}

	/**
	 * Verifies that exactly equal conditions are comparable.
	 */
	@Test
	public void equalConditionsAreComparable()
	{
		// Requirement: R-M4
		Comparability comparability = Comparability.of(Builder.confirmedIdentity("model-a"),
			Builder.confirmedIdentity("model-a"));
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.COMPARABLE);
		requireThat(comparability.isComparable(), "isComparable").isTrue();
		requireThat(comparability.differing(), "differing").isEmpty();
		requireThat(comparability.missing(), "missing").isEmpty();
	}

	/**
	 * Verifies that each differing condition is named and makes the observations not comparable.
	 */
	@Test
	public void differingConditionsAreNamed()
	{
		// Requirement: R-M4
		MeasurementIdentity first = Builder.confirmedIdentity("model-a");
		MeasurementIdentity second = first.replaceWith(identity(MeasurementCondition.MODEL, "model-b",
			MeasurementCondition.JUDGE, "j2"));
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.NOT_COMPARABLE);
		requireThat(comparability.isComparable(), "isComparable").isFalse();
		requireThat(comparability.differing(), "differing").
			containsExactly(List.of(MeasurementCondition.JUDGE, MeasurementCondition.MODEL));
	}

	/**
	 * Verifies that a condition that only one side records makes comparability indeterminate.
	 */
	@Test
	public void missingConditionIsIndeterminate()
	{
		// Requirement: R-M4
		MeasurementIdentity first = Builder.confirmedIdentity("model-a");
		Map<MeasurementCondition, String> values = new EnumMap<>(first.values());
		values.remove(MeasurementCondition.JUDGE);
		MeasurementIdentity second = new MeasurementIdentity(values);
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.INDETERMINATE);
		requireThat(comparability.isComparable(), "isComparable").isFalse();
		requireThat(comparability.missing(), "missing").containsExactly(List.of(MeasurementCondition.JUDGE));
	}

	/**
	 * Verifies that missing data makes comparison indeterminate while each known difference is still named.
	 */
	@Test
	public void missingDataKeepsDifferences()
	{
		// Requirement: R-M4
		MeasurementIdentity first = Builder.confirmedIdentity("model-a");
		Map<MeasurementCondition, String> values = new EnumMap<>(first.values());
		values.remove(MeasurementCondition.JUDGE);
		values.put(MeasurementCondition.MODEL, "model-b");
		MeasurementIdentity second = new MeasurementIdentity(values);
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.INDETERMINATE);
		requireThat(comparability.differing(), "differing").containsExactly(List.of(MeasurementCondition.MODEL));
		requireThat(comparability.missing(), "missing").containsExactly(List.of(MeasurementCondition.JUDGE));
	}

	/**
	 * Verifies that unobserved conditions absent from both observations do not prevent comparison.
	 */
	@Test
	public void unobservedConditionsOnBothAreComparable()
	{
		// Requirement: R-M4
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		values.remove(MeasurementCondition.EXTERNAL_SOURCES);
		MeasurementIdentity partial = new MeasurementIdentity(values);
		Comparability comparability = Comparability.of(partial, partial);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.COMPARABLE);
		requireThat(comparability.isComparable(), "isComparable").isTrue();
		requireThat(comparability.missing(), "missing").isEmpty();
	}

	/**
	 * Verifies that complete cell observations supply required conditions without a campaign-wide value.
	 */
	@Test
	public void cellValuesSupplyRequiredConditions()
	{
		// Requirement: R-M4, R-E17
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.MODEL);
		MeasurementIdentity identity = new MeasurementIdentity(values,
			Map.of("formatting", Map.of(MeasurementCondition.MODEL, "observed")));
		requireThat(Comparability.of(identity, identity).status(), "status").
			isEqualTo(Comparability.Status.COMPARABLE);
	}
	/**
	 * Verifies that a missing observed model identifies the side, condition and cell in words.
	 */
	@Test
	public void missingObservedModelNamesCellAndSide()
	{
		// Requirement: R-M4, R-E17
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.MODEL);
		MeasurementIdentity first = new MeasurementIdentity(values,
			Map.of("formatting", Map.of(MeasurementCondition.MODEL, "model-a")));
		MeasurementIdentity second = new MeasurementIdentity(values);
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.INDETERMINATE);
		requireThat(comparability.unavailable(), "unavailable").isEqualTo(
			"The incumbent baseline does not record the trial model observed in cell formatting " +
			"for the working candidate (model-a).");
	}

	/**
	 * Verifies that differing judge versions name both values in words.
	 */
	@Test
	public void differingJudgeNamesValues()
	{
		// Requirement: R-M4
		MeasurementIdentity first = Builder.confirmedIdentity("model-a").
			replaceWith(identity(MeasurementCondition.JUDGE, "1"));
		MeasurementIdentity second = first.replaceWith(identity(MeasurementCondition.JUDGE, "2"));
		requireThat(Comparability.of(first, second).unavailable(), "unavailable").isEqualTo(
			"The judge version differs: working candidate 1; incumbent baseline 2.");
	}

	/**
	 * Verifies that observed host tools require an observation from both sides.
	 */
	@Test
	public void observedHostToolsRequireBothSides()
	{
		// Requirement: R-M4
		MeasurementIdentity first = Builder.confirmedIdentity("model-a");
		Map<MeasurementCondition, String> values = new EnumMap<>(first.values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		Comparability comparability = Comparability.of(first, new MeasurementIdentity(values));
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.INDETERMINATE);
		requireThat(comparability.missing(), "missing").containsExactly(List.of(MeasurementCondition.HOST_TOOLS));
		requireThat(comparability.unavailable(), "unavailable").contains("host tools");
	}
	/**
	 * Verifies that comparison of a cell uses that cell's observations independently of other cells.
	 */
	@Test
	public void cellComparisonUsesMatchingCell()
	{
		// Requirement: R-M4, R-E17
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.MODEL);
		MeasurementIdentity first = new MeasurementIdentity(values, Map.of(
			"formatting", Map.of(MeasurementCondition.MODEL, "model-a"),
			"reasoning", Map.of(MeasurementCondition.MODEL, "model-b")));
		MeasurementIdentity second = new MeasurementIdentity(values, Map.of(
			"formatting", Map.of(MeasurementCondition.MODEL, "model-a"),
			"reasoning", Map.of(MeasurementCondition.MODEL, "model-c")));
		requireThat(Comparability.of(first, second, "formatting").status(), "formattingStatus").
			isEqualTo(Comparability.Status.COMPARABLE);
		Comparability differing = Comparability.of(first, second, "reasoning");
		requireThat(differing.status(), "reasoningStatus").isEqualTo(Comparability.Status.NOT_COMPARABLE);
		requireThat(differing.unavailable(), "unavailable").isEqualTo(
			"The trial model differs in cell reasoning: working candidate model-b; incumbent baseline model-c.");
	}

	/**
	 * Verifies that observations with differing CAT versions remain comparable, campaign-wide and for a cell.
	 */
	@Test
	public void differingCatVersionsAreComparable()
	{
		// Requirement: R-M4
		MeasurementIdentity first = Builder.confirmedIdentity("model-a");
		MeasurementIdentity second = first.replaceWith(identity(MeasurementCondition.CAT_VERSION, "2.0",
			MeasurementCondition.RUNNER_VERSION, "2.0"));
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.COMPARABLE);
		requireThat(comparability.differing(), "differing").isEmpty();
		comparability = Comparability.of(first, second, "formatting");
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.COMPARABLE);
		requireThat(comparability.differing(), "differing").isEmpty();
	}
}
