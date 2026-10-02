package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun.Role;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link Promotion} and the author attempts that it judges.
 */
public final class PromotionTest
{
	private static final Sha256 ATTEMPT = CampaignBuilder.snapshot("attempt");

	/**
	 * Creates the test.
	 */
	public PromotionTest()
	{
	}

	/**
	 * Returns an unassigned run.
	 *
	 * @param role the run's role
	 * @param instance the instance
	 * @return the run
	 */
	private static PromotionRun run(Role role, TrialInstance instance)
	{
		return new PromotionRun(role, instance, null, null);
	}

	/**
	 * Returns a judged run.
	 *
	 * @param role the run's role
	 * @param instance the instance
	 * @param verdict the run's outcome
	 * @return the run
	 */
	private static PromotionRun run(Role role, TrialInstance instance, Verdict verdict)
	{
		TrialKey key = new TrialKey(Purpose.PROMOTION, 1, ATTEMPT, instance.categoryId(), 1);
		return new PromotionRun(role, instance, key, verdict);
	}

	/**
	 * Returns an author attempt on {@code safety}.
	 *
	 * @param runs the attempt's runs
	 * @return the attempt
	 */
	private static AuthorAttempt attempt(PromotionRun... runs)
	{
		return new AuthorAttempt(1, "safety", ATTEMPT, List.of(runs), Minimality.start(List.of(), ATTEMPT));
	}

	/**
	 * Verifies that the plan runs each triggering instance twice, each other regression instance once, and the
	 * working candidate once on each regression instance that it has no result on.
	 */
	@Test
	public void planRunsTriggeringTwiceAndRegressionOnce()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		TrialInstance formal = targeted("formatting", "formal");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(logs, Verdict.FAIL),
			new RegressionInstance(cache, Verdict.PASS), new RegressionInstance(formal, null));
		List<PromotionRun> plan = Promotion.plan("safety", List.of(logs, formal), regressionSet,
			new Generator(ContractFixtures.contract()));

		requireThat(plan, "plan").isEqualTo(List.of(run(Role.TRIGGERING, logs), run(Role.TRIGGERING, logs),
			run(Role.REGRESSION, cache), run(Role.REGRESSION, formal), run(Role.WORKING_REFERENCE, formal)));
	}

	/**
	 * Verifies that an attempt is promoted when every triggering run passes and it fails no regression instance
	 * that the working candidate passes.
	 */
	@Test
	public void passingAttemptIsPromoted()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(cache, Verdict.PASS));
		requireThat(Promotion.decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.REGRESSION, cache, Verdict.PASS)), regressionSet),
			"decision").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that one failing triggering run rejects the attempt.
	 */
	@Test
	public void failingTriggeringRunRejects()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		requireThat(Promotion.decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS),
			run(Role.TRIGGERING, logs, Verdict.FAIL)), List.of()), "decision").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(Promotion.decide(attempt(run(Role.TRIGGERING, logs, Verdict.FAIL),
			run(Role.TRIGGERING, logs)), List.of()), "early").isEqualTo(Promotion.Decision.REJECT);
	}

	/**
	 * Verifies that a regression failure rejects the attempt only where the working candidate passes.
	 */
	@Test
	public void regressionCountsOnlyWhereWorkingPasses()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance cache = targeted("safety", "cache");
		TrialInstance tmp = targeted("safety", "tmp");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(cache, Verdict.PASS),
			new RegressionInstance(tmp, Verdict.FAIL));
		PromotionRun first = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun second = run(Role.TRIGGERING, logs, Verdict.PASS);

		requireThat(Promotion.decide(attempt(first, second, run(Role.REGRESSION, cache, Verdict.FAIL),
			run(Role.REGRESSION, tmp, Verdict.PASS)), regressionSet), "workingPasses").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(Promotion.decide(attempt(first, second, run(Role.REGRESSION, cache, Verdict.PASS),
			run(Role.REGRESSION, tmp, Verdict.FAIL)), regressionSet), "workingFails").
			isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that the working candidate's reference run decides a regression instance that it had no result on.
	 */
	@Test
	public void referenceRunDecidesUnknownWorkingResult()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance formal = targeted("formatting", "formal");
		List<RegressionInstance> regressionSet = List.of(new RegressionInstance(formal, null));
		PromotionRun first = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun second = run(Role.TRIGGERING, logs, Verdict.PASS);
		PromotionRun regression = run(Role.REGRESSION, formal, Verdict.FAIL);

		requireThat(Promotion.decide(attempt(first, second, regression,
			run(Role.WORKING_REFERENCE, formal, Verdict.PASS)), regressionSet), "referencePasses").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(Promotion.decide(attempt(first, second, regression,
			run(Role.WORKING_REFERENCE, formal, Verdict.FAIL)), regressionSet), "referenceFails").
			isEqualTo(Promotion.Decision.PROMOTE);
		requireThat(Promotion.decide(attempt(first, second, regression, run(Role.WORKING_REFERENCE, formal)),
			regressionSet), "referencePending").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Verifies that an attempt is pending while a needed run has no outcome.
	 */
	@Test
	public void unjudgedRunIsPending()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		requireThat(Promotion.decide(attempt(run(Role.TRIGGERING, logs, Verdict.PASS), run(Role.TRIGGERING, logs)),
			List.of()), "decision").isEqualTo(Promotion.Decision.PENDING);
	}

	/**
	 * Returns a campaign in a round on {@code safety} whose working candidate failed {@code logs}.
	 *
	 * @return the campaign
	 */
	private static Campaign inRound()
	{
		TrialInstance logs = targeted("safety", "logs");
		return new CampaignBuilder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(logs).regression(logs, Verdict.FAIL).
			round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Verifies that promotion runs never count as representative evidence, and that a promoted attempt becomes the
	 * working candidate with new counts.
	 */
	@Test
	public void promotionRunsNeverCount()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT, List.of()));
		AuthorAttempt author = campaign.development().author();
		requireThat(author.runs(), "runs").size().isEqualTo(2);
		TrialInstance logs = author.runs().getFirst().instance();

		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(CampaignBuilder.counts(60, 1));
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);

		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.PASS);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));
		requireThat(campaign.development().working(), "promoted").isEqualTo(new Candidate(2, ATTEMPT));
		requireThat(campaign.development().count("safety"), "newCounts").isEqualTo(CategoryCounts.EMPTY);
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.lineage().regressionSet(), "regressionSet").
			isEqualTo(List.of(new RegressionInstance(logs, Verdict.PASS)));
	}

	/**
	 * Verifies that a run's outcome is recorded on that run, even when a run of another category or snapshot has the
	 * same sequence number.
	 */
	@Test
	public void outcomeReachesItsOwnRun()
	{
		// Requirement: R-D3
		TrialInstance logs = targeted("safety", "logs");
		TrialInstance formal = targeted("formatting", "formal");
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(logs).regression(logs, Verdict.FAIL).
			regression(formal, Verdict.PASS).round(new Round(Map.of("safety", 0))).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT, List.of()));
		AuthorAttempt author = campaign.development().author();
		requireThat(author.runs().stream().map(PromotionRun::role).toList(), "roles").
			isEqualTo(List.of(Role.TRIGGERING, Role.TRIGGERING, Role.REGRESSION));
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT,
			author.runs().get(2).instance(), Verdict.PASS);

		List<PromotionRun> runs = campaign.development().author().runs();
		requireThat(runs.get(0).verdict(), "triggering").isNull();
		requireThat(runs.get(2).verdict(), "regression").isEqualTo(Verdict.PASS);
	}

	/**
	 * Verifies that a rejected attempt keeps the working candidate and uses one of the category's attempts.
	 */
	@Test
	public void rejectedAttemptUsesRoundAttempt()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT, List.of()));
		AuthorAttempt author = campaign.development().author();
		TrialInstance logs = author.runs().getFirst().instance();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.FAIL);

		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
		requireThat(campaign.development().author(), "author").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 1)));
		requireThat(campaign.authorAttempts(), "authorAttempts").isEqualTo(1);
	}

	/**
	 * Verifies that an attempt that leaves the working candidate unchanged is rejected without runs.
	 */
	@Test
	public void unchangedAttemptIsRejected()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety",
			campaign.development().working().snapshot(), List.of()));
		requireThat(campaign.development().author(), "author").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 1)));
	}

	/**
	 * Verifies that the outcome of an earlier author attempt's run is dropped.
	 */
	@Test
	public void staleRunIsDropped()
	{
		// Requirement: R-D3
		Campaign campaign = inRound();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", ATTEMPT, List.of()));
		AuthorAttempt author = campaign.development().author();
		TrialInstance logs = author.runs().getFirst().instance();
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs);
		TrialKey slow = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(slow));
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, logs, Verdict.FAIL);
		requireThat(campaign.development().author(), "author").isNull();

		List<LogicalTrial> before = new ArrayList<>(campaign.trials());
		campaign = TransitionSteps.complete(campaign, slow, Verdict.PASS);
		requireThat(campaign.trials(), "trials").size().isEqualTo(before.size() - 1);
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 1)));
	}
}
