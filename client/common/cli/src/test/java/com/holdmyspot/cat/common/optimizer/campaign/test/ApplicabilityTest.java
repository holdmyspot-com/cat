package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameter;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
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
				{MeasurementCondition.CONTRACT},
				{MeasurementCondition.GENERATOR},
				{MeasurementCondition.JUDGE},
				{MeasurementCondition.MODEL},
				{MeasurementCondition.REASONING},
				{MeasurementCondition.PROVIDER},
				{MeasurementCondition.HARNESS_VERSION},
				{MeasurementCondition.SESSION_ENVIRONMENT_VERSION},
				{MeasurementCondition.LOADER_CONFIGURATION},
				{MeasurementCondition.PROMPT_SOURCES}
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
		values.putAll(Builder.confirmedIdentity("model-a").values());
		values.put(condition, "changed");
		return new MeasurementIdentity(values);
	}

	/**
	 * Verifies that a result is current while no recorded condition or prompt content changed.
	 */
	@Test
	public void unchangedResultIsCurrent()
	{
		// Requirement: R-M2
		Sha256 snapshot = Builder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, Builder.OBSERVED, snapshot,
			Builder.observed("model-a"));
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
		Sha256 snapshot = Builder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, Builder.confirmedIdentity("model-a"),
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
		Applicability applicability = Applicability.of(Builder.snapshot("candidate"),
			Builder.OBSERVED, Builder.snapshot("edited"), Builder.OBSERVED);
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isTrue();
		requireThat(applicability.changed(), "changed").isEmpty();
	}

	/**
	 * Verifies that a condition missing from the current observation does not make a result historical, and is named
	 * as unchecked.
	 */
	@Test
	public void missingConditionIsNotAChange()
	{
		// Requirement: R-M2, R-M6
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(Builder.OBSERVED.values());
		values.remove(MeasurementCondition.PROMPT_SOURCES);
		Sha256 snapshot = Builder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, Builder.OBSERVED, snapshot,
			new MeasurementIdentity(values));
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.CURRENT);
		requireThat(applicability.changed(), "changed").isEmpty();
		requireThat(applicability.unchecked(), "unchecked").
			containsExactly(List.of(MeasurementCondition.PROMPT_SOURCES));
	}

	/**
	 * Verifies that a historical certification attempt is kept, with its outcome, after a material change.
	 */
	@Test
	public void historicalAttemptIsKept()
	{
		// Requirement: R-M2
		Campaign campaign = new Builder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).cell("formatting", 100, 0).cell("safety", 100, 0).
			outcome(Outcome.CERTIFIED).build();
		MeasurementIdentity observed = changed(MeasurementCondition.MODEL);
		campaign = Transitions.apply(campaign, new CampaignEvent.ChangeMeasurement(observed));
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));

		CertificationAttempt attempt = campaign.attempt(1);
		requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
		requireThat(attempt.count("formatting").passes(), "passes").isEqualTo(100);
		Applicability applicability = Applicability.of(attempt.candidate().snapshot(), attempt.identity(),
			campaign.development().working().snapshot(), campaign.identity());
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(applicability.changed(), "changed").containsExactly(List.of(MeasurementCondition.MODEL));
	}

	/**
	 * Returns one information-only condition per row.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] informationOnly()
	{
		return new Object[][]
			{
				{MeasurementCondition.RUNNER_VERSION},
				{MeasurementCondition.CAT_VERSION}
			};
	}

	/**
	 * Verifies that a change to the runner or CAT version alone keeps a result current.
	 *
	 * @param condition the condition that changes
	 */
	@Test(dataProvider = "informationOnly")
	public void versionChangeAloneKeepsResultCurrent(MeasurementCondition condition)
	{
		// Requirement: R-M2
		Sha256 snapshot = Builder.snapshot("candidate");
		Applicability applicability = Applicability.of(snapshot, Builder.confirmedIdentity("model-a"),
			snapshot, changed(condition));
		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.CURRENT);
		requireThat(applicability.changed(), "changed").isEmpty();
	}

	/**
	 * Returns the current judge version per row, with the applicability of a certification whose recorded judge
	 * version is {@code judge-1}.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] judgeVersions()
	{
		return new Object[][]
			{
				{"judge-1", Applicability.Status.CURRENT},
				{"judge-2", Applicability.Status.HISTORICAL}
			};
	}

	/**
	 * Verifies that a certification stays current while its judge version is unchanged and is historical under a new
	 * judge version.
	 *
	 * @param currentJudge the current judge version
	 * @param expected the certification's applicability
	 */
	@Test(dataProvider = "judgeVersions")
	public void judgeVersionDecidesApplicability(String currentJudge, Applicability.Status expected)
	{
		// Requirement: R-M2
		Campaign campaign = new Builder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).cell("formatting", 100, 0).outcome(Outcome.CERTIFIED).build();
		CertificationAttempt attempt = campaign.attempt(1);
		requireThat(attempt.identity().values().get(MeasurementCondition.JUDGE), "recordedJudge").
			isEqualTo("judge-1");
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(attempt.identity().values());
		values.put(MeasurementCondition.JUDGE, currentJudge);
		Applicability applicability = Applicability.of(attempt, attempt.candidate().snapshot(),
			new MeasurementIdentity(values, attempt.identity().cells()),
			CertificationParameters.current(1, Contract.DEFAULT_MINIMUM_PASS_RATE));
		requireThat(applicability.status(), "status").isEqualTo(expected);
	}

	/**
	 * Returns a current minimum pass rate per row, with whether a certification recorded at
	 * {@link Contract#DEFAULT_MINIMUM_PASS_RATE} stays current under it.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] currentPassRates()
	{
		BigDecimal recorded = Contract.DEFAULT_MINIMUM_PASS_RATE;
		return new Object[][]
			{
				// The recorded threshold is above the current one
				{recorded.subtract(new BigDecimal("0.01")), true},
				{recorded, true},
				// The recorded threshold is below the current one
				{recorded.add(new BigDecimal("0.01")), false}
			};
	}

	/**
	 * Verifies that a certification stays current while its recorded pass-probability threshold is at least the
	 * current one, and is historical, naming the parameter, otherwise.
	 *
	 * @param currentPassRate the current minimum pass rate
	 * @param staysCurrent {@code true} if the certification stays current
	 */
	@Test(dataProvider = "currentPassRates")
	public void passRateDecidesApplicability(BigDecimal currentPassRate, boolean staysCurrent)
	{
		// Requirement: R-M2
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		assertParameterApplicability(new CertificationParameters(currentPassRate, level), staysCurrent,
			CertificationParameter.MINIMUM_PASS_RATE);
	}

	/**
	 * Returns a current certification level per row, with whether a certification recorded at the level of the
	 * first attempt stays current under it.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] currentLevels()
	{
		BigInteger recorded = ErrorBudget.certificationLevel(1).threshold();
		return new Object[][]
			{
				// The recorded level is below the current one
				{new SignificanceLevel(recorded.subtract(BigInteger.ONE)), true},
				{new SignificanceLevel(recorded), true},
				// The recorded level is above the current one
				{new SignificanceLevel(recorded.add(BigInteger.ONE)), false}
			};
	}

	/**
	 * Verifies that a certification stays current while its recorded certification level is at most the current
	 * level for its attempt index, and is historical, naming the parameter, otherwise.
	 *
	 * @param currentLevel the current certification level
	 * @param staysCurrent {@code true} if the certification stays current
	 */
	@Test(dataProvider = "currentLevels")
	public void levelDecidesApplicability(SignificanceLevel currentLevel, boolean staysCurrent)
	{
		// Requirement: R-M2
		CertificationParameters current = new CertificationParameters(Contract.DEFAULT_MINIMUM_PASS_RATE,
			currentLevel);
		assertParameterApplicability(current, staysCurrent, CertificationParameter.CERTIFICATION_LEVEL);
	}

	/**
	 * A certification record compared with a changed prompt file, judge version and session environment version is
	 * historical and names each change. The trial model, which only a session observes, is unchecked rather than
	 * changed.
	 */
	@Test
	public void recordNamesEachChangedCondition()
	{
		// Requirement: R-M6
		CertificationRecord record = record();
		Map<MeasurementCondition, String> known = new EnumMap<>(MeasurementCondition.class);
		known.put(MeasurementCondition.PROVIDER, "claude");
		known.put(MeasurementCondition.JUDGE, "judge-2");
		known.put(MeasurementCondition.SESSION_ENVIRONMENT_VERSION, "environment-2");

		Applicability applicability = record.applicability(Map.of(Path.of("CLAUDE.md"), hash("prompt v3")),
			new MeasurementIdentity(known), Contract.DEFAULT_MINIMUM_PASS_RATE);

		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isTrue();
		requireThat(applicability.changed(), "changed").containsExactly(List.of(MeasurementCondition.JUDGE,
			MeasurementCondition.SESSION_ENVIRONMENT_VERSION));
		requireThat(applicability.unchecked(), "unchecked").containsExactly(List.of(MeasurementCondition.MODEL));
		requireThat(applicability.parameters(), "parameters").isEmpty();
	}

	/**
	 * A certification record compared with unchanged prompt files and conditions stays current, even though the trial
	 * model that only a session observes is unchecked.
	 */
	@Test
	public void uncheckedConditionKeepsRecordCurrent()
	{
		// Requirement: R-M6
		CertificationRecord record = record();
		Map<MeasurementCondition, String> known = new EnumMap<>(MeasurementCondition.class);
		known.put(MeasurementCondition.PROVIDER, "claude");
		known.put(MeasurementCondition.JUDGE, "judge-1");
		known.put(MeasurementCondition.SESSION_ENVIRONMENT_VERSION, "environment-1");

		Applicability applicability = record.applicability(Map.of(Path.of("CLAUDE.md"), hash("prompt v2")),
			new MeasurementIdentity(known), Contract.DEFAULT_MINIMUM_PASS_RATE);

		requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.CURRENT);
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isFalse();
		requireThat(applicability.changed(), "changed").isEmpty();
		requireThat(applicability.unchecked(), "unchecked").containsExactly(List.of(MeasurementCondition.MODEL));
	}

	/**
	 * A certification record of another harness is compared with the prompt files alone: the harness's own conditions
	 * are unchecked rather than changed, so the record stays current until a prompt file changes, and the history that
	 * keeps one latest record per harness can tell each harness whether its own record applies.
	 */
	@Test
	public void otherHarnessRecordChecksOnlyPromptFiles()
	{
		// Requirement: R-M6
		CertificationRecord record = record("codex");
		Map<MeasurementCondition, String> known = new EnumMap<>(MeasurementCondition.class);
		known.put(MeasurementCondition.PROVIDER, "claude");
		known.put(MeasurementCondition.JUDGE, "judge-2");
		known.put(MeasurementCondition.SESSION_ENVIRONMENT_VERSION, "environment-2");
		MeasurementIdentity identity = new MeasurementIdentity(known);

		Applicability unchanged = record.applicability(Map.of(Path.of("CLAUDE.md"), hash("prompt v2")), identity,
			Contract.DEFAULT_MINIMUM_PASS_RATE);
		Applicability edited = record.applicability(Map.of(Path.of("CLAUDE.md"), hash("prompt v3")), identity,
			Contract.DEFAULT_MINIMUM_PASS_RATE);

		requireThat(unchanged.status(), "unchangedStatus").isEqualTo(Applicability.Status.CURRENT);
		requireThat(unchanged.changed(), "unchangedChanged").isEmpty();
		requireThat(unchanged.unchecked(), "unchecked").contains(MeasurementCondition.JUDGE).
			contains(MeasurementCondition.PROVIDER).contains(MeasurementCondition.SESSION_ENVIRONMENT_VERSION);
		requireThat(edited.status(), "editedStatus").isEqualTo(Applicability.Status.HISTORICAL);
		requireThat(edited.promptContentChanged(), "promptContentChanged").isTrue();
		requireThat(edited.changed(), "editedChanged").isEmpty();
	}

	/**
	 * Returns the record of a first certification of {@code CLAUDE.md} by Claude Code under the current parameters,
	 * measured with the trial model {@code model-a}, judge version {@code judge-1} and session environment version
	 * {@code environment-1}.
	 *
	 * @return the record
	 */
	private static CertificationRecord record()
	{
		return record("claude");
	}

	/**
	 * Returns the record of a first certification of {@code CLAUDE.md} under the current parameters, measured with
	 * the trial model {@code model-a}, judge version {@code judge-1} and session environment version
	 * {@code environment-1}.
	 *
	 * @param harness the model provider that the record names
	 * @return the record
	 */
	private static CertificationRecord record(String harness)
	{
		Map<MeasurementCondition, String> recorded = new EnumMap<>(MeasurementCondition.class);
		recorded.put(MeasurementCondition.PROVIDER, harness);
		recorded.put(MeasurementCondition.JUDGE, "judge-1");
		recorded.put(MeasurementCondition.SESSION_ENVIRONMENT_VERSION, "environment-1");
		recorded.put(MeasurementCondition.MODEL, "model-a");
		return new CertificationRecord(Path.of("CLAUDE.md"), 1, CertificationScope.LATEST_VERSIONS,
			CertificationParameters.current(1, Contract.DEFAULT_MINIMUM_PASS_RATE),
			List.of(new CertificationRecord.CellResult("formatting", "formatting",
			100, 0, Map.of(), Map.of())), Map.of(Path.of("CLAUDE.md"), hash("prompt v2")),
			new MeasurementIdentity(recorded));
	}

	/**
	 * Returns the hash of a text.
	 *
	 * @param text the text
	 * @return the hash of its UTF-8 bytes
	 */
	private static Sha256 hash(String text)
	{
		return Sha256.of(text.getBytes(UTF_8));
	}

	/**
	 * Checks the applicability of an unchanged certification of the first attempt under the current parameters.
	 *
	 * @param current the current parameters
	 * @param staysCurrent {@code true} if the certification must stay current
	 * @param parameter the parameter that must be named if it does not
	 */
	private static void assertParameterApplicability(CertificationParameters current, boolean staysCurrent,
		CertificationParameter parameter)
	{
		Campaign campaign = new Builder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).cell("formatting", 100, 0).outcome(Outcome.CERTIFIED).build();
		CertificationAttempt attempt = campaign.attempt(1);
		Applicability applicability = Applicability.of(attempt, attempt.candidate().snapshot(), attempt.identity(),
			current);
		requireThat(applicability.changed(), "changed").isEmpty();
		requireThat(applicability.promptContentChanged(), "promptContentChanged").isFalse();
		if (staysCurrent)
		{
			requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.CURRENT);
			requireThat(applicability.parameters(), "parameters").isEmpty();
		}
		else
		{
			requireThat(applicability.status(), "status").isEqualTo(Applicability.Status.HISTORICAL);
			requireThat(applicability.parameters(), "parameters").containsExactly(List.of(parameter));
		}
	}
}
