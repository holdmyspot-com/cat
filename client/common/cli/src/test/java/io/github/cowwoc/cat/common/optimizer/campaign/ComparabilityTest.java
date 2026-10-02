package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentityTest.identity;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
		Comparability comparability = Comparability.of(CampaignBuilder.confirmedIdentity("model-a"),
			CampaignBuilder.confirmedIdentity("model-a"));
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
		MeasurementIdentity first = CampaignBuilder.confirmedIdentity("model-a");
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
		MeasurementIdentity first = CampaignBuilder.confirmedIdentity("model-a");
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
		MeasurementIdentity first = CampaignBuilder.confirmedIdentity("model-a");
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
	 * Verifies that a required condition absent from both observations makes their comparison unavailable.
	 */
	@Test
	public void absentOnBothIsIndeterminate()
	{
		// Requirement: R-M4
		Map<MeasurementCondition, String> values = new EnumMap<>(CampaignBuilder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		MeasurementIdentity partial = new MeasurementIdentity(values);
		Comparability comparability = Comparability.of(partial, partial);
		requireThat(comparability.status(), "status").isEqualTo(Comparability.Status.INDETERMINATE);
		requireThat(comparability.isComparable(), "isComparable").isFalse();
		requireThat(comparability.missing(), "missing").containsExactly(List.of(MeasurementCondition.HOST_TOOLS));
	}

	/**
	 * Verifies that complete cell observations supply required conditions without a campaign-wide value.
	 */
	@Test
	public void cellValuesSupplyRequiredConditions()
	{
		// Requirement: R-M4, R-E17
		Map<MeasurementCondition, String> values = new EnumMap<>(CampaignBuilder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.MODEL);
		MeasurementIdentity identity = new MeasurementIdentity(values,
			Map.of("formatting", Map.of(MeasurementCondition.MODEL, "observed")));
		requireThat(Comparability.of(identity, identity).status(), "status").
			isEqualTo(Comparability.Status.COMPARABLE);
	}
}
