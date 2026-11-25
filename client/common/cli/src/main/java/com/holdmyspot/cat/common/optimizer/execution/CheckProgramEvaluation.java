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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides the built-in tools of a completed trial whose test declares a check program.
 */
@FunctionalInterface
public interface CheckProgramEvaluation
{
	/** Reports that a context has no owner-side check-program launcher. */
	CheckProgramEvaluation UNAVAILABLE = (test, testCase, record) ->
	{
		throw new IOException("The campaign owner has no check program launcher");
	};

	/**
	 * Creates an evaluator that uses the owner's authenticated sandbox launch.
	 *
	 * @param launch the executable paths and read policy
	 * @param json the input mapper
	 * @param stagingParent a private directory for a trial's staged input and its disposable scratch directories
	 * @return the evaluator for completed trials
	 * @throws NullPointerException if any argument is null
	 */
	static CheckProgramEvaluation isolated(CheckProgram.Launch launch, JsonMapper json, Path stagingParent)
	{
		requireThat(launch, "launch").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(stagingParent, "stagingParent").isNotNull();
		return (test, testCase, record) ->
		{
			PromptTest.Draft draft = test.draft().orElseThrow(() ->
				new IOException("Confirmed check files are missing for " + test.id()));
			try (TemporaryDirectories directories = new TemporaryDirectories())
			{
				Path input = directories.add(Files.createTempDirectory(stagingParent, "check-input-"));
				CheckProgramInput.stage(json, input, record, draft.checkFiles());
				Map<String, String> variables = PromptTest.textByName(testCase);
				Map<String, BuiltInToolResult> results = new HashMap<>();
				for (Assertion assertion : test.allAssertions())
				{
					if (assertion.builtInTool().orElse(null) instanceof BuiltInTool.CheckProgram program)
					{
						CheckProgram.Result result = CheckProgram.run(launch, input, stagingParent, program, variables,
							CheckProgram.TIME_LIMIT, CheckProgram.OUTPUT_LIMIT);
						BuiltInToolResult decision = switch (result.outcome())
						{
							case PASS -> new BuiltInToolResult(assertion, true, result.observed());
							case FAIL -> new BuiltInToolResult(assertion, false, result.observed());
							case ERROR -> BuiltInToolResult.programError(assertion, result.observed());
						};
						results.put(assertion.id(), decision);
					}
				}
				return test.decide(testCase, record, results);
			}
		};
	}

	/**
	 * Decides a test's built-in tools, including its check programs.
	 *
	 * @param test the confirmed prompt test
	 * @param testCase the case that the trial ran
	 * @param record the trial's final evidence
	 * @return one result per built-in assertion, in assertion order
	 * @throws IOException if the evidence cannot be staged or the host cannot run a check program
	 * @throws InterruptedException if the driver interrupts the evaluation
	 * @throws UndecidableToolException if the evidence does not let a built-in tool decide
	 */
	List<BuiltInToolResult> decide(PromptTest test, TestCase testCase, TrialRecord record)
		throws IOException, InterruptedException, UndecidableToolException;
}
