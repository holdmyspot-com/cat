package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_LOW;
import static io.github.cowwoc.cat.common.optimizer.contract.TierFixtures.BIG_MEDIUM;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the reproduction of a failed certification attempt's formal failures before any author works on them.
 */
public final class ReproductionTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	/**
	 * The requirement and behavior that the formal failures failed.
	 */
	private static final FailedRequirement WRONG_TONE = new FailedRequirement("r1", "wrong_tone");
	/**
	 * Another behavior through which a trial can fail.
	 */
	private static final FailedRequirement INCORRECT_RESULT = new FailedRequirement("core_task", "incorrect_result");
	/**
	 * The formal failures' instance.
	 */
	private static final TrialInstance FAILED = new Generator(TierFixtures.tieredContract()).draw(BIG_MEDIUM,
		DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
	/**
	 * The formal failures' instance in the lowest effort of its model.
	 */
	private static final TrialInstance FAILED_LOW = new TrialInstance(BIG_LOW, FAILED.categoryId(),
		FAILED.testId(), FAILED.choices(), FAILED.turns(), FAILED.expectedBehaviour(), FAILED.generator(),
		FAILED.streamKind(), FAILED.streamPosition(), FAILED.targeted());
	/**
	 * {@code formatting@big/low} under a tool-output token limit of 5000.
	 */
	private static final String BIG_LOW_LIMITED = BIG_LOW + "/tool-output-5000";
	/**
	 * {@code formatting@big/medium} under a tool-output token limit of 5000.
	 */
	private static final String BIG_MEDIUM_LIMITED = BIG_MEDIUM + "/tool-output-5000";

	/**
	 * Returns the definition of the failed test.
	 *
	 * @return the test
	 */
	private static TestDefinition failedTest()
	{
		return TierFixtures.tieredContract().findTest(FAILED.testId()).orElseThrow();
	}

	/**
	 * Returns new key-value combinations of the failed test.
	 *
	 * @param prefix distinguishes the combinations of different rounds
	 * @return 4 combinations
	 */
	private static List<AddedCombination> combinations(String prefix)
	{
		TestDefinition test = failedTest();
		List<AddedCombination> combinations = new ArrayList<>();
		for (int i = 1; i <= 4; ++i)
		{
			String suffix = prefix + "_" + i;
			List<VariationValue> values = test.keyRules().stream().map(rule -> new VariationValue(
				rule.dimension() + "_" + suffix, rule.dimension() + " " + suffix, 0.25)).toList();
			combinations.add(new AddedCombination(1, test.id(), values));
		}
		return combinations;
	}

	/**
	 * Returns combinations with counts.
	 *
	 * @param combinations the combinations
	 * @param trials the trials of each combination
	 * @return the tried combinations, without failures
	 */
	private static List<TriedCombination> tried(List<AddedCombination> combinations, int... trials)
	{
		List<TriedCombination> tried = new ArrayList<>();
		for (int i = 0; i < combinations.size(); ++i)
			tried.add(new TriedCombination(combinations.get(i), trials[i], 0));
		return tried;
	}

	/**
	 * Returns a campaign builder whose first certification attempt failed with two formal failures of
	 * {@link #WRONG_TONE} in {@code formatting@big/medium}, before any reproduction ran.
	 *
	 * @return the builder
	 */
	private static Builder.AttemptBuilder failed()
	{
		return failed(Harness.CLAUDE, FAILED);
	}

	/**
	 * Returns a campaign builder whose first certification attempt failed with two formal failures of
	 * {@link #WRONG_TONE}, before any reproduction ran.
	 *
	 * @param harness the harness that started the campaign
	 * @param instance the formal failures' instance
	 * @return the builder
	 */
	private static Builder.AttemptBuilder failed(Harness harness, TrialInstance instance)
	{
		Failure failure = new Failure(instance, "", List.of(WRONG_TONE), null, List.of(), List.of(), Builder.FILES);
		Header header = new Header("campaign-1", Path.of("/project"), Path.of("/project/AGENTS.md"), harness,
			Lifecycle.ACTIVE);
		return new Builder().header(header).confirmed(TierFixtures.tieredContract(), 1).working(1).readyBaseline().
			readyDevelopment().certificationAttempt(1).failure(failure).failure(failure).outcome(Outcome.BELOW_MINIMUM);
	}

	/**
	 * Returns a failure group of the failed attempt with given rounds.
	 *
	 * @param rounds the rounds
	 * @return the group
	 */
	private static FailureGroup group(ReproductionRound... rounds)
	{
		return group(BIG_MEDIUM, FailureGroup.Status.REPRODUCING, rounds);
	}

	/**
	 * Returns a failure group of the failed attempt with given rounds.
	 *
	 * @param cellId the cell in which the formal failures occurred
	 * @param status the group's reproduction status
	 * @param rounds the rounds
	 * @return the group
	 */
	private static FailureGroup group(String cellId, FailureGroup.Status status, ReproductionRound... rounds)
	{
		return new FailureGroup(cellId, FAILED.testId(), WRONG_TONE, 2, FailureGroup.ROUNDS, List.of(rounds), status);
	}

	/**
	 * Returns the new assignments of a purpose in the campaign's next plan.
	 *
	 * @param campaign the campaign
	 * @param purpose the trials' purpose
	 * @return the assignments
	 */
	private static List<Admission.Assign> planned(Campaign campaign, Purpose purpose)
	{
		return Scheduler.plan(campaign, NOW, 8).stream().filter(admission -> admission instanceof Admission.Assign).
			map(Admission.Assign.class::cast).filter(assign -> assign.purpose() == purpose).toList();
	}

	/**
	 * Assigns, runs and completes the next planned reproduction trial.
	 *
	 * @param campaign the campaign
	 * @param verdict the trial's outcome
	 * @param failed the requirements that a failed trial failed
	 * @return the updated campaign
	 */
	private static Campaign reproduce(Campaign campaign, Verdict verdict, FailedRequirement... failed)
	{
		Admission.Assign admission = planned(campaign, Purpose.REPRODUCTION).getFirst();
		Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(admission));
		TrialKey key = TransitionSteps.last(assigned);
		Campaign started = Transitions.apply(assigned, new CampaignEvent.Start(key));
		return Transitions.apply(started, new CampaignEvent.Complete(key, verdict, "", List.of(failed), null,
			started.identity(), null, List.of(), Builder.FILES));
	}

	/**
	 * Verifies that an attempt that ends with formal failures groups them by cell, test, failed requirement and
	 * behavior, and that a failure without a failed requirement forms no group.
	 */
	@Test
	public void endedAttemptGroupsFormalFailures()
	{
		// Requirement: R-C19
		TrialInstance other = new Generator(TierFixtures.tieredContract()).draw(BIG_LOW,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		List<Failure> failures = List.of(
			new Failure(FAILED, "", List.of(WRONG_TONE, INCORRECT_RESULT), null, List.of(), List.of(), Builder.FILES),
			new Failure(FAILED, "", List.of(WRONG_TONE), null, List.of(), List.of(), Builder.FILES),
			new Failure(other, "", List.of(WRONG_TONE), null, List.of(), List.of(), Builder.FILES),
			new Failure(other, "", List.of(), null, List.of(), List.of(), Builder.FILES));

		List<FailureGroup> groups = FailureGroup.of(failures);

		requireThat(groups.stream().map(group -> List.of(group.cellId(), group.testId(), group.failed(),
			group.formalFailures())).toList(), "groups").isEqualTo(List.of(
			List.of(BIG_MEDIUM, FAILED.testId(), WRONG_TONE, 2),
			List.of(BIG_MEDIUM, FAILED.testId(), INCORRECT_RESULT, 1),
			List.of(BIG_LOW, other.testId(), WRONG_TONE, 1)));
		for (FailureGroup group : groups)
		{
			requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCING);
			requireThat(group.awaitsValues(), "awaitsValues").isTrue();
			requireThat(group.round().cellId(), "round().cellId()").isEqualTo(group.cellId());
		}
	}

	/**
	 * Verifies that while a failed attempt's groups await the deriver's values, neither development nor reproduction
	 * trials run and the readiness gate stays closed.
	 */
	@Test
	public void noDevelopmentBeforeValues()
	{
		// Requirement: R-C19
		Campaign campaign = failed().build();

		requireThat(campaign.attempt(1).groups().size(), "groups").isEqualTo(1);
		requireThat(campaign.reproducing().map(CertificationAttempt::index).orElse(0), "reproducing").isEqualTo(1);
		requireThat(planned(campaign, Purpose.DEVELOPMENT), "development").isEmpty();
		requireThat(planned(campaign, Purpose.REPRODUCTION), "reproduction").isEmpty();
		requireThat(ReadinessGate.isMet(campaign), "isMet").isFalse();
	}

	/**
	 * Verifies that the deriver's values start the group's round: reproduction trials of the working candidate run
	 * in the group's cell, and development trials still do not.
	 */
	@Test
	public void valuesStartTheRound()
	{
		// Requirement: R-C19
		Campaign campaign = Transitions.apply(failed().build(),
			new CampaignEvent.ReproductionValues(1, 1, combinations("a")));

		ReproductionRound round = campaign.attempt(1).group(1).round();
		requireThat(round.combinations(), "combinations").isEqualTo(tried(combinations("a"), 0, 0, 0, 0));
		List<Admission.Assign> reproduction = planned(campaign, Purpose.REPRODUCTION);
		requireThat(reproduction, "reproduction").isNotEmpty();
		for (Admission.Assign assign : reproduction)
		{
			requireThat(assign.series(), "series").isEqualTo(1);
			requireThat(assign.cell(), "cell").isEqualTo(BIG_MEDIUM);
			requireThat(assign.snapshot(), "snapshot").isEqualTo(campaign.development().working().snapshot());
		}
		requireThat(planned(campaign, Purpose.DEVELOPMENT), "development").isEmpty();
	}

	/**
	 * Verifies that a reproduction trial runs an instance of the failed test that holds one of the round's
	 * combinations.
	 */
	@Test
	public void reproductionTrialRunsARoundCombination()
	{
		// Requirement: R-C19
		Campaign campaign = Transitions.apply(failed().build(),
			new CampaignEvent.ReproductionValues(1, 1, combinations("a")));

		Campaign assigned = Transitions.apply(campaign,
			new CampaignEvent.Assign(planned(campaign, Purpose.REPRODUCTION).getFirst()));

		TrialInstance instance = assigned.trials().getLast().instance();
		requireThat(instance.cellId(), "cellId").isEqualTo(BIG_MEDIUM);
		requireThat(campaign.attempt(1).group(1).round().indexOf(failedTest(), instance), "indexOf").
			isNotNegative();
	}

	/**
	 * Verifies that a round never runs more than {@value FailureGroup#TRIALS} trials.
	 */
	@Test
	public void roundStopsAtItsTrialLimit()
	{
		// Requirement: R-C19
		Campaign campaign = failed().groups(List.of(group(new ReproductionRound(BIG_MEDIUM,
			tried(combinations("a"), 25, 25, 25, 24))))).campaign().slowStart(new SlowStart(8, 0)).build();

		requireThat(planned(campaign, Purpose.REPRODUCTION).size(), "reproduction").isEqualTo(1);
	}

	/**
	 * Verifies that two trials that fail the requirement through the group's behavior reproduce the group: its
	 * combinations join development, development counts restart, and development resumes, while the contract and the
	 * measurement identity are unchanged. A failure through another behavior does not count.
	 */
	@Test
	public void twoMatchingFailuresReproduceTheGroup()
	{
		// Requirement: R-C17
		// Requirement: R-C19
		Campaign failed = failed().build();
		Campaign campaign = Transitions.apply(failed, new CampaignEvent.ReproductionValues(1, 1, combinations("a")));
		int series = campaign.development().series();

		campaign = reproduce(campaign, Verdict.FAIL, WRONG_TONE);
		campaign = reproduce(campaign, Verdict.FAIL, INCORRECT_RESULT);
		campaign = reproduce(campaign, Verdict.PASS);
		requireThat(campaign.attempt(1).group(1).status(), "status").isEqualTo(FailureGroup.Status.REPRODUCING);
		requireThat(campaign.attempt(1).group(1).round().trials(), "trials").isEqualTo(3);
		requireThat(campaign.attempt(1).group(1).round().failures(), "failures").isEqualTo(1);
		requireThat(campaign.development().series(), "series").isEqualTo(series);

		campaign = reproduce(campaign, Verdict.FAIL, WRONG_TONE);

		requireThat(campaign.attempt(1).group(1).status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
		requireThat(campaign.lineage().added().combinations(), "added").isEqualTo(combinations("a"));
		requireThat(campaign.development().series(), "series").isEqualTo(series + 1);
		requireThat(campaign.development().counts(), "counts").isEmpty();
		requireThat(campaign.reproducing(), "reproducing").isEmpty();
		requireThat(planned(campaign, Purpose.DEVELOPMENT), "development").isNotEmpty();
		requireThat(campaign.contract(), "contract").isEqualTo(failed.contract());
		requireThat(campaign.lineage().contractHash(), "contractHash").isEqualTo(failed.lineage().contractHash());
		requireThat(campaign.identity(), "identity").isEqualTo(failed.identity());
	}

	/**
	 * Verifies that a reproduction trial that completes after its round ended counts nowhere.
	 */
	@Test
	public void trialAfterTheRoundEndsIsDiscarded()
	{
		// Requirement: R-C19
		Campaign campaign = Transitions.apply(failed().campaign().slowStart(new SlowStart(8, 0)).build(),
			new CampaignEvent.ReproductionValues(1, 1, combinations("a")));
		List<TrialKey> keys = new ArrayList<>();
		for (Admission.Assign admission : planned(campaign, Purpose.REPRODUCTION).subList(0, 3))
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.Assign(admission));
			keys.add(TransitionSteps.last(campaign));
		}
		for (TrialKey key : keys)
			campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		for (TrialKey key : keys)
		{
			campaign = Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.FAIL, "",
				List.of(WRONG_TONE), null, campaign.identity(), null, List.of(), Builder.FILES));
		}

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
		requireThat(group.trials(), "trials").isEqualTo(2);
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that a round that ends without reproducing the group repeats in the stress cell, the next lower
	 * reasoning effort of the same model, with the same combinations and fresh counts.
	 */
	@Test
	public void unreproducedRoundRepeatsUnderStress()
	{
		// Requirement: R-C19
		Campaign campaign = failed().groups(List.of(group(new ReproductionRound(BIG_MEDIUM,
			tried(combinations("a"), 25, 25, 25, 24))))).build();

		campaign = reproduce(campaign, Verdict.PASS);

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCING);
		requireThat(group.rounds().size(), "rounds").isEqualTo(2);
		requireThat(group.round(), "round").isEqualTo(new ReproductionRound(BIG_LOW,
			tried(combinations("a"), 0, 0, 0, 0)));
		for (Admission.Assign assign : planned(campaign, Purpose.REPRODUCTION))
			requireThat(assign.cell(), "cell").isEqualTo(BIG_LOW);
	}

	/**
	 * Verifies that a stress round that ends without reproducing the group asks the deriver for new values in the
	 * group's cell.
	 */
	@Test
	public void unreproducedStressRoundAwaitsNewValues()
	{
		// Requirement: R-C19
		Campaign campaign = failed().groups(List.of(group(
			new ReproductionRound(BIG_MEDIUM, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 24))))).build();

		campaign = reproduce(campaign, Verdict.PASS);

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.awaitsValues(), "awaitsValues").isTrue();
		requireThat(group.round().cellId(), "round().cellId()").isEqualTo(BIG_MEDIUM);
		requireThat(planned(campaign, Purpose.REPRODUCTION), "reproduction").isEmpty();

		campaign = Transitions.apply(campaign, new CampaignEvent.ReproductionValues(1, 1, combinations("b")));
		requireThat(campaign.attempt(1).group(1).round().added(), "added").isEqualTo(combinations("b"));
	}

	/**
	 * Verifies that a reproduction in the stress cell makes it a development cell.
	 */
	@Test
	public void stressReproductionAddsADevelopmentCell()
	{
		// Requirement: R-C19
		List<TriedCombination> stressed = new ArrayList<>(tried(combinations("a"), 10, 0, 0, 0));
		stressed.set(0, new TriedCombination(combinations("a").getFirst(), 10, 1));
		Campaign campaign = failed().groups(List.of(group(
			new ReproductionRound(BIG_MEDIUM, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW, stressed)))).build();
		requireThat(CellTier.developmentCells(campaign).stream().map(Cell::id).toList(), "developmentCells").
			doesNotContain(BIG_LOW);

		campaign = reproduce(campaign, Verdict.FAIL, WRONG_TONE);

		requireThat(campaign.attempt(1).group(1).status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
		requireThat(CellTier.developmentCells(campaign).stream().map(Cell::id).toList(), "developmentCells").
			contains(BIG_LOW);
	}

	/**
	 * Verifies that in Codex, a round in the lowest effort of its model that ends without reproducing the group
	 * repeats with the same combinations and fresh counts in the same cell under a tool-output token limit of 5000,
	 * whose reproduction trials run in that limited cell.
	 */
	@Test
	public void codexStressesTheLowestEffortByToolOutput()
	{
		// Requirement: R-C19
		Campaign campaign = failed(Harness.CODEX, FAILED_LOW).groups(List.of(group(BIG_LOW,
			FailureGroup.Status.REPRODUCING, new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 24))))).
			build();

		campaign = reproduce(campaign, Verdict.PASS);

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCING);
		requireThat(group.round(), "round").isEqualTo(new ReproductionRound(BIG_LOW_LIMITED,
			tried(combinations("a"), 0, 0, 0, 0)));
		List<Admission.Assign> reproduction = planned(campaign, Purpose.REPRODUCTION);
		requireThat(reproduction, "reproduction").isNotEmpty();
		for (Admission.Assign assign : reproduction)
			requireThat(assign.cell(), "cell").isEqualTo(BIG_LOW_LIMITED);
		Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(reproduction.getFirst()));
		requireThat(assigned.trials().getLast().instance().cellId(), "cellId").isEqualTo(BIG_LOW_LIMITED);
	}

	/**
	 * Verifies that Claude offers no tool-output stress: a round in the lowest effort of its model that ends without
	 * reproducing the group asks the deriver for new values in the group's cell.
	 */
	@Test
	public void claudeOffersNoToolOutputStress()
	{
		// Requirement: R-C19
		Campaign campaign = failed(Harness.CLAUDE, FAILED_LOW).groups(List.of(group(BIG_LOW,
			FailureGroup.Status.REPRODUCING, new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 24))))).
			build();

		campaign = reproduce(campaign, Verdict.PASS);

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.rounds().size(), "rounds").isEqualTo(2);
		requireThat(group.awaitsValues(), "awaitsValues").isTrue();
		requireThat(group.round().cellId(), "round().cellId()").isEqualTo(BIG_LOW);
	}

	/**
	 * Verifies that in Codex, a stress round under the next lower effort that ends without reproducing the group
	 * repeats in the group's cell under a tool-output token limit of 5000.
	 */
	@Test
	public void codexStressesToolOutputAfterLowerEffort()
	{
		// Requirement: R-C19
		Campaign campaign = failed(Harness.CODEX, FAILED).groups(List.of(group(BIG_MEDIUM,
			FailureGroup.Status.REPRODUCING,
			new ReproductionRound(BIG_MEDIUM, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 24))))).build();

		campaign = reproduce(campaign, Verdict.PASS);

		requireThat(campaign.attempt(1).group(1).round(), "round").isEqualTo(new ReproductionRound(
			BIG_MEDIUM_LIMITED, tried(combinations("a"), 0, 0, 0, 0)));
	}

	/**
	 * Verifies that in Codex, a round under the last stress setting that ends without reproducing the group asks
	 * the deriver for new values in the group's cell.
	 */
	@Test
	public void codexLastStressRoundAwaitsNewValues()
	{
		// Requirement: R-C19
		Campaign campaign = failed(Harness.CODEX, FAILED).groups(List.of(group(BIG_MEDIUM,
			FailureGroup.Status.REPRODUCING,
			new ReproductionRound(BIG_MEDIUM, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_MEDIUM_LIMITED, tried(combinations("a"), 25, 25, 25, 24))))).build();

		campaign = reproduce(campaign, Verdict.PASS);

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.rounds().size(), "rounds").isEqualTo(4);
		requireThat(group.awaitsValues(), "awaitsValues").isTrue();
		requireThat(group.round().cellId(), "round().cellId()").isEqualTo(BIG_MEDIUM);
	}

	/**
	 * Verifies that a reproduction under a tool-output token limit makes the limited cell a development cell:
	 * development trials run in it, and the readiness gate waits for it.
	 */
	@Test
	public void toolOutputReproductionAddsDevelopment()
	{
		// Requirement: R-C19
		List<TriedCombination> limited = new ArrayList<>(tried(combinations("a"), 10, 0, 0, 0));
		limited.set(0, new TriedCombination(combinations("a").getFirst(), 10, 1));
		Campaign campaign = failed(Harness.CODEX, FAILED_LOW).groups(List.of(group(BIG_LOW,
			FailureGroup.Status.REPRODUCING,
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW_LIMITED, limited)))).build();

		campaign = reproduce(campaign, Verdict.FAIL, WRONG_TONE);

		requireThat(campaign.attempt(1).group(1).status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
		requireThat(CellTier.developmentCellIds(campaign), "developmentCells").contains(BIG_LOW_LIMITED);

		// Every contract cell is ready, so the next development trial can only go to the limited cell
		Campaign reproduced = failed(Harness.CODEX, FAILED_LOW).groups(List.of(group(BIG_LOW,
			FailureGroup.Status.REPRODUCED,
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW_LIMITED, limited)))).build();
		requireThat(planned(reproduced, Purpose.DEVELOPMENT).stream().map(Admission.Assign::cell).distinct().
			toList(), "development").isEqualTo(List.of(BIG_LOW_LIMITED));
	}

	/**
	 * Verifies that the readiness gate waits for a development cell under a tool-output token limit until it is
	 * ready.
	 */
	@Test
	public void gateCoversToolOutputDevelopmentCell()
	{
		// Requirement: R-C19
		List<TriedCombination> limited = new ArrayList<>(tried(combinations("a"), 10, 0, 0, 0));
		limited.set(0, new TriedCombination(combinations("a").getFirst(), 10, 2));
		Builder reproduced = failed(Harness.CODEX, FAILED_LOW).groups(List.of(group(BIG_LOW,
			FailureGroup.Status.REPRODUCED,
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW_LIMITED, limited)))).campaign();

		requireThat(ReadinessGate.isMet(reproduced.build()), "isMet").isFalse();
		requireThat(ReadinessGate.isMet(reproduced.development(BIG_LOW_LIMITED, 100, 0).build()), "readyIsMet").
			isTrue();
	}

	/**
	 * Returns a campaign builder whose only group ran {@value FailureGroup#ROUNDS} rounds without reproduction,
	 * before its last trial.
	 *
	 * @return the builder
	 */
	private static Builder beforeLastTrial()
	{
		return failed().groups(List.of(group(
			new ReproductionRound(BIG_MEDIUM, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW, tried(combinations("a"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_MEDIUM, tried(combinations("b"), 25, 25, 25, 25)),
			new ReproductionRound(BIG_LOW, tried(combinations("b"), 25, 25, 25, 24))))).campaign();
	}

	/**
	 * Verifies that after {@value FailureGroup#ROUNDS} rounds without reproduction, the group is recorded as not
	 * reproduced with its formal and reproduction counts, and CAT pauses for the user's choice.
	 */
	@Test
	public void fourUnreproducedRoundsPause()
	{
		// Requirement: R-C19
		Campaign campaign = reproduce(beforeLastTrial().build(), Verdict.PASS);

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.NOT_REPRODUCED);
		requireThat(group.formalFailures(), "formalFailures").isEqualTo(2);
		requireThat(group.trials(), "trials").isEqualTo(400);
		requireThat(group.failures(), "failures").isEqualTo(0);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.FailuresNotReproduced(1));
		requireThat(campaign.choices(), "choices").isEqualTo(List.of(Choice.MORE_ROUNDS, Choice.NEW_ATTEMPT,
			Choice.STOP));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
		requireThat(ReadinessGate.isMet(campaign), "isMet").isFalse();
	}

	/**
	 * Verifies that choosing more rounds gives the group {@value FailureGroup#ROUNDS} more rounds, starting with
	 * new values in its cell.
	 */
	@Test
	public void moreRoundsResumeReproduction()
	{
		// Requirement: R-C19
		Campaign campaign = reproduce(beforeLastTrial().build(), Verdict.PASS);

		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.MORE_ROUNDS));

		FailureGroup group = campaign.attempt(1).group(1);
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCING);
		requireThat(group.roundLimit(), "roundLimit").isEqualTo(2 * FailureGroup.ROUNDS);
		requireThat(group.rounds().size(), "rounds").isEqualTo(FailureGroup.ROUNDS + 1);
		requireThat(group.awaitsValues(), "awaitsValues").isTrue();
		requireThat(group.round().cellId(), "round().cellId()").isEqualTo(BIG_MEDIUM);
		requireThat(planned(campaign, Purpose.DEVELOPMENT), "development").isEmpty();
	}

	/**
	 * Verifies that choosing a new attempt leaves the group unreproduced and resumes development with the unchanged
	 * candidate, so that the readiness gate decides when the next attempt starts.
	 */
	@Test
	public void newAttemptResumesDevelopment()
	{
		// Requirement: R-C19
		Campaign paused = reproduce(beforeLastTrial().development(BIG_MEDIUM, 60, 0).build(), Verdict.PASS);

		Campaign campaign = Transitions.apply(paused, CampaignEvent.Choose.of(Choice.NEW_ATTEMPT));

		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.attempt(1).group(1).status(), "status").isEqualTo(FailureGroup.Status.ABANDONED);
		requireThat(campaign.reproducing(), "reproducing").isEmpty();
		requireThat(campaign.development().working(), "working").isEqualTo(paused.development().working());
		requireThat(campaign.lineage().added(), "added").isEqualTo(paused.lineage().added());
		requireThat(planned(campaign, Purpose.DEVELOPMENT), "development").isNotEmpty();
		requireThat(ReadinessGate.isCommitDue(campaign), "isCommitDue").isFalse();
	}

	/**
	 * Verifies that choosing a new attempt starts it at once when the unchanged candidate already meets the readiness
	 * gate.
	 */
	@Test
	public void newAttemptStartsOnceTheGateIsMet()
	{
		// Requirement: R-C19
		Campaign campaign = Transitions.apply(reproduce(beforeLastTrial().build(), Verdict.PASS),
			CampaignEvent.Choose.of(Choice.NEW_ATTEMPT));

		requireThat(ReadinessGate.isCommitDue(campaign), "isCommitDue").isTrue();
	}

	/**
	 * Verifies that values for a group whose round does not await them are refused.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void refusesValuesThatNoRoundAwaits()
	{
		// Requirement: R-C19
		Campaign campaign = Transitions.apply(failed().build(),
			new CampaignEvent.ReproductionValues(1, 1, combinations("a")));
		Transitions.apply(campaign, new CampaignEvent.ReproductionValues(1, 1, combinations("b")));
	}

	/**
	 * Verifies that values for an attempt that is not reproducing are refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesValuesForAnotherAttempt()
	{
		// Requirement: R-C19
		Transitions.apply(failed().build(), new CampaignEvent.ReproductionValues(2, 1,
			combinations("a").stream().map(combination -> new AddedCombination(2, combination.testId(),
				combination.values())).toList()));
	}
}
