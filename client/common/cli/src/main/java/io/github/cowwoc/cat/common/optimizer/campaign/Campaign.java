package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The persisted state of one campaign. {@link CampaignTransitions} derives each next state.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param header the campaign's identity, binding and lifecycle state
 * @param ceiling the most nested sessions that may run at once
 * @param lineages every lineage, the last being the current one; empty before the first confirmation
 * @param identity the current measurement identity
 * @param incumbent the incumbent
 * @param candidates the number of candidates promoted so far
 * @param authorAttempts the number of author attempts validated so far
 * @param development the working candidate's development state
 * @param baseline the current incumbent baseline, or {@code null} before the first confirmation
 * @param attempts every certification attempt, in index order
 * @param trials the logical trials that are not adopted, in assignment order
 * @param sequences the last sequence number assigned in each sequence group
 * @param slowStart the slow-start limit on the active candidate's concurrency
 * @param throttle the rate-limit response
 * @param pendingDecisions the decisions that the campaign waits for, in the order they arose; the first one is
 *   asked now
 * @param revisionRequested {@code true} if the user asked to revise the contract and no new contract is confirmed
 * @param outcome the outcome that the campaign reports, or {@code null} if none
 * @param delivery the candidate that the campaign decided to deliver, or {@code null} if none
 */
public record Campaign(CampaignHeader header, int ceiling, List<Lineage> lineages, MeasurementIdentity identity,
	Candidate incumbent, int candidates, int authorAttempts, Development development, IncumbentBaseline baseline,
	List<CertificationAttempt> attempts, List<LogicalTrial> trials, Map<SequenceGroup, Long> sequences,
	SlowStart slowStart, Throttle throttle, List<PendingDecision> pendingDecisions, boolean revisionRequested,
	Outcome outcome, DeliveryRequest delivery)
{
	/**
	 * The default campaign ceiling.
	 */
	public static final int DEFAULT_CEILING = 8;
	/**
	 * The largest campaign ceiling.
	 */
	public static final int MAXIMUM_CEILING = 16;
	/**
	 * The runs without an outcome, not counting rate-limit rejections, after which a logical trial pauses the
	 * campaign.
	 */
	public static final int INFRASTRUCTURE_FAILURE_LIMIT = 3;

	/**
	 * Creates a campaign state.
	 *
	 * @param header the campaign's identity, binding and lifecycle state
	 * @param ceiling the most nested sessions that may run at once
	 * @param lineages every lineage, the last being the current one; empty before the first confirmation
	 * @param identity the current measurement identity
	 * @param incumbent the incumbent
	 * @param candidates the number of candidates promoted so far
	 * @param authorAttempts the number of author attempts validated so far
	 * @param development the working candidate's development state
	 * @param baseline the current incumbent baseline, or {@code null} before the first confirmation
	 * @param attempts every certification attempt, in index order
	 * @param trials the logical trials that are not adopted, in assignment order
	 * @param sequences the last sequence number assigned in each sequence group
	 * @param slowStart the slow-start limit on the active candidate's concurrency
	 * @param throttle the rate-limit response
	 * @param pendingDecisions the decisions that the campaign waits for, in the order they arose; the first one is
	 *   asked now
	 * @param revisionRequested {@code true} if the user asked to revise the contract and no new contract is confirmed
	 * @param outcome the outcome that the campaign reports, or {@code null} if none
	 * @param delivery the candidate that the campaign decided to deliver, or {@code null} if none
	 * @throws NullPointerException if a component other than {@code baseline}, {@code outcome} or
	 *   {@code delivery} is null
	 * @throws IllegalArgumentException if {@code ceiling} is outside [1, {@value #MAXIMUM_CEILING}], a count is
	 *   negative, the incumbent is not candidate 0, the baseline is missing after a confirmation, attempts are not
	 *   numbered 1, 2, …, more than one attempt is running, a group's last sequence number is not positive, or a
	 *   trial's sequence number exceeds its group's
	 */
	public Campaign
	{
		requireThat(header, "header").isNotNull();
		requireThat(ceiling, "ceiling").isBetween(1, true, MAXIMUM_CEILING, true);
		requireThat(lineages, "lineages").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(incumbent, "incumbent").isNotNull();
		requireThat(incumbent.isIncumbent(), "incumbent.isIncumbent()").isTrue();
		requireThat(candidates, "candidates").isNotNegative();
		requireThat(authorAttempts, "authorAttempts").isNotNegative();
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
		for (Map.Entry<SequenceGroup, Long> entry : sequences.entrySet())
			requireThat(entry.getValue(), "lastSequence").withContext(entry.getKey(), "group").isPositive();
		for (LogicalTrial trial : trials)
		{
			TrialKey key = trial.key();
			requireThat(key.sequence(), "trial.key().sequence()").
				isLessThanOrEqualTo(sequences.getOrDefault(key.sequenceGroup(), 0L), "lastSequence");
		}
		requireThat(pendingDecisions, "pendingDecisions").isNotNull();
		requireThat(slowStart, "slowStart").isNotNull();
		requireThat(throttle, "throttle").isNotNull();
		lineages = List.copyOf(lineages);
		attempts = List.copyOf(attempts);
		trials = List.copyOf(trials);
		sequences = Map.copyOf(sequences);
		pendingDecisions = List.copyOf(pendingDecisions);
	}

	/**
	 * Returns a new campaign that waits for its first confirmed contract.
	 *
	 * @param header the campaign's identity and binding; its lifecycle must be {@link Lifecycle#ACTIVE}
	 * @param ceiling the most nested sessions that may run at once
	 * @param incumbent the snapshot recorded at the fork
	 * @param identity the measurement conditions observed so far
	 * @return the campaign
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the header is not active or {@code ceiling} is outside
	 *   [1, {@value #MAXIMUM_CEILING}]
	 */
	public static Campaign start(CampaignHeader header, int ceiling, Sha256 incumbent, MeasurementIdentity identity)
	{
		requireThat(header, "header").isNotNull();
		requireThat(header.lifecycle(), "header.lifecycle()").isEqualTo(Lifecycle.ACTIVE);
		Candidate candidate = new Candidate(0, incumbent);
		return new Campaign(header, ceiling, List.of(), identity, candidate, 0, 0, Development.start(candidate, 0),
			null, List.of(), List.of(), Map.of(), SlowStart.INITIAL, Throttle.NONE, List.of(), false, null,
			null);
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
	 * Returns the current lineage.
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
	 * Returns the certification index that the next commit receives. Only a new campaign starts again from 1.
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
	 * Returns the choices that the user may give now.
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
					choices.add(Choice.NEW_IDENTITY);
			}
			case PendingDecision.UnownedChange _ -> choices.add(Choice.RESUME);
			case PendingDecision.TrialFailures _, PendingDecision.CalibrationFailure _ -> choices.add(Choice.RETRY);
			case PendingDecision.CheckpointReached _ ->
			{
				choices.add(Choice.MORE_EVIDENCE);
				choices.add(Choice.TARGETED_OPTIMIZATION);
				if (offersKeep)
					choices.add(Choice.KEEP_IMPROVEMENT);
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
			case PendingDecision.PlateauReached _, PendingDecision.CertificationUnavailable _ ->
			{
				if (offersKeep)
					choices.add(Choice.KEEP_IMPROVEMENT);
				if (acceptable)
					choices.add(Choice.ACCEPT_WITHOUT_CERTIFICATION);
			}
		}
		choices.add(Choice.STOP);
		return choices;
	}
}
