package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.execution.AssertionVerdict;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selects authored examples for the frozen judge and interprets its decisions against the examples' labels.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AssertionChecks
{
	/**
	 * An example from a mechanically valid test bound to the case that filled it.
	 *
	 * @param check the result of its mechanical check
	 * @param test the test that owns the example
	 * @param testCase the selected case
	 * @param casePosition the one-based position in the authored values file
	 * @param files the test's source files, used to stage its workspace
	 * @param excluded paths relative to the project that trials do not receive
	 */
	public record PreparedExample(AssertionExampleCheck check, PromptTest test, TestCase testCase, int casePosition,
		PromptTestFiles files, Set<Path> excluded)
	{
		/**
		 * Creates a prepared example.
		 *
		 * @param check the result of its mechanical check
		 * @param test the test that owns the example
		 * @param testCase the selected case
		 * @param casePosition the one-based position in the authored values file
		 * @param files the test's source files, used to stage its workspace
		 * @param excluded paths relative to the project that trials do not receive
		 * @throws NullPointerException if a required argument or exclusion is null
		 * @throws IllegalArgumentException if the position is not positive
		 */
		public PreparedExample
		{
			requireThat(check, "check").isNotNull();
			requireThat(test, "test").isNotNull();
			requireThat(testCase, "testCase").isNotNull();
			requireThat(casePosition, "casePosition").isPositive();
			requireThat(files, "files").isNotNull();
			excluded = Set.copyOf(excluded);
		}

		/**
		 * Renders this example into a private directory for a judge to read.
		 *
		 * @param json the operation mapper
		 * @param project the staged base project
		 * @param stageBase whether the evidence scope needs the default trial project
		 * @param scratch the private directory of this check
		 * @return the filled replies, project snapshots and normalized activity
		 * @throws IOException if the files cannot be materialized or read
		 */
		public RecordedTrial render(JsonMapper json, Path project, boolean stageBase, Path scratch) throws IOException
		{
			return ExampleChecks.render(json, test, files, testCase, project, excluded, check.exampleId(),
				check.assertionId(), stageBase, scratch);
		}
	}

	/**
	 * Returns the examples that the frozen judge needs to decide.
	 *
	 * @param contract the proposed contract
	 * @param checks   the mechanical example results for the tests that passed mechanical checks
	 * @return examples of declared assertions without tools or whose tools failed under {@code judge}, in check order
	 * @throws NullPointerException if an argument or check is null
	 * @throws java.util.NoSuchElementException if a selected check refers to a test or declared assertion absent
	 *   from the contract
	 */
	public static List<JudgedExample> selectExamples(Contract contract, List<AssertionExampleCheck> checks)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(checks, "checks").isNotNull();
		List<JudgedExample> selected = new ArrayList<>();
		for (AssertionExampleCheck check : checks)
		{
			if (check.passed() || check.assertionId().equals(TestCase.EXPECTED_OUTCOME))
				continue;
			PromptTest test = contract.findTest(check.testId()).orElseThrow();
			MappedAssertion mapped = test.assertions().stream().
				filter(candidate -> candidate.assertion().id().equals(check.assertionId())).findFirst().orElseThrow();
			Assertion assertion = mapped.assertion();
			if (assertion.tool() != null && assertion.onToolFailure() != OnToolFailure.JUDGE)
				continue;
			Requirement requirement = contract.requirements().stream().
				filter(candidate -> candidate.id().equals(mapped.requirementId())).findFirst().orElseThrow();
			selected.add(new JudgedExample(test, new JudgedAssertion(assertion, requirement), check));
		}
		return List.copyOf(selected);
	}

	/**
	 * An authored example that the frozen judge decides.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param test      the prompt test that owns the example
	 * @param assertion the declared assertion to decide
	 * @param check     the mechanical result identifying this example
	 */
	public record JudgedExample(PromptTest test, JudgedAssertion assertion, AssertionExampleCheck check)
	{
		/**
		 * Creates a selected example.
		 *
		 * @param test      the prompt test that owns the example
		 * @param assertion the declared assertion to decide
		 * @param check     the mechanical result identifying this example
		 * @throws NullPointerException if any argument is null
		 */
		public JudgedExample
		{
			requireThat(test, "test").isNotNull();
			requireThat(assertion, "assertion").isNotNull();
			requireThat(check, "check").isNotNull();
		}
	}

	/**
	 * The effect of a judge's example label on the proposal.
	 */
	public enum Disposition
	{
		/**
		 * The judge agrees with the example's label.
		 */
		ACCEPTED,
		/**
		 * The judge fails a passing example.
		 */
		WARNING,
		/**
		 * The judge passes a failing example.
		 */
		REFUSAL
	}

	/**
	 * The frozen judge's decision on an authored example and its effect on the proposal.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param example the judged example
	 * @param verdict the judge's label and rationale for its assertion
	 */
	public record ExampleResult(JudgedExample example, AssertionVerdict verdict)
	{
		/**
		 * Creates a result for an example.
		 *
		 * @param example the judged example
		 * @param verdict the judge's label and rationale for its assertion
		 * @throws NullPointerException     if either argument is null
		 * @throws IllegalArgumentException if the verdict names a different assertion
		 */
		public ExampleResult
		{
			requireThat(example, "example").isNotNull();
			requireThat(verdict, "verdict").isNotNull();
			requireThat(verdict.assertion(), "verdict.assertion()").isEqualTo(example.check().assertionId());
		}

		/**
		 * Returns the effect of the example verdict on the proposal.
		 *
		 * @return the warning, refusal or acceptance
		 */
		public Disposition disposition()
		{
			boolean passing = example.check().exampleId().startsWith("pass_");
			boolean judgePassed = verdict.verdict() == Verdict.PASS;
			if (passing == judgePassed)
				return Disposition.ACCEPTED;
			if (passing)
				return Disposition.WARNING;
			return Disposition.REFUSAL;
		}

		/**
		 * Returns the author-facing diagnostic for a warning or refusal.
		 *
		 * @return the text naming the test, assertion and example with the judge's rationale, or empty if accepted
		 */
		public Optional<String> diagnostic()
		{
			Disposition disposition = disposition();
			if (disposition == Disposition.ACCEPTED)
				return Optional.empty();
			String message;
			if (disposition == Disposition.REFUSAL)
				message = "the judge passed a failing example.";
			else
				message = "the judge failed a passing example.";
			AssertionExampleCheck check = example.check();
			return Optional.of("For prompt test `" + check.testId() + "`, assertion `" + check.assertionId() +
				"`, example `" + check.exampleId() + "`, " + message + " " + verdict.rationale());
		}

		/**
		 * Returns the author-facing diagnostic with the case that filled the example.
		 *
		 * @param prepared the prepared example that the judge decided
		 * @return the diagnostic with the case's position in the values file and its exact expected outcome,
		 *   or empty if accepted
		 * @throws NullPointerException     if {@code prepared} is null
		 * @throws IllegalArgumentException if {@code prepared} has a different owning test or mechanical check
		 */
		public Optional<String> diagnostic(PreparedExample prepared)
		{
			requireThat(prepared, "prepared").isNotNull();
			requireThat(prepared.check(), "prepared.check()").isEqualTo(example.check());
			requireThat(prepared.test().id(), "prepared.test().id()").isEqualTo(example.test().id());
			return diagnostic().map(text -> text + "\nValues file case " + prepared.casePosition() +
				" has this expected outcome.\n" + prepared.testCase().expectedOutcome());
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private AssertionChecks()
	{
	}
}
