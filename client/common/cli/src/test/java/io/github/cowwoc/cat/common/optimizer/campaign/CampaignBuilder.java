package io.github.cowwoc.cat.common.optimizer.campaign;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds campaign states without running predecessor transitions.
 * <p>
 * For example, {@code new CampaignBuilder().confirmed().certificationAttempt(1).category("safety", 250, 2).build()}
 * returns a campaign whose first certification attempt has 250 passes and 2 failures in {@code safety}.
 * <p>
 * Cell counts that a test gives as {@code (passes, failures)} place the failures in the earliest batches.
 */
public final class CampaignBuilder
{
	/**
	 * The incumbent's snapshot.
	 */
	public static final Sha256 INCUMBENT = snapshot("incumbent");
	/**
	 * The default measurement conditions observed before the first confirmation.
	 */
	public static final MeasurementIdentity OBSERVED = observed("model-a");

	private CampaignHeader header = new CampaignHeader("campaign-1", Path.of("/project"),
		Path.of("/project/CLAUDE.md"), Harness.CLAUDE, Lifecycle.ACTIVE);
	private int ceiling = Campaign.DEFAULT_CEILING;
	private Contract contract;
	private int lineage;
	private MeasurementIdentity identity = OBSERVED;
	private Candidate working = new Candidate(0, INCUMBENT);
	private int candidates;
	private int authorAttempts;
	private int series = 1;
	private final Map<String, CategoryCounts> development = new LinkedHashMap<>();
	private final List<Failure> failures = new ArrayList<>();
	private Diagnosis diagnosis;
	private Round round;
	private AuthorAttempt author;
	private final Map<String, Integer> reviewPoints = new LinkedHashMap<>();
	private boolean widened;
	private final Set<String> failedCells = new LinkedHashSet<>();
	private int baselineIndex = 1;
	private final Map<String, CategoryCounts> baseline = new LinkedHashMap<>();
	private final List<AttemptBuilder> attempts = new ArrayList<>();
	private final List<LogicalTrial> trials = new ArrayList<>();
	private final List<RegressionInstance> regressionSet = new ArrayList<>();
	private final Set<Outcome> unavailable = EnumSet.noneOf(Outcome.class);
	private SlowStart slowStart = SlowStart.INITIAL;
	private Throttle throttle = Throttle.NONE;
	private final Set<Path> authorizedTargets = new LinkedHashSet<>();
	private final List<PendingDecision> pendingDecisions = new ArrayList<>();
	private boolean revisionRequested;
	private Outcome outcome;
	private DeliveryRequest delivery;
	private OptimizationEvidence optimization = OptimizationEvidence.EMPTY;
	private final Map<SequenceGroup, Long> sequences = new HashMap<>();
	private long evidenceRevision;

	/**
	 * Supplies descriptive optimization measurements.
	 *
	 * @param evidence the measurements
	 * @return this builder
	 */
	public CampaignBuilder optimization(OptimizationEvidence evidence)
	{
		optimization = evidence;
		return this;
	}

	/**
	 * Creates a builder for an active campaign that has no confirmed contract.
	 */
	public CampaignBuilder()
	{
	}

	/**
	 * Returns a snapshot identity derived from a name.
	 *
	 * @param name the snapshot's name
	 * @return the identity
	 */
	public static Sha256 snapshot(String name)
	{
		return Sha256.of(name.getBytes(UTF_8));
	}

	/**
	 * Returns a complete set of observed conditions, other than the contract and generator, with the given model.
	 *
	 * @param model the observed model
	 * @return the identity
	 */
	public static MeasurementIdentity observed(String model)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		for (MeasurementCondition condition : MeasurementCondition.values())
			values.put(condition, condition.name().toLowerCase(Locale.ROOT) + "-1");
		values.remove(MeasurementCondition.CONTRACT);
		values.remove(MeasurementCondition.GENERATOR);
		values.put(MeasurementCondition.MODEL, model);
		return new MeasurementIdentity(values);
	}

	/**
	 * Returns {@link #observed(String)} extended with the fixture contract's and generator's identities, as the
	 * campaign records it after {@link #confirmed()}.
	 *
	 * @param model the observed model
	 * @return the identity
	 */
	public static MeasurementIdentity confirmedIdentity(String model)
	{
		return withContract(observed(model));
	}

	/**
	 * Returns an identity extended with the fixture contract's and generator's identities.
	 *
	 * @param identity the identity
	 * @return the extended identity
	 */
	private static MeasurementIdentity withContract(MeasurementIdentity identity)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(identity.values());
		values.put(MeasurementCondition.CONTRACT, contractHash().hex());
		values.put(MeasurementCondition.GENERATOR, Generator.IDENTITY);
		return new MeasurementIdentity(values, identity.cells());
	}

	/**
	 * Returns the identity of the fixture contract.
	 *
	 * @return the identity
	 */
	public static Sha256 contractHash()
	{
		return snapshot("contract");
	}

	/**
	 * Returns the first representative instance of a fixture-contract category, drawn from a fixed development
	 * stream, whose turns contain a text.
	 *
	 * @param category the category
	 * @param text the text that the instance's turns contain, for example {@code "logs"} for the {@code safety}
	 *   instance that deletes the {@code logs} directory
	 * @return the instance
	 * @throws IllegalArgumentException if the first 1000 draws contain no such instance
	 */
	public static TrialInstance instance(String category, String text)
	{
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1);
		for (int draw = 0; draw < 1000; ++draw)
		{
			TrialInstance instance = generator.draw(category, stream);
			if (String.join("\n", instance.turns()).contains(text))
				return instance;
		}
		throw new IllegalArgumentException("No instance of " + category + " contains: " + text);
	}

	/**
	 * Returns the targeted copy of {@link #instance(String, String)}.
	 *
	 * @param category the category
	 * @param text the text that the instance's turns contain
	 * @return the instance
	 */
	public static TrialInstance targeted(String category, String text)
	{
		return new Generator(ContractFixtures.contract()).markTargeted(instance(category, text));
	}

	/**
	 * Confirms the fixture contract as lineage 1.
	 *
	 * @return this builder
	 */
	public CampaignBuilder confirmed()
	{
		return confirmed(ContractFixtures.contract(), 1);
	}

	/**
	 * Confirms a contract.
	 *
	 * @param confirmedContract the contract
	 * @param number the lineage number
	 * @return this builder
	 */
	public CampaignBuilder confirmed(Contract confirmedContract, int number)
	{
		this.contract = confirmedContract;
		this.lineage = number;
		identity = withContract(identity);
		return this;
	}

	/**
	 * Sets the campaign's header.
	 *
	 * @param value the header
	 * @return this builder
	 */
	public CampaignBuilder header(CampaignHeader value)
	{
		this.header = value;
		return this;
	}

	/**
	 * Sets the lifecycle state.
	 *
	 * @param state the state
	 * @return this builder
	 */
	public CampaignBuilder lifecycle(Lifecycle state)
	{
		this.header = header.withLifecycle(state);
		return this;
	}

	/**
	 * Sets the campaign ceiling.
	 *
	 * @param value the ceiling
	 * @return this builder
	 */
	public CampaignBuilder ceiling(int value)
	{
		this.ceiling = value;
		return this;
	}

	/**
	 * Sets the current measurement identity.
	 *
	 * @param value the identity
	 * @return this builder
	 */
	public CampaignBuilder identity(MeasurementIdentity value)
	{
		this.identity = value;
		return this;
	}

	/**
	 * Makes a promoted candidate the working candidate.
	 *
	 * @param number the candidate's number, at least 1
	 * @return this builder
	 */
	public CampaignBuilder working(int number)
	{
		this.working = new Candidate(number, snapshot("candidate-" + number));
		this.candidates = Math.max(candidates, number);
		return this;
	}

	/**
	 * Sets the development series.
	 *
	 * @param value the series
	 * @return this builder
	 */
	public CampaignBuilder series(int value)
	{
		this.series = value;
		return this;
	}

	/**
	 * Sets a cell's development counts.
	 *
	 * @param category the cell identifier
	 * @param passes the passes
	 * @param failures the failures, placed in the earliest batches
	 * @return this builder
	 */
	public CampaignBuilder development(String category, int passes, int failures)
	{
		development.put(category, counts(passes, failures));
		return this;
	}

	/**
	 * Sets a cell's development counts with explicit batches.
	 *
	 * @param category the cell identifier
	 * @param passes the passes
	 * @param batchFailures the failures of each started batch
	 * @return this builder
	 */
	public CampaignBuilder development(String category, int passes, List<Integer> batchFailures)
	{
		int failureCount = batchFailures.stream().mapToInt(Integer::intValue).sum();
		development.put(category, new CategoryCounts(passes, failureCount, batchFailures));
		return this;
	}

	/**
	 * Gives every cell of the contract 100 passing development trials.
	 *
	 * @return this builder
	 */
	public CampaignBuilder readyDevelopment()
	{
		for (String cell : contract.cellIds())
			development(cell, 100, 0);
		return this;
	}

	/**
	 * Adds a failure that an author attempt receives, without a rationale or diagnostic reruns.
	 *
	 * @param instance the instance that failed
	 * @return this builder
	 */
	public CampaignBuilder failure(TrialInstance instance)
	{
		return failure(new Failure(instance, "", List.of()));
	}

	/**
	 * Adds a failure that an author attempt receives.
	 *
	 * @param failure the failure
	 * @return this builder
	 */
	public CampaignBuilder failure(Failure failure)
	{
		failures.add(failure);
		return this;
	}

	/**
	 * Sets the diagnosis in progress.
	 *
	 * @param value the diagnosis
	 * @return this builder
	 */
	public CampaignBuilder diagnosis(Diagnosis value)
	{
		this.diagnosis = value;
		return this;
	}

	/**
	 * Sets the development round in progress.
	 *
	 * @param value the round
	 * @return this builder
	 */
	public CampaignBuilder round(Round value)
	{
		this.round = value;
		return this;
	}

	/**
	 * Sets the author attempt in progress.
	 *
	 * @param value the attempt
	 * @return this builder
	 */
	public CampaignBuilder author(AuthorAttempt value)
	{
		this.author = value;
		this.authorAttempts = Math.max(authorAttempts, value.number());
		return this;
	}

	/**
	 * Sets the number of validated author attempts.
	 *
	 * @param value the number
	 * @return this builder
	 */
	public CampaignBuilder authorAttempts(int value)
	{
		this.authorAttempts = value;
		return this;
	}

	/**
	 * Sets the development-outcome threshold at which a cell is reviewed.
	 *
	 * @param category the cell identifier
	 * @param point the number of adopted development outcomes that triggers review
	 * @return this builder
	 */
	public CampaignBuilder reviewPoint(String category, int point)
	{
		reviewPoints.put(category, point);
		return this;
	}

	/**
	 * Marks the development series as widened, so that every cell is eligible for representative trials.
	 *
	 * @return this builder
	 */
	public CampaignBuilder widened()
	{
		widened = true;
		return this;
	}

	/**
	 * Records that a candidate of the campaign has failed a trial in a cell, which makes the cell active.
	 *
	 * @param cell the cell's identifier
	 * @return this builder
	 */
	public CampaignBuilder failedCell(String cell)
	{
		failedCells.add(cell);
		return this;
	}

	/**
	 * Sets the incumbent baseline's index.
	 *
	 * @param index the index
	 * @return this builder
	 */
	public CampaignBuilder baselineIndex(int index)
	{
		this.baselineIndex = index;
		return this;
	}

	/**
	 * Sets a cell's baseline counts.
	 *
	 * @param category the cell identifier
	 * @param passes the passes
	 * @param failures the failures, placed in the earliest batches
	 * @return this builder
	 */
	public CampaignBuilder baseline(String category, int passes, int failures)
	{
		baseline.put(category, counts(passes, failures));
		return this;
	}

	/**
	 * Gives every cell of the contract 50 passing baseline trials, which meets the baseline.
	 *
	 * @return this builder
	 */
	public CampaignBuilder readyBaseline()
	{
		for (String cell : contract.cellIds())
			baseline(cell, 50, 0);
		return this;
	}

	/**
	 * Adds an instance to the lineage's regression set.
	 *
	 * @param instance the instance
	 * @param workingVerdict the working candidate's latest outcome on it, or {@code null} if none
	 * @return this builder
	 */
	public CampaignBuilder regression(TrialInstance instance, Verdict workingVerdict)
	{
		regressionSet.add(new RegressionInstance(instance, workingVerdict));
		return this;
	}

	/**
	 * Records a condition that makes certification unavailable under the lineage.
	 *
	 * @param value the outcome
	 * @return this builder
	 */
	public CampaignBuilder unavailable(Outcome value)
	{
		unavailable.add(value);
		return this;
	}

	/**
	 * Adds an unadopted logical trial.
	 *
	 * @param trial the trial
	 * @return this builder
	 */
	public CampaignBuilder trial(LogicalTrial trial)
	{
		trials.add(trial);
		sequences.merge(trial.key().sequenceGroup(), trial.key().sequence(), Math::max);
		return this;
	}

	/**
	 * Sets the number of outcomes the campaign has adopted.
	 *
	 * @param value the evidence revision
	 * @return this builder
	 */
	public CampaignBuilder evidenceRevision(long value)
	{
		this.evidenceRevision = value;
		return this;
	}

	/**
	 * Sets the slow-start state.
	 *
	 * @param value the state
	 * @return this builder
	 */
	public CampaignBuilder slowStart(SlowStart value)
	{
		this.slowStart = value;
		return this;
	}

	/**
	 * Sets the throttle state.
	 *
	 * @param value the state
	 * @return this builder
	 */
	public CampaignBuilder throttle(Throttle value)
	{
		this.throttle = value;
		return this;
	}

	/**
	 * Adds an external target whose content the user authorized CAT to edit.
	 *
	 * @param value the target's absolute path
	 * @return this builder
	 */
	public CampaignBuilder authorizedTarget(Path value)
	{
		authorizedTargets.add(value);
		return this;
	}

	/**
	 * Adds a pending decision behind those added before.
	 *
	 * @param value the decision
	 * @return this builder
	 */
	public CampaignBuilder pending(PendingDecision value)
	{
		pendingDecisions.add(value);
		return this;
	}

	/**
	 * Marks a contract revision as requested.
	 *
	 * @return this builder
	 */
	public CampaignBuilder revisionRequested()
	{
		this.revisionRequested = true;
		return this;
	}

	/**
	 * Sets the outcome that the campaign reports.
	 *
	 * @param value the outcome
	 * @return this builder
	 */
	public CampaignBuilder outcome(Outcome value)
	{
		this.outcome = value;
		return this;
	}

	/**
	 * Sets the delivery request.
	 *
	 * @param value the request
	 * @return this builder
	 */
	public CampaignBuilder delivery(DeliveryRequest value)
	{
		this.delivery = value;
		return this;
	}

	/**
	 * Returns the builder of a certification attempt, creating it and any earlier missing attempts.
	 * <p>
	 * A new attempt is running and tests the working candidate under the current lineage and identity, with the
	 * {@link Boundaries#standard(int) standard} boundaries. Earlier attempts created implicitly ended
	 * {@link Outcome#INCONCLUSIVE} with no outcomes.
	 *
	 * @param index the certification index
	 * @return the attempt's builder
	 */
	public AttemptBuilder certificationAttempt(int index)
	{
		while (attempts.size() < index)
		{
			AttemptBuilder attempt = new AttemptBuilder(attempts.size() + 1);
			if (attempts.size() + 1 < index)
				attempt.outcome(Outcome.INCONCLUSIVE);
			attempts.add(attempt);
		}
		return attempts.get(index - 1);
	}

	/**
	 * Returns counts whose failures fill the earliest batches.
	 *
	 * @param passes the passes
	 * @param failures the failures
	 * @return the counts
	 */
	public static CategoryCounts counts(int passes, int failures)
	{
		int outcomes = passes + failures;
		List<Integer> batches = new ArrayList<>();
		int remaining = failures;
		for (int start = 0; start < outcomes; start += CategoryCounts.BATCH_SIZE)
		{
			int size = Math.min(CategoryCounts.BATCH_SIZE, outcomes - start);
			int batch = Math.min(size, remaining);
			batches.add(batch);
			remaining -= batch;
		}
		return new CategoryCounts(passes, failures, batches);
	}

	/**
	 * Builds the campaign.
	 *
	 * @return the campaign
	 */
	public Campaign build()
	{
		List<Lineage> lineages = new ArrayList<>();
		IncumbentBaseline incumbentBaseline = null;
		if (contract != null)
		{
			for (int number = 1; number < lineage; ++number)
				lineages.add(lineage(number, List.of(), Set.of()));
			lineages.add(lineage(lineage, regressionSet, unavailable));
			incumbentBaseline = new IncumbentBaseline(baselineIndex, baseline);
		}
		Development state = new Development(working, series, development, failures, diagnosis, round, author,
			reviewPoints, widened);
		List<CertificationAttempt> built = new ArrayList<>();
		for (AttemptBuilder attempt : attempts)
			built.add(attempt.toAttempt());
		return new Campaign(header, ceiling, lineages, identity, new Candidate(0, INCUMBENT), candidates,
			authorAttempts, state, failedCells, incumbentBaseline, built, trials, sequences, evidenceRevision, slowStart,
			throttle, authorizedTargets, pendingDecisions, revisionRequested, outcome, delivery, optimization);
	}

	/**
	 * Returns a lineage of the confirmed contract.
	 *
	 * @param number the lineage's number
	 * @param regressions the regression set
	 * @param conditions the conditions that make certification unavailable
	 * @return the lineage
	 */
	private Lineage lineage(int number, List<RegressionInstance> regressions, Set<Outcome> conditions)
	{
		return new Lineage(number, contract, contractHash(), new DrawStream.State(DrawStream.Kind.DEVELOPMENT,
			number, 0, true), new DrawStream.State(DrawStream.Kind.BASELINE, number, 0, true), regressions,
			conditions);
	}

	/**
	 * Builds one certification attempt.
	 */
	public final class AttemptBuilder
	{
		private final int index;
		private final Map<String, CategoryCounts> counts = new LinkedHashMap<>();
		private final List<Failure> attemptFailures = new ArrayList<>();
		private Integer attemptLineage;
		private Candidate candidate;
		private MeasurementIdentity attemptIdentity;
		private DecisionBoundaries boundaries;
		private Outcome attemptOutcome;

		/**
		 * Creates a builder for a running attempt.
		 *
		 * @param index the certification index
		 */
		private AttemptBuilder(int index)
		{
			this.index = index;
		}

		/**
		 * Sets a cell's certification counts.
		 *
		 * @param category the cell identifier
		 * @param passes the passes
		 * @param failures the failures, placed in the earliest batches
		 * @return this builder
		 */
		public AttemptBuilder category(String category, int passes, int failures)
		{
			counts.put(category, counts(passes, failures));
			return this;
		}

		/**
		 * Adds a formal failure, without a rationale.
		 *
		 * @param instance the instance that failed
		 * @return this builder
		 */
		public AttemptBuilder failure(TrialInstance instance)
		{
			return failure(new Failure(instance, "", List.of()));
		}

		/**
		 * Adds a formal failure.
		 *
		 * @param failure the failure
		 * @return this builder
		 */
		public AttemptBuilder failure(Failure failure)
		{
			attemptFailures.add(failure);
			return this;
		}

		/**
		 * Sets the attempt's lineage.
		 *
		 * @param number the lineage number
		 * @return this builder
		 */
		public AttemptBuilder lineage(int number)
		{
			this.attemptLineage = number;
			return this;
		}

		/**
		 * Sets the frozen candidate.
		 *
		 * @param value the candidate
		 * @return this builder
		 */
		public AttemptBuilder candidate(Candidate value)
		{
			this.candidate = value;
			return this;
		}

		/**
		 * Sets the attempt's measurement identity.
		 *
		 * @param value the identity
		 * @return this builder
		 */
		public AttemptBuilder identity(MeasurementIdentity value)
		{
			this.attemptIdentity = value;
			return this;
		}

		/**
		 * Sets the attempt's boundaries.
		 *
		 * @param value the boundaries
		 * @return this builder
		 */
		public AttemptBuilder boundaries(DecisionBoundaries value)
		{
			this.boundaries = value;
			return this;
		}

		/**
		 * Sets the attempt's outcome or leaves it running.
		 *
		 * @param value the outcome, or {@code null} to keep it running
		 * @return this builder
		 */
		public AttemptBuilder outcome(Outcome value)
		{
			this.attemptOutcome = value;
			return this;
		}

		/**
		 * Returns the campaign builder.
		 *
		 * @return the campaign builder
		 */
		public CampaignBuilder campaign()
		{
			return CampaignBuilder.this;
		}

		/**
		 * Builds the campaign.
		 *
		 * @return the campaign
		 */
		public Campaign build()
		{
			return CampaignBuilder.this.build();
		}

		/**
		 * Builds the attempt.
		 *
		 * @return the attempt
		 */
		private CertificationAttempt toAttempt()
		{
			List<String> categories = contract.cellIds();
			DecisionBoundaries frozen = boundaries;
			if (frozen == null)
				frozen = Boundaries.standard(categories.size());
			CertificationProcedure procedure = new CertificationProcedure(
				ErrorBudget.improvementLevel(index, categories.size()), baselineIndex, categories, frozen,
				CertificationProcedure.BETS, CertificationProcedure.ALLOCATION_POLICY);
			int number = lineage;
			if (attemptLineage != null)
				number = attemptLineage;
			Candidate frozenCandidate = candidate;
			if (frozenCandidate == null)
				frozenCandidate = working;
			MeasurementIdentity frozenIdentity = attemptIdentity;
			if (frozenIdentity == null)
				frozenIdentity = identity;
			return new CertificationAttempt(index, number, frozenCandidate, frozenIdentity, procedure, counts,
				attemptFailures, new DrawStream.State(DrawStream.Kind.FORMAL, index, 0, true), attemptOutcome);
		}
	}
}
