package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.Diagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Lineage;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.OptimizationEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.RegressionTestCase;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.SequenceGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.SoundTest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisions;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialFiles;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.PairedComparison;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.PairTally;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import io.github.cowwoc.cat.common.secrets.RoundSecrets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds campaign states without running predecessor transitions.
 * <p>
 * For example, {@code new Builder().confirmed().certificationAttempt(1).cell("safety", 250, 2).build()}
 * returns a campaign whose first certification attempt has 250 passes and 2 failures in {@code safety}.
 * <p>
 * Cell counts that a test gives as {@code (passes, failures)} place the failures in the earliest batches. A cell
 * whose counts certify its minimum-pass-rate part, and whose control comparisons the test does not set, has every
 * assertion's control comparison passed by the fewest pairs that only the candidate passed. Unless the candidate is
 * the incumbent, the same holds for its incumbent comparisons.
 */
public final class Builder
{
	/**
	 * The incumbent's snapshot.
	 */
	public static final Sha256 INCUMBENT = snapshot("incumbent");
	/**
	 * The control source's snapshot.
	 */
	public static final Sha256 CONTROL = snapshot("control");
	/**
	 * The default measurement conditions observed before the first confirmation.
	 */
	public static final MeasurementIdentity OBSERVED = observed("model-a");
	/**
	 * Where a judged trial's harness events and file changes were retained, for trials whose evidence a test does not
	 * read.
	 */
	public static final TrialFiles FILES = new TrialFiles(Path.of("/runs/trial/transcript.jsonl"),
		Path.of("/runs/trial/effects"), Path.of("/runs/trial/initial-project"));

	private Header header = new Header("campaign-1", Path.of("/project"),
		Path.of("/project/CLAUDE.md"), Harness.CLAUDE, Lifecycle.ACTIVE);
	private int ceiling = Campaign.DEFAULT_CEILING;
	private Contract contract;
	private int lineage;
	private MeasurementIdentity identity = OBSERVED;
	private Candidate working = new Candidate(0, INCUMBENT);
	private int candidates;
	private int promptAuthorAttempts;
	private int series = 1;
	private final Map<String, CellCounts> development = new LinkedHashMap<>();
	private final List<Failure> failures = new ArrayList<>();
	private Diagnosis diagnosis;
	private Round round;
	private PromptAuthorAttempt promptAuthor;
	private final Map<String, Integer> reviewPoints = new LinkedHashMap<>();
	private int baselineIndex = 1;
	private MeasurementIdentity baselineIdentity;
	private final Map<String, CellCounts> baseline = new LinkedHashMap<>();
	private final List<AttemptBuilder> attempts = new ArrayList<>();
	private final List<LogicalTrial> trials = new ArrayList<>();
	private final List<RegressionTestCase> regressionSet = new ArrayList<>();
	private final Set<Outcome> unavailable = EnumSet.noneOf(Outcome.class);
	private AddedTestCases added = AddedTestCases.NONE;
	private SlowStart slowStart = SlowStart.INITIAL;
	private Throttle throttle = Throttle.NONE;
	private final List<PendingDecision> pendingDecisions = new ArrayList<>();
	private boolean revisionRequested;
	private Outcome outcome;
	private DeliveryRequest delivery;
	private OptimizationEvidence optimization = OptimizationEvidence.EMPTY;
	private final Map<SequenceGroup, Long> sequences = new HashMap<>();
	private long evidenceRevision;
	private final List<AuthorityWarning> answeredWarnings = new ArrayList<>();
	private TestRevisions testRevisions = TestRevisions.NONE;

	/**
	 * Supplies descriptive optimization measurements.
	 *
	 * @param evidence the measurements
	 * @return this builder
	 */
	public Builder optimization(OptimizationEvidence evidence)
	{
		optimization = evidence;
		return this;
	}

	/**
	 * Creates a builder for an active campaign that has no confirmed contract.
	 */
	public Builder()
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
	 * Returns a complete set of observed conditions, other than the contract, the generator and the tool-output token
	 * limit that only a cell under such a limit records, with the given model.
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
		values.remove(MeasurementCondition.TOOL_OUTPUT_LIMIT);
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
	 * Returns the first representative test case of a fixture-contract test, drawn from a fixed development
	 * stream, whose turns contain a text.
	 *
	 * @param cell the test
	 * @param text the text that the test case's turns contain, for example {@code "logs"} for the {@code safety}
	 *   test case that deletes the {@code logs} directory
	 * @return the test case
	 * @throws IllegalArgumentException if the first 1000 draws contain no such test case
	 */
	public static DrawnTestCase testCase(String cell, String text)
	{
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1);
		for (int draw = 0; draw < 1000; ++draw)
		{
			DrawnTestCase testCase = generator.draw(cell, stream);
			if (String.join("\n", testCase.turns()).contains(text))
				return testCase;
		}
		throw new IllegalArgumentException("No test case of " + cell + " contains: " + text);
	}

	/**
	 * Returns the targeted copy of {@link #testCase(String, String)}.
	 *
	 * @param cell the test
	 * @param text the text that the test case's turns contain
	 * @return the test case
	 */
	public static DrawnTestCase targeted(String cell, String text)
	{
		return new Generator(ContractFixtures.contract()).markTargeted(testCase(cell, text));
	}

	/**
	 * Confirms the fixture contract as lineage 1.
	 *
	 * @return this builder
	 */
	public Builder confirmed()
	{
		return confirmed(ContractFixtures.contract(), 1);
	}

	/**
	 * Confirms a contract.
	 *
	 * @param confirmedContract the contract; a contract without a certification scope is confirmed under
	 *   {@link CertificationScope#LATEST_VERSIONS}
	 * @param number the lineage number
	 * @return this builder
	 */
	public Builder confirmed(Contract confirmedContract, int number)
	{
		if (confirmedContract.profiles().scope() == null)
			confirmedContract = confirmedContract.withScope(CertificationScope.LATEST_VERSIONS);
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
	public Builder header(Header value)
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
	public Builder lifecycle(Lifecycle state)
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
	public Builder ceiling(int value)
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
	public Builder identity(MeasurementIdentity value)
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
	public Builder working(int number)
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
	public Builder series(int value)
	{
		this.series = value;
		return this;
	}

	/**
	 * Sets a cell's development counts.
	 *
	 * @param cell the cell identifier
	 * @param passes the passes
	 * @param failures the failures, placed in the earliest batches
	 * @return this builder
	 */
	public Builder development(String cell, int passes, int failures)
	{
		development.put(cell, counts(passes, failures));
		return this;
	}

	/**
	 * Sets a cell's development counts with explicit batches.
	 *
	 * @param cell the cell identifier
	 * @param passes the passes
	 * @param batchFailures the failures of each started batch
	 * @return this builder
	 */
	public Builder development(String cell, int passes, List<Integer> batchFailures)
	{
		int failureCount = batchFailures.stream().mapToInt(Integer::intValue).sum();
		development.put(cell, new CellCounts(passes, failureCount, batchFailures));
		return this;
	}

	/**
	 * Gives every cell of the contract 100 passing development trials.
	 *
	 * @return this builder
	 */
	public Builder readyDevelopment()
	{
		for (String cell : contract.cellIds())
			development(cell, 100, 0);
		return this;
	}

	/**
	 * Adds a failure that a prompt author attempt receives, without a rationale or diagnostic reruns.
	 *
	 * @param testCase the test case that failed
	 * @return this builder
	 */
	public Builder failure(DrawnTestCase testCase)
	{
		return failure(new Failure(testCase, "", List.of(), null, List.of(), List.of(), FILES));
	}

	/**
	 * Adds a failure that a prompt author attempt receives.
	 *
	 * @param failure the failure
	 * @return this builder
	 */
	public Builder failure(Failure failure)
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
	public Builder diagnosis(Diagnosis value)
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
	public Builder round(Round value)
	{
		this.round = value;
		return this;
	}

	/**
	 * Sets the prompt author attempt in progress.
	 *
	 * @param value the attempt
	 * @return this builder
	 */
	public Builder promptAuthor(PromptAuthorAttempt value)
	{
		this.promptAuthor = value;
		if (round != null)
		{
			if (!round.reservations().containsKey(value.number()))
				round = RoundSecrets.access().reserve(round, value.number(), value.cell());
			round = RoundSecrets.access().withAttempt(round, value);
		}
		this.promptAuthorAttempts = Math.max(promptAuthorAttempts, value.number());
		return this;
	}

	/**
	 * Sets the number of validated prompt author attempts.
	 *
	 * @param value the number
	 * @return this builder
	 */
	public Builder promptAuthorAttempts(int value)
	{
		this.promptAuthorAttempts = value;
		return this;
	}

	/**
	 * Sets the development-outcome threshold at which a cell is reviewed.
	 *
	 * @param cell the cell identifier
	 * @param point the number of adopted development outcomes that triggers review
	 * @return this builder
	 */
	public Builder reviewPoint(String cell, int point)
	{
		reviewPoints.put(cell, point);
		return this;
	}

	/**
	 * Sets the incumbent baseline's index.
	 *
	 * @param index the index
	 * @return this builder
	 */
	public Builder baselineIndex(int index)
	{
		this.baselineIndex = index;
		return this;
	}

	/**
	 * Sets the incumbent baseline's measurement conditions, overriding the current identity used by default.
	 *
	 * @param value the conditions under which the baseline was measured
	 * @return this builder
	 */
	public Builder baselineIdentity(MeasurementIdentity value)
	{
		this.baselineIdentity = value;
		return this;
	}

	/**
	 * Sets a cell's baseline counts.
	 *
	 * @param cell the cell identifier
	 * @param passes the passes
	 * @param failures the failures, placed in the earliest batches
	 * @return this builder
	 */
	public Builder baseline(String cell, int passes, int failures)
	{
		baseline.put(cell, counts(passes, failures));
		return this;
	}

	/**
	 * Gives every cell of the contract 50 passing baseline trials, which meets the baseline.
	 *
	 * @return this builder
	 */
	public Builder readyBaseline()
	{
		for (String cell : contract.cellIds())
			baseline(cell, 50, 0);
		return this;
	}

	/**
	 * Adds a test case to the lineage's regression set whose latest working-candidate outcome is not settled.
	 *
	 * @param testCase the test case
	 * @param workingVerdict the working candidate's latest outcome on it, or {@code null} if none
	 * @return this builder
	 */
	public Builder regression(DrawnTestCase testCase, Verdict workingVerdict)
	{
		regressionSet.add(new RegressionTestCase(testCase, workingVerdict, false));
		return this;
	}

	/**
	 * Adds a test case to the lineage's regression set that the working candidate failed last, where an optimization
	 * round that ended without a promotable attempt settled that failure.
	 *
	 * @param testCase the test case
	 * @return this builder
	 */
	public Builder settledRegression(DrawnTestCase testCase)
	{
		regressionSet.add(new RegressionTestCase(testCase, Verdict.FAIL, true));
		return this;
	}

	/**
	 * Records a condition that makes certification unavailable under the lineage.
	 *
	 * @param value the outcome
	 * @return this builder
	 */
	public Builder unavailable(Outcome value)
	{
		unavailable.add(value);
		return this;
	}

	/**
	 * Sets the development test cases added to the lineage's tests.
	 *
	 * @param value the added test cases
	 * @return this builder
	 */
	public Builder added(AddedTestCases value)
	{
		added = value;
		return this;
	}

	/**
	 * Adds an unadopted logical trial.
	 *
	 * @param trial the trial
	 * @return this builder
	 */
	public Builder trial(LogicalTrial trial)
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
	public Builder evidenceRevision(long value)
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
	public Builder slowStart(SlowStart value)
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
	public Builder throttle(Throttle value)
	{
		this.throttle = value;
		return this;
	}

	/**
	 * Adds a pending decision behind those added before.
	 *
	 * @param value the decision
	 * @return this builder
	 */
	public Builder pending(PendingDecision value)
	{
		pendingDecisions.add(value);
		return this;
	}

	/**
	 * Records a warning that the user answered by choosing to use an authorized source, behind those added before.
	 *
	 * @param value the warning
	 * @return this builder
	 */
	public Builder answered(AuthorityWarning value)
	{
		answeredWarnings.add(value);
		return this;
	}

	/**
	 * Sets the requested, applied and declined test revisions.
	 *
	 * @param value the test revisions
	 * @return this builder
	 */
	public Builder testRevisions(TestRevisions value)
	{
		this.testRevisions = value;
		return this;
	}

	/**
	 * Records that a test revision examined each test in the first lineage and found it sound.
	 *
	 * @param tests the identifiers of the tests
	 * @return this builder
	 */
	public Builder sound(String... tests)
	{
		List<SoundTest> sound = new ArrayList<>(testRevisions.sound());
		for (String test : tests)
			sound.add(new SoundTest(1, test, "The turns are organic and prove the claims."));
		this.testRevisions = new TestRevisions(testRevisions.requests(), testRevisions.revisions(), sound);
		return this;
	}

	/**
	 * Marks a contract revision as requested.
	 *
	 * @return this builder
	 */
	public Builder revisionRequested()
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
	public Builder outcome(Outcome value)
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
	public Builder delivery(DeliveryRequest value)
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
	public static CellCounts counts(int passes, int failures)
	{
		int outcomes = passes + failures;
		List<Integer> batches = new ArrayList<>();
		int remaining = failures;
		for (int start = 0; start < outcomes; start += CellCounts.BATCH_SIZE)
		{
			int size = Math.min(CellCounts.BATCH_SIZE, outcomes - start);
			int batch = Math.min(size, remaining);
			batches.add(batch);
			remaining -= batch;
		}
		return new CellCounts(passes, failures, batches);
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
				lineages.add(lineage(number, List.of(), Set.of(), AddedTestCases.NONE));
			lineages.add(lineage(lineage, regressionSet, unavailable, added));
			MeasurementIdentity measuredIdentity = baselineIdentity;
			if (measuredIdentity == null)
				measuredIdentity = identity;
			incumbentBaseline = new IncumbentBaseline(baselineIndex, measuredIdentity, baseline);
		}
		Development state = new Development(working, series, development, failures, diagnosis, round, promptAuthor,
			reviewPoints);
		List<CertificationAttempt> built = new ArrayList<>();
		for (AttemptBuilder attempt : attempts)
			built.add(attempt.toAttempt());
		return new Campaign(header, ceiling, lineages, identity, new Candidate(0, INCUMBENT), candidates,
			promptAuthorAttempts, state, incumbentBaseline, built, trials, sequences, evidenceRevision, slowStart,
			throttle, pendingDecisions, revisionRequested, outcome, delivery, optimization, answeredWarnings,
			testRevisions);
	}

	/**
	 * Returns a lineage of the confirmed contract.
	 *
	 * @param number the lineage's number
	 * @param regressions the regression set
	 * @param conditions the conditions that make certification unavailable
	 * @param addedTestCases the development test cases added to the lineage's tests
	 * @return the lineage
	 */
	private Lineage lineage(int number, List<RegressionTestCase> regressions, Set<Outcome> conditions,
		AddedTestCases addedTestCases)
	{
		return new Lineage(number, contract, contractHash(), new DrawStream.State(DrawStream.Kind.DEVELOPMENT,
			number, 0, true), new DrawStream.State(DrawStream.Kind.BASELINE, number, 0, true), regressions,
			conditions, addedTestCases);
	}

	/**
	 * Builds one certification attempt.
	 */
	public final class AttemptBuilder
	{
		private final int index;
		private final Map<String, CellCounts> counts = new LinkedHashMap<>();
		private final Map<String, Pairing> controlPairings = new LinkedHashMap<>();
		private final Map<String, Pairing> incumbentPairings = new LinkedHashMap<>();
		private final List<Failure> attemptFailures = new ArrayList<>();
		private Integer attemptLineage;
		private Candidate candidate;
		private MeasurementIdentity attemptIdentity;
		private MeasurementIdentity incumbentIdentity;
		private DecisionBoundaries boundaries;
		private Outcome attemptOutcome;
		private List<FailureGroup> attemptGroups;

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
		 * @param cell the cell identifier
		 * @param passes the passes
		 * @param failures the failures, placed in the earliest batches
		 * @return this builder
		 */
		public AttemptBuilder cell(String cell, int passes, int failures)
		{
			counts.put(cell, counts(passes, failures));
			return this;
		}

		/**
		 * Sets a cell's control comparisons.
		 *
		 * @param cell the cell identifier
		 * @param pairing the cell's control comparisons
		 * @return this builder
		 */
		public AttemptBuilder controlPairing(String cell, Pairing pairing)
		{
			controlPairings.put(cell, pairing);
			return this;
		}

		/**
		 * Sets a cell's incumbent comparisons.
		 *
		 * @param cell the cell identifier
		 * @param pairing the cell's incumbent comparisons
		 * @return this builder
		 */
		public AttemptBuilder incumbentPairing(String cell, Pairing pairing)
		{
			incumbentPairings.put(cell, pairing);
			return this;
		}

		/**
		 * Adds a formal failure, without a rationale.
		 *
		 * @param testCase the test case that failed
		 * @return this builder
		 */
		public AttemptBuilder failure(DrawnTestCase testCase)
		{
			return failure(new Failure(testCase, "", List.of(), null, List.of(), List.of(), FILES));
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
		 * Sets the measurement conditions of the frozen incumbent bounds, overriding the attempt identity used by
		 * default.
		 *
		 * @param value the conditions under which the frozen baseline was measured
		 * @return this builder
		 */
		public AttemptBuilder baselineIdentity(MeasurementIdentity value)
		{
			this.incumbentIdentity = value;
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
		 * Sets the attempt's failure groups, overriding the groups that an ended attempt forms from its failures.
		 *
		 * @param values the groups
		 * @return this builder
		 */
		public AttemptBuilder groups(List<FailureGroup> values)
		{
			this.attemptGroups = values;
			return this;
		}

		/**
		 * Returns the campaign builder.
		 *
		 * @return the campaign builder
		 */
		public Builder campaign()
		{
			return Builder.this;
		}

		/**
		 * Builds the campaign.
		 *
		 * @return the campaign
		 */
		public Campaign build()
		{
			return Builder.this.build();
		}

		/**
		 * Returns comparisons in which every assertion of a cell passed by the fewest pairs that only the candidate
		 * passed, with no candidate formal trial waiting for a reference trial.
		 *
		 * @param cell the cell identifier
		 * @param level the attempt's certification level
		 * @return the comparisons
		 */
		private Pairing passedComparisons(String cell, SignificanceLevel level)
		{
			int pairs = PairedComparison.fewestCandidateAheadPairsToPass(PairTally.EMPTY, level, 1000);
			Map<String, PairCounts> comparisons = new LinkedHashMap<>();
			for (Assertion assertion : contract.testOf(contract.findCell(cell).orElseThrow()).allAssertions())
				comparisons.put(assertion.id(), new PairCounts(0, pairs, 0, 0));
			return new Pairing(List.of(), comparisons);
		}

		/**
		 * Builds the attempt.
		 *
		 * @return the attempt
		 */
		private CertificationAttempt toAttempt()
		{
			List<String> cells = CellTier.scopeCellIds(contract);
			DecisionBoundaries frozen = boundaries;
			if (frozen == null)
				frozen = Boundaries.standard(cells.size());
			int number = lineage;
			if (attemptLineage != null)
				number = attemptLineage;
			Candidate frozenCandidate = candidate;
			if (frozenCandidate == null)
				frozenCandidate = working;
			MeasurementIdentity frozenIdentity = attemptIdentity;
			if (frozenIdentity == null)
				frozenIdentity = identity;
			MeasurementIdentity frozenBaselineIdentity = incumbentIdentity;
			if (frozenBaselineIdentity == null)
				frozenBaselineIdentity = frozenIdentity;
			CertificationProcedure procedure = new CertificationProcedure(
				ErrorBudget.improvementLevel(index, cells.size()), baselineIndex, frozenBaselineIdentity,
				cells, frozen, CertificationProcedure.BETS, CertificationProcedure.ALLOCATION_POLICY);
			List<FailureGroup> groups = attemptGroups;
			if (groups == null)
			{
				if (attemptOutcome == Outcome.BELOW_MINIMUM || attemptOutcome == Outcome.INCONCLUSIVE)
					groups = FailureGroup.of(attemptFailures);
				else
					groups = List.of();
			}
			Map<String, Pairing> frozenControlPairings = new LinkedHashMap<>(controlPairings);
			Map<String, Pairing> frozenIncumbentPairings = new LinkedHashMap<>(incumbentPairings);
			boolean comparesIncumbent = !frozenCandidate.snapshot().equals(INCUMBENT);
			for (Map.Entry<String, CellCounts> entry : counts.entrySet())
			{
				String cell = entry.getKey();
				CellCounts cellCounts = entry.getValue();
				if (!frozen.certifies(cellCounts.passes(), cellCounts.failures()))
					continue;
				if (!frozenControlPairings.containsKey(cell))
					frozenControlPairings.put(cell, passedComparisons(cell, frozen.level()));
				if (comparesIncumbent && !frozenIncumbentPairings.containsKey(cell))
					frozenIncumbentPairings.put(cell, passedComparisons(cell, frozen.level()));
			}
			return new CertificationAttempt(index, number, frozenCandidate, frozenIdentity, procedure, CONTROL, counts,
				frozenControlPairings, frozenIncumbentPairings, attemptFailures,
				new DrawStream.State(DrawStream.Kind.FORMAL, index, 0, true), attemptOutcome, groups);
		}
	}
}
