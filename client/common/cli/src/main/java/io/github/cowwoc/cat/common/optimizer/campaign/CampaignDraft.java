package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;

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
final class CampaignDraft
{
	/**
	 * The campaign's header.
	 */
	CampaignHeader header;
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
	List<RegressionInstance> regressionSet = new ArrayList<>();
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
	 * The current measurement identity.
	 */
	MeasurementIdentity identity;
	/**
	 * The incumbent.
	 */
	final Candidate incumbent;
	/**
	 * The number of candidates promoted so far.
	 */
	int candidates;
	/**
	 * The number of author attempts validated so far.
	 */
	int authorAttempts;
	/**
	 * The working candidate.
	 */
	Candidate working;
	/**
	 * The development series.
	 */
	int series;
	/**
	 * The working candidate's adopted development outcomes per category.
	 */
	Map<String, CategoryCounts> developmentCounts;
	/**
	 * The failures that the next author attempt receives.
	 */
	List<Failure> developmentFailures;
	/**
	 * The diagnosis in progress, or {@code null}.
	 */
	Diagnosis diagnosis;
	/**
	 * The targeted-optimization round in progress, or {@code null}.
	 */
	Round round;
	/**
	 * The author attempt under validation, or {@code null}.
	 */
	AuthorAttempt author;
	/**
	 * The review point of each category that differs from the default.
	 */
	Map<String, Integer> reviewPoints;
	/**
	 * The current incumbent baseline index, or 0 before the first confirmation.
	 */
	int baselineIndex;
	/**
	 * The current incumbent baseline's counts per category.
	 */
	Map<String, CategoryCounts> baselineCounts = new LinkedHashMap<>();
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
	 * {@code true} if a contract revision is requested.
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
	 * Copies a campaign.
	 *
	 * @param campaign the campaign
	 */
	CampaignDraft(Campaign campaign)
	{
		header = campaign.header();
		ceiling = campaign.ceiling();
		lineages = new ArrayList<>(campaign.lineages());
		if (!lineages.isEmpty())
			loadLineage(lineages.getLast());
		identity = campaign.identity();
		incumbent = campaign.incumbent();
		candidates = campaign.candidates();
		authorAttempts = campaign.authorAttempts();
		Development development = campaign.development();
		working = development.working();
		series = development.series();
		developmentCounts = new LinkedHashMap<>(development.counts());
		developmentFailures = new ArrayList<>(development.failures());
		diagnosis = development.diagnosis();
		round = development.round();
		author = development.author();
		reviewPoints = new LinkedHashMap<>(development.reviewPoints());
		IncumbentBaseline baseline = campaign.baseline();
		if (baseline != null)
		{
			baselineIndex = baseline.index();
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
	}

	/**
	 * Builds the current lineage from its mutable parts.
	 *
	 * @return the lineage
	 */
	private Lineage currentLineage()
	{
		Lineage stored = lineages.getLast();
		return new Lineage(stored.number(), stored.contract(), stored.contractHash(), developmentStream,
			baselineStream, regressionSet, unavailable);
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
	 * Returns a generator for the current contract.
	 *
	 * @return the generator
	 * @throws IllegalStateException if no contract is confirmed
	 */
	Generator generator()
	{
		return new Generator(contract());
	}

	/**
	 * Starts a lineage.
	 *
	 * @param lineage the new lineage
	 */
	void startLineage(Lineage lineage)
	{
		if (!lineages.isEmpty())
			lineages.set(lineages.size() - 1, currentLineage());
		lineages.add(lineage);
		loadLineage(lineage);
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
		diagnosis = null;
		round = null;
		author = null;
		reviewPoints = new LinkedHashMap<>();
	}

	/**
	 * Starts an incumbent baseline with no outcomes.
	 */
	void startBaseline()
	{
		++baselineIndex;
		baselineCounts = new LinkedHashMap<>();
	}

	/**
	 * Returns a category's development counts.
	 *
	 * @param category the category
	 * @return the counts
	 */
	CategoryCounts developmentCount(String category)
	{
		return developmentCounts.getOrDefault(category, CategoryCounts.EMPTY);
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
	 * Ends the running certification attempt, if any, and reports its outcome.
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
			builtLineages.set(builtLineages.size() - 1, currentLineage());
			baseline = new IncumbentBaseline(baselineIndex, baselineCounts);
		}
		Development development = new Development(working, series, developmentCounts, developmentFailures,
			diagnosis, round, author, reviewPoints);
		return new Campaign(header, ceiling, builtLineages, identity, incumbent, candidates, authorAttempts,
			development, baseline, attempts, trials, sequences, evidenceRevision, slowStart, throttle, pendingDecisions,
			revisionRequested, outcome, delivery);
	}
}
