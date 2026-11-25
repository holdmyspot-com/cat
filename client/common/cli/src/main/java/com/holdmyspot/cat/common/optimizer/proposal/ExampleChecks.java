package com.holdmyspot.cat.common.optimizer.proposal;

import com.holdmyspot.cat.common.Identifier;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionExamples;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspaces;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramEvaluation;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramProblem;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.PreparedExample;
import com.holdmyspot.cat.common.optimizer.workspace.FileTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the assertion examples in a prompt test's draft.
 */
final class ExampleChecks
{
	/**
	 * Returns the problems with the required examples and the layout of the supplied examples.
	 *
	 * @param testId the prompt test's identifier
	 * @param files the prompt test's files
	 * @param boundaryClasses the test's boundary classes
	 * @return the problems, in deterministic order
	 */
	static List<String> layoutProblems(String testId, PromptTestFiles files, Set<String> boundaryClasses)
	{
		Map<String, Set<String>> examples = exampleIds(files.exampleDirectories());
		List<String> problems = new ArrayList<>();
		Set<String> passIds = new TreeSet<>();
		for (String boundaryClass : boundaryClasses)
			passIds.add("pass_" + boundaryClass);
		for (Assertion assertion : files.scenario().assertions())
		{
			String prefix = "Prompt test `" + testId + "`, assertion `" + assertion.id() + "`";
			Set<String> ids = examples.remove(assertion.id());
			if (ids == null)
				ids = Set.of();
			for (String id : ids)
			{
				if (!passIds.contains(id) && !isFailingExample(id))
				{
					problems.add(prefix + " has example `" + id + "`, which must name a boundary class " +
						"as `pass_<class>` or a failing example as `fail_<snake_case_name>`.");
				}
			}
			addMissingExamples(prefix, assertion, passIds, ids, problems);
		}
		for (String id : new TreeSet<>(examples.keySet()))
			problems.add("Prompt test `" + testId + "` has assertion examples for undeclared assertion `" + id + "`.");
		for (String file : files.exampleSources().keySet())
		{
			int separator = file.indexOf('/');
			if (separator < 0 || file.indexOf('/', separator + 1) < 0)
			{
				problems.add("Prompt test `" + testId + "` has example file `" + file +
					"` outside an assertion's example directory.");
			}
		}
		return problems;
	}

	/**
	 * Checks the required example directory membership recorded with a prompt test.
	 *
	 * @param test the prompt test
	 * @return each missing example, in test and boundary order
	 */
	static List<String> missingExamples(PromptTest test)
	{
		Set<String> directories = test.draft().map(PromptTest.Draft::exampleDirectories).orElseGet(Set::of);
		Map<String, Set<String>> examples = exampleIds(directories);
		Set<String> passIds = new TreeSet<>();
		for (TestCase testCase : test.testCases())
			passIds.add("pass_" + testCase.boundaryClass());
		List<String> problems = new ArrayList<>();
		for (MappedAssertion mapped : test.assertions())
		{
			Assertion assertion = mapped.assertion();
			String prefix = "Prompt test `" + test.id() + "`, assertion `" + assertion.id() + "`";
			addMissingExamples(prefix, assertion, passIds, examples.getOrDefault(assertion.id(), Set.of()), problems);
		}
		return List.copyOf(problems);
	}

	/**
	 * Reports missing example directories for one declared assertion.
	 *
	 * @param prefix identifies the test and assertion
	 * @param assertion the declared assertion
	 * @param passIds the expected passing examples
	 * @param ids the recorded example identifiers
	 * @param problems receives diagnostics for missing examples
	 */
	private static void addMissingExamples(String prefix, Assertion assertion, Set<String> passIds, Set<String> ids,
		List<String> problems)
	{
		if (!requiresExamples(assertion))
			return;
		for (String id : passIds)
		{
			if (!ids.contains(id))
				problems.add(prefix + " is missing example `" + id + "`.");
		}
		if (ids.stream().noneMatch(ExampleChecks::isFailingExample))
			problems.add(prefix + " is missing a failing example named `fail_<snake_case_name>`.");
	}

	/**
	 * Renders the assertion examples and checks the decisions of their tools.
	 *
	 * @param json the operation mapper
	 * @param test the parsed prompt test
	 * @param files the prompt test's source files
	 * @param orderedCases the test cases in the values file's order
	 * @param project the base project
	 * @param excluded the paths relative to the project that trials do not receive
	 * @param checkPrograms runs the test's check programs on an example's record
	 * @param problems receives contradictions and malformed examples
	 * @return the mechanical results of the valid examples
	 * @throws ProposalCheck.Interrupted if the thread is interrupted while a check program runs
	 */
	static List<PreparedExample> check(JsonMapper json, PromptTest test, PromptTestFiles files,
		List<TestCase> orderedCases, Path project, Set<Path> excluded, CheckProgramEvaluation checkPrograms,
		List<String> problems)
	{
		PromptTest.Draft draft = test.draft().orElseThrow();
		Map<String, Set<String>> examples = exampleIds(draft.exampleDirectories());
		List<PreparedExample> checks = new ArrayList<>();
		for (MappedAssertion mapped : test.assertions())
		{
			Assertion assertion = mapped.assertion();
			BuiltInTool tool = assertion.builtInTool().orElse(null);
			for (String id : examples.getOrDefault(assertion.id(), Set.of()))
			{
				String name = "Prompt test `" + test.id() + "`, assertion `" + assertion.id() +
					"`, example `" + id + "`";
				try (TemporaryDirectories cleanup = new TemporaryDirectories())
				{
					boolean passing = id.startsWith("pass_");
					String boundaryClass = null;
					if (passing)
						boundaryClass = id.substring("pass_".length());
					TestCase selected = AssertionExamples.selectCase(orderedCases, boundaryClass);
					Path scratch = cleanup.add(Files.createTempDirectory("cat-assertion-example-"));
					boolean stageBase = tool != null && !tool.examinesOnlyReplies();
					TrialRecord record = render(json, test, files, selected, project, excluded, id, assertion.id(),
						stageBase, scratch);
					String toolName = null;
					boolean passed = false;
					String problem = null;
					if (tool instanceof BuiltInTool.CheckProgram)
					{
						toolName = tool.name();
						List<CheckProgram.Result> runs = checkPrograms.run(test, Set.of(assertion.id()), selected,
							record, 2).get(assertion.id());
						passed = runs.stream().allMatch(run -> run.outcome() == CheckProgram.Outcome.PASS);
						problem = checkProgramProblem(passing, runs).orElse(null);
					}
					else if (tool != null)
					{
						toolName = tool.name();
						passed = tool.decide(record, PromptTest.textByName(selected)).passed();
						if (!passing && passed)
							problem = tool.name() + " passed a failing example.";
						if (passing && tool.examinesOnlyReplies() && !passed)
							problem = tool.name() + " failed a passing example.";
					}
					AssertionExampleCheck check = new AssertionExampleCheck(test.id(), assertion.id(), id, toolName, passed);
					checks.add(new PreparedExample(check, test, selected, orderedCases.indexOf(selected) + 1,
						files, excluded));
					if (problem != null)
						problems.add(name + ": " + problem);
				}
				catch (IOException | UndecidableToolException | IllegalArgumentException e)
				{
					problems.add(name + ": " + e.getMessage());
				}
				catch (InterruptedException e)
				{
					throw new ProposalCheck.Interrupted(e);
				}
			}
		}
		return checks;
	}

	/**
	 * Returns what is wrong with the two runs of a check program on one of its assertion's examples. The program must
	 * pass a passing example and fail a failing example in both runs, which must agree; a program error counts as
	 * neither.
	 *
	 * @param passing whether the example is a passing example
	 * @param runs    the program's two runs on the example, in run order
	 * @return the problem, starting with a lowercase clause and followed by both runs with their outputs, or empty if
	 *   there is none
	 */
	private static Optional<String> checkProgramProblem(boolean passing, List<CheckProgram.Result> runs)
	{
		CheckProgram.Outcome expected = CheckProgram.Outcome.FAIL;
		String kind = "failing";
		String rightDecision = "failed";
		if (passing)
		{
			expected = CheckProgram.Outcome.PASS;
			kind = "passing";
			rightDecision = "passed";
		}
		Set<CheckProgram.Outcome> wrong = EnumSet.noneOf(CheckProgram.Outcome.class);
		for (CheckProgram.Result run : runs)
		{
			if (run.outcome() != expected)
				wrong.add(run.outcome());
		}
		CheckProgram.Result first = runs.getFirst();
		CheckProgram.Result second = runs.getLast();
		String disagreement = CheckProgramProblem.disagreement(first, second).orElse(null);
		if (wrong.isEmpty() && disagreement == null)
			return Optional.empty();
		StringBuilder text = new StringBuilder(200).append("the check program ");
		if (wrong.isEmpty())
		{
			text.append(rightDecision).append(" this ").append(kind).append(" example, but ").append(disagreement);
		}
		else
		{
			text.append(wrongOutcomes(wrong, passing));
			if (disagreement != null)
				text.append(", and ").append(disagreement);
		}
		text.append(".\n");
		PilotReport.run(text, 1, first);
		PilotReport.run(text, 2, second);
		return Optional.of(text.toString().stripTrailing());
	}

	/**
	 * Describes the outcomes that a check program should not have had on an example.
	 *
	 * @param wrong   the outcomes of the runs that differ from the example's label
	 * @param passing whether the example is a passing example
	 * @return a clause that names the outcomes and the kind of example
	 */
	private static String wrongOutcomes(Set<CheckProgram.Outcome> wrong, boolean passing)
	{
		String wrongDecision = "passed this failing example";
		String error = "had a program error on this failing example, which does not count as failing it";
		if (passing)
		{
			wrongDecision = "failed this passing example";
			error = "had a program error on this passing example";
		}
		if (wrong.size() > 1)
			return wrongDecision + " in one run and had a program error on it in the other";
		if (wrong.contains(CheckProgram.Outcome.ERROR))
			return error;
		return wrongDecision;
	}

	/**
	 * Renders one confirmed snapshot of an example.
	 *
	 * @param json the operation mapper
	 * @param test the parsed prompt test
	 * @param files the prompt test's source files
	 * @param selected the test case that fills the example
	 * @param project the base project
	 * @param excluded the paths relative to the project that trials do not receive
	 * @param exampleId the example identifier
	 * @param assertionId the assertion identifier
	 * @param stageBase whether a tool needs the default staged project
	 * @param scratch the private directory of this check
	 * @return the normalized example evidence
	 * @throws IOException if example evidence cannot be materialized or read
	 */
	static RecordedTrial render(JsonMapper json, PromptTest test, PromptTestFiles files, TestCase selected,
		Path project, Set<Path> excluded, String exampleId, String assertionId, boolean stageBase, Path scratch)
		throws IOException
	{
		String prefix = assertionId + "/" + exampleId + "/";
		PromptTest.Draft draft = test.draft().orElseThrow();
		Map<String, Path> sources = new HashMap<>();
		Path raw = scratch.resolve("raw");
		for (Map.Entry<String, FileContent> entry : draft.exampleFiles().entrySet())
		{
			if (!entry.getKey().startsWith(prefix))
				continue;
			String name = entry.getKey().substring(prefix.length());
			Path file = raw.resolve(name);
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue().bytes());
			sources.put(name, file);
		}
		Set<String> directories = new TreeSet<>();
		for (String directory : draft.exampleDirectories())
		{
			if (directory.startsWith(prefix))
				directories.add(directory.substring(prefix.length()));
		}
		Path initial = null;
		if (!directories.contains("initial"))
		{
			initial = Files.createDirectory(scratch.resolve("default"));
			if (stageBase)
			{
				FileTree.copy(project, initial, path -> excluded.stream().anyMatch(project.relativize(path)::startsWith));
				TestWorkspaces.copy(files, selected, initial);
			}
		}
		return AssertionExamples.render(json, test.turns().size(), selected, sources, directories, initial, scratch);
	}

	/**
	 * Groups the immediate example directories by assertion.
	 *
	 * @param directories all paths relative to the test's examples directory
	 * @return each assertion's example identifiers, sorted by identifier
	 */
	private static Map<String, Set<String>> exampleIds(Set<String> directories)
	{
		Map<String, Set<String>> examples = new HashMap<>();
		for (String path : directories)
		{
			int separator = path.indexOf('/');
			if (separator >= 0 && path.indexOf('/', separator + 1) < 0)
			{
				String assertionId = path.substring(0, separator);
				examples.computeIfAbsent(assertionId, _ -> new TreeSet<>()).add(path.substring(separator + 1));
			}
		}
		return examples;
	}

	/**
	 * Indicates whether a name identifies a failing example.
	 *
	 * @param id the example's name below the assertion directory
	 * @return whether the suffix of the failure prefix is a snake_case identifier
	 */
	private static boolean isFailingExample(String id)
	{
		String prefix = "fail_";
		return id.startsWith(prefix) && Identifier.SNAKE_CASE.matcher(id.substring(prefix.length())).matches();
	}

	/**
	 * Indicates whether an assertion needs examples before the proposal can be accepted.
	 *
	 * @param assertion the declared assertion
	 * @return whether the judge, a reply tool or a check program can decide the assertion
	 */
	private static boolean requiresExamples(Assertion assertion)
	{
		return assertion.tool() == null || assertion.onToolFailure() == OnToolFailure.JUDGE ||
			assertion.builtInTool().map(tool -> tool.examinesOnlyReplies() || tool instanceof BuiltInTool.CheckProgram).
				orElse(false);
	}

	/**
	 * Prevents instantiation.
	 */
	private ExampleChecks()
	{
	}
}
