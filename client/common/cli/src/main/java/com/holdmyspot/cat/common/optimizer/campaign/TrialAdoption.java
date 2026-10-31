package com.holdmyspot.cat.common.optimizer.campaign;

import com.holdmyspot.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.secrets.TrialAdoptionAccess;
import com.holdmyspot.cat.common.secrets.TrialAdoptionSecrets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Records judged trials in campaign evidence and applies the policies that each adoption triggers.
 * <p>
 * Representative and reproduction outcomes enter counts in assignment order within each adoption group: trials
 * sharing a purpose, series, snapshot and cell, where a cell tests one prompt test with one model and reasoning effort.
 * Completion order therefore cannot choose which outcomes enter a decision. Diagnostic and promotion outcomes are
 * adopted as soon as they are judged and do not enter representative counts. A trial whose purpose the state no
 * longer serves is stale: its outcome is discarded.
 */
public final class TrialAdoption
{
	static
	{
		TrialAdoptionSecrets.setTrialAdoptionAccess(new TrialAdoptionAccess()
		{
			@Override
			public Round failingRound(Map<String, CellCounts> counts, List<Cell> developmentCells, String required)
			{
				return TrialAdoption.failingRound(counts, developmentCells, required);
			}
		});
	}

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
			case CONTROL -> isStaleReference(draft, key, Reference.CONTROL);
			case INCUMBENT -> isStaleReference(draft, key, Reference.INCUMBENT);
			case REPRODUCTION -> isStaleReproduction(draft, trial);
			case DIAGNOSTIC -> key.series() != draft.series || draft.diagnosis == null ||
				!key.equals(draft.diagnosis.rerun());
			case PROMOTION ->
			{
				if (draft.isWorkingReference(trial))
					yield false;
				PromptAuthorAttempt promptAuthor = draft.findPromptAuthor(key.series());
				if (promptAuthor == null)
					yield findRetainedRun(draft, key) < 0;
				yield promptAuthor.runs().stream().noneMatch(run -> key.equals(run.trial()));
			}
		};
	}

	/**
	 * Indicates whether a trial is a queued promotion run of a rejected attempt. The decision cannot change, so the
	 * run is cancelled before it starts; a run that already started finishes, and its outcome is recorded once it is
	 * judged.
	 *
	 * @param draft the state
	 * @param trial the trial
	 * @return {@code true} if the trial must not start
	 */
	private static boolean isCancelled(Draft draft, LogicalTrial trial)
	{
		TrialKey key = trial.key();
		if (key.purpose() != Purpose.PROMOTION || trial.state() != TrialState.ASSIGNED || draft.isWorkingReference(trial))
			return false;
		PromptAuthorAttempt promptAuthor = draft.findPromptAuthor(key.series());
		if (promptAuthor == null)
			return findRetainedRun(draft, key) >= 0;
		return decision(draft, promptAuthor) == Promotion.Decision.REJECT;
	}

	/**
	 * Finds a retained attempt result's unjudged run of a trial, which a rejected sequential attempt left running.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @return the index of the result in the retained attempts, or {@code -1} if none has such a run
	 */
	private static int findRetainedRun(Draft draft, TrialKey key)
	{
		List<AttemptResult> results = draft.optimization.attempts();
		for (int i = 0; i < results.size(); ++i)
		{
			AttemptResult result = results.get(i);
			if (result.number() == key.series() &&
				result.runs().stream().anyMatch(run -> key.equals(run.trial()) && run.verdict() == null))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Returns the promotion decision on an attempt, keeping the recorded decision of a retained original attempt.
	 *
	 * @param draft the state
	 * @param attempt the attempt
	 * @return the decision
	 */
	static Promotion.Decision decision(Draft draft, PromptAuthorAttempt attempt)
	{
		return Promotion.decide(attempt, draft.working.snapshot(), draft.regressionSet, draft.testCaseOutcomes,
			draft.optimization.attempts());
	}

	/**
	 * Plans the runs that an attempt's adopted outcomes make due, sharing the working candidate's reference runs that
	 * another trial or weak contender already owns.
	 *
	 * @param draft the state
	 * @param attempt the attempt
	 * @return the attempt with the due runs appended
	 */
	static PromptAuthorAttempt refresh(Draft draft, PromptAuthorAttempt attempt)
	{
		List<DrawnTestCase> references = new ArrayList<>();
		for (LogicalTrial trial : draft.trials)
		{
			if (draft.isWorkingReference(trial))
				references.add(trial.testCase());
		}
		if (draft.round.weak())
		{
			for (PromptAuthorAttempt other : draft.round.validationAttempts())
			{
				if (other.number() == attempt.number())
					continue;
				for (PromotionRun run : other.runs())
				{
					if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
						references.add(run.testCase());
				}
			}
		}
		return Promotion.extend(attempt, draft.regressionSet, draft.testCaseOutcomes, references);
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
		return !attempt.isRunning() ||
			DecisionTable.cellDecision(attempt, key.cell()) != DecisionTable.CellDecision.UNDECIDED;
	}

	/**
	 * Indicates whether a control or incumbent trial's attempt is absent or ended, the trial ran another snapshot than
	 * the attempt's control or the incumbent, or its cell already has a decision.
	 *
	 * @param draft the state
	 * @param key the trial's key
	 * @param reference the kind of reference trial
	 * @return {@code true} if the trial's outcome must be discarded
	 */
	private static boolean isStaleReference(Draft draft, TrialKey key, Reference reference)
	{
		if (key.series() < 1 || key.series() > draft.attempts.size())
			return true;
		CertificationAttempt attempt = draft.attempt(key.series());
		Sha256 snapshot = switch (reference)
		{
			case CONTROL -> attempt.control();
			case INCUMBENT -> draft.incumbent.snapshot();
		};
		return !attempt.isRunning() || !key.snapshot().equals(snapshot) ||
			DecisionTable.cellDecision(attempt, key.cell()) != DecisionTable.CellDecision.UNDECIDED;
	}

	/**
	 * Indicates whether a reproduction trial's group no longer runs the trial's round: no attempt is reproducing, the
	 * group is not reproducing, its current round runs in another cell or lacks the trial's test case, or the
	 * working candidate changed.
	 *
	 * @param draft the state
	 * @param trial the trial
	 * @return {@code true} if the trial's outcome must be discarded
	 */
	private static boolean isStaleReproduction(Draft draft, LogicalTrial trial)
	{
		TrialKey key = trial.key();
		Optional<CertificationAttempt> reproducing = draft.reproducing();
		if (reproducing.isEmpty() || !key.snapshot().equals(draft.working.snapshot()))
			return true;
		CertificationAttempt attempt = reproducing.get();
		if (key.series() < 1 || key.series() > attempt.groups().size())
			return true;
		FailureGroup group = attempt.group(key.series());
		ReproductionRound round = group.round();
		if (group.status() != FailureGroup.Status.REPRODUCING || !round.cellId().equals(key.cell()))
			return true;
		PromptTest test = draft.contract().findTest(group.testId()).orElseThrow();
		return round.indexOf(test, trial.testCase()) < 0;
	}

	/**
	 * Applies the running attempt's decision, discards stale trials that are not running and adopts every judged trial
	 * whose turn has come, until nothing changes. Each adoption advances the campaign's evidence revision.
	 * <p>
	 * Applying the decision before any adoption ends an attempt that an earlier rule left running although its
	 * adopted counts already decide it, such as a cell that can no longer certify within its cap, at the first event
	 * after it is loaded.
	 *
	 * @param draft the state
	 */
	static void settle(Draft draft)
	{
		if (draft.header.lifecycle() == Lifecycle.ACTIVE)
			draft.runningAttempt().ifPresent(attempt -> applyDecision(draft, attempt));
		boolean changed = true;
		while (changed)
		{
			changed = draft.trials.removeIf(trial -> trial.state() != TrialState.RUNNING &&
				(isStale(draft, trial) || isCancelled(draft, trial)));
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
		reviewPromotion(draft);
	}

	/**
	 * Applies the promotion decision of the attempts under validation, so that runs that became due without a new
	 * outcome are planned, such as paired reruns of an attempt that a campaign saved under an earlier promotion rule
	 * left undecided. The decision is idempotent, so repeating it changes nothing.
	 *
	 * @param draft the state
	 */
	private static void reviewPromotion(Draft draft)
	{
		if (draft.revisionRequested || draft.round == null)
			return;
		if (draft.promptAuthor != null)
			decidePromotion(draft);
		else if (draft.round.weak() && !draft.round.validationAttempts().isEmpty())
			decideWeakRound(draft);
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
			if (!trial.key().purpose().adoptsInOrder() || !waitsForEarlierTrial(draft, trial))
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
	 * Adopts a judged trial.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adopt(Draft draft, LogicalTrial trial)
	{
		Purpose purpose = trial.key().purpose();
		if (purpose != Purpose.REPRODUCTION && purpose != Purpose.CONTROL && purpose != Purpose.INCUMBENT &&
			trial.key().snapshot().equals(draft.working.snapshot()))
		{
			draft.testCaseOutcomes = TestCaseOutcomes.add(draft.testCaseOutcomes, trial.testCase(), trial.verdict());
			// A paired rerun extends one group's check; the regression set keeps the outcome that the check started from
			if (!isWorkingRerun(draft, trial))
				recordWorkingVerdict(draft, trial.testCase(), trial.verdict());
		}
		switch (purpose)
		{
			case DEVELOPMENT -> adoptDevelopment(draft, trial);
			case BASELINE -> adoptBaseline(draft, trial);
			case FORMAL -> adoptFormal(draft, trial);
			case CONTROL -> adoptReference(draft, trial, Reference.CONTROL);
			case INCUMBENT -> adoptReference(draft, trial, Reference.INCUMBENT);
			case DIAGNOSTIC -> adoptDiagnostic(draft, trial);
			case PROMOTION -> adoptPromotion(draft, trial);
			case REPRODUCTION -> adoptReproduction(draft, trial);
		}
	}

	/**
	 * Adopts a reproduction outcome into its failure group's current round. A failure counts toward the group only
	 * when it failed the group's assertion through the group's behavior. Once the group reproduces, its round's
	 * test cases join development and the working candidate's development counts restart, because the development
	 * distribution changed. Once no group is reproducing and some group did not reproduce, the user decides how to
	 * continue.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptReproduction(Draft draft, LogicalTrial trial)
	{
		int number = trial.key().series();
		CertificationAttempt attempt = draft.reproducing().orElseThrow();
		FailureGroup group = attempt.group(number);
		Contract contract = draft.contract();
		int index = group.round().indexOf(contract.findTest(group.testId()).orElseThrow(), trial.testCase());
		boolean matched = trial.verdict() == Verdict.FAIL && trial.failedAssertions().contains(group.failed());
		if (trial.verdict() == Verdict.PASS)
			draft.slowStart = draft.slowStart.afterPass();
		else
			draft.slowStart = draft.slowStart.afterFailure();
		List<String> stressCellIds = CellTier.stressCells(contract, draft.header.harness(), group.cellId()).stream().
			map(Cell::id).toList();
		FailureGroup updated = group.adopt(index, matched, stressCellIds);
		CertificationAttempt next = attempt.withGroup(number, updated);
		draft.replaceAttempt(next);

		if (updated.status() == FailureGroup.Status.REPRODUCED)
		{
			draft.added = draft.added.plus(updated.round().added());
			draft.restartDevelopmentCounts();
		}
		List<FailureGroup.Status> statuses = next.groups().stream().map(FailureGroup::status).toList();
		if (!statuses.contains(FailureGroup.Status.REPRODUCING) &&
			statuses.contains(FailureGroup.Status.NOT_REPRODUCED))
		{
			draft.raise(new PendingDecision.FailuresNotReproduced(attempt.index()));
		}
	}

	/**
	 * Adopts a development outcome. A {@link Impact#BLOCKING blocking} failure that does not withdraw
	 * the working candidate fails fast; any other failure is diagnosed by reruns when no round or prompt author attempt
	 * is in progress.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptDevelopment(Draft draft, LogicalTrial trial)
	{
		String cell = trial.key().cell();
		Verdict verdict = trial.verdict();
		draft.developmentCounts.put(cell, draft.developmentCount(cell).add(verdict));
		if (verdict == Verdict.PASS)
		{
			draft.slowStart = draft.slowStart.afterPass();
		}
		else
		{
			draft.slowStart = draft.slowStart.afterFailure();
			DrawnTestCase targeted = draft.generator().markTargeted(trial.testCase());
			draft.developmentFailures.add(new Failure(targeted, trial.rationale(), trial.failedAssertions(),
				trial.triage(), List.of(), trial.responses(), trial.files()));
			addRegression(draft, targeted, Verdict.FAIL);
			if (isBlocking(trial))
			{
				if (!revertIfWorse(draft, cell))
					failFast(draft);
				return;
			}
			if (draft.round == null && draft.promptAuthor == null)
			{
				if (draft.diagnosis == null)
					draft.diagnosis = new Diagnosis(List.of(targeted), 0, null, false);
				else if (Promotion.containsTestCase(draft.diagnosis.pending(), targeted))
				{
					// Another trial of a test case awaiting reruns failed, so the test case fails again
					draft.diagnosis = new Diagnosis(draft.diagnosis.pending(), draft.diagnosis.passedReruns(),
						draft.diagnosis.rerun(), true);
				}
				else
				{
					List<DrawnTestCase> pending = new ArrayList<>(draft.diagnosis.pending());
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
	 * adopted outcomes. Starts a round over the failing cells unless a round or prompt author attempt is in progress.
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
				case BASELINE, FORMAL, CONTROL, INCUMBENT, PROMOTION, REPRODUCTION -> false;
			};
		});
		draft.diagnosis = null;
		if (draft.round == null && draft.promptAuthor == null)
			draft.round = failingRound(draft.developmentCounts, draft.developmentCells(), null);
	}

	/**
	 * Restores a promoted candidate's predecessor when its development 95% Wilson upper bound in an addressed cell is
	 * below the predecessor's development 95% Wilson lower bound there, so that the two intervals do not overlap. The
	 * withdrawn evidence stays available for diagnosis and the selected attempt becomes an unproductive round attempt
	 * before a complete round ends.
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
		// An all-pass sample has Wilson upper bound 1, which cannot be below a lower bound
		if (current.failures() == 0)
			return false;
		double upperBound = ExploratoryBounds.wilson(current.passes(), current.outcomes()).upper();
		double predecessorLowerBound = ExploratoryBounds.wilson(retained.passes(), retained.outcomes()).lower();
		if (upperBound >= predecessorLowerBound)
			return false;

		draft.reverts.add(new Revert(draft.working, cell, upperBound, predecessorLowerBound,
			snapshotDevelopment(draft)));
		Development restored = predecessor.development();
		draft.startDevelopment(restored.working());
		draft.developmentCounts = new LinkedHashMap<>(restored.counts());
		draft.developmentFailures = new ArrayList<>(restored.failures());
		draft.testCaseOutcomes = restored.testCaseOutcomes();
		draft.reviewPoints = new LinkedHashMap<>(restored.reviewPoints());
		restoreRegressionVerdicts(draft, predecessor.regressionSet());
		draft.round = predecessor.round();
		draft.slowStart = SlowStart.INITIAL;
		if (draft.round.weak())
		{
			if (isWeakRoundDecided(draft))
				endUnproductiveRound(draft);
		}
		else
			rejectPromptAuthorAttempt(draft, predecessor.cell());
		return true;
	}

	/**
	 * Indicates whether the promoting round addressed a cell, including all profiles of a weak round's test.
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
		String test = draft.contract().findCell(cell).orElseThrow().testId();
		return round.attempts().keySet().stream().anyMatch(addressed ->
			draft.contract().findCell(addressed).orElseThrow().testId().equals(test));
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
			null, null, null, draft.reviewPoints, draft.testCaseOutcomes, null, List.of());
	}

	/**
	 * Restores the predecessor's known verdicts, and whether each failing verdict was settled, while retaining newly
	 * discovered regression test cases without a verdict.
	 *
	 * @param draft the campaign state
	 * @param retained the predecessor's regression verdicts
	 */
	private static void restoreRegressionVerdicts(Draft draft, List<RegressionTestCase> retained)
	{
		for (int index = 0; index < draft.regressionSet.size(); ++index)
		{
			RegressionTestCase regression = draft.regressionSet.get(index);
			RegressionTestCase restored = new RegressionTestCase(regression.testCase(), null, false);
			for (RegressionTestCase prior : retained)
			{
				if (Promotion.isSameTestCase(prior.testCase(), regression.testCase()))
				{
					restored = new RegressionTestCase(regression.testCase(), prior.working(), prior.settled());
					break;
				}
			}
			draft.regressionSet.set(index, restored);
		}
	}

	/**
	 * Adopts an incumbent baseline outcome. Its held-out test case never enters development evidence or the
	 * regression set.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptBaseline(Draft draft, LogicalTrial trial)
	{
		String cell = trial.key().cell();
		CellCounts counts = draft.baselineCounts.getOrDefault(cell, CellCounts.EMPTY);
		draft.baselineCounts.put(cell, counts.add(trial.verdict()));
	}

	/**
	 * Adds a test case to the regression set unless the set already holds it.
	 *
	 * @param draft the state
	 * @param targeted the targeted copy of the test case
	 * @param working the working candidate's verdict on it, or {@code null} if unknown
	 */
	private static void addRegression(Draft draft, DrawnTestCase targeted, Verdict working)
	{
		for (RegressionTestCase regression : draft.regressionSet)
		{
			if (Promotion.isSameTestCase(regression.testCase(), targeted))
				return;
		}
		draft.regressionSet.add(new RegressionTestCase(targeted, working, false));
	}

	/**
	 * Records the working candidate's verdict on the regression set's entry for the same test case. The recorded verdict
	 * replaces a settled failure and is not settled.
	 *
	 * @param draft the state
	 * @param testCase the test case that the working candidate ran
	 * @param verdict the verdict
	 */
	private static void recordWorkingVerdict(Draft draft, DrawnTestCase testCase, Verdict verdict)
	{
		for (int i = 0; i < draft.regressionSet.size(); ++i)
		{
			RegressionTestCase regression = draft.regressionSet.get(i);
			if (Promotion.isSameTestCase(regression.testCase(), testCase))
				draft.regressionSet.set(i, new RegressionTestCase(regression.testCase(), verdict, false));
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
			draft.developmentFailures, draft.diagnosis, null, null, draft.reviewPoints);
		switch (EditOrContinue.decide(development, draft.contract().minimumPassRate()))
		{
			case RERUN ->
			{
				// The next rerun is scheduled
			}
			case EDIT ->
			{
				draft.diagnosis = null;
				draft.round = failingRound(draft.developmentCounts, draft.developmentCells(), null);
			}
			case CONTINUE -> draft.diagnosis = null;
		}
	}

	/**
	 * Creates a round from the currently failing development cells and freezes its weak-candidate policy.
	 *
	 * @param counts the current development counts
	 * @param developmentCells the development cells, in contract order
	 * @param required a development cell to address even without failures, or null
	 * @return the round
	 */
	static Round failingRound(Map<String, CellCounts> counts, List<Cell> developmentCells, String required)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		Map<String, String> tests = new LinkedHashMap<>();
		for (Cell cell : developmentCells)
		{
			String id = cell.id();
			tests.put(id, cell.testId());
			if (counts.getOrDefault(id, CellCounts.EMPTY).failures() > 0 || id.equals(required))
				attempts.put(id, 0);
		}
		boolean weak = AttemptSelection.isWeak(counts, developmentCells, List.copyOf(attempts.keySet()));
		if (weak)
		{
			Set<String> addressed = new HashSet<>();
			attempts.keySet().removeIf(cell -> !addressed.add(tests.get(cell)));
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
			List<DrawnTestCase> pending = diagnosis.pending().subList(1, diagnosis.pending().size());
			draft.diagnosis = new Diagnosis(pending, 0, null, diagnosis.reproduced());
		}
		resolveDiagnosis(draft);
	}

	/**
	 * Adds a diagnostic rerun to the development failure whose test case it reran.
	 *
	 * @param draft the state
	 * @param testCase the failing test case that the rerun ran
	 * @param rerun the rerun's outcome
	 * @throws IllegalStateException if no development failure has the test case
	 */
	private static void recordRerun(Draft draft, DrawnTestCase testCase, DiagnosticRerun rerun)
	{
		for (int i = 0; i < draft.developmentFailures.size(); ++i)
		{
			Failure failure = draft.developmentFailures.get(i);
			if (failure.testCase().equals(testCase))
			{
				draft.developmentFailures.set(i, failure.withRerun(rerun));
				return;
			}
		}
		throw new IllegalStateException("No development failure has the rerun's test case: " + testCase);
	}

	/**
	 * Indicates whether a trial is the working candidate's paired rerun.
	 *
	 * @param draft the state
	 * @param trial the trial
	 * @return {@code true} if the trial's owner planned it as a paired rerun of the working candidate
	 */
	private static boolean isWorkingRerun(Draft draft, LogicalTrial trial)
	{
		TrialKey key = trial.key();
		if (key.purpose() != Purpose.PROMOTION)
			return false;
		List<PromotionRun> runs;
		PromptAuthorAttempt owner = draft.findPromptAuthor(key.series());
		if (owner != null)
			runs = owner.runs();
		else
		{
			int retained = findRetainedRun(draft, key);
			if (retained < 0)
				return false;
			runs = draft.optimization.attempts().get(retained).runs();
		}
		return runs.stream().anyMatch(run -> run.role() == PromotionRun.Role.WORKING_RERUN && key.equals(run.trial()));
	}

	/**
	 * Adopts a promotion run outcome and applies the promotion decision.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptPromotion(Draft draft, LogicalTrial trial)
	{
		PromptAuthorAttempt promptAuthor = draft.findPromptAuthor(trial.key().series());
		if (promptAuthor == null)
		{
			int retained = findRetainedRun(draft, trial.key());
			if (retained >= 0)
				recordRetainedOutcome(draft, retained, trial);
			if (draft.promptAuthor != null)
				decidePromotion(draft);
			return;
		}
		List<PromotionRun> runs = new ArrayList<>(promptAuthor.runs());
		for (int i = 0; i < runs.size(); ++i)
		{
			PromotionRun run = runs.get(i);
			if (trial.key().equals(run.trial()))
			{
				runs.set(i, new PromotionRun(run.role(), run.testCase(), run.trial(), trial.verdict()));
				break;
			}
		}
		draft.updatePromptAuthor(new PromptAuthorAttempt(promptAuthor.number(), promptAuthor.cell(),
			promptAuthor.snapshot(), runs, promptAuthor.minimality()));
		if (draft.round.weak() && draft.promptAuthor == null)
			decideWeakRound(draft);
		else
			decidePromotion(draft);
	}

	/**
	 * Records the outcome of a run that was running when its sequential attempt was rejected. The outcome is shown
	 * with the attempt's result but cannot change the recorded decision.
	 *
	 * @param draft the state
	 * @param index the index of the retained result
	 * @param trial the judged trial
	 */
	private static void recordRetainedOutcome(Draft draft, int index, LogicalTrial trial)
	{
		List<AttemptResult> results = new ArrayList<>(draft.optimization.attempts());
		AttemptResult result = results.get(index);
		List<PromotionRun> runs = new ArrayList<>(result.runs());
		for (int i = 0; i < runs.size(); ++i)
		{
			PromotionRun run = runs.get(i);
			if (trial.key().equals(run.trial()))
				runs.set(i, new PromotionRun(run.role(), run.testCase(), run.trial(), trial.verdict()));
		}
		results.set(index, new AttemptResult(result.round(), result.working(), result.number(), result.cell(),
			result.snapshot(), runs, result.changedLines(), result.accepted(), result.winner()));
		draft.optimization = new OptimizationEvidence(draft.optimization.promptAuthors(), draft.optimization.removed(),
			draft.optimization.promptSizes(), draft.optimization.trialInputs(), results, draft.optimization.tokenUsage(),
			draft.optimization.approaches(), draft.optimization.commands());
	}

	/**
	 * Applies the promotion decision of the prompt author attempt under validation.
	 *
	 * @param draft the state
	 */
	static void decidePromotion(Draft draft)
	{
		if (decision(draft, draft.promptAuthor) == Promotion.Decision.PENDING)
			draft.promptAuthor = refresh(draft, draft.promptAuthor);
		PromptAuthorAttempt promptAuthor = draft.promptAuthor;
		switch (decision(draft, promptAuthor))
		{
			case PENDING ->
			{
				// More runs are needed
			}
			case PROMOTE ->
			{
				if (promptAuthor.minimality().acceptedRuns().isEmpty())
					retainAttempt(draft, promptAuthor, true, true);
			}
			case REJECT ->
			{
				if (promptAuthor.minimality().testing() != null)
					return;
				retainAttempt(draft, promptAuthor, false, false);
				draft.promptAuthor = null;
				rejectPromptAuthorAttempt(draft, promptAuthor.cell());
			}
		}
	}

	/**
	 * Retains each contender as soon as it is decided, and selects among the accepted contenders once every addressed
	 * test received all three attempts and every contender is decided. A rejected contender is retained when it is
	 * rejected; outcomes of its runs that were already running update its retained runs, never its decision.
	 *
	 * @param draft the campaign state
	 */
	static void decideWeakRound(Draft draft)
	{
		for (PromptAuthorAttempt attempt : draft.round.validationAttempts())
		{
			if (decision(draft, attempt) == Promotion.Decision.PENDING)
				draft.round = draft.round.withAttempt(refresh(draft, attempt));
		}
		Round round = draft.round;
		List<PromptAuthorAttempt> accepted = new ArrayList<>();
		for (PromptAuthorAttempt attempt : round.validationAttempts())
		{
			Promotion.Decision decision = decision(draft, attempt);
			if (decision == Promotion.Decision.PENDING)
				continue;
			boolean promotes = !attempt.snapshot().equals(draft.working.snapshot()) &&
				decision == Promotion.Decision.PROMOTE;
			retainAttempt(draft, attempt, promotes, false);
			if (promotes)
				accepted.add(attempt);
		}
		if (!isWeakRoundDecided(draft))
			return;
		Optional<PromptAuthorAttempt> winner = AttemptSelection.select(accepted);
		if (winner.isPresent())
		{
			draft.promptAuthor = winner.get();
			retainAttempt(draft, draft.promptAuthor, true, true);
			return;
		}
		endUnproductiveRound(draft);
	}

	/**
	 * Indicates whether a weak round received all of its attempts and decided each of them.
	 *
	 * @param draft the state, whose round is weak
	 * @return {@code true} if the round can select its winner
	 */
	private static boolean isWeakRoundDecided(Draft draft)
	{
		return draft.round.isComplete() && draft.round.validationAttempts().stream().
			allMatch(attempt -> decision(draft, attempt) != Promotion.Decision.PENDING);
	}

	/**
	 * Ends a complete round that produced no promotable attempt, however many failures the working candidate has. The
	 * working candidate stays, and its representative trials resume with a concurrency window of one; the round's
	 * attempts and prompt author diagnoses remain in the optimization evidence. A later failure goes through
	 * edit-or-continue and can start a new round with new failure evidence. Every regression test case that the working
	 * candidate failed last becomes settled, so that failure no longer blocks the commit gate until the working
	 * candidate runs the test case again.
	 *
	 * @param draft the state, whose round is complete
	 */
	private static void endUnproductiveRound(Draft draft)
	{
		draft.round = null;
		draft.slowStart = SlowStart.INITIAL;
		for (int i = 0; i < draft.regressionSet.size(); ++i)
		{
			RegressionTestCase regression = draft.regressionSet.get(i);
			if (regression.working() == Verdict.FAIL)
				draft.regressionSet.set(i, new RegressionTestCase(regression.testCase(), Verdict.FAIL, true));
		}
	}

	/**
	 * Saves an original attempt's result without allowing minimality to replace its measured evidence.
	 *
	 * @param draft the campaign state
	 * @param attempt the original attempt
	 * @param accepted whether the promotion rule accepted it
	 * @param winner whether it was selected
	 */
	static void retainAttempt(Draft draft, PromptAuthorAttempt attempt, boolean accepted, boolean winner)
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
		draft.optimization = new OptimizationEvidence(draft.optimization.promptAuthors(), draft.optimization.removed(),
			draft.optimization.promptSizes(), draft.optimization.trialInputs(), results, draft.optimization.tokenUsage(),
			draft.optimization.approaches(), draft.optimization.commands());
	}

	/**
	 * Makes a prompt author attempt the working candidate.
	 *
	 * @param draft the state
	 * @param promptAuthor the prompt author attempt
	 */
	static void promote(Draft draft, PromptAuthorAttempt promptAuthor)
	{
		Predecessor predecessor = new Predecessor(snapshotDevelopment(draft), draft.round, promptAuthor.cell(),
			draft.regressionSet);
		for (int i = 0; i < draft.regressionSet.size(); ++i)
		{
			RegressionTestCase regression = draft.regressionSet.get(i);
			draft.regressionSet.set(i, new RegressionTestCase(regression.testCase(),
				promotedVerdict(promptAuthor, regression.testCase()), false));
		}
		++draft.candidates;
		draft.startDevelopment(new Candidate(draft.candidates, promptAuthor.snapshot()));
		draft.predecessor = predecessor;
		for (PromotionRun run : promptAuthor.runs())
		{
			if (!run.role().measuresWorking() && run.verdict() != null)
				draft.testCaseOutcomes = TestCaseOutcomes.add(draft.testCaseOutcomes, run.testCase(), run.verdict());
		}
		draft.slowStart = SlowStart.INITIAL;
	}

	/**
	 * Returns the promoted candidate's verdict on a regression test case.
	 *
	 * @param promptAuthor the promoted prompt author attempt
	 * @param testCase the test case
	 * @return the verdict of the attempt's latest judged run on the test case, or {@code null} if the attempt did not
	 *   run it
	 */
	private static Verdict promotedVerdict(PromptAuthorAttempt promptAuthor, DrawnTestCase testCase)
	{
		Verdict verdict = null;
		for (PromotionRun run : promptAuthor.runs())
		{
			if (!run.role().measuresWorking() && run.verdict() != null &&
				Promotion.isSameTestCase(run.testCase(), testCase))
			{
				verdict = run.verdict();
			}
		}
		return verdict;
	}

	/**
	 * Counts a rejected prompt author attempt against its round test and ends the round once every test has
	 * used its attempts.
	 *
	 * @param draft the state
	 * @param cell the round test
	 */
	static void rejectPromptAuthorAttempt(Draft draft, String cell)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>(draft.round.attempts());
		attempts.put(cell, attempts.get(cell) + 1);
		draft.round = new Round(attempts, draft.round.weak(), draft.round.reservations(),
			draft.round.validationAttempts(), draft.round.approachOrders(), draft.round.rankApproaches(),
			draft.round.rotationSize());
		if (draft.round.isComplete())
			endUnproductiveRound(draft);
	}

	/**
	 * Adopts a formal outcome and applies the attempt's decision. A failure stays with the attempt, for the user's
	 * report; its held-out test case never enters development evidence or the regression set. The trial waits for
	 * the control trial and, unless the candidate is the incumbent, the incumbent trial that pair with it. The trial
	 * that certifies the cell's minimum-pass-rate part starts the cell's control comparisons and, unless the candidate
	 * is the incumbent, its incumbent comparisons. When the attempt's minimum pass rate implies the comparisons, the
	 * trial pairs with no reference trial and no comparison starts.
	 *
	 * @param draft the state
	 * @param trial the trial
	 */
	private static void adoptFormal(Draft draft, LogicalTrial trial)
	{
		CertificationAttempt attempt = draft.attempt(trial.key().series());
		String cell = trial.key().cell();
		Map<String, CellCounts> counts = new LinkedHashMap<>(attempt.counts());
		CellCounts cellCounts = attempt.count(cell).add(trial.verdict());
		counts.put(cell, cellCounts);
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		Map<String, Pairing> controlPairings = new LinkedHashMap<>(attempt.controlPairings());
		Map<String, Pairing> incumbentPairings = new LinkedHashMap<>(attempt.incumbentPairings());
		if (!PairedComparison.isImpliedBy(boundaries.minimumPassRate()))
		{
			PromptTest test = testOf(draft, cell);
			UnpairedTrial unpaired = new UnpairedTrial(trial.testCase(), passedAssertions(test, trial, true));
			boolean minimumCertified = DecisionTable.minimumDecision(boundaries, cellCounts) ==
				DecisionTable.CellDecision.CERTIFIED;
			List<String> assertionIds = test.allAssertions().stream().map(Assertion::id).toList();
			controlPairings.put(cell, withCandidate(attempt.pairing(Reference.CONTROL, cell), unpaired,
				minimumCertified, assertionIds));
			if (!attempt.candidate().snapshot().equals(draft.incumbent.snapshot()))
			{
				incumbentPairings.put(cell, withCandidate(attempt.pairing(Reference.INCUMBENT, cell), unpaired,
					minimumCertified, assertionIds));
			}
		}
		List<Failure> failures = new ArrayList<>(attempt.failures());
		if (trial.verdict() == Verdict.FAIL)
		{
			failures.add(new Failure(trial.testCase(), trial.rationale(), trial.failedAssertions(), null, List.of(),
				trial.responses(), trial.files()));
			draft.slowStart = draft.slowStart.afterFailure();
		}
		else
			draft.slowStart = draft.slowStart.afterPass();
		CertificationAttempt updated = attempt.withEvidence(attempt.identity(), counts, controlPairings,
			incumbentPairings, failures, attempt.formalStream());
		draft.replaceAttempt(updated);
		applyDecision(draft, updated);
	}

	/**
	 * Returns a pairing with one more candidate formal trial waiting for its reference trial, comparing the cell's
	 * assertions once its minimum-pass-rate part certified.
	 *
	 * @param pairing the pairing
	 * @param trial the candidate formal trial
	 * @param minimumCertified {@code true} if the cell's minimum-pass-rate part certified
	 * @param assertionIds the identifiers of the prompt test's assertions, in its assertion order
	 * @return the updated pairing
	 */
	private static Pairing withCandidate(Pairing pairing, UnpairedTrial trial, boolean minimumCertified,
		List<String> assertionIds)
	{
		Pairing updated = pairing.withCandidate(trial);
		if (minimumCertified && !updated.isComparing())
			updated = updated.startComparing(assertionIds);
		return updated;
	}

	/**
	 * Adopts a control or incumbent outcome into its cell's comparisons with that kind of reference trial and applies
	 * the attempt's decision. A failed reference trial stays out of the attempt's failures: it measures the prompt's
	 * absence or the incumbent, not the candidate.
	 *
	 * @param draft the state
	 * @param trial the trial
	 * @param reference the kind of reference trial
	 */
	private static void adoptReference(Draft draft, LogicalTrial trial, Reference reference)
	{
		CertificationAttempt attempt = draft.attempt(trial.key().series());
		String cell = trial.key().cell();
		Pairing pairing = attempt.pairing(reference, cell).withReference(trial.testCase(),
			passedAssertions(testOf(draft, cell), trial, false));
		CertificationAttempt updated = attempt.withPairing(reference, cell, pairing);
		draft.replaceAttempt(updated);
		applyDecision(draft, updated);
	}

	/**
	 * Returns the prompt test of a cell.
	 *
	 * @param draft the state
	 * @param cell the cell's identifier
	 * @return the prompt test
	 */
	private static PromptTest testOf(Draft draft, String cell)
	{
		Contract contract = draft.contract();
		return contract.findTest(contract.findCell(cell).orElseThrow().testId()).orElseThrow();
	}

	/**
	 * Returns the assertions that a trial passed. A passing trial passed every assertion, and a failed trial every
	 * assertion that it did not fail. A failed built-in tool decides its trial without a judge, so the trial's judged
	 * assertions were never decided: both sides count them against the candidate, failed for a candidate trial and
	 * passed for a control or incumbent trial, so that the missing evidence cannot favour the candidate. A failure
	 * that names no assertion passed none.
	 *
	 * @param test the trial's prompt test
	 * @param trial the judged trial
	 * @param candidate {@code true} for a candidate formal trial, {@code false} for a control or incumbent trial
	 * @return the identifiers of the assertions that the trial passed, in the prompt test's assertion order
	 */
	private static Set<String> passedAssertions(PromptTest test, LogicalTrial trial, boolean candidate)
	{
		Set<String> passed = new LinkedHashSet<>();
		for (Assertion assertion : test.allAssertions())
			passed.add(assertion.id());
		if (trial.verdict() == Verdict.PASS)
			return passed;
		if (trial.failedAssertions().isEmpty())
			return Set.of();
		boolean checkFailed = false;
		for (FailedAssertion failed : trial.failedAssertions())
		{
			passed.remove(failed.assertionId());
			for (Assertion assertion : test.allAssertions())
			{
				if (assertion.id().equals(failed.assertionId()) && assertion.tool() != null)
					checkFailed = true;
			}
		}
		if (checkFailed && candidate)
		{
			for (Assertion assertion : test.judgedAssertions())
				passed.remove(assertion.id());
		}
		return passed;
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
			case BELOW_CONTROL -> Outcome.BELOW_CONTROL;
			case BELOW_INCUMBENT -> Outcome.BELOW_INCUMBENT;
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
			case RETURN_TO_DEVELOPMENT ->
			{
				// Development continues: the attempt's failed cells are development cells, and prompt authors see each
				// formal failure's cell, test and failed assertions, never its held-out test case
			}
		}
	}

	/**
	 * Raises a review decision for the first cell whose development review point is due, if the active, confirmed
	 * campaign has no pending decision, revision, certification attempt, diagnosis, optimization round or prompt author
	 * attempt.
	 *
	 * @param draft the state
	 */
	static void checkReviewPoints(Draft draft)
	{
		if (draft.header.lifecycle() != Lifecycle.ACTIVE || !draft.isConfirmed() || !draft.pendingDecisions.isEmpty() ||
			draft.revisionRequested || draft.runningAttempt().isPresent() || draft.diagnosis != null ||
			draft.round != null || draft.promptAuthor != null || !draft.testRevisions.requests().isEmpty())
		{
			return;
		}
		Development development = new Development(draft.working, draft.series, draft.developmentCounts,
			draft.developmentFailures, null, null, null, draft.reviewPoints);
		Optional<String> due = ReviewCheckpoint.due(development, draft.contract());
		if (due.isPresent())
		{
			String cell = due.get();
			String test = draft.contract().findCell(cell).orElseThrow().testId();
			if (draft.testRevisions.isSound(draft.lineageNumber(), test))
				draft.raise(new PendingDecision.CheckpointReached(cell, draft.developmentCount(cell).outcomes()));
			else
			{
				draft.testRevisions = draft.testRevisions.request(new TestRevisionRequest(test,
					TestRevisionRequest.Trigger.REVIEW_CHECKPOINT, latestKinds(draft, cell), Map.of()));
			}
		}
	}

	/**
	 * Returns the kinds of failure that the latest valid prompt author diagnosis of a cell in the current lineage
	 * reported.
	 *
	 * @param draft the state
	 * @param cell the cell's identifier
	 * @return the kinds, empty if no valid diagnosis exists
	 */
	private static List<PromptAuthorKind> latestKinds(Draft draft, String cell)
	{
		List<PromptAuthorDiagnosis> diagnoses = draft.optimization.promptAuthors();
		for (int i = diagnoses.size() - 1; i >= 0; --i)
		{
			PromptAuthorDiagnosis diagnosis = diagnoses.get(i);
			if (diagnosis.lineage() == draft.lineageNumber() && diagnosis.cell().equals(cell) &&
				diagnosis.rejections().isEmpty())
			{
				return diagnosis.kinds();
			}
		}
		return List.of();
	}
}
