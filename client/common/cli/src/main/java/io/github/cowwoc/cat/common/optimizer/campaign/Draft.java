package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A mutable copy of a {@link Campaign} that one transition edits before it builds the next state.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class Draft
{
	/**
	 * The campaign's header.
	 */
	Header header;
	/**
	 * The most nested sessions that may run at once.
	 */
	private final int ceiling;
	/**
	 * Every lineage; the current lineage's mutable parts live in the fields below.
	 */
	private final List<Lineage> lineages;
	/**
	 * The current lineage's regression set.
	 */
	List<RegressionTestCase> regressionSet = new ArrayList<>();
	/**
	 * The conditions that make certification unavailable under the current lineage.
	 */
	Set<Outcome> unavailable = EnumSet.noneOf(Outcome.class);
	/**
	 * The current lineage's development draw stream.
	 */
	DrawStream.State developmentStream;
	/**
	 * The current lineage's baseline draw stream.
	 */
	DrawStream.State baselineStream;
	/**
	 * The development values added to the current lineage's tests.
	 */
	AddedTestCases added = AddedTestCases.NONE;
	/**
	 * The current measurement identity.
	 */
	MeasurementIdentity identity;
	/**
	 * The incumbent.
	 */
	Candidate incumbent;
	/**
	 * The number of candidates promoted so far.
	 */
	int candidates;
	/**
	 * The number of prompt author attempts validated so far.
	 */
	int promptAuthorAttempts;
	/**
	 * The working candidate.
	 */
	Candidate working;
	/**
	 * The development series.
	 */
	int series;
	/**
	 * The working candidate's development outcomes included in evidence per cell (prompt test and trial profile).
	 */
	Map<String, CellCounts> developmentCounts;
	/**
	 * The failures that the next prompt author attempt receives.
	 */
	List<Failure> developmentFailures;
	/**
	 * The working candidate's adopted outcomes grouped by test case.
	 */
	List<TestCaseOutcomes> testCaseOutcomes;
	/**
	 * The retained state before the latest promotion, or {@code null}.
	 */
	Predecessor predecessor;
	/**
	 * The candidates withdrawn after representative outcomes, in withdrawal order.
	 */
	final List<Revert> reverts;
	/**
	 * The diagnosis in progress, or {@code null}.
	 */
	Diagnosis diagnosis;
	/**
	 * The targeted-optimization round in progress, or {@code null}.
	 */
	Round round;
	/**
	 * The prompt author attempt under validation, or {@code null}.
	 */
	PromptAuthorAttempt promptAuthor;
	/**
	 * Descriptive prompt author, minimality and snapshot measurements.
	 */
	OptimizationEvidence optimization;
	/**
	 * The next review threshold of each cell that differs from the default.
	 */
	Map<String, Integer> reviewPoints;
	/**
	 * The current incumbent baseline index, or 0 before the first confirmation.
	 */
	int baselineIndex;
	/**
	 * The current incumbent baseline's observed measurement conditions.
	 */
	MeasurementIdentity baselineIdentity;
	/**
	 * The current incumbent baseline's counts per cell.
	 */
	Map<String, CellCounts> baselineCounts = new LinkedHashMap<>();
	/**
	 * Every certification attempt, in index order.
	 */
	final List<CertificationAttempt> attempts;
	/**
	 * The logical trials that are not adopted, in assignment order.
	 */
	final List<LogicalTrial> trials;
	/**
	 * The last sequence number assigned in each sequence group.
	 */
	private final Map<SequenceGroup, Long> sequences;
	/**
	 * The number of outcomes the campaign has adopted.
	 */
	long evidenceRevision;
	/**
	 * The slow-start limit.
	 */
	SlowStart slowStart;
	/**
	 * The rate-limit response.
	 */
	Throttle throttle;
	/**
	 * The decisions that the campaign waits for, in the order they arose.
	 */
	final List<PendingDecision> pendingDecisions;
	/**
	 * Indicates whether a contract revision is requested.
	 */
	boolean revisionRequested;
	/**
	 * The outcome that the campaign reports, or {@code null}.
	 */
	Outcome outcome;
	/**
	 * The candidate to deliver, or {@code null}.
	 */
	DeliveryRequest delivery;
	/**
	 * The warnings that the user answered by choosing to use an authorized source, each once, in answer order.
	 */
	final List<AuthorityWarning> answeredWarnings;
	/**
	 * The pending test revision requests, the applied test revisions and the tests reported sound.
	 */
	TestRevisions testRevisions;
	/**
	 * The trial plugins that every trial installs, or empty until {@code start} has built them.
	 */
	final Optional<List<TrialPlugin>> plugins;

	/**
	 * Copies a campaign.
	 *
	 * @param campaign the campaign
	 */
	Draft(Campaign campaign)
	{
		header = campaign.header();
		ceiling = campaign.ceiling();
		lineages = new ArrayList<>(campaign.lineages());
		if (!lineages.isEmpty())
			loadLineage(lineages.getLast());
		identity = campaign.identity();
		incumbent = campaign.incumbent();
		candidates = campaign.candidates();
		promptAuthorAttempts = campaign.promptAuthorAttempts();
		Development development = campaign.development();
		working = development.working();
		series = development.series();
		developmentCounts = new LinkedHashMap<>(development.counts());
		developmentFailures = new ArrayList<>(development.failures());
		testCaseOutcomes = development.testCaseOutcomes();
		predecessor = development.predecessor();
		reverts = new ArrayList<>(development.reverts());
		diagnosis = development.diagnosis();
		round = development.round();
		promptAuthor = development.promptAuthor();
		optimization = campaign.optimization();
		reviewPoints = new LinkedHashMap<>(development.reviewPoints());
		IncumbentBaseline baseline = campaign.baseline();
		if (baseline != null)
		{
			baselineIndex = baseline.index();
			baselineIdentity = baseline.identity();
			baselineCounts = new LinkedHashMap<>(baseline.counts());
		}
		attempts = new ArrayList<>(campaign.attempts());
		trials = new ArrayList<>(campaign.trials());
		sequences = new HashMap<>(campaign.sequences());
		evidenceRevision = campaign.evidenceRevision();
		slowStart = campaign.slowStart();
		throttle = campaign.throttle();
		pendingDecisions = new ArrayList<>(campaign.pendingDecisions());
		revisionRequested = campaign.revisionRequested();
		outcome = campaign.outcome();
		delivery = campaign.delivery();
		answeredWarnings = new ArrayList<>(campaign.answeredWarnings());
		testRevisions = campaign.testRevisions();
		plugins = campaign.plugins();
	}

	/**
	 * Loads the mutable parts of a lineage.
	 *
	 * @param lineage the lineage
	 */
	private void loadLineage(Lineage lineage)
	{
		regressionSet = new ArrayList<>(lineage.regressionSet());
		unavailable = EnumSet.noneOf(Outcome.class);
		unavailable.addAll(lineage.unavailable());
		developmentStream = lineage.development();
		baselineStream = lineage.baseline();
		added = lineage.added();
	}

	/**
	 * Builds the current lineage from its mutable parts.
	 *
	 * @return the lineage
	 */
	private Lineage buildCurrentLineage()
	{
		Lineage stored = lineages.getLast();
		return new Lineage(stored.number(), stored.contract(), stored.contractHash(), developmentStream,
			baselineStream, regressionSet, unavailable, added);
	}

	/**
	 * Indicates whether a contract is confirmed.
	 *
	 * @return {@code true} if a lineage exists
	 */
	boolean isConfirmed()
	{
		return !lineages.isEmpty();
	}

	/**
	 * Returns the current lineage's number.
	 *
	 * @return the number, or 0 before the first confirmation
	 */
	int lineageNumber()
	{
		if (lineages.isEmpty())
			return 0;
		return lineages.getLast().number();
	}

	/**
	 * Returns the development cells of the current lineage.
	 *
	 * @return the cells, in contract order
	 * @throws IllegalStateException if no contract is confirmed
	 */
	List<Cell> developmentCells()
	{
		return CellTier.developmentCells(contract(), lineageNumber(), attempts);
	}

	/**
	 * Returns the current contract.
	 *
	 * @return the contract
	 * @throws IllegalStateException if no contract is confirmed
	 */
	Contract contract()
	{
		if (lineages.isEmpty())
			throw new IllegalStateException("No contract has been confirmed");
		return lineages.getLast().contract();
	}

	/**
	 * Returns a generator for the current contract and the development values added to its tests.
	 *
	 * @return the generator
	 * @throws IllegalStateException if no contract is confirmed
	 */
	Generator generator()
	{
		return new Generator(contract(), added);
	}

	/**
	 * Returns a generator for the current contract that also holds every test case that a failure group of the
	 * current lineage tried or tries, so that it draws and validates reproduction test cases.
	 *
	 * @return the generator
	 * @throws IllegalStateException if no contract is confirmed
	 */
	Generator reproductionGenerator()
	{
		return new Generator(contract(), Campaign.developmentTestCases(added, lineageNumber(), attempts));
	}

	/**
	 * Returns the certification attempt whose formal failure groups hold back development.
	 *
	 * @return the current lineage's latest attempt while it is reproducing, or empty
	 */
	Optional<CertificationAttempt> reproducing()
	{
		return Campaign.reproducing(lineageNumber(), attempts);
	}

	/**
	 * Starts a lineage.
	 *
	 * @param lineage the new lineage
	 */
	void startLineage(Lineage lineage)
	{
		if (!lineages.isEmpty())
			lineages.set(lineages.size() - 1, buildCurrentLineage());
		lineages.add(lineage);
		loadLineage(lineage);
	}

	/**
	 * Finds a validating contender or selected attempt by its reserved number.
	 *
	 * @param number the attempt number
	 * @return the attempt, or null if it is absent
	 */
	PromptAuthorAttempt findPromptAuthor(int number)
	{
		if (promptAuthor != null && promptAuthor.number() == number)
			return promptAuthor;
		if (round == null || !round.weak())
			return null;
		return round.validationAttempts().stream().filter(attempt -> attempt.number() == number).findFirst().
			orElse(null);
	}

	/**
	 * Indicates whether a trial measures a working reference registered by an attempt in the current round.
	 * The reference remains shared evidence even when its owning sequential attempt has rejected.
	 *
	 * @param trial the trial to classify
	 * @return {@code true} if the trial uses the working snapshot and its owner's reference content
	 */
	boolean isWorkingReference(LogicalTrial trial)
	{
		TrialKey key = trial.key();
		if (key.purpose() != Purpose.PROMOTION || !key.snapshot().equals(working.snapshot()) || round == null)
			return false;
		PromptAuthorAttempt owner = findPromptAuthor(key.series());
		if (owner != null)
		{
			return owner.runs().stream().anyMatch(run -> run.role() == PromotionRun.Role.WORKING_REFERENCE &&
				key.equals(run.trial()));
		}
		for (PromptAuthorAttempt attempt : round.validationAttempts())
		{
			if (attempt.number() != key.series())
				continue;
			return attempt.runs().stream().anyMatch(run -> run.role() == PromotionRun.Role.WORKING_REFERENCE &&
				Promotion.isSameTestCase(run.testCase(), trial.testCase()));
		}
		return false;
	}

	/**
	 * Replaces either the selected attempt or one concurrent contender.
	 *
	 * @param attempt the updated attempt
	 */
	void updatePromptAuthor(PromptAuthorAttempt attempt)
	{
		if (promptAuthor != null && promptAuthor.number() == attempt.number())
			promptAuthor = attempt;
		else
			round = round.withAttempt(attempt);
	}

	/**
	 * Starts a development series for a working candidate, which discards the previous series' state.
	 *
	 * @param candidate the working candidate
	 */
	void startDevelopment(Candidate candidate)
	{
		working = candidate;
		++series;
		developmentCounts = new LinkedHashMap<>();
		developmentFailures = new ArrayList<>();
		testCaseOutcomes = List.of();
		predecessor = null;
		diagnosis = null;
		round = null;
		promptAuthor = null;
		reviewPoints = new LinkedHashMap<>();
	}

	/**
	 * Restarts the working candidate's development counts after the development distribution changed: starts a new
	 * development series with no counted outcomes, and discards the retained predecessor, whose counts measured the
	 * earlier distribution. Failures, test case outcomes and the revert history remain.
	 */
	void restartDevelopmentCounts()
	{
		++series;
		developmentCounts = new LinkedHashMap<>();
		reviewPoints = new LinkedHashMap<>();
		predecessor = null;
	}

	/**
	 * Starts an incumbent baseline with no outcomes.
	 */
	void startBaseline()
	{
		++baselineIndex;
		baselineIdentity = identity;
		baselineCounts = new LinkedHashMap<>();
	}

	/**
	 * Returns a cell's development counts.
	 *
	 * @param cell the cell identifier
	 * @return the counts
	 */
	CellCounts developmentCount(String cell)
	{
		return developmentCounts.getOrDefault(cell, CellCounts.EMPTY);
	}

	/**
	 * Returns the running certification attempt.
	 *
	 * @return the attempt, or empty if none runs
	 */
	Optional<CertificationAttempt> runningAttempt()
	{
		if (attempts.isEmpty() || !attempts.getLast().isRunning())
			return Optional.empty();
		return Optional.of(attempts.getLast());
	}

	/**
	 * Returns a certification attempt.
	 *
	 * @param index the attempt's certification index
	 * @return the attempt
	 * @throws IllegalArgumentException if no attempt has the index
	 */
	CertificationAttempt attempt(int index)
	{
		if (index < 1 || index > attempts.size())
			throw new IllegalArgumentException("No certification attempt has index " + index);
		return attempts.get(index - 1);
	}

	/**
	 * Replaces a certification attempt with its updated state.
	 *
	 * @param attempt the updated attempt
	 */
	void replaceAttempt(CertificationAttempt attempt)
	{
		attempts.set(attempt.index() - 1, attempt);
	}

	/**
	 * Indicates whether a running certification attempt was ended and its outcome reported.
	 *
	 * @param ending the attempt's outcome
	 * @return {@code true} if an attempt was running
	 */
	boolean endRunningAttempt(Outcome ending)
	{
		Optional<CertificationAttempt> running = runningAttempt();
		if (running.isEmpty())
			return false;
		replaceAttempt(running.get().end(ending));
		outcome = ending;
		return true;
	}

	/**
	 * Returns the decision that the campaign asks for now.
	 *
	 * @return the first pending decision, or {@code null} if the campaign waits for none
	 */
	PendingDecision pending()
	{
		if (pendingDecisions.isEmpty())
			return null;
		return pendingDecisions.getFirst();
	}

	/**
	 * Adds a decision behind the decisions that are already pending, unless an equal decision is pending.
	 *
	 * @param decision the decision
	 */
	void raise(PendingDecision decision)
	{
		if (!pendingDecisions.contains(decision))
			pendingDecisions.add(decision);
	}

	/**
	 * Removes the decision that the user answered.
	 *
	 * @throws IllegalStateException if no decision is pending
	 */
	void resolve()
	{
		if (pendingDecisions.isEmpty())
			throw new IllegalStateException("No decision is pending");
		pendingDecisions.removeFirst();
	}

	/**
	 * Numbers the next logical trial of a sequence group.
	 *
	 * @param group the group
	 * @return the trial's sequence number, starting at 1
	 */
	long nextSequence(SequenceGroup group)
	{
		return sequences.merge(group, 1L, Long::sum);
	}

	/**
	 * Returns the index of the logical trial with a key.
	 *
	 * @param key the key
	 * @return the index
	 * @throws IllegalArgumentException if no unadopted trial has the key
	 */
	int indexOf(TrialKey key)
	{
		for (int i = 0; i < trials.size(); ++i)
		{
			if (trials.get(i).key().equals(key))
				return i;
		}
		throw new IllegalArgumentException("No unadopted trial has the key " + key);
	}

	/**
	 * Builds the next state.
	 *
	 * @return the campaign
	 */
	Campaign build()
	{
		List<Lineage> builtLineages = new ArrayList<>(lineages);
		IncumbentBaseline baseline = null;
		if (!builtLineages.isEmpty())
		{
			builtLineages.set(builtLineages.size() - 1, buildCurrentLineage());
			baseline = new IncumbentBaseline(baselineIndex, baselineIdentity, baselineCounts);
		}
		Development development = new Development(working, series, developmentCounts, developmentFailures,
			diagnosis, round, promptAuthor, reviewPoints, testCaseOutcomes, predecessor, reverts);
		return new Campaign(header, ceiling, builtLineages, identity, incumbent, candidates, promptAuthorAttempts,
			development, baseline, attempts, trials, sequences, evidenceRevision, slowStart, throttle,
			pendingDecisions, revisionRequested, outcome, delivery, optimization, answeredWarnings, testRevisions,
			plugins);
	}
}
