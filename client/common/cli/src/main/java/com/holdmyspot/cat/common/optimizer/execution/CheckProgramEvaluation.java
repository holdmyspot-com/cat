package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the check programs of a completed trial whose test declares them.
 */
@FunctionalInterface
public interface CheckProgramEvaluation
{
	/** Reports that a context has no owner-side check-program launcher. */
	CheckProgramEvaluation UNAVAILABLE = (_, _, _, _) ->
	{
		throw new IOException("The campaign owner has no check program launcher");
	};

	/**
	 * Creates an evaluator that uses the owner's authenticated sandbox launch.
	 *
	 * @param launch        the executable paths and read policy
	 * @param json          the input mapper
	 * @param stagingParent a private directory for a trial's staged input and its disposable scratch directories
	 * @return the evaluator for completed trials
	 * @throws NullPointerException if any argument is null
	 */
	static CheckProgramEvaluation isolated(CheckProgram.Launch launch, JsonMapper json, Path stagingParent)
	{
		requireThat(launch, "launch").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(stagingParent, "stagingParent").isNotNull();
		return (test, testCase, record, runs) ->
		{
			requireThat(runs, "runs").isPositive();
			PromptTest.Draft draft = test.draft().orElseThrow(() ->
				new IOException("Confirmed check files are missing for " + test.id()));
			try (TemporaryDirectories directories = new TemporaryDirectories())
			{
				Path input = directories.add(Files.createTempDirectory(stagingParent, "check-input-"));
				CheckProgramInput.stage(json, input, record, draft.checkFiles());
				Map<String, String> variables = PromptTest.textByName(testCase);
				Map<String, List<CheckProgram.Result>> results = new LinkedHashMap<>();
				for (Assertion assertion : test.allAssertions())
				{
					if (assertion.builtInTool().orElse(null) instanceof BuiltInTool.CheckProgram program)
					{
						List<CheckProgram.Result> assertionRuns = new ArrayList<>(runs);
						// Each run gets its own scratch directory, so that one run cannot leave state for the next
						for (int i = 0; i < runs; ++i)
						{
							assertionRuns.add(CheckProgram.run(launch, input, stagingParent, program, variables,
								CheckProgram.TIME_LIMIT, CheckProgram.OUTPUT_LIMIT));
						}
						results.put(assertion.id(), List.copyOf(assertionRuns));
					}
				}
				return results;
			}
		};
	}

	/**
	 * Converts the first run of each check program to its assertion's result.
	 *
	 * @param test the confirmed prompt test
	 * @param runs the runs of each check program, keyed by assertion ID, each list in run order
	 * @return the result of each check-program assertion, keyed by assertion ID
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code runs} names an assertion that the test lacks or has no run of it
	 */
	static Map<String, BuiltInToolResult> toolResults(PromptTest test, Map<String, List<CheckProgram.Result>> runs)
	{
		requireThat(test, "test").isNotNull();
		requireThat(runs, "runs").isNotNull();
		Map<String, BuiltInToolResult> results = new HashMap<>();
		for (Map.Entry<String, List<CheckProgram.Result>> entry : runs.entrySet())
		{
			Assertion assertion = test.allAssertions().stream().
				filter(candidate -> candidate.id().equals(entry.getKey())).findFirst().
				orElseThrow(() -> new IllegalArgumentException("The test " + test.id() + " has no assertion " +
					entry.getKey()));
			if (entry.getValue().isEmpty())
				throw new IllegalArgumentException("The check program of " + entry.getKey() + " did not run");
			CheckProgram.Result result = entry.getValue().getFirst();
			BuiltInToolResult decision = switch (result.outcome())
			{
				case PASS -> new BuiltInToolResult(assertion, true, result.observed());
				case FAIL -> new BuiltInToolResult(assertion, false, result.observed());
				case ERROR -> BuiltInToolResult.programError(assertion, result.observed());
			};
			results.put(assertion.id(), decision);
		}
		return results;
	}

	/**
	 * Runs each check program of a test on one staged copy of a trial's evidence.
	 *
	 * @param test     the confirmed prompt test
	 * @param testCase the case that the trial ran
	 * @param record   the trial's final evidence
	 * @param runs     the positive number of times to run each program
	 * @return the runs of each check program, keyed by assertion ID in assertion order, each list in run order
	 * @throws IOException          if the evidence cannot be staged or the host cannot run a check program
	 * @throws InterruptedException if the caller interrupts the evaluation
	 */
	Map<String, List<CheckProgram.Result>> run(PromptTest test, TestCase testCase, TrialRecord record, int runs)
		throws IOException, InterruptedException;

	/**
	 * Decides a test's built-in tools, running each check program once.
	 *
	 * @param test     the confirmed prompt test
	 * @param testCase the case that the trial ran
	 * @param record   the trial's final evidence
	 * @return one result per built-in assertion, in assertion order
	 * @throws IOException              if the evidence cannot be staged or the host cannot run a check program
	 * @throws InterruptedException     if the driver interrupts the evaluation
	 * @throws UndecidableToolException if the evidence does not let a built-in tool decide
	 */
	default List<BuiltInToolResult> decide(PromptTest test, TestCase testCase, TrialRecord record)
		throws IOException, InterruptedException, UndecidableToolException
	{
		return test.decide(testCase, record, toolResults(test, run(test, testCase, record, 1)));
	}
}
