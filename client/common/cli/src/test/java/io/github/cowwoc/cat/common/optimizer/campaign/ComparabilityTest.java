package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentityTest.identity;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
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
		Comparability comparability = Comparability.of(CampaignBuilder.OBSERVED, CampaignBuilder.observed("model-a"));
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
		MeasurementIdentity first = identity(MeasurementCondition.MODEL, "model-a", MeasurementCondition.JUDGE, "j1",
			MeasurementCondition.PROVIDER, "p1");
		MeasurementIdentity second = identity(MeasurementCondition.MODEL, "model-b", MeasurementCondition.JUDGE, "j2",
			MeasurementCondition.PROVIDER, "p1");
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
		MeasurementIdentity first = identity(MeasurementCondition.MODEL, "model-a", MeasurementCondition.JUDGE, "j1");
		MeasurementIdentity second = identity(MeasurementCondition.MODEL, "model-a");
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.INDETERMINATE);
		requireThat(comparability.isComparable(), "isComparable").isFalse();
		requireThat(comparability.missing(), "missing").containsExactly(List.of(MeasurementCondition.JUDGE));
	}

	/**
	 * Verifies that a known difference decides comparability even when other data is missing, and that both lists
	 * are reported.
	 */
	@Test
	public void differenceOutranksMissingData()
	{
		// Requirement: R-M4
		MeasurementIdentity first = identity(MeasurementCondition.MODEL, "model-a", MeasurementCondition.JUDGE, "j1");
		MeasurementIdentity second = identity(MeasurementCondition.MODEL, "model-b");
		Comparability comparability = Comparability.of(first, second);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.NOT_COMPARABLE);
		requireThat(comparability.differing(), "differing").containsExactly(List.of(MeasurementCondition.MODEL));
		requireThat(comparability.missing(), "missing").containsExactly(List.of(MeasurementCondition.JUDGE));
	}
}
