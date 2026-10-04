package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Records judged trials in campaign evidence and applies the policies that each adoption triggers.
 * <p>
 * Representative outcomes enter counts in assignment order within each adoption group: trials sharing a purpose,
 * series, snapshot and cell, where a cell tests one category with one model and reasoning effort. Completion order
 * therefore cannot choose which outcomes enter a decision. Diagnostic and promotion outcomes are adopted as soon
 * as they are judged and do not enter representative counts. A trial whose purpose the state no longer serves is
 * stale: its outcome is discarded.
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
	static boolean isStale(Draft draft, LogicalTrial trial)
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
			case PROMOTION ->
			{
				if (draft.isWorkingReference(trial))
					yield false;
				AuthorAttempt author = draft.findAuthor(key.series());
				yield author == null || author.runs().stream().noneMatch(run -> key.equals(run.trial()));
			}
		};
	}

	/**
	 * Indicates whether a formal trial's attempt is absent or ended, or its cell already has a decision.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @return {@code true} if the trial's outcome must be discarded
	 */
	private static boolean isStaleFormal(Draft draft, TrialKey key)
	{
		if (key.series() < 1 || key.series() > draft.attempts.size())
			return true;
		CertificationAttempt attempt = draft.attempt(key.series());
		return !attempt.isRunning() || DecisionTable.cellDecision(attempt.procedure().boundaries(),
			attempt.count(key.cell())) != DecisionTable.CellDecision.UNDECIDED;
	}

	/**
	 * Discards stale trials that are not running and adopts every judged trial whose turn has come, until nothing
	 * changes. Each adoption advances the campaign's evidence revision.
	 *
	 * @param draft the state
	 */
	static void settle(Draft draft)
	{
		boolean changed = true;
		while (changed)
		{
			changed = draft.trials.removeIf(trial -> trial.state() != TrialState.RUNNING && isStale(draft, trial));
			Optional<LogicalTrial> next = findNextAdoptable(draft);
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
	 * Finds the first judged trial whose outcome can be adopted, in trial-list order.
	 *
	 * @param draft the state
	 * @return the trial, or empty if none is judged or every judged trial waits for an earlier trial of its group
	 */
	private static Optional<LogicalTrial> findNextAdoptable(Draft draft)
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
	private static boolean waitsForEarlierTrial(Draft draft, LogicalTrial trial)
	{
		for (LogicalTrial other : draft.trials)
		{
			if (other.key().isSameGroup(trial.key()) && other.key().sequence() < trial.key().sequence())
				return true;
		}
		return false;
	}

	/**
	 * Adopts a judged trial. A failure of a candidate makes its cell active for the rest of the campaign
	 * ({@link CellTier}); a promotion run's failure does not, because an author attempt is not a candidate.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adopt(Draft draft, LogicalTrial trial)
	{
		if (trial.key().snapshot().equals(draft.working.snapshot()))
		{
			draft.instanceOutcomes = InstanceOutcomes.add(draft.instanceOutcomes, trial.instance(), trial.verdict());
			recordWorkingVerdict(draft, trial.instance(), trial.verdict());
		}
		if (trial.verdict() == Verdict.FAIL && trial.key().purpose() != Purpose.PROMOTION)
			draft.failedCells.add(trial.key().cell());
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
	 * Adopts a development outcome. Development widens to every cell once every active cell meets the readiness
	 * gate's count conditions ({@link CellTier}). A {@link Impact#BLOCKING blocking} failure that does not withdraw
	 * the working candidate fails fast; any other failure is diagnosed by reruns when no round or author attempt is in
	 * progress.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptDevelopment(Draft draft, LogicalTrial trial)
	{
		String cell = trial.key().cell();
		Verdict verdict = trial.verdict();
		draft.developmentCounts.put(cell, draft.developmentCount(cell).add(verdict));
		if (!draft.widened)
			draft.widened = CellTier.isActiveSetReady(draft.contract(), draft.failedCells, draft.developmentCounts);
		if (draft.working.isIncumbent())
			addBaseline(draft, cell, verdict);
		if (verdict == Verdict.PASS)
		{
			draft.slowStart = draft.slowStart.afterPass();
		}
		else
		{
			draft.slowStart = draft.slowStart.afterFailure();
			TrialInstance targeted = draft.generator().markTargeted(trial.instance());
			draft.developmentFailures.add(new Failure(targeted, trial.rationale(), trial.triage(), List.of(),
				trial.responses()));
			addRegression(draft, targeted, Verdict.FAIL);
			if (isBlocking(trial))
			{
				if (!revertIfWorse(draft, cell))
					failFast(draft);
				return;
			}
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
		if (!revertIfWorse(draft, cell))
			resolveDiagnosis(draft);
	}

	/**
	 * Indicates whether a trial is a failure whose triage found it blocking.
	 *
	 * @param trial the trial
	 * @return {@code true} if the trial's impact is {@link Impact#BLOCKING}
	 */
	private static boolean isBlocking(LogicalTrial trial)
	{
		return trial.triage() != null && trial.triage().impact() == Impact.BLOCKING;
	}

	/**
	 * Edits at once after a blocking failure. Discards the working candidate's development trials that have no adopted
	 * outcome, whatever their state, and ends any diagnosis together with its rerun, so the decision depends only on
	 * adopted outcomes. Starts a round over the failing cells unless a round or author attempt is in progress.
	 *
	 * @param draft the state after adopting the failure
	 */
	private static void failFast(Draft draft)
	{
		draft.trials.removeIf(other ->
		{
			TrialKey key = other.key();
			if (key.series() != draft.series)
				return false;
			return switch (key.purpose())
			{
				case DEVELOPMENT -> key.snapshot().equals(draft.working.snapshot());
				case DIAGNOSTIC -> true;
				case BASELINE, FORMAL, PROMOTION -> false;
			};
		});
		draft.diagnosis = null;
		if (draft.round == null && draft.author == null)
			draft.round = failingRound(draft.developmentCounts, draft.contract(), null);
	}

	/**
	 * Restores a promoted candidate's predecessor when its Wilson upper bound in an addressed cell is below the
	 * predecessor's observed representative pass rate. The withdrawn evidence stays available for diagnosis and the
	 * selected attempt becomes an unproductive round attempt before the plateau policy runs.
	 *
	 * @param draft the campaign state after adopting a representative development outcome
	 * @param cell the outcome's cell
	 * @return {@code true} if the predecessor was restored
	 */
	private static boolean revertIfWorse(Draft draft, String cell)
	{
		Predecessor predecessor = draft.predecessor;
		if (predecessor == null || !isAddressed(draft, predecessor.round(), cell))
			return false;
		CellCounts retained = predecessor.development().count(cell);
		if (retained.outcomes() == 0)
			return false;
		CellCounts current = draft.developmentCount(cell);
		// An all-pass sample has Wilson upper bound 1, which cannot be below an observed pass rate
		if (current.failures() == 0)
			return false;
		double upperBound = ExploratoryBounds.wilson(current.passes(), current.outcomes()).upper();
		double predecessorRate = (double) retained.passes() / retained.outcomes();
		if (upperBound >= predecessorRate)
			return false;

		draft.reverts.add(new Revert(draft.working, cell, upperBound, predecessorRate, snapshotDevelopment(draft)));
		Development restored = predecessor.development();
		draft.startDevelopment(restored.working());
		draft.developmentCounts = new LinkedHashMap<>(restored.counts());
		draft.developmentFailures = new ArrayList<>(restored.failures());
		draft.instanceOutcomes = restored.instanceOutcomes();
		draft.reviewPoints = new LinkedHashMap<>(restored.reviewPoints());
		draft.widened = restored.widened();
		restoreRegressionVerdicts(draft, predecessor.regressionSet());
		draft.round = predecessor.round();
		draft.slowStart = SlowStart.INITIAL;
		if (draft.round.weak())
		{
			if (Plateau.isReached(draft.round))
			{
				draft.outcome = Outcome.PLATEAU;
				draft.raise(new PendingDecision.PlateauReached());
			}
		}
		else
			rejectAuthorAttempt(draft, predecessor.cell());
		return true;
	}

	/**
	 * Indicates whether the promoting round addressed a cell, including all profiles of a weak round's category.
	 *
	 * @param draft the campaign state
	 * @param round the promoting round
	 * @param cell the outcome's cell
	 * @return {@code true} if the round addressed the cell
	 */
	private static boolean isAddressed(Draft draft, Round round, String cell)
	{
		if (round.attempts().containsKey(cell))
			return true;
		if (!round.weak())
			return false;
		String category = draft.contract().findCell(cell).orElseThrow().categoryId();
		return round.attempts().keySet().stream().anyMatch(addressed ->
			draft.contract().findCell(addressed).orElseThrow().categoryId().equals(category));
	}

	/**
	 * Captures a candidate's retained evidence without pending work, another predecessor or the shared revert history.
	 *
	 * @param draft the current candidate state
	 * @return the retained development evidence
	 */
	private static Development snapshotDevelopment(Draft draft)
	{
		return new Development(draft.working, draft.series, draft.developmentCounts, draft.developmentFailures,
			null, null, null, draft.reviewPoints, draft.widened, draft.instanceOutcomes, null, List.of());
	}

	/**
	 * Restores known predecessor verdicts while retaining newly discovered regression instances without a verdict.
	 *
	 * @param draft the campaign state
	 * @param retained the predecessor's regression verdicts
	 */
	private static void restoreRegressionVerdicts(Draft draft, List<RegressionInstance> retained)
	{
		for (int index = 0; index < draft.regressionSet.size(); ++index)
		{
			RegressionInstance regression = draft.regressionSet.get(index);
			Verdict verdict = null;
			for (RegressionInstance prior : retained)
			{
				if (Promotion.isSameContent(prior.instance(), regression.instance()))
				{
					verdict = prior.working();
					break;
				}
			}
			draft.regressionSet.set(index, new RegressionInstance(regression.instance(), verdict));
		}
	}

	/**
	 * Adopts an incumbent baseline outcome.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptBaseline(Draft draft, LogicalTrial trial)
	{
		addBaseline(draft, trial.key().cell(), trial.verdict());
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
	 * @param cell the cell identifier used to update baseline counts
	 * @param verdict the outcome
	 */
	private static void addBaseline(Draft draft, String cell, Verdict verdict)
	{
		CellCounts counts = draft.baselineCounts.getOrDefault(cell, CellCounts.EMPTY);
		draft.baselineCounts.put(cell, counts.add(verdict));
	}

	/**
	 * Adds an instance to the regression set unless an instance with the same content is in it.
	 *
	 * @param draft the state
	 * @param targeted the targeted copy of the instance
	 * @param working the working candidate's verdict on it, or {@code null} if unknown
	 */
	private static void addRegression(Draft draft, TrialInstance targeted, Verdict working)
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
	private static void recordWorkingVerdict(Draft draft, TrialInstance instance, Verdict verdict)
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
	private static void resolveDiagnosis(Draft draft)
	{
		if (draft.diagnosis == null)
			return;
		Development development = new Development(draft.working, draft.series, draft.developmentCounts,
			draft.developmentFailures, draft.diagnosis, null, null, draft.reviewPoints, draft.widened);
		switch (EditOrContinue.decide(development))
		{
			case RERUN ->
			{
				// The next rerun is scheduled
			}
			case EDIT ->
			{
				draft.diagnosis = null;
				draft.round = failingRound(draft.developmentCounts, draft.contract(), null);
			}
			case CONTINUE -> draft.diagnosis = null;
		}
	}

	/**
	 * Creates a round from currently failing cells and freezes its weak-candidate policy.
	 *
	 * @param counts the current development counts
	 * @param contract the confirmed contract
	 * @param required a cell to address even without failures, or null
	 * @return the round
	 */
	static Round failingRound(Map<String, CellCounts> counts, Contract contract, String required)
	{
		return failingRound(counts, counts, contract, required);
	}

	/**
	 * Creates a round addressing failures from either phase while testing weakness from development alone.
	 *
	 * @param failures the counts that identify failing cells
	 * @param development the working candidate's development outcomes
	 * @param contract the confirmed contract
	 * @param required a required cell, or null
	 * @return the frozen round
	 */
	private static Round failingRound(Map<String, CellCounts> failures, Map<String, CellCounts> development,
		Contract contract, String required)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		for (String cell : contract.cellIds())
		{
			if (failures.getOrDefault(cell, CellCounts.EMPTY).failures() > 0 || cell.equals(required))
				attempts.put(cell, 0);
		}
		boolean weak = AttemptSelection.isWeak(development, contract, List.copyOf(attempts.keySet()));
		if (weak)
		{
			Set<String> addressed = new HashSet<>();
			attempts.keySet().removeIf(cell -> !addressed.add(contract.findCell(cell).orElseThrow().categoryId()));
		}
		return new Round(attempts, weak, Map.of(), List.of());
	}

	/**
	 * Records a diagnostic rerun's verdict and rationale on the development failure it reran, then applies the
	 * edit-or-continue decision. The failure retains that rerun evidence after the decision.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptDiagnostic(Draft draft, LogicalTrial trial)
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
	private static void recordRerun(Draft draft, TrialInstance instance, DiagnosticRerun rerun)
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
	private static void adoptPromotion(Draft draft, LogicalTrial trial)
	{
		AuthorAttempt author = draft.findAuthor(trial.key().series());
		if (author == null)
		{
			if (draft.author != null)
				decidePromotion(draft);
			return;
		}
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
		draft.updateAuthor(new AuthorAttempt(author.number(), author.cell(), author.snapshot(), runs,
			author.minimality()));
		if (draft.round.weak() && draft.author == null)
			decideWeakRound(draft);
		else
			decidePromotion(draft);
	}

	/**
	 * Applies the promotion decision of the author attempt under validation.
	 *
	 * @param draft the state
	 */
	static void decidePromotion(Draft draft)
	{
		if (Promotion.decide(draft.author, draft.regressionSet, draft.instanceOutcomes) == Promotion.Decision.PENDING)
			draft.author = Promotion.extend(draft.author, draft.instanceOutcomes);
		AuthorAttempt author = draft.author;
		switch (Promotion.decide(author, draft.regressionSet, draft.instanceOutcomes))
		{
			case PENDING ->
			{
				// More runs are needed
			}
			case PROMOTE ->
			{
				if (author.minimality().acceptedRuns().isEmpty())
					retainAttempt(draft, author, true, true);
			}
			case REJECT ->
			{
				if (author.minimality().testing() != null)
					return;
				retainAttempt(draft, author, false, false);
				draft.author = null;
				rejectAuthorAttempt(draft, author.cell());
			}
		}
	}

	/**
	 * Retains settled contenders and selects only after every addressed category received all three attempts.
	 *
	 * @param draft the campaign state
	 */
	static void decideWeakRound(Draft draft)
	{
		for (AuthorAttempt attempt : draft.round.validationAttempts())
		{
			if (Promotion.decide(attempt, draft.regressionSet, draft.instanceOutcomes) == Promotion.Decision.PENDING)
				draft.round = draft.round.withAttempt(Promotion.extend(attempt, draft.instanceOutcomes));
		}
		Round round = draft.round;
		for (AuthorAttempt attempt : round.validationAttempts())
		{
			if (attempt.runs().stream().allMatch(run -> run.verdict() != null))
			{
				boolean accepted = !attempt.snapshot().equals(draft.working.snapshot()) &&
					Promotion.decide(attempt, draft.regressionSet, draft.instanceOutcomes) == Promotion.Decision.PROMOTE;
				retainAttempt(draft, attempt, accepted, false);
			}
		}
		if (!Plateau.isReached(round))
			return;
		Optional<AuthorAttempt> winner = AttemptSelection.select(round.validationAttempts(),
			draft.working.snapshot(), draft.regressionSet, draft.instanceOutcomes);
		if (winner.isPresent())
		{
			draft.author = winner.get();
			retainAttempt(draft, draft.author, true, true);
			return;
		}
		draft.outcome = Outcome.PLATEAU;
		draft.raise(new PendingDecision.PlateauReached());
	}

	/**
	 * Saves an original attempt's result without allowing minimality to replace its measured evidence.
	 *
	 * @param draft the campaign state
	 * @param attempt the original attempt
	 * @param accepted whether the promotion rule accepted it
	 * @param winner whether it was selected
	 */
	static void retainAttempt(Draft draft, AuthorAttempt attempt, boolean accepted, boolean winner)
	{
		List<AttemptResult> results = new ArrayList<>(draft.optimization.attempts());
		AttemptResult result = new AttemptResult(draft.round.number(), draft.working.snapshot(), attempt.number(),
			attempt.cell(), attempt.snapshot(), attempt.runs(), AttemptSelection.changedLines(attempt), accepted,
			winner);
		int index = -1;
		for (int i = 0; i < results.size(); ++i)
		{
			if (results.get(i).number() == attempt.number())
				index = i;
		}
		if (index >= 0)
		{
			if (results.get(index).winner())
				return;
			results.set(index, result);
		}
		else
			results.add(result);
		results.sort(Comparator.comparingInt(AttemptResult::number));
		draft.optimization = new OptimizationEvidence(draft.optimization.authors(), draft.optimization.removed(),
			draft.optimization.promptSizes(), draft.optimization.trialInputs(), results, draft.optimization.tokenUsage());
	}

	/**
	 * Makes an author attempt the working candidate.
	 *
	 * @param draft the state
	 * @param author the author attempt
	 */
	static void promote(Draft draft, AuthorAttempt author)
	{
		Predecessor predecessor = new Predecessor(snapshotDevelopment(draft), draft.round, author.cell(),
			draft.regressionSet);
		for (int i = 0; i < draft.regressionSet.size(); ++i)
		{
			RegressionInstance regression = draft.regressionSet.get(i);
			draft.regressionSet.set(i, new RegressionInstance(regression.instance(),
				promotedVerdict(author, regression.instance())));
		}
		++draft.candidates;
		draft.startDevelopment(new Candidate(draft.candidates, author.snapshot()));
		draft.predecessor = predecessor;
		for (PromotionRun run : author.runs())
		{
			if (run.role() != PromotionRun.Role.WORKING_REFERENCE && run.verdict() != null)
				draft.instanceOutcomes = InstanceOutcomes.add(draft.instanceOutcomes, run.instance(), run.verdict());
		}
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
	 * @param cell the round category
	 */
	static void rejectAuthorAttempt(Draft draft, String cell)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>(draft.round.attempts());
		attempts.put(cell, attempts.get(cell) + 1);
		draft.round = new Round(attempts, draft.round.weak(), draft.round.reservations(),
			draft.round.validationAttempts());
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
	private static void adoptFormal(Draft draft, LogicalTrial trial)
	{
		CertificationAttempt attempt = draft.attempt(trial.key().series());
		String cell = trial.key().cell();
		Map<String, CellCounts> counts = new LinkedHashMap<>(attempt.counts());
		counts.put(cell, attempt.count(cell).add(trial.verdict()));
		List<Failure> failures = new ArrayList<>(attempt.failures());
		if (trial.verdict() == Verdict.FAIL)
		{
			TrialInstance targeted = draft.generator().markTargeted(trial.instance());
			failures.add(new Failure(targeted, trial.rationale(), null, List.of(), trial.responses()));
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
	private static void applyDecision(Draft draft, CertificationAttempt attempt)
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
	 * Adds an uncertified attempt's formal failures to development evidence, excluding duplicate instance content.
	 * If that attempt has failures, replaces the current diagnosis and optimization round with a new round for its
	 * failed cells. An attempt without failures leaves the diagnosis and round unchanged.
	 *
	 * @param draft the state
	 * @param attempt the ended attempt
	 */
	static void returnToDevelopment(Draft draft, CertificationAttempt attempt)
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
		draft.round = failingRound(attempt.counts(), draft.developmentCounts, draft.contract(), null);
	}

	/**
	 * Raises a review decision for the first cell whose development review point is due, if the active, confirmed
	 * campaign has no pending decision, revision, certification attempt, diagnosis, optimization round or author attempt.
	 *
	 * @param draft the state
	 */
	static void checkReviewPoints(Draft draft)
	{
		if (draft.header.lifecycle() != Lifecycle.ACTIVE || !draft.isConfirmed() || !draft.pendingDecisions.isEmpty() ||
			draft.revisionRequested || draft.runningAttempt().isPresent() || draft.diagnosis != null ||
			draft.round != null || draft.author != null)
		{
			return;
		}
		Development development = new Development(draft.working, draft.series, draft.developmentCounts,
			draft.developmentFailures, null, null, null, draft.reviewPoints, draft.widened);
		Optional<String> due = ReviewCheckpoint.due(development, draft.contract());
		if (due.isPresent())
		{
			String cell = due.get();
			draft.raise(new PendingDecision.CheckpointReached(cell, draft.developmentCount(cell).outcomes()));
		}
	}
}
