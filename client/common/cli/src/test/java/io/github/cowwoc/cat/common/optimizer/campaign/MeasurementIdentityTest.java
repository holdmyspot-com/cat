package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link MeasurementIdentity} and that every trial and result records one.
 */
public final class MeasurementIdentityTest
{
	/**
	 * Creates the test.
	 */
	public MeasurementIdentityTest()
	{
	}

	/**
	 * Returns an identity with the given values.
	 *
	 * @param entries alternating conditions and values
	 * @return the identity
	 */
	static MeasurementIdentity identity(Object... entries)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		for (int i = 0; i < entries.length; i += 2)
			values.put((MeasurementCondition) entries[i], (String) entries[i + 1]);
		return new MeasurementIdentity(values);
	}

	/**
	 * Verifies that the differences name each condition that both identities record with different values, and only
	 * those.
	 */
	@Test
	public void differencesNameEachDifferingCondition()
	{
		// Requirement: R-M1
		MeasurementIdentity first = identity(MeasurementCondition.MODEL, "model-a", MeasurementCondition.JUDGE, "j1",
			MeasurementCondition.HOST_TOOLS, "git-2.44");
		MeasurementIdentity second = identity(MeasurementCondition.MODEL, "model-b", MeasurementCondition.JUDGE, "j1",
			MeasurementCondition.REASONING, "high");

		requireThat(first.differences(second), "differences").containsExactly(List.of(MeasurementCondition.MODEL));
		requireThat(first.missing(second), "missing").
			containsExactly(List.of(MeasurementCondition.REASONING, MeasurementCondition.HOST_TOOLS));
		requireThat(first.changes(second), "changes").containsExactly(List.of(MeasurementCondition.MODEL,
			MeasurementCondition.REASONING, MeasurementCondition.HOST_TOOLS));
		requireThat(first.differences(first), "differences(first)").isEmpty();
	}

	/**
	 * Verifies that extending an identity records newly observed conditions and keeps recorded ones.
	 */
	@Test
	public void extendKeepsRecordedValues()
	{
		// Requirement: R-M1
		MeasurementIdentity recorded = identity(MeasurementCondition.MODEL, "model-a");
		MeasurementIdentity observed = identity(MeasurementCondition.MODEL, "model-b", MeasurementCondition.JUDGE,
			"j1");

		requireThat(recorded.extendWith(observed), "extended").isEqualTo(identity(MeasurementCondition.MODEL,
			"model-a", MeasurementCondition.JUDGE, "j1"));
		requireThat(recorded.replaceWith(observed), "replaced").isEqualTo(observed);
	}

	/**
	 * Verifies that replacing an identity keeps the conditions that the observation does not report.
	 */
	@Test
	public void replaceKeepsUnreportedValues()
	{
		// Requirement: R-M1
		MeasurementIdentity recorded = identity(MeasurementCondition.MODEL, "model-a", MeasurementCondition.CONTRACT,
			"c1");
		MeasurementIdentity observed = identity(MeasurementCondition.MODEL, "model-b");

		requireThat(recorded.replaceWith(observed), "replaced").isEqualTo(identity(MeasurementCondition.MODEL,
			"model-b", MeasurementCondition.CONTRACT, "c1"));
	}

	/**
	 * Verifies that a blank value is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void blankValueIsRejected()
	{
		// Requirement: R-M1
		identity(MeasurementCondition.MODEL, " ");
	}

	/**
	 * Verifies that an assigned trial records the campaign's measurement identity, and that a completed trial
	 * records the conditions that its run newly observed.
	 */
	@Test
	public void trialRecordsIdentity()
	{
		// Requirement: R-M1
		MeasurementIdentity partial = identity(MeasurementCondition.MODEL, "model-a");
		Campaign campaign = new CampaignBuilder().confirmed().identity(partial).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
			Purpose.DEVELOPMENT, 1, CampaignBuilder.INCUMBENT, "safety", null)));
		LogicalTrial trial = campaign.trials().getFirst();
		requireThat(trial.identity(), "assigned.identity()").isEqualTo(partial);

		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(trial.key()));
		MeasurementIdentity observed = identity(MeasurementCondition.MODEL, "model-a", MeasurementCondition.REASONING,
			"high");
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Complete(trial.key(), Verdict.PASS,
			observed));
		requireThat(campaign.identity(), "campaign.identity()").isEqualTo(observed);
	}

	/**
	 * Verifies that a certification attempt records the measurement identity it was committed under.
	 */
	@Test
	public void attemptRecordsIdentity()
	{
		// Requirement: R-M1
		Campaign campaign = new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Commit(
			new CapCalibration(ErrorBudget.certificationLevel(1), 262), 11));
		requireThat(campaign.attempt(1).identity(), "attempt.identity()").isEqualTo(campaign.identity());
	}
}
