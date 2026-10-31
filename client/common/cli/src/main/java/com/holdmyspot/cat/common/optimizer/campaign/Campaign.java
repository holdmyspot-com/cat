package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The saved state of one prompt-optimization campaign. {@link Transitions} derives each next state.
 * A lineage is the evidence history under one confirmed contract.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param header the campaign's identity, binding and lifecycle state
 * @param ceiling the most nested sessions that may run at once
 * @param lineages every lineage, the last being the current one; empty before the first confirmation
 * @param identity the current measurement identity
 * @param incumbent the prompt snapshot recorded before optimization
 * @param candidates the number of candidates promoted so far
 * @param promptAuthorAttempts the number of prompt author attempts validated so far
 * @param development the working candidate's development state
 * @param baseline the original prompt's measured outcomes, or {@code null} before the first confirmation
 * @param attempts every certification attempt, in index order
 * @param trials the assigned trials whose outcomes have not entered campaign evidence, in assignment order
 * @param sequences the last sequence number assigned in each sequence group
 * @param evidenceRevision the number of outcomes the campaign has adopted, so that each {@link Allocation} identifies
 *   the evidence its decision used
 * @param windows the concurrency windows of the units of work, and the halvings after runs without an outcome
 * @param throttle the provider's rate-limit waiting and concurrency limits
 * @param pendingDecisions the decisions that the campaign waits for, in the order they arose; the first one is
 *   asked now
 * @param revisionRequested {@code true} if the user asked to revise the contract and no new contract is confirmed
 * @param outcome the outcome that the campaign reports, or {@code null} if none
 * @param delivery the candidate that the campaign decided to deliver, or {@code null} if none
 * @param optimization the retained prompt author and minimality evidence
 * @param answeredWarnings the warnings about edits outside the edit authority that the user answered by choosing to
 *   use an authorized source, each once, in the order they were answered; a later warning equal to one of them raises
 *   no decision
 * @param testRevisions the pending test revision requests, the applied test revisions and the tests reported sound
 * @param plugins the trial plugins that every trial installs, in configuration order; empty until {@code start} has
 *   built them and copied their bases
 */
public record Campaign(Header header, int ceiling, List<Lineage> lineages, MeasurementIdentity identity,
	Candidate incumbent, int candidates, int promptAuthorAttempts, Development development,
	IncumbentBaseline baseline, List<CertificationAttempt> attempts, List<LogicalTrial> trials,
	Map<SequenceGroup, Long> sequences, long evidenceRevision, ConcurrencyWindows windows, Throttle throttle,
	List<PendingDecision> pendingDecisions, boolean revisionRequested, Outcome outcome,
	DeliveryRequest delivery, OptimizationEvidence optimization, List<AuthorityWarning> answeredWarnings,
	TestRevisions testRevisions, Optional<List<TrialPlugin>> plugins)
{
	/**
	 * The default maximum number of concurrent nested sessions.
	 */
	public static final int DEFAULT_CEILING = 8;
	/**
	 * The largest permitted number of concurrent nested sessions.
	 */
	public static final int MAXIMUM_CEILING = 16;
	/**
	 * Pause the campaign when one trial reaches this many infrastructure failures; rate-limit rejections and
	 * interruptions do not count toward the limit.
	 */
	public static final int INFRASTRUCTURE_FAILURE_LIMIT = 3;

	/**
	 * Creates a campaign state.
	 *
	 * @param header the campaign's identity, binding and lifecycle state
	 * @param ceiling the most nested sessions that may run at once
	 * @param lineages every lineage, the last being the current one; empty before the first confirmation
	 * @param identity the current measurement identity
	 * @param incumbent the prompt snapshot recorded before optimization
	 * @param candidates the number of candidates promoted so far
	 * @param promptAuthorAttempts the number of prompt author attempts validated so far
	 * @param development the working candidate's development state
	 * @param baseline the original prompt's measured outcomes, or {@code null} before the first confirmation
	 * @param attempts every certification attempt, in index order
	 * @param trials the assigned trials whose outcomes have not entered campaign evidence, in assignment order
	 * @param sequences the last sequence number assigned in each sequence group
	 * @param evidenceRevision the number of outcomes the campaign has adopted
	 * @param windows the concurrency windows of the units of work, and the halvings after runs without an outcome
	 * @param throttle the provider's rate-limit waiting and concurrency limits
	 * @param pendingDecisions the decisions that the campaign waits for, in the order they arose; the first one is
	 *   asked now
	 * @param revisionRequested {@code true} if the user asked to revise the contract and no new contract is confirmed
	 * @param outcome the outcome that the campaign reports, or {@code null} if none
	 * @param delivery the candidate that the campaign decided to deliver, or {@code null} if none
	 * @param optimization the retained prompt author and minimality evidence
	 * @param answeredWarnings the warnings about edits outside the edit authority that the user answered by choosing
	 *   to use an authorized source, each once, in the order they were answered
	 * @param testRevisions the pending test revision requests, the applied test revisions and the tests reported
	 *   sound
	 * @param plugins the trial plugins that every trial installs, in configuration order; empty until {@code start}
	 *   has built them and copied their bases
	 * @throws NullPointerException if a component other than {@code baseline}, {@code outcome} or
	 *   {@code delivery} is null, {@code baseline} is null after a confirmation, or {@code answeredWarnings} contains
	 *   null
	 * @throws IllegalArgumentException if {@code ceiling} is outside [1, {@value #MAXIMUM_CEILING}], a count is
	 *   negative, the incumbent is not candidate 0, attempts are not
	 *   numbered 1, 2, …, more than one attempt is running, a group's last sequence number is not positive, a
	 *   trial's sequence number exceeds its group's
	 */
	public Campaign
	{
		requireThat(optimization, "optimization").isNotNull();
		requireThat(header, "header").isNotNull();
		requireThat(ceiling, "ceiling").isBetween(1, true, MAXIMUM_CEILING, true);
		requireThat(lineages, "lineages").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(incumbent, "incumbent").isNotNull();
		requireThat(incumbent.isIncumbent(), "incumbent.isIncumbent()").isTrue();
		requireThat(candidates, "candidates").isNotNegative();
		requireThat(promptAuthorAttempts, "promptAuthorAttempts").isNotNegative();
		requireThat(development, "development").isNotNull();
		if (!lineages.isEmpty())
			requireThat(baseline, "baseline").isNotNull();
		requireThat(attempts, "attempts").isNotNull();
		int running = 0;
		for (int i = 0; i < attempts.size(); ++i)
		{
			CertificationAttempt attempt = attempts.get(i);
			requireThat(attempt.index(), "attempts[" + i + "].index()").isEqualTo(i + 1);
			if (attempt.isRunning())
				++running;
		}
		requireThat(running, "runningAttempts").isLessThanOrEqualTo(1);
		requireThat(trials, "trials").isNotNull();
		requireThat(sequences, "sequences").isNotNull();
		requireThat(evidenceRevision, "evidenceRevision").isNotNegative();
		for (Map.Entry<SequenceGroup, Long> entry : sequences.entrySet())
			requireThat(entry.getValue(), "lastSequence").withContext(entry.getKey(), "group").isPositive();
		for (LogicalTrial trial : trials)
		{
			TrialKey key = trial.key();
			requireThat(key.sequence(), "trial.key().sequence()").
				isLessThanOrEqualTo(sequences.getOrDefault(key.sequenceGroup(), 0L), "lastSequence");
		}
		requireThat(pendingDecisions, "pendingDecisions").isNotNull();
		requireThat(windows, "windows").isNotNull();
		requireThat(throttle, "throttle").isNotNull();
		lineages = List.copyOf(lineages);
		attempts = List.copyOf(attempts);
		trials = List.copyOf(trials);
		sequences = Map.copyOf(sequences);
		pendingDecisions = List.copyOf(pendingDecisions);
		answeredWarnings = List.copyOf(answeredWarnings);
		requireThat(testRevisions, "testRevisions").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		plugins = plugins.map(List::copyOf);
	}

	/**
	 * Returns a new campaign that waits for its first confirmed contract.
	 *
	 * @param header the campaign's identity and binding; its lifecycle must be {@link Lifecycle#ACTIVE}
	 * @param ceiling the most nested sessions that may run at once
	 * @param incumbent the prompt snapshot recorded before optimization
	 * @param identity the measurement conditions observed so far
	 * @return the campaign
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the header is not active or {@code ceiling} is outside
	 *   [1, {@value #MAXIMUM_CEILING}]
	 */
	public static Campaign start(Header header, int ceiling, Sha256 incumbent, MeasurementIdentity identity)
	{
		requireThat(header, "header").isNotNull();
		requireThat(header.lifecycle(), "header.lifecycle()").isEqualTo(Lifecycle.ACTIVE);
		Candidate candidate = new Candidate(0, incumbent);
		return new Campaign(header, ceiling, List.of(), identity, candidate, 0, 0, Development.start(candidate, 0),
			null, List.of(), List.of(), Map.of(), 0, ConcurrencyWindows.EMPTY, Throttle.NONE, List.of(), false, null, null,
			OptimizationEvidence.EMPTY, List.of(), TestRevisions.NONE, Optional.empty());
	}

	/**
	 * Returns this campaign, which waits for its first confirmed contract, bound to a different incumbent snapshot.
	 * Presenting a proposal can change the fork's files, and the incumbent must follow them; the evidence gathered so
	 * far, such as session usage, stays.
	 *
	 * @param snapshot the identity of the new incumbent snapshot
	 * @return the rebound campaign
	 * @throws NullPointerException if {@code snapshot} is null
	 * @throws IllegalStateException if a contract has been confirmed; a confirmed campaign changes its incumbent only
	 *   when a confirmed revision moves it onto a larger fork
	 */
	public Campaign withIncumbent(Sha256 snapshot)
	{
		requireThat(snapshot, "snapshot").isNotNull();
		if (isConfirmed())
			throw new IllegalStateException("A confirmed campaign keeps its incumbent");
		Candidate candidate = new Candidate(0, snapshot);
		return new Campaign(header, ceiling, lineages, identity, candidate, candidates, promptAuthorAttempts,
			Development.start(candidate, 0), baseline, attempts, trials, sequences, evidenceRevision, windows, throttle,
			pendingDecisions, revisionRequested, outcome, delivery, optimization, answeredWarnings, testRevisions,
			plugins);
	}

	/**
	 * Returns this campaign with its trial plugins recorded, and the conditions that they set added to its
	 * measurement identity.
	 *
	 * @param value    the trial plugins that every trial installs, in configuration order
	 * @param versions the version that the manifest in each plugin's base declares, keyed by the plugin's name; a
	 *                 plugin whose manifest declares none is absent
	 * @return the campaign
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Campaign withPlugins(List<TrialPlugin> value, Map<String, String> versions)
	{
		requireThat(value, "value").isNotNull();
		return new Campaign(header, ceiling, lineages, identity.replaceWith(MeasurementIdentity.ofPlugins(value,
			versions)),
			incumbent, candidates, promptAuthorAttempts, development, baseline, attempts, trials, sequences,
			evidenceRevision, windows, throttle, pendingDecisions, revisionRequested, outcome, delivery, optimization,
			answeredWarnings, testRevisions, Optional.of(value));
	}

	/**
	 * Indicates whether the campaign waits for a test revision to answer a pending request.
	 *
	 * @return {@code true} if a test revision request is pending
	 */
	public boolean awaitsTestRevision()
	{
		return !testRevisions.requests().isEmpty();
	}

	/**
	 * Returns the campaign's lifecycle state.
	 *
	 * @return the state
	 */
	public Lifecycle lifecycle()
	{
		return header.lifecycle();
	}

	/**
	 * Indicates whether a contract has been confirmed.
	 *
	 * @return {@code true} if a lineage exists
	 */
	public boolean isConfirmed()
	{
		return !lineages.isEmpty();
	}

	/**
	 * Indicates whether the user kept the incumbent: the campaign finished as {@code certified} without delivering a
	 * candidate, so that a delivery writes its prompt tests alone.
	 *
	 * @return {@code true} if the user kept the incumbent
	 */
	public boolean isIncumbentKept()
	{
		return lifecycle() == Lifecycle.FINISHED && outcome == Outcome.CERTIFIED && delivery == null;
	}

	/**
	 * Returns the evidence history under the latest confirmed contract.
	 *
	 * @return the lineage
	 * @throws IllegalStateException if no contract has been confirmed
	 */
	public Lineage lineage()
	{
		if (lineages.isEmpty())
			throw new IllegalStateException("No contract has been confirmed");
		return lineages.getLast();
	}

	/**
	 * Returns the current contract.
	 *
	 * @return the contract
	 * @throws IllegalStateException if no contract has been confirmed
	 */
	public Contract contract()
	{
		return lineage().contract();
	}

	/**
	 * Returns the certification attempt whose formal failure groups hold back development: the current lineage's latest
	 * attempt, while one of its groups is reproducing or did not reproduce and waits for the user's choice. Until then,
	 * development draws nothing, no prompt author works on its failures and the commit gate is not met.
	 *
	 * @return the attempt, or empty if none is reproducing
	 */
	public Optional<CertificationAttempt> reproducing()
	{
		if (lineages.isEmpty())
			return Optional.empty();
		return reproducing(lineage().number(), attempts);
	}

	/**
	 * Returns the certification attempt whose formal failure groups hold back a lineage's development.
	 *
	 * @param lineage the lineage's number
	 * @param attempts the campaign's certification attempts
	 * @return the latest attempt if it belongs to the lineage and is reproducing, or empty
	 */
	static Optional<CertificationAttempt> reproducing(int lineage, List<CertificationAttempt> attempts)
	{
		if (attempts.isEmpty())
			return Optional.empty();
		CertificationAttempt last = attempts.getLast();
		if (last.lineage() != lineage || !last.isReproducing())
			return Optional.empty();
		return Optional.of(last);
	}

	/**
	 * Returns the test cases that the current lineage's tests may hold beyond the confirmed contract: the test cases
	 * added to development, and every test case that a failure group of the lineage tried or tries.
	 *
	 * @return the test cases, each once
	 * @throws IllegalStateException if no contract has been confirmed
	 */
	public AddedTestCases developmentTestCases()
	{
		Lineage current = lineage();
		return developmentTestCases(current.added(), current.number(), attempts);
	}

	/**
	 * Returns the test cases that a lineage's tests may hold beyond its confirmed contract.
	 *
	 * @param added the test cases added to the lineage's development
	 * @param lineage the lineage's number
	 * @param attempts the campaign's certification attempts
	 * @return the added test cases, then every test case that a failure group of the lineage tried or tries, each once
	 */
	static AddedTestCases developmentTestCases(AddedTestCases added, int lineage, List<CertificationAttempt> attempts)
	{
		Set<AddedTestCase> testCases = new LinkedHashSet<>(added.testCases());
		for (CertificationAttempt attempt : attempts)
		{
			if (attempt.lineage() != lineage)
				continue;
			for (FailureGroup group : attempt.groups())
				testCases.addAll(group.testCases());
		}
		return new AddedTestCases(List.copyOf(testCases));
	}

	/**
	 * Returns the certification attempt that is running.
	 *
	 * @return the attempt, or empty if none is running
	 */
	public Optional<CertificationAttempt> runningAttempt()
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
	public CertificationAttempt attempt(int index)
	{
		requireThat(index, "index").isBetween(1, true, attempts.size(), true);
		return attempts.get(index - 1);
	}

	/**
	 * Returns the next certification attempt's index, assigned when its procedure is frozen. Only a new campaign
	 * starts again from 1.
	 *
	 * @return the index
	 */
	public int nextCertificationIndex()
	{
		return attempts.size() + 1;
	}

	/**
	 * Returns the decision that the campaign asks for now.
	 *
	 * @return the first pending decision, or {@code null} if the campaign waits for none
	 */
	public PendingDecision pending()
	{
		if (pendingDecisions.isEmpty())
			return null;
		return pendingDecisions.getFirst();
	}

	/**
	 * Returns the choices that the user may select now.
	 *
	 * @return the choices, empty if the campaign waits for no decision
	 */
	public List<Choice> choices()
	{
		boolean offersKeep = !KeepImprovement.offers(this).isEmpty();
		boolean acceptable = !development.working().isIncumbent();
		List<Choice> choices = new ArrayList<>();
		if (lifecycle() == Lifecycle.CANCELLED)
		{
			choices.add(Choice.RESUME);
			if (offersKeep)
				choices.add(Choice.KEEP_IMPROVEMENT);
			if (acceptable)
				choices.add(Choice.ACCEPT_WITHOUT_CERTIFICATION);
			return choices;
		}
		PendingDecision pending = pending();
		if (lifecycle() != Lifecycle.ACTIVE || pending == null)
			return choices;
		switch (pending)
		{
			case PendingDecision.MeasurementChanged changed ->
			{
				choices.add(Choice.RESTORE);
				if (changed.isLoaderChanged())
					choices.add(Choice.NEW_CAMPAIGN);
				else
				{
					choices.add(Choice.NEW_IDENTITY);
					choices.add(Choice.KEEP_IDENTITY);
				}
			}
			case PendingDecision.ConflictFound _ -> choices.add(Choice.RESOLUTION);
			case PendingDecision.UnownedChange _ -> choices.add(Choice.RESUME);
			case PendingDecision.TrialFailures _, PendingDecision.CalibrationFailure _ -> choices.add(Choice.RETRY);
			case PendingDecision.CheckpointReached _ ->
			{
				choices.add(Choice.MORE_EVIDENCE);
				choices.add(Choice.TARGETED_OPTIMIZATION);
				if (offersKeep)
					choices.add(Choice.KEEP_IMPROVEMENT);
				if (acceptable)
					choices.add(Choice.ACCEPT_WITHOUT_CERTIFICATION);
			}
			case PendingDecision.FailuresNotReproduced _ ->
			{
				choices.add(Choice.MORE_ROUNDS);
				choices.add(Choice.NEW_ATTEMPT);
			}
			case PendingDecision.ImprovementProven _ ->
			{
				choices.add(Choice.KEEP_IMPROVEMENT);
				choices.add(Choice.OPTIMIZE_FURTHER);
			}
			case PendingDecision.RegressionTradeOff _ ->
			{
				choices.add(Choice.ACCEPT_TRADE_OFF);
				choices.add(Choice.RETAIN_INCUMBENT);
				return choices;
			}
			case PendingDecision.CertificationUnavailable _ ->
			{
				if (offersKeep)
					choices.add(Choice.KEEP_IMPROVEMENT);
				if (acceptable)
					choices.add(Choice.ACCEPT_WITHOUT_CERTIFICATION);
			}
			case PendingDecision.AuthorityWarnings _ -> choices.add(Choice.USE_AUTHORIZED_SOURCE);
			case PendingDecision.TestRevisionBlocked blocked ->
			{
				if (!blocked.proposedChange().isBlank())
					choices.add(Choice.PROPOSED_CHANGE);
			}
		}
		choices.add(Choice.STOP);
		return choices;
	}
}
