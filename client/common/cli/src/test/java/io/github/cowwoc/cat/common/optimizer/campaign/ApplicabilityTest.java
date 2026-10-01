package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link Applicability}.
 */
public final class ApplicabilityTest
{
	/**
	 * Creates the test.
	 */
	public ApplicabilityTest()
	{
	}

	/**
	 * Returns one material change kind per row: the condition that records it.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] changeKinds()
	{
		return new Object[][]
			{
				// the contract
				{MeasurementCondition.CONTRACT},
				{MeasurementCondition.GENERATOR},
				// the judge
				{MeasurementCondition.JUDGE},
				// the runner model or reasoning profile
				{MeasurementCondition.MODEL},
				{MeasurementCondition.REASONING},
				// a provider, harness, source or host tool capable of affecting outcomes
				{MeasurementCondition.PROVIDER},
				{MeasurementCondition.HARNESS_VERSION},
				{MeasurementCondition.RUNNER_VERSION},
				{MeasurementCondition.CAT_VERSION},
				{MeasurementCondition.LOADER_CONFIGURATION},
				{MeasurementCondition.PROMPT_SOURCES},
				{MeasurementCondition.HOST_TOOLS},
				{MeasurementCondition.EXTERNAL_SOURCES}
			};
	}

	/**
	 * Returns a full identity in which one condition has another value.
	 *
	 * @param condition the condition to change
	 * @return the identity
	 */
	private static MeasurementIdentity changed(MeasurementCondition condition)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(CampaignBuilder.confirmedIdentity("model-a").values());
		values.put(condition, "changed");
		return new MeasurementIdentity(values);
	}

	/**
	 * Verifies that a result is current while nothing changed.
	 */
	@Test
	public void unchangedResultIsCurrent()
	{
		// Requirement: R-M2
		Sha256 snapshot = CampaignBuilder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, CampaignBuilder.OBSERVED, snapshot,
			CampaignBuilder.observed("model-a"));
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.CURRENT);
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isFalse();
		requireThat(applicability.changed(), "changed").isEmpty();
	}

	/**
	 * Verifies that each material condition change makes a result historical and names the condition.
	 *
	 * @param condition the condition that changes
	 */
	@Test(dataProvider = "changeKinds")
	public void conditionChangeMakesResultHistorical(MeasurementCondition condition)
	{
		// Requirement: R-M2
		Sha256 snapshot = CampaignBuilder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, CampaignBuilder.confirmedIdentity("model-a"),
			snapshot, changed(condition));
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(applicability.changed(), "changed").containsExactly(List.of(condition));
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isFalse();
	}

	/**
	 * Verifies that a change to the behavioural prompt content makes a result historical.
	 */
	@Test
	public void promptContentChangeMakesResultHistorical()
	{
		// Requirement: R-M2
		Applicability applicability = Applicability.of(CampaignBuilder.snapshot("candidate"),
			CampaignBuilder.OBSERVED, CampaignBuilder.snapshot("edited"), CampaignBuilder.OBSERVED);
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isTrue();
		requireThat(applicability.changed(), "changed").isEmpty();
	}

	/**
	 * Verifies that a condition that the current observation does not report is missing data, not a change, and
	 * that the result keeps its record.
	 */
	@Test
	public void missingConditionIsNotAChange()
	{
		// Requirement: R-M2
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(CampaignBuilder.OBSERVED.values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		Sha256 snapshot = CampaignBuilder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, CampaignBuilder.OBSERVED, snapshot,
			new MeasurementIdentity(values));
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.CURRENT);
	}

	/**
	 * Verifies that a historical certification attempt is kept, with its outcome, after a material change.
	 */
	@Test
	public void historicalAttemptIsKept()
	{
		// Requirement: R-M2
		Campaign campaign = new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).category("formatting", 100, 0).category("safety", 100, 0).
			outcome(Outcome.CERTIFIED).build();
		MeasurementIdentity observed = changed(MeasurementCondition.MODEL);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ChangeMeasurement(observed));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));

		CertificationAttempt attempt = campaign.attempt(1);
		requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
		requireThat(attempt.count("formatting").passes(), "passes").isEqualTo(100);
		Applicability applicability = Applicability.of(attempt.candidate().snapshot(), attempt.identity(),
			campaign.development().working().snapshot(), campaign.identity());
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(applicability.changed(), "changed").containsExactly(List.of(MeasurementCondition.MODEL));
	}
}
