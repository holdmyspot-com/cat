package io.github.cowwoc.cat.common.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns live worker coordination for one fixed-budget paired benchmark round.
 */
public final class BenchmarkExecution
{
	private static final String ACTIVE_MARKER = ".cat-prompt-evaluation-active";
	private static final String EVIDENCE_MARKER = ".cat-prompt-evaluation-evidence";

	private final Access access;

	/**
	 * Creates the paired-benchmark live-worker owner.
	 *
	 * @param access the evaluator operations that own records and worker execution
	 */
	public BenchmarkExecution(Access access)
	{
		this.access = access;
	}

	/**
	 * Runs one matched round without letting an early prompt result reduce the agreed evidence budget.
	 *
	 * @param baselineRun the prepared baseline record
	 * @param baselineWorkspace the initially absent baseline round workspace
	 * @param baselinePlan the baseline execution plan
	 * @param candidateRun the prepared changed-candidate record
	 * @param candidateWorkspace the initially absent changed-candidate round workspace
	 * @param candidatePlan the changed-candidate execution plan
	 * @param roundSize the equal number of trials to claim for each candidate
	 * @param maximumConcurrency the shared worker capacity for the complete pair
	 * @param evaluationRunner the runner that retains executor and validator results
	 * @throws IOException if either candidate cannot retain terminal evidence
	 */
	public void runFixedBenchmarkRound(Path baselineRun, Path baselineWorkspace, Path baselinePlan,
		Path candidateRun, Path candidateWorkspace, Path candidatePlan, int roundSize, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		access.validateMaximumConcurrency(maximumConcurrency);
		access.bindExecutionPlan(baselineRun, baselinePlan);
		access.bindExecutionPlan(candidateRun, candidatePlan);
		access.ensureFixedBenchmarkRun(baselineRun);
		access.ensureFixedBenchmarkRun(candidateRun);
		access.bindMaximumConcurrency(baselineRun, maximumConcurrency);
		access.bindMaximumConcurrency(candidateRun, maximumConcurrency);
		List<Integer> baselineTrials = existingOrClaimedTrials(baselineRun, baselineWorkspace, roundSize);
		List<Integer> candidateTrials = existingOrClaimedTrials(candidateRun, candidateWorkspace, roundSize);
		if (!baselineTrials.equals(candidateTrials))
			throw new IOException("Paired benchmark candidates retained different trial claims for one comparison round");

		recoverInterruptedBatch(baselineRun, baselineWorkspace);
		recoverInterruptedBatch(candidateRun, candidateWorkspace);
		if (roundComplete(baselineRun, baselineTrials) && roundComplete(candidateRun, candidateTrials))
			return;
		if (maximumConcurrency == 1)
		{
			runBatch(baselineRun, baselineTrials, baselineWorkspace, baselinePlan, maximumConcurrency, evaluationRunner);
			runBatch(candidateRun, candidateTrials, candidateWorkspace, candidatePlan, maximumConcurrency,
				evaluationRunner);
			return;
		}
		int baselineCapacity = (maximumConcurrency + 1) / 2;
		int candidateCapacity = maximumConcurrency - baselineCapacity;
		try (ExecutorService workers = Executors.newFixedThreadPool(2))
		{
			Future<?> baseline = workers.submit(() ->
			{
				runBatch(baselineRun, baselineTrials, baselineWorkspace, baselinePlan, baselineCapacity, evaluationRunner);
				return null;
			});
			Future<?> candidate = workers.submit(() ->
			{
				runBatch(candidateRun, candidateTrials, candidateWorkspace, candidatePlan, candidateCapacity,
					evaluationRunner);
				return null;
			});
			awaitBatch(baseline);
			awaitBatch(candidate);
		}
	}

	/**
	 * Stops a pair whose retained evidence contains an infrastructure failure.
	 *
	 * @param baselineRun the baseline record
	 * @param candidateRun the changed-candidate record
	 * @throws IOException if a runner, validator, or execution-environment failure prevents a valid comparison
	 */
	public void refuseInfrastructureFailure(Path baselineRun, Path candidateRun) throws IOException
	{
		if (access.hasInfrastructureFailure(baselineRun) || access.hasInfrastructureFailure(candidateRun))
		{
			throw new IOException("Paired benchmark stopped because a runner, validator, or execution-environment failure " +
				"prevented a valid comparison. Inspect the attempt evidence in " + baselineRun + " and " + candidateRun +
				" before retrying.");
		}
	}

	/**
	 * Records a failed pair best-effort without replacing its actionable failure.
	 *
	 * @param runPath the prepared record
	 * @param failure the failure that interrupted the pair
	 */
	public void recordInterruption(Path runPath, IOException failure)
	{
		try
		{
			if (Files.isRegularFile(runPath, NOFOLLOW_LINKS))
				RecordStore.writeText(interruptionMarker(runPath), failure.getMessage() + System.lineSeparator());
		}
		catch (IOException _)
		{
			// The original paired-benchmark failure is the actionable result; a missing record is retained by its caller.
		}
	}

	/**
	 * Reuses a retained round claim or reserves the next matching trial range.
	 *
	 * @param runPath the candidate record
	 * @param workspace the round workspace
	 * @param count the requested range size
	 * @return the retained or newly claimed trial numbers
	 * @throws IOException if a retained round cannot safely resume
	 */
	private List<Integer> existingOrClaimedTrials(Path runPath, Path workspace, int count) throws IOException
	{
		if (Files.notExists(workspace, NOFOLLOW_LINKS))
			return access.claimFixedBenchmarkTrials(runPath, count);
		Path marker = workspace.resolve(EVIDENCE_MARKER);
		if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) ||
			!Files.readString(marker, UTF_8).strip().equals(runPath.toAbsolutePath().normalize().toString()))
		{
			throw new IOException("Paired benchmark round cannot resume without its evaluation ownership receipt: " +
				workspace);
		}
		List<Integer> trials = access.trialsIn(workspace);
		if (trials.isEmpty())
			throw new IOException("Paired benchmark round has no retained trial claim: " + workspace);
		return trials;
	}

	/**
	 * Recovers an ownerless retained batch before the pair reuses it.
	 *
	 * @param runPath the candidate record
	 * @param workspace the round workspace
	 * @throws IOException if an evaluator or runner still owns the batch
	 */
	private void recoverInterruptedBatch(Path runPath, Path workspace) throws IOException
	{
		if (Files.notExists(workspace, NOFOLLOW_LINKS))
			return;
		if (access.hasActiveEvaluationOwner(workspace.resolve(ACTIVE_MARKER)))
			throw new IOException("Cannot resume a paired benchmark while another evaluator owns its round: " + workspace);
		ArrayNode workers = access.workerRecords(workspace);
		if (access.hasNonterminalWorker(workers))
		{
			if (access.hasActiveWorkerOwner(workers))
			{
				throw new IOException("Cannot resume a paired benchmark while a runner owns its worker: " + workspace);
			}
			access.terminalizeOwnerlessWorkers(workspace);
		}
		Files.deleteIfExists(workspace.resolve(ACTIVE_MARKER));
		Files.deleteIfExists(interruptionMarker(runPath));
	}

	/**
	 * Determines whether every case retains an outcome for the claimed round trials.
	 *
	 * @param runPath the candidate record
	 * @param trials the retained trial numbers
	 * @return whether the round is already complete
	 * @throws IOException if retained suite evidence cannot be inspected
	 */
	private boolean roundComplete(Path runPath, List<Integer> trials) throws IOException
	{
		ObjectNode run = access.object(access.read(runPath), "paired benchmark record");
		Path suite = access.path(access.text(run, "suite", runPath));
		for (JsonNode node : access.array(SuiteContract.read(suite).path("cases"), "cases", suite))
		{
			String caseId = access.text(access.object(node, "test case"), "id", suite);
			if (!access.unrecordedTrials(runPath, caseId, trials).isEmpty())
				return false;
		}
		return true;
	}

	/**
	 * Runs one candidate's claimed batch through its owned evaluator transaction.
	 *
	 * @param runPath the candidate record
	 * @param trials the retained trial numbers
	 * @param workspace the candidate workspace
	 * @param planPath the bound execution plan
	 * @param workerCapacity the candidate capacity share
	 * @param evaluationRunner the runner retaining executor and validator evidence
	 * @throws IOException if the batch cannot retain terminal evidence
	 */
	private void runBatch(Path runPath, List<Integer> trials, Path workspace, Path planPath, int workerCapacity,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		access.runFixedBenchmarkBatch(runPath, trials, workspace, planPath, workerCapacity, evaluationRunner);
	}

	/**
	 * Waits for one concurrently scheduled candidate batch.
	 *
	 * @param batch the submitted candidate batch
	 * @throws IOException if the batch fails or waiting is interrupted
	 */
	private void awaitBatch(Future<?> batch) throws IOException
	{
		try
		{
			batch.get();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for a paired benchmark batch", e);
		}
		catch (ExecutionException e)
		{
			if (e.getCause() instanceof IOException failure)
				throw failure;
			throw new IOException("Paired benchmark batch did not retain terminal evidence", e.getCause());
		}
	}

	/**
	 * Returns the retained interruption marker adjacent to one record.
	 *
	 * @param runPath the record path
	 * @return the adjacent interruption marker
	 */
	private static Path interruptionMarker(Path runPath)
	{
		return runPath.resolveSibling(runPath.getFileName() + ".interrupted");
	}

	/**
	 * Supplies retained-record and owned-workspace operations used by a paired live benchmark.
	 */
	public interface Access
	{
		/**
		 * Validates the shared pair capacity.
		 *
		 * @param maximumConcurrency the requested capacity
		 * @throws IOException if the capacity is invalid
		 */
		void validateMaximumConcurrency(int maximumConcurrency) throws IOException;

		/**
		 * Binds one record to its exact execution plan.
		 *
		 * @param runPath the record path
		 * @param planPath the execution plan
		 * @throws IOException if the binding cannot be established
		 */
		void bindExecutionPlan(Path runPath, Path planPath) throws IOException;

		/**
		 * Validates one fixed-budget paired benchmark record.
		 *
		 * @param runPath the record path
		 * @throws IOException if the record is not a fixed-budget benchmark
		 */
		void ensureFixedBenchmarkRun(Path runPath) throws IOException;

		/**
		 * Binds one record to the shared pair capacity.
		 *
		 * @param runPath the record path
		 * @param maximumConcurrency the shared capacity
		 * @throws IOException if the capacity differs from retained evidence
		 */
		void bindMaximumConcurrency(Path runPath, int maximumConcurrency) throws IOException;

		/**
		 * Reserves the next fixed benchmark trial range.
		 *
		 * @param runPath the record path
		 * @param count the required trial count
		 * @return the claimed trial numbers
		 * @throws IOException if the range cannot be reserved
		 */
		List<Integer> claimFixedBenchmarkTrials(Path runPath, int count) throws IOException;

		/**
		 * Reads claimed trials from a retained round workspace.
		 *
		 * @param workspace the round workspace
		 * @return the retained trial numbers
		 * @throws IOException if the workspace cannot be read
		 */
		List<Integer> trialsIn(Path workspace) throws IOException;

		/**
		 * Determines whether an evaluator still owns one active marker.
		 *
		 * @param marker the active marker
		 * @return whether the marker has a live owner
		 * @throws IOException if marker ownership cannot be determined
		 */
		boolean hasActiveEvaluationOwner(Path marker) throws IOException;

		/**
		 * Reads retained worker ownership records.
		 *
		 * @param workspace the round workspace
		 * @return the retained worker records
		 * @throws IOException if worker records cannot be read
		 */
		ArrayNode workerRecords(Path workspace) throws IOException;

		/**
		 * Determines whether any retained worker lacks terminal evidence.
		 *
		 * @param workers the retained worker records
		 * @return whether a worker remains nonterminal
		 */
		boolean hasNonterminalWorker(ArrayNode workers);

		/**
		 * Determines whether a retained worker has a live owner.
		 *
		 * @param workers the retained worker records
		 * @return whether a worker remains actively owned
		 */
		boolean hasActiveWorkerOwner(ArrayNode workers);

		/**
		 * Marks ownerless retained workers terminal before recovery continues.
		 *
		 * @param workspace the round workspace
		 * @throws IOException if retained worker evidence cannot be finalized
		 */
		void terminalizeOwnerlessWorkers(Path workspace) throws IOException;

		/**
		 * Reads one retained JSON document.
		 *
		 * @param source the document path
		 * @return the parsed document
		 * @throws IOException if the document cannot be read
		 */
		JsonNode read(Path source) throws IOException;

		/**
		 * Validates that a JSON value is an object.
		 *
		 * @param value the JSON value
		 * @param description the diagnostic description
		 * @return the validated object
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Reads a required text field.
		 *
		 * @param value the containing object
		 * @param field the field name
		 * @param source the source path for diagnostics
		 * @return the text value
		 * @throws IOException if the field is invalid
		 */
		String text(ObjectNode value, String field, Path source) throws IOException;

		/**
		 * Converts a retained path value to a normalized path.
		 *
		 * @param value the retained text value
		 * @return the normalized path
		 */
		Path path(String value);

		/**
		 * Validates that a JSON value is an array.
		 *
		 * @param value the JSON value
		 * @param description the diagnostic description
		 * @param source the source path for diagnostics
		 * @return the validated array
		 * @throws IOException if the value is not an array
		 */
		ArrayNode array(JsonNode value, String description, Path source) throws IOException;

		/**
		 * Finds trial numbers lacking one testcase outcome.
		 *
		 * @param runPath the record path
		 * @param caseId the testcase identity
		 * @param trials the claimed trial numbers
		 * @return the unrecorded trial numbers
		 * @throws IOException if the record cannot be inspected
		 */
		List<Integer> unrecordedTrials(Path runPath, String caseId, List<Integer> trials) throws IOException;

		/**
		 * Runs one candidate's claimed non-certifying batch.
		 *
		 * @param runPath the record path
		 * @param trials the claimed trial numbers
		 * @param workspace the candidate workspace
		 * @param planPath the bound execution plan
		 * @param workerCapacity the candidate capacity share
		 * @param evaluationRunner the runner retaining executor and validator evidence
		 * @throws IOException if the batch cannot retain terminal evidence
		 */
		void runFixedBenchmarkBatch(Path runPath, List<Integer> trials, Path workspace, Path planPath,
			int workerCapacity, HarnessEvaluationRunner evaluationRunner) throws IOException;

		/**
		 * Determines whether a record contains an infrastructure failure.
		 *
		 * @param runPath the record path
		 * @return whether retained evidence blocks a valid comparison
		 * @throws IOException if retained evidence cannot be inspected
		 */
		boolean hasInfrastructureFailure(Path runPath) throws IOException;
	}
}
