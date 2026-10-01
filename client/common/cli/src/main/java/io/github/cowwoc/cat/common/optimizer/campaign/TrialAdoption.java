package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adopts judged trials into the campaign's counts and applies the policies that each adoption triggers.
 * <p>
 * Representative outcomes are adopted in assignment order within their group, so the order in which sessions finish
 * cannot bias the counts. Targeted outcomes are adopted as soon as they are judged. A trial whose purpose the state
 * no longer serves is stale: its outcome is discarded.
 */
final class TrialAdoption
{
	/**
	 * Prevents instantiation.
	 */
	private TrialAdoption()
	{
	}

	/**
	 * Indicates whether the state no longer serves a trial's purpose.
	 *
	 * @param draft the state
	 * @param trial the trial
	 * @return {@code true} if the trial's outcome must be discarded
	 */
	static boolean isStale(CampaignDraft draft, LogicalTrial trial)
	{
		if (draft.revisionRequested)
			return true;
		TrialKey key = trial.key();
		return switch (key.purpose())
		{
			case DEVELOPMENT -> key.series() != draft.series || !key.snapshot().equals(draft.working.snapshot());
			case BASELINE -> key.series() != draft.baselineIndex;
			case FORMAL -> isStaleFormal(draft, key);
			case DIAGNOSTIC -> key.series() != draft.series || draft.diagnosis == null ||
				!key.equals(draft.diagnosis.rerun());
			case PROMOTION -> draft.author == null || key.series() != draft.author.number();
		};
	}

	/**
	 * Indicates whether a formal trial's attempt ended or its category is decided.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @return {@code true} if the trial's outcome must be discarded
	 */
	private static boolean isStaleFormal(CampaignDraft draft, TrialKey key)
	{
		if (key.series() < 1 || key.series() > draft.attempts.size())
			return true;
		CertificationAttempt attempt = draft.attempt(key.series());
		return !attempt.isRunning() || DecisionTable.categoryDecision(attempt.procedure().boundaries(),
			attempt.count(key.category())) != DecisionTable.CategoryDecision.UNDECIDED;
	}

	/**
	 * Discards stale trials that are not running and adopts every judged trial whose turn has come, until nothing
	 * changes. Each adoption advances the campaign's evidence revision.
	 *
	 * @param draft the state
	 */
	static void settle(CampaignDraft draft)
	{
		boolean changed = true;
		while (changed)
		{
			changed = draft.trials.removeIf(trial -> trial.state() != TrialState.RUNNING && isStale(draft, trial));
			Optional<LogicalTrial> next = nextAdoptable(draft);
			if (next.isPresent())
			{
				LogicalTrial trial = next.get();
				draft.trials.remove(trial);
				adopt(draft, trial);
				++draft.evidenceRevision;
				changed = true;
			}
		}
	}

	/**
	 * Returns the judged trial to adopt next.
	 *
	 * @param draft the state
	 * @return the trial, or empty if every judged trial waits for an earlier trial of its group
	 */
	private static Optional<LogicalTrial> nextAdoptable(CampaignDraft draft)
	{
		for (LogicalTrial trial : draft.trials)
		{
			if (trial.state() != TrialState.JUDGED)
				continue;
			if (!trial.key().purpose().isRepresentative() || !waitsForEarlierTrial(draft, trial))
				return Optional.of(trial);
		}
		return Optional.empty();
	}

	/**
	 * Indicates whether an earlier trial of the same group is not adopted yet.
	 *
	 * @param draft the state
	 * @param trial the trial
	 * @return {@code true} if the trial must wait
	 */
	private static boolean waitsForEarlierTrial(CampaignDraft draft, LogicalTrial trial)
	{
		for (LogicalTrial other : draft.trials)
		{
			if (other.key().isSameGroup(trial.key()) && other.key().sequence() < trial.key().sequence())
				return true;
		}
		return false;
	}

	/**
	 * Adopts a judged trial.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adopt(CampaignDraft draft, LogicalTrial trial)
	{
		switch (trial.key().purpose())
		{
			case DEVELOPMENT -> adoptDevelopment(draft, trial);
			case BASELINE -> adoptBaseline(draft, trial);
			case FORMAL -> adoptFormal(draft, trial);
			case DIAGNOSTIC -> adoptDiagnostic(draft, trial);
			case PROMOTION -> adoptPromotion(draft, trial);
		}
	}

	/**
	 * Adopts a development outcome.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptDevelopment(CampaignDraft draft, LogicalTrial trial)
	{
		String category = trial.key().category();
		Verdict verdict = trial.verdict();
		draft.developmentCounts.put(category, draft.developmentCount(category).add(verdict));
		if (draft.working.isIncumbent())
			addBaseline(draft, category, verdict);
		recordWorkingVerdict(draft, trial.instance(), verdict);
		if (verdict == Verdict.PASS)
		{
			draft.slowStart = draft.slowStart.afterPass();
		}
		else
		{
			draft.slowStart = draft.slowStart.afterFailure();
			TrialInstance targeted = draft.generator().markTargeted(trial.instance());
			draft.developmentFailures.add(new Failure(targeted, trial.rationale(), List.of()));
			addRegression(draft, targeted, Verdict.FAIL);
			if (draft.round == null && draft.author == null)
			{
				if (draft.diagnosis == null)
					draft.diagnosis = new Diagnosis(List.of(targeted), 0, null, false);
				else
				{
					List<TrialInstance> pending = new ArrayList<>(draft.diagnosis.pending());
					pending.add(targeted);
					draft.diagnosis = new Diagnosis(pending, draft.diagnosis.passedReruns(),
						draft.diagnosis.rerun(), draft.diagnosis.reproduced());
				}
			}
		}
		resolveDiagnosis(draft);
	}

	/**
	 * Adopts an incumbent baseline outcome.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptBaseline(CampaignDraft draft, LogicalTrial trial)
	{
		addBaseline(draft, trial.key().category(), trial.verdict());
		if (trial.verdict() == Verdict.FAIL)
		{
			TrialInstance targeted = draft.generator().markTargeted(trial.instance());
			addRegression(draft, targeted, null);
		}
	}

	/**
	 * Adds an outcome to the incumbent baseline.
	 *
	 * @param draft the state
	 * @param category the category
	 * @param verdict the outcome
	 */
	private static void addBaseline(CampaignDraft draft, String category, Verdict verdict)
	{
		CategoryCounts counts = draft.baselineCounts.getOrDefault(category, CategoryCounts.EMPTY);
		draft.baselineCounts.put(category, counts.add(verdict));
	}

	/**
	 * Adds an instance to the regression set unless an instance with the same content is in it.
	 *
	 * @param draft the state
	 * @param targeted the targeted copy of the instance
	 * @param working the working candidate's verdict on it, or {@code null} if unknown
	 */
	private static void addRegression(CampaignDraft draft, TrialInstance targeted, Verdict working)
	{
		for (RegressionInstance regression : draft.regressionSet)
		{
			if (Promotion.isSameContent(regression.instance(), targeted))
				return;
		}
		draft.regressionSet.add(new RegressionInstance(targeted, working));
	}

	/**
	 * Records the working candidate's verdict on every regression instance with the same content.
	 *
	 * @param draft the state
	 * @param instance the instance that the working candidate ran
	 * @param verdict the verdict
	 */
	private static void recordWorkingVerdict(CampaignDraft draft, TrialInstance instance, Verdict verdict)
	{
		for (int i = 0; i < draft.regressionSet.size(); ++i)
		{
			RegressionInstance regression = draft.regressionSet.get(i);
			if (Promotion.isSameContent(regression.instance(), instance))
				draft.regressionSet.set(i, new RegressionInstance(regression.instance(), verdict));
		}
	}

	/**
	 * Applies the edit-or-continue rule to the diagnosis in progress, if any.
	 *
	 * @param draft the state
	 */
	private static void resolveDiagnosis(CampaignDraft draft)
	{
		if (draft.diagnosis == null)
			return;
		Development development = new Development(draft.working, draft.series, draft.developmentCounts,
			draft.developmentFailures, draft.diagnosis, null, null, draft.reviewPoints);
		switch (EditOrContinue.decide(development))
		{
			case RERUN ->
			{
				// The next rerun is scheduled
			}
			case EDIT ->
			{
				draft.diagnosis = null;
				draft.round = failingRound(draft.developmentCounts, draft.contract().cellIds(), null);
			}
			case CONTINUE -> draft.diagnosis = null;
		}
	}

	/**
	 * Returns a targeted-optimization round over the categories that have failures.
	 *
	 * @param counts the counts per category
	 * @param categories the categories, in contract order
	 * @param required a category that the round always includes, or {@code null}
	 * @return the round
	 */
	static Round failingRound(Map<String, CategoryCounts> counts, List<String> categories, String required)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		for (String category : categories)
		{
			CategoryCounts categoryCounts = counts.getOrDefault(category, CategoryCounts.EMPTY);
			if (categoryCounts.failures() > 0 || category.equals(required))
				attempts.put(category, 0);
		}
		return new Round(attempts);
	}

	/**
	 * Adopts a diagnostic rerun outcome. The outcome joins the diagnosis of the development failure that it reran, which
	 * the failure keeps whether the campaign edits or continues.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptDiagnostic(CampaignDraft draft, LogicalTrial trial)
	{
		Diagnosis diagnosis = draft.diagnosis;
		recordRerun(draft, diagnosis.pending().getFirst(), new DiagnosticRerun(trial.verdict(), trial.rationale()));
		if (trial.verdict() == Verdict.FAIL)
			draft.diagnosis = new Diagnosis(diagnosis.pending(), diagnosis.passedReruns(), null, true);
		else if (diagnosis.passedReruns() + 1 < Diagnosis.RERUNS)
		{
			draft.diagnosis = new Diagnosis(diagnosis.pending(), diagnosis.passedReruns() + 1, null,
				diagnosis.reproduced());
		}
		else
		{
			List<TrialInstance> pending = diagnosis.pending().subList(1, diagnosis.pending().size());
			draft.diagnosis = new Diagnosis(pending, 0, null, diagnosis.reproduced());
		}
		resolveDiagnosis(draft);
	}

	/**
	 * Adds a diagnostic rerun to the development failure whose instance it reran.
	 *
	 * @param draft the state
	 * @param instance the failing instance that the rerun ran
	 * @param rerun the rerun's outcome
	 * @throws IllegalStateException if no development failure has the instance
	 */
	private static void recordRerun(CampaignDraft draft, TrialInstance instance, DiagnosticRerun rerun)
	{
		for (int i = 0; i < draft.developmentFailures.size(); ++i)
		{
			Failure failure = draft.developmentFailures.get(i);
			if (failure.instance().equals(instance))
			{
				draft.developmentFailures.set(i, failure.withRerun(rerun));
				return;
			}
		}
		throw new IllegalStateException("No development failure has the rerun's instance: " + instance);
	}

	/**
	 * Adopts a promotion run outcome and applies the promotion decision.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptPromotion(CampaignDraft draft, LogicalTrial trial)
	{
		AuthorAttempt author = draft.author;
		List<PromotionRun> runs = new ArrayList<>(author.runs());
		for (int i = 0; i < runs.size(); ++i)
		{
			PromotionRun run = runs.get(i);
			if (trial.key().equals(run.trial()))
			{
				runs.set(i, new PromotionRun(run.role(), run.instance(), run.trial(), trial.verdict()));
				break;
			}
		}
		draft.author = new AuthorAttempt(author.number(), author.category(), author.snapshot(), runs);
		decidePromotion(draft);
	}

	/**
	 * Applies the promotion decision of the author attempt under validation.
	 *
	 * @param draft the state
	 */
	static void decidePromotion(CampaignDraft draft)
	{
		AuthorAttempt author = draft.author;
		switch (Promotion.decide(author, draft.regressionSet))
		{
			case PENDING ->
			{
				// More runs are needed
			}
			case PROMOTE -> promote(draft, author);
			case REJECT ->
			{
				draft.author = null;
				rejectAuthorAttempt(draft, author.category());
			}
		}
	}

	/**
	 * Makes an author attempt the working candidate.
	 *
	 * @param draft the state
	 * @param author the author attempt
	 */
	private static void promote(CampaignDraft draft, AuthorAttempt author)
	{
		for (int i = 0; i < draft.regressionSet.size(); ++i)
		{
			RegressionInstance regression = draft.regressionSet.get(i);
			draft.regressionSet.set(i, new RegressionInstance(regression.instance(),
				promotedVerdict(author, regression.instance())));
		}
		++draft.candidates;
		draft.startDevelopment(new Candidate(draft.candidates, author.snapshot()));
		draft.slowStart = SlowStart.INITIAL;
	}

	/**
	 * Returns the promoted candidate's verdict on a regression instance.
	 *
	 * @param author the promoted author attempt
	 * @param instance the instance
	 * @return the verdict of the attempt's run on the instance, or {@code null} if the attempt did not run it
	 */
	private static Verdict promotedVerdict(AuthorAttempt author, TrialInstance instance)
	{
		Verdict verdict = null;
		for (PromotionRun run : author.runs())
		{
			if (run.role() == PromotionRun.Role.WORKING_REFERENCE || run.verdict() == null ||
				!Promotion.isSameContent(run.instance(), instance))
			{
				continue;
			}
			if (run.verdict() == Verdict.FAIL)
				return Verdict.FAIL;
			verdict = Verdict.PASS;
		}
		return verdict;
	}

	/**
	 * Counts a rejected author attempt against its round category and reports a plateau once every category has
	 * used its attempts.
	 *
	 * @param draft the state
	 * @param category the round category
	 */
	static void rejectAuthorAttempt(CampaignDraft draft, String category)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>(draft.round.attempts());
		attempts.put(category, attempts.get(category) + 1);
		draft.round = new Round(attempts);
		if (Plateau.isReached(draft.round))
		{
			draft.outcome = Outcome.PLATEAU;
			draft.raise(new PendingDecision.PlateauReached());
		}
	}

	/**
	 * Adopts a formal outcome and applies the attempt's decision.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptFormal(CampaignDraft draft, LogicalTrial trial)
	{
		CertificationAttempt attempt = draft.attempt(trial.key().series());
		String category = trial.key().category();
		Map<String, CategoryCounts> counts = new LinkedHashMap<>(attempt.counts());
		counts.put(category, attempt.count(category).add(trial.verdict()));
		List<Failure> failures = new ArrayList<>(attempt.failures());
		if (trial.verdict() == Verdict.FAIL)
		{
			TrialInstance targeted = draft.generator().markTargeted(trial.instance());
			failures.add(new Failure(targeted, trial.rationale(), List.of()));
			Verdict working = null;
			if (attempt.candidate().equals(draft.working))
				working = Verdict.FAIL;
			addRegression(draft, targeted, working);
			draft.slowStart = draft.slowStart.afterFailure();
		}
		else
			draft.slowStart = draft.slowStart.afterPass();
		CertificationAttempt updated = new CertificationAttempt(attempt.index(), attempt.lineage(),
			attempt.candidate(), attempt.identity(), attempt.procedure(), counts, failures, attempt.formalStream(),
			null);
		draft.replaceAttempt(updated);
		applyDecision(draft, updated);
	}

	/**
	 * Applies a running attempt's decision.
	 *
	 * @param draft the state
	 * @param attempt the attempt
	 */
	private static void applyDecision(CampaignDraft draft, CertificationAttempt attempt)
	{
		DecisionTable.AttemptDecision decision = DecisionTable.attemptDecision(attempt);
		Outcome ending = switch (decision)
		{
			case UNDECIDED -> null;
			case CERTIFIED, CERTIFIED_PROVEN_WORSE -> Outcome.CERTIFIED;
			case BELOW_MINIMUM -> Outcome.BELOW_MINIMUM;
			case INCONCLUSIVE -> Outcome.INCONCLUSIVE;
		};
		if (ending == null)
			return;
		CertificationAttempt ended = attempt.end(ending);
		draft.replaceAttempt(ended);
		draft.outcome = ending;
		boolean qualifies = KeepImprovement.qualifies(ended, draft.lineageNumber());
		switch (DecisionTable.action(decision, qualifies))
		{
			case CONTINUE ->
			{
				// Unreachable: an undecided attempt returned above
			}
			case DELIVER ->
			{
				draft.header = draft.header.withLifecycle(Lifecycle.FINISHED);
				draft.delivery = new DeliveryRequest(ended.candidate(), ended.index(), Outcome.CERTIFIED);
			}
			case AWAIT_TRADE_OFF -> draft.raise(new PendingDecision.RegressionTradeOff(ended.index()));
			case OFFER_KEEP_IMPROVEMENT -> draft.raise(new PendingDecision.ImprovementProven(ended.index()));
			case RETURN_TO_DEVELOPMENT -> returnToDevelopment(draft, ended);
		}
	}

	/**
	 * Returns to development after an attempt ended without certification: the attempt's formal failures become
	 * diagnostic input for a targeted-optimization round over the categories that failed.
	 *
	 * @param draft the state
	 * @param attempt the ended attempt
	 */
	static void returnToDevelopment(CampaignDraft draft, CertificationAttempt attempt)
	{
		for (Failure failure : attempt.failures())
		{
			List<TrialInstance> developmentInstances = draft.developmentFailures.stream().map(Failure::instance).
				toList();
			if (!Promotion.containsContent(developmentInstances, failure.instance()))
				draft.developmentFailures.add(failure);
		}
		if (attempt.failures().isEmpty())
			return;
		draft.diagnosis = null;
		draft.round = failingRound(attempt.counts(), attempt.procedure().categories(), null);
	}

	/**
	 * Reaches the review checkpoint of the first category that is due, if the campaign waits for nothing else.
	 *
	 * @param draft the state
	 */
	static void checkReviewPoints(CampaignDraft draft)
	{
		if (draft.header.lifecycle() != Lifecycle.ACTIVE || !draft.isConfirmed() || !draft.pendingDecisions.isEmpty() ||
			draft.revisionRequested || draft.runningAttempt().isPresent() || draft.diagnosis != null ||
			draft.round != null || draft.author != null)
		{
			return;
		}
		Development development = new Development(draft.working, draft.series, draft.developmentCounts,
			draft.developmentFailures, null, null, null, draft.reviewPoints);
		Optional<String> due = ReviewCheckpoint.due(development, draft.contract());
		if (due.isPresent())
		{
			String category = due.get();
			draft.raise(new PendingDecision.CheckpointReached(category, draft.developmentCount(category).outcomes()));
		}
	}
}
