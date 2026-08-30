package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns benchmark comparison policy and paired-benchmark lifecycle coordination.
 */
public final class Benchmark
{
	/**
	 * Supplies evaluator-wide limits that benchmark policy must preserve.
	 */
	@FunctionalInterface
	public interface Access
	{
		/**
		 * Returns the maximum number of suite trials one evaluation record may contain.
		 *
		 * @return the evaluator-wide maximum trial count
		 */
		int maximumSuiteRuns();
	}

	private static volatile Access access;

	/**
	 * Executes the evaluator-owned work that a paired benchmark schedules.
	 */
	public interface Operations
	{
		/**
		 * Validates the inputs for one unpaired benchmark.
		 *
		 * @param request the immutable benchmark request
		 * @throws IOException if the suite, candidate, or runner inputs are invalid
		 */
		void validateSingle(SingleRequest request) throws IOException;

		/**
		 * Prepares the assembled runner evidence for one unpaired benchmark.
		 *
		 * @param request the immutable benchmark request
		 * @return the assembled runner evidence
		 * @throws IOException if assembly cannot be prepared
		 */
		ObjectNode prepareSingleAssembly(SingleRequest request) throws IOException;

		/**
		 * Prepares the retained record for one unpaired benchmark.
		 *
		 * @param request the immutable benchmark request
		 * @param assembly the assembled runner evidence
		 * @return the prepared retained-record fields
		 * @throws IOException if the record cannot be prepared
		 */
		ObjectNode prepareSingleRecord(SingleRequest request, ObjectNode assembly) throws IOException;

		/**
		 * Binds the prepared benchmark to its retained execution plan.
		 *
		 * @param prepared the prepared retained-record fields
		 * @throws IOException if the plan cannot be bound
		 */
		void bindSinglePlan(ObjectNode prepared) throws IOException;

		/**
		 * Retains every trial outcome for one unpaired benchmark.
		 *
		 * @param prepared the prepared retained-record fields
		 * @param maximumConcurrency the shared worker capacity
		 * @param evaluationRunner the runner that retains executor and validator results
		 * @return the terminal benchmark decision
		 * @throws IOException if the benchmark cannot produce terminal evidence
		 */
		ObjectNode runSingle(ObjectNode prepared, int maximumConcurrency,
			HarnessEvaluationRunner evaluationRunner) throws IOException;

		/**
		 * Completes an unpaired benchmark record after all its trials stop.
		 *
		 * @param prepared the prepared retained-record fields
		 * @throws IOException if its terminal decision cannot be retained
		 */
		void completeSingle(ObjectNode prepared) throws IOException;

		/**
		 * Prepares the shared assembled runner for a new pair.
		 *
		 * @param temporaryRoot the CAT work temp directory
		 * @param suitePath the durable scenario suite
		 * @param buildPlanPath the runner build plan
		 * @param runner the selected runner launcher
		 * @return the assembled runner evidence
		 * @throws IOException if preparation fails
		 */
		ObjectNode prepareAssembly(Path temporaryRoot, Path suitePath, Path buildPlanPath, Path runner)
			throws IOException;

		/**
		 * Reconstructs one retained candidate record.
		 *
		 * @param runPath the retained candidate record
		 * @param candidate the expected immutable candidate
		 * @param trialCount the fixed evidence budget
		 * @return the prepared retained-record fields
		 * @throws IOException if the retained record cannot resume
		 */
		ObjectNode existingRecord(Path runPath, CandidateBundle.Candidate candidate, int trialCount)
			throws IOException;

		/**
		 * Prepares one candidate record for the fixed evidence budget.
		 *
		 * @param request the complete paired request
		 * @param candidate the candidate to prepare
		 * @param assembly the shared assembled runner evidence
		 * @param retainedDirectory the candidate evidence destination
		 * @return the prepared retained-record fields
		 * @throws IOException if preparation fails
		 */
		ObjectNode prepareRecord(Request request, CandidateBundle.Candidate candidate,
			ObjectNode assembly, Path retainedDirectory) throws IOException;

		/**
		 * Executes one equal-trial round for both candidates.
		 *
		 * @param baseline the prepared baseline record
		 * @param candidate the prepared changed-candidate record
		 * @param firstTrial the first trial in this round
		 * @param roundSize the equal number of trials to execute
		 * @param request the complete paired request
		 * @throws IOException if either candidate cannot retain its evidence
		 */
		void executeRound(ObjectNode baseline, ObjectNode candidate, int firstTrial, int roundSize,
			Request request) throws IOException;

		/**
		 * Rejects a pair whose retained evidence has an infrastructure failure.
		 *
		 * @param baselineRun the baseline record
		 * @param candidateRun the changed-candidate record
		 * @throws IOException if the pair cannot produce a valid comparison
		 */
		void failOnInfrastructure(Path baselineRun, Path candidateRun) throws IOException;

		/**
		 * Retains the failure that interrupted one candidate record.
		 *
		 * @param runPath the interrupted candidate record
		 * @param failure the retained interruption cause
		 */
		void recordInterruption(Path runPath, IOException failure);

		/**
		 * Completes one candidate record after pair execution stops.
		 *
		 * @param runPath the completed candidate record
		 * @throws IOException if a terminal decision cannot be retained
		 */
		void complete(Path runPath) throws IOException;

		/**
		 * Produces the final comparison of the terminal candidate records.
		 *
		 * @param baselineRun the terminal baseline record
		 * @param candidateRun the terminal changed-candidate record
		 * @return the retained comparison result
		 * @throws IOException if comparison evidence is incomplete
		 */
		ObjectNode compare(Path baselineRun, Path candidateRun) throws IOException;
	}

	/**
	 * Describes one immutable paired-benchmark request.
	 *
	 * @param temporaryRoot the CAT work temp directory for build work
	 * @param suitePath the durable scenario suite
	 * @param baseline the immutable baseline candidate
	 * @param candidate the immutable changed candidate
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator contract
	 * @param runner the selected runner launcher
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the pair's retained evidence directory
	 * @param trialCount the equal number of trials for each candidate
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that retains executor and validator results
	 */
	public record Request(Path temporaryRoot, Path suitePath, CandidateBundle.Candidate baseline,
		CandidateBundle.Candidate candidate, String harness, Path contextPath, Path runner,
		Path buildPlanPath, Path retainedDirectory, int trialCount, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner)
	{
	}

	/**
	 * Describes one immutable unpaired benchmark request.
	 *
	 * @param temporaryRoot the CAT work temp directory for build work
	 * @param suitePath the durable scenario suite
	 * @param candidate the immutable candidate
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator contract
	 * @param runner the selected runner launcher
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the benchmark's retained evidence directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that retains executor and validator results
	 */
	public record SingleRequest(Path temporaryRoot, Path suitePath, CandidateBundle.Candidate candidate,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory,
		int maximumConcurrency, HarnessEvaluationRunner evaluationRunner)
	{
	}

	private final JsonMapper json;
	private final Operations operations;

	/**
	 * Creates the paired-benchmark lifecycle owner.
	 *
	 * @param json the mapper that creates benchmark receipts
	 * @param operations the evaluator operations that prepare records and execute retained trial evidence
	 */
	public Benchmark(JsonMapper json, Operations operations)
	{
		this.json = json;
		this.operations = operations;
	}

	/**
	 * Measures one immutable candidate without changing candidate-state or promotion state.
	 *
	 * @param request the immutable candidate, retained evidence destination, and evaluator inputs
	 * @return the prepared record, assembly identity, and terminal benchmark decision
	 * @throws IOException if a prerequisite or benchmark batch cannot produce terminal evidence
	 */
	public ObjectNode execute(SingleRequest request) throws IOException
	{
		operations.validateSingle(request);
		ObjectNode assembly = operations.prepareSingleAssembly(request);
		ObjectNode prepared = operations.prepareSingleRecord(request, assembly);
		operations.bindSinglePlan(prepared);
		prepared.set("assembly", assembly);
		Timing.start(json, Path.of(prepared.path("run").asString()));
		prepared.set("decision", operations.runSingle(prepared, request.maximumConcurrency(),
			request.evaluationRunner()));
		operations.completeSingle(prepared);
		return prepared;
	}

	/**
	 * Validates the equal evidence budget for each candidate in a paired comparison.
	 *
	 * @param trialCount the requested count
	 * @throws IOException if the count cannot fit in one evaluation record
	 */
	public static void validateTrialCount(int trialCount) throws IOException
	{
		if (trialCount < 1 || trialCount > access().maximumSuiteRuns())
			throw new IOException("Paired benchmark trial count must be between 1 and " +
				access().maximumSuiteRuns());
	}

	/**
	 * Measures both immutable candidates under one retained fixed-budget lifecycle.
	 *
	 * @param request the immutable candidates, shared evidence budget, and evaluator inputs
	 * @return both prepared records, their terminal decisions, and their shared comparison result
	 * @throws IOException if a prerequisite or benchmark batch cannot produce terminal evidence
	 */
	public ObjectNode execute(Request request) throws IOException
	{
		validateTrialCount(request.trialCount());
		Path retainedDirectory = request.retainedDirectory().toAbsolutePath().normalize();
		Files.createDirectories(retainedDirectory);
		Path pairMarker = retainedDirectory.resolve(".cat-prompt-evaluation-pair-active");
		try (Lock pairLock = Lock.claim(pairMarker))
		{
			pairLock.verifyHeld();
			ObjectNode assembly;
			ObjectNode baseline;
			ObjectNode candidate;
			Path baselineRecord = retainedDirectory.resolve("baseline/run.json");
			Path candidateRecord = retainedDirectory.resolve("candidate/run.json");
			if (Files.exists(baselineRecord, LinkOption.NOFOLLOW_LINKS) ||
				Files.exists(candidateRecord, LinkOption.NOFOLLOW_LINKS))
			{
				baseline = operations.existingRecord(baselineRecord, request.baseline(), request.trialCount());
				candidate = operations.existingRecord(candidateRecord, request.candidate(), request.trialCount());
				assembly = resumedAssembly();
			}
			else
			{
				assembly = operations.prepareAssembly(request.temporaryRoot(), request.suitePath(), request.buildPlanPath(),
					request.runner());
				baseline = operations.prepareRecord(request, request.baseline(), assembly,
					retainedDirectory.resolve("baseline"));
				candidate = operations.prepareRecord(request, request.candidate(), assembly,
					retainedDirectory.resolve("candidate"));
			}
			Path baselineRun = Path.of(baseline.path("run").asString());
			Path candidateRun = Path.of(candidate.path("run").asString());
			try
			{
				for (int firstTrial = 1; firstTrial <= request.trialCount();
					firstTrial += request.maximumConcurrency())
				{
					int roundSize = Math.min(request.maximumConcurrency(), request.trialCount() - firstTrial + 1);
					operations.executeRound(baseline, candidate, firstTrial, roundSize, request);
					operations.failOnInfrastructure(baselineRun, candidateRun);
				}
			}
			catch (IOException e)
			{
				operations.recordInterruption(baselineRun, e);
				operations.recordInterruption(candidateRun, e);
				operations.complete(baselineRun);
				operations.complete(candidateRun);
				throw e;
			}
			operations.complete(baselineRun);
			operations.complete(candidateRun);
			ObjectNode result = json.createObjectNode();
			result.set("assembly", assembly);
			result.set("baseline", baseline);
			result.set("candidate", candidate);
			result.set("comparison", operations.compare(baselineRun, candidateRun));
			return result;
		}
	}

	/**
	 * Returns the result marker for a retained pair resumed by a later caller.
	 *
	 * @return the resumed-pair marker
	 */
	private ObjectNode resumedAssembly()
	{
		ObjectNode result = json.createObjectNode();
		result.put("resumed", true);
		return result;
	}

	/**
	 * Determines whether a retained paired benchmark still owes its fixed evidence budget.
	 *
	 * @param run the evaluation record
	 * @param completedTrials the number of completed whole-suite trials
	 * @param infrastructureFailure whether a terminal infrastructure failure occurred
	 * @return whether an otherwise terminal statistical decision must wait for the fixed budget
	 * @throws IOException if the fixed budget is invalid
	 */
	public static boolean inProgress(ObjectNode run, int completedTrials, boolean infrastructureFailure)
		throws IOException
	{
		if (!hasFixedTrialBudget(run))
			return false;
		int budget = run.path("fixed_trial_budget").asInt(-1);
		validateTrialCount(budget);
		return !infrastructureFailure && completedTrials < budget;
	}

	/**
	 * Returns the maximum trial number allowed by an evaluation record.
	 *
	 * @param run the evaluation record
	 * @return the formal maximum or the paired benchmark's fixed budget
	 * @throws IOException if the paired benchmark's budget is invalid
	 */
	public static int trialLimit(ObjectNode run) throws IOException
	{
		if (!hasFixedTrialBudget(run))
			return access().maximumSuiteRuns();
		int budget = run.path("fixed_trial_budget").asInt(-1);
		validateTrialCount(budget);
		return budget;
	}

	/**
	 * Determines whether a record is a paired benchmark with a fixed evidence budget.
	 *
	 * @param run the evaluation record
	 * @return whether paired-benchmark budget policy applies
	 */
	private static boolean hasFixedTrialBudget(ObjectNode run)
	{
		return run.path("mode").asString().equals("benchmark") && run.has("fixed_trial_budget");
	}

	/**
	 * Registers evaluator-wide limits used by benchmark policy.
	 *
	 * @param access the evaluator-wide limits
	 */
	public static void configure(Access access)
	{
		Benchmark.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Returns the configured evaluator-wide limits.
	 *
	 * @return the evaluator-wide limits
	 * @throws AssertionError if evaluator composition did not configure benchmark policy
	 */
	private static Access access()
	{
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure benchmark access");
		return result;
	}
}
