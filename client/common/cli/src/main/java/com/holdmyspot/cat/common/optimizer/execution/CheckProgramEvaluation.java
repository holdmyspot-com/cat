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
import com.holdmyspot.cat.common.runner.CheckProgramClient;
import com.holdmyspot.cat.common.runner.CheckProgramRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the check programs that a prompt test declares on a completed trial or on the record of an assertion example.
 */
@FunctionalInterface
public interface CheckProgramEvaluation
{
	/** Reports that a context has no owner-side check-program launcher. */
	CheckProgramEvaluation UNAVAILABLE = (_, _, _, _, _) ->
	{
		throw new IOException("The campaign owner has no check program launcher");
	};

	/**
	 * Identifies the input format and the Java runtime with which this evaluation runs check programs, so that a
	 * measurement or pilot outcome from another version is not reused.
	 *
	 * @return the measurement version
	 */
	default String version()
	{
		return CheckProgram.version();
	}

	/**
	 * Runs one check program from a staged input directory.
	 */
	@FunctionalInterface
	interface ProgramRunner
	{
		/**
		 * Runs a program once.
		 *
		 * @param input     the staged input directory
		 * @param program   the program's path inside {@code input}
		 * @param arguments the program's arguments, with their placeholders filled
		 * @return the program's decision or error
		 * @throws IOException          if the host cannot run the program
		 * @throws InterruptedException if the caller interrupts the run
		 */
		CheckProgram.Result run(Path input, String program, List<String> arguments)
			throws IOException, InterruptedException;
	}

	/**
	 * Creates an evaluator that uses the owner's authenticated sandbox launch in this process.
	 *
	 * @param launch        the executable paths and read policy
	 * @param json          the input mapper
	 * @param stagingParent a private directory for a trial's staged input and its disposable scratch directories
	 * @return the evaluator for completed trials and assertion examples
	 * @throws NullPointerException if any argument is null
	 */
	static CheckProgramEvaluation isolated(CheckProgram.Launch launch, JsonMapper json, Path stagingParent)
	{
		requireThat(launch, "launch").isNotNull();
		requireThat(stagingParent, "stagingParent").isNotNull();
		return using((input, program, arguments) -> CheckProgram.run(launch, input, stagingParent, program, arguments,
			CheckProgram.TIME_LIMIT, CheckProgram.OUTPUT_LIMIT), json, stagingParent);
	}

	/**
	 * Creates an evaluator whose programs run outside the sandbox of the session that this process runs in, because the
	 * programs' own sandbox cannot start inside it. A receiver that serves the session's requests runs each program.
	 *
	 * @param client        sends each run to the receiver
	 * @param json          the input mapper
	 * @param stagingParent a private directory for a trial's staged input
	 * @param heldOut       the absolute paths that no program may see
	 * @return the evaluator for completed trials and assertion examples
	 * @throws NullPointerException if any argument is null
	 */
	static CheckProgramEvaluation requested(CheckProgramClient client, JsonMapper json, Path stagingParent,
		List<Path> heldOut)
	{
		requireThat(client, "client").isNotNull();
		requireThat(heldOut, "heldOut").isNotNull();
		List<Path> masks = List.copyOf(heldOut);
		return using((input, program, arguments) -> client.run(new CheckProgramRequest(input, program, arguments,
			masks)), json, stagingParent);
	}

	/**
	 * Creates an evaluator that stages each trial's input and hands each program to a runner.
	 *
	 * @param runner        runs one program
	 * @param json          the input mapper
	 * @param stagingParent a private directory for a trial's staged input
	 * @return the evaluator for completed trials and assertion examples
	 * @throws NullPointerException if any argument is null
	 */
	private static CheckProgramEvaluation using(ProgramRunner runner, JsonMapper json, Path stagingParent)
	{
		requireThat(json, "json").isNotNull();
		requireThat(stagingParent, "stagingParent").isNotNull();
		return (test, assertionIds, testCase, record, runs) ->
		{
			requireThat(assertionIds, "assertionIds").isNotNull();
			requireThat(runs, "runs").isPositive();
			PromptTest.Draft draft = test.draft().orElseThrow(() ->
				new IOException("The check files of " + test.id() + " are missing"));
			try (TemporaryDirectories directories = new TemporaryDirectories())
			{
				Path input = directories.add(Files.createTempDirectory(stagingParent, "check-input-"));
				CheckProgramInput.stage(json, input, record, draft.checkFiles());
				Map<String, String> variables = PromptTest.textByName(testCase);
				Map<String, List<CheckProgram.Result>> results = new LinkedHashMap<>();
				for (Assertion assertion : test.allAssertions())
				{
					if (assertionIds.contains(assertion.id()) &&
						assertion.builtInTool().orElse(null) instanceof BuiltInTool.CheckProgram program)
					{
						List<String> arguments = program.filledArguments(variables);
						List<CheckProgram.Result> assertionRuns = new ArrayList<>(runs);
						// Each run gets its own scratch directory, so that one run cannot leave state for the next
						for (int i = 0; i < runs; ++i)
							assertionRuns.add(runner.run(input, program.program(), arguments));
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
	 * Runs some check programs of a test on one staged copy of a trial's or an assertion example's evidence.
	 *
	 * @param test         the prompt test, whose draft holds its check files
	 * @param assertionIds the IDs of the check-program assertions whose programs run; another ID is ignored
	 * @param testCase     the case that fills the programs' arguments
	 * @param record       the trial's final evidence, or the record that an assertion example describes
	 * @param runs         the positive number of times to run each program
	 * @return the runs of each selected check program, keyed by assertion ID in assertion order, each list in run
	 *   order
	 * @throws IOException          if the evidence cannot be staged or the host cannot run a check program
	 * @throws InterruptedException if the caller interrupts the evaluation
	 */
	Map<String, List<CheckProgram.Result>> run(PromptTest test, Set<String> assertionIds, TestCase testCase,
		TrialRecord record, int runs) throws IOException, InterruptedException;

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
	default Map<String, List<CheckProgram.Result>> run(PromptTest test, TestCase testCase, TrialRecord record,
		int runs) throws IOException, InterruptedException
	{
		Set<String> assertionIds = new HashSet<>();
		for (Assertion assertion : test.allAssertions())
		{
			if (assertion.builtInTool().orElse(null) instanceof BuiltInTool.CheckProgram)
				assertionIds.add(assertion.id());
		}
		return run(test, assertionIds, testCase, record, runs);
	}

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
