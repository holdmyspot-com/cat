package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;


/**
 * Schedules ordered testcase execution across bounded concurrent prompt-evaluation trials.
 */
public final class ExecutionScheduler
{
	/**
	 * Identifies one queued testcase within a claimed whole-prompt trial.
	 *
	 * @param trial the claimed whole-prompt trial
	 * @param testCase the testcase contract
	 * @param caseId the stable testcase identifier
	 * @param queuedAtNanos the time when the worker entered the executor queue
	 */
	private record SuiteWork(int trial, ObjectNode testCase, String caseId, long queuedAtNanos)
	{
	}

	/**
	 * Prevents instantiation.
	 */
	private ExecutionScheduler()
	{
	}

	/**
	 * Partitions claimed trials into ordered rounds that do not exceed the shared worker capacity.
	 *
	 * @param trials the claimed trial numbers in execution order
	 * @param maximumConcurrency the maximum trials in one round
	 * @return the ordered execution rounds
	 */
	public static List<List<Integer>> rounds(List<Integer> trials, int maximumConcurrency)
	{
		List<List<Integer>> result = new ArrayList<>();
		for (int firstTrial = 0; firstTrial < trials.size(); firstTrial += maximumConcurrency)
			result.add(trials.subList(firstTrial, Math.min(firstTrial + maximumConcurrency, trials.size())));
		return result;
	}

	/**
	 * Executes complete claimed prompt suites in trial order before independent validation.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable suite manifest
	 * @param cases the ordered testcase contracts
	 * @param trials the claimed whole-prompt trials in this round
	 * @param workspaceRoot the owned batch workspace
	 * @param plan the parsed execution plan
	 * @param maximumConcurrency the shared cap on active trial workers
	 * @param stopWhenTerminal whether a terminal decision cancels remaining trial work
	 * @return retained executor evidence for non-terminal testcase results
	 * @throws IOException if evidence cannot be retained
	 */
	public static List<ExecutorEvidence> executeRound(Path runPath, Path suitePath,
		List<ObjectNode> cases, List<Integer> trials, Path workspaceRoot, ObjectNode plan, int maximumConcurrency,
		boolean stopWhenTerminal) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return executeRound(runPath, suitePath, cases, trials, workspaceRoot, plan, maximumConcurrency,
				stopWhenTerminal, Execution.access().defaultRunner(scope.json()));
		}
	}

	/**
	 * Executes complete claimed prompt suites with the supplied typed harness runner before independent validation.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable suite manifest
	 * @param cases the ordered testcase contracts
	 * @param trials the claimed whole-prompt trials in this round
	 * @param workspaceRoot the owned batch workspace
	 * @param plan the parsed execution plan
	 * @param maximumConcurrency the shared cap on active trial workers
	 * @param stopWhenTerminal whether a terminal decision cancels remaining trial work
	 * @param runner the runner that retains each executor result
	 * @return retained executor evidence for non-terminal testcase results
	 * @throws IOException if evidence cannot be retained
	 */
	public static List<ExecutorEvidence> executeRound(Path runPath, Path suitePath,
		List<ObjectNode> cases, List<Integer> trials, Path workspaceRoot, ObjectNode plan, int maximumConcurrency,
		boolean stopWhenTerminal, HarnessEvaluationRunner runner) throws IOException
	{
		List<ExecutorEvidence> result = new ArrayList<>();
		ObjectNode suite = SuiteContract.read(suitePath);
		Set<Integer> canceledTrials = ConcurrentHashMap.newKeySet();
		Map<Integer, Integer> nextCaseIndexes = new HashMap<>();
		for (int trial : trials)
			nextCaseIndexes.put(trial, firstUnrecordedCaseIndex(runPath, cases, trial));
		try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(maximumConcurrency, trials.size())))
		{
			CompletionService<ExecutorEvidence> completed = new ExecutorCompletionService<>(executor);
			Map<Future<ExecutorEvidence>, SuiteWork> submitted = new HashMap<>();
			for (int trial : trials)
				submitNextCase(completed, submitted, nextCaseIndexes, runPath, suitePath, cases, trial, workspaceRoot,
					plan, canceledTrials, runner);
			while (!submitted.isEmpty())
				try
				{
					Future<ExecutorEvidence> worker = completed.take();
					SuiteWork work = submitted.remove(worker);
					if (work == null)
						throw new IOException("Suite executor completion is not owned by this round");
					ExecutorEvidence item = worker.get();
					Execution.access().publishActiveStatus(workspaceRoot.getParent(), "executing_suite_round");
					if (item != null && !item.terminallyRecorded())
						result.add(item);
					if (item == null || item.terminallyRecorded())
						canceledTrials.add(work.trial());
					if (stopWhenTerminal && item != null && item.terminallyRecorded() && isCertifyingRun(runPath) &&
						Execution.access().isTerminal(runPath, suitePath))
					{
						canceledTrials.addAll(trials);
					}
					if (!canceledTrials.contains(work.trial()))
						submitNextCase(completed, submitted, nextCaseIndexes, runPath, suitePath, cases, work.trial(),
							workspaceRoot, plan, canceledTrials, runner);
				}
				catch (ExecutionException e)
				{
					throw new IOException("Batch executor did not retain a terminal result", e.getCause());
				}
			for (int trial : canceledTrials)
				recordCanceledCases(runPath, suite, trial, workspaceRoot.resolve("trial-" + trial),
					"A completed testcase made later cases in this trial unnecessary.");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for suite executors", e);
		}
		return result;
	}

	/**
	 * Executes one testcase across selected trials for a smoke or bounded diagnostic.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable suite manifest
	 * @param testCase the selected testcase
	 * @param trials the selected trial numbers
	 * @param workspaceRoot the owned execution workspace
	 * @param plan the parsed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @return retained executor evidence for the selected testcase
	 * @throws IOException if evidence cannot be retained
	 */
	public static List<ExecutorEvidence> executeCase(Path runPath, Path suitePath, ObjectNode testCase,
		List<Integer> trials, Path workspaceRoot, ObjectNode plan, int maximumConcurrency) throws IOException
	{
		return executeRound(runPath, suitePath, List.of(testCase), trials, workspaceRoot, plan, maximumConcurrency,
			true);
	}

	/**
	 * Executes one testcase across selected trials with the supplied typed harness runner.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable suite manifest
	 * @param testCase the selected testcase
	 * @param trials the selected trial numbers
	 * @param workspaceRoot the owned execution workspace
	 * @param plan the parsed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains each executor result
	 * @return retained executor evidence for the selected testcase
	 * @throws IOException if evidence cannot be retained
	 */
	public static List<ExecutorEvidence> executeCase(Path runPath, Path suitePath, ObjectNode testCase,
		List<Integer> trials, Path workspaceRoot, ObjectNode plan, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		return executeRound(runPath, suitePath, List.of(testCase), trials, workspaceRoot, plan, maximumConcurrency,
			true, runner);
	}

	/**
	 * Records every unstarted testcase after a terminal batch decision.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suite the immutable testcase suite
	 * @param trials the claimed whole-prompt trials
	 * @param workspaceRoot the batch workspace
	 * @param reason the terminal decision that made the work unnecessary
	 * @throws IOException if cancellation evidence cannot be retained
	 */
	public static void recordCanceledTrials(Path runPath, ObjectNode suite, List<Integer> trials, Path workspaceRoot,
		String reason) throws IOException
	{
		for (int trial : trials)
			recordCanceledCases(runPath, suite, trial, workspaceRoot.resolve("trial-" + trial), reason);
	}

	/**
	 * Records the unstarted testcases of a stopped trial without treating their absence as an infrastructure failure.
	 *
	 * @param runPath the persistent evaluation record
	 * @param suite the immutable testcase suite
	 * @param trial the claimed whole-prompt trial
	 * @param workspaceRoot the trial workspace
	 * @param reason the completed result that made this work unnecessary
	 * @throws IOException if cancellation evidence cannot be retained
	 */
	private static void recordCanceledCases(Path runPath, ObjectNode suite, int trial, Path workspaceRoot, String reason)
		throws IOException
	{
		for (JsonNode node : PromptEvaluation.array(suite.path("cases"), "cases", runPath))
		{
			ObjectNode testCase = PromptEvaluation.object(node, "test case");
			String caseId = PromptEvaluation.text(testCase, "id", runPath);
			if (Execution.access().unrecordedTrials(runPath, caseId, List.of(trial)).isEmpty())
				continue;
			Path cancellation = workspaceRoot.resolve(caseId).resolve("canceled.txt");
			Files.createDirectories(cancellation.getParent());
			Files.writeString(cancellation, reason + System.lineSeparator(), StandardCharsets.UTF_8);
			Execution.access().record(runPath, caseId, trial, AttemptStatus.CANCELED, cancellation, cancellation);
		}
		terminalizeCanceledHarnessRuns(workspaceRoot, reason);
	}

	/**
	 * Marks harness records terminal after the evaluator canceled their enclosing testcase.
	 *
	 * <p>
	 * The evaluator interrupts a runner process when a completed result makes the remaining testcase unnecessary. The
	 * runner cannot write its own terminal manifest after that interruption, so leaving its record {@code running} would
	 * later make a finished evaluation appear to have live work. This method runs only after the executor pool has
	 * joined; it rejects a still-owned record rather than misrepresenting a live harness as stopped.
	 *
	 * @param testcaseWorkspace the canceled testcase workspace
	 * @param reason the completed evaluation result that made the work unnecessary
	 * @throws IOException if a retained runner is still active or cannot be made terminal
	 */
	public static void terminalizeCanceledHarnessRuns(Path testcaseWorkspace, String reason) throws IOException
	{
		requireThat(testcaseWorkspace, "testcaseWorkspace").isNotNull();
		requireThat(reason, "reason").isNotBlank();
		Path runRoot = testcaseWorkspace.resolve("executor-project/runner-runs");
		if (!Files.isDirectory(runRoot, NOFOLLOW_LINKS))
			return;
		try (Stream<Path> harnesses = Files.list(runRoot))
		{
			for (Path harnessDirectory : harnesses.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).sorted().toList())
				terminalizeCanceledHarnessRuns(runRoot, harnessDirectory, reason);
		}
	}

	/**
	 * Makes inactive runs under one harness directory terminal.
	 *
	 * @param runRoot the root owned by the harness run manager
	 * @param harnessDirectory the harness-specific child directory
	 * @param reason the completed evaluation result that canceled the work
	 * @throws IOException if a retained runner is active or cannot be made terminal
	 */
	private static void terminalizeCanceledHarnessRuns(Path runRoot, Path harnessDirectory, String reason)
		throws IOException
	{
		String harness = harnessDirectory.getFileName().toString();
		try (Stream<Path> runs = Files.list(harnessDirectory))
		{
			for (Path runDirectory : runs.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).sorted().toList())
			{
				Path manifestPath = runDirectory.resolve("run.json");
				if (!Files.isRegularFile(manifestPath, NOFOLLOW_LINKS))
					continue;
				ObjectNode manifest = PromptEvaluation.object(PromptEvaluation.read(manifestPath), "runner manifest");
				if (!PromptEvaluation.text(manifest, "state", manifestPath).equals("running"))
					continue;
				if (Execution.access().hasActiveWorkerOwner(manifestPath))
					throw new IOException("Canceled harness is still active: " + manifestPath);
				new HarnessRunManager(runRoot, java.time.Clock.systemUTC()).fail(harness,
					PromptEvaluation.text(manifest, "id", manifestPath), "Evaluator canceled this work: " + reason, 1);
			}
		}
	}

	/**
	 * Returns the first testcase in a trial that lacks retained terminal evidence.
	 *
	 * @param runPath the persistent evaluation record
	 * @param cases the ordered testcase contracts
	 * @param trial the claimed whole-prompt trial
	 * @return the unrecorded testcase index, or the case count when the trial is complete
	 * @throws IOException if retained attempts cannot be inspected
	 */
	private static int firstUnrecordedCaseIndex(Path runPath, List<ObjectNode> cases, int trial) throws IOException
	{
		for (int index = 0; index < cases.size(); ++index)
		{
			String caseId = PromptEvaluation.text(cases.get(index), "id", runPath);
			if (!Execution.access().unrecordedTrials(runPath, caseId, List.of(trial)).isEmpty())
				return index;
		}
		return cases.size();
	}

	/**
	 * Indicates whether the evaluation record is for certification.
	 *
	 * @param runPath the persistent evaluation record
	 * @return whether the record is certifying
	 * @throws IOException if the record cannot be read
	 */
	private static boolean isCertifyingRun(Path runPath) throws IOException
	{
		return PromptEvaluation.text(PromptEvaluation.object(PromptEvaluation.read(runPath), "run"), "purpose", runPath).
			equals("certification");
	}

	/**
	 * Submits a trial's next eligible testcase after its preceding testcase reached a terminal boundary.
	 *
	 * @param completed the executor completion queue
	 * @param submitted the active workers and their owning testcase
	 * @param nextCaseIndexes the next unstarted index for each trial
	 * @param runPath the persistent evaluation record
	 * @param suitePath the immutable suite manifest
	 * @param cases the ordered testcase contracts
	 * @param trial the claimed whole-prompt trial
	 * @param workspaceRoot the evaluator-owned batch workspace
	 * @param plan the parsed execution plan
	 * @param canceledTrials trials whose remaining cases must not start
	 * @param runner the runner that retains the testcase's executor evidence
	 * @throws IOException if the next testcase is invalid
	 */
	private static void submitNextCase(CompletionService<ExecutorEvidence> completed,
		Map<Future<ExecutorEvidence>, SuiteWork> submitted, Map<Integer, Integer> nextCaseIndexes,
		Path runPath, Path suitePath, List<ObjectNode> cases, int trial, Path workspaceRoot, ObjectNode plan,
		Set<Integer> canceledTrials, HarnessEvaluationRunner runner) throws IOException
	{
		if (canceledTrials.contains(trial))
			return;
		int index = nextCaseIndexes.get(trial);
		if (index >= cases.size())
			return;
		ObjectNode testCase = cases.get(index);
		String caseId = PromptEvaluation.text(testCase, "id", suitePath);
		SuiteWork work = new SuiteWork(trial, testCase, caseId, System.nanoTime());
		Future<ExecutorEvidence> worker = completed.submit(() -> Executor.executeClaimed(
			runPath, suitePath, work.testCase(), work.caseId(), work.trial(), workspaceRoot.resolve("trial-" +
				work.trial()).resolve(work.caseId()), plan, work.queuedAtNanos(), canceledTrials, runner));
		submitted.put(worker, work);
		nextCaseIndexes.put(trial, index + 1);
	}
}
