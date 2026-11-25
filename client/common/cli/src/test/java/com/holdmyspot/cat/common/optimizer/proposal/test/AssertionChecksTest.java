package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.RiskRank;
import com.holdmyspot.cat.common.optimizer.contract.Scenario;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.AssertionVerdict;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.Disposition;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.ExampleResult;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.JudgedExample;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.PreparedExample;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionExampleCheck;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the selection and interpretation of authored assertion examples that require a judge.
 */
public final class AssertionChecksTest
{
	private static final String FORMATTING = "formatting";
	private static final String SECOND_TEST = "formatting_follow_up";
	private static final String READABLE = "readable";
	private static final String REPORT_MENTIONED = "report_mentioned";
	private static final String PLAIN_TEXT = "plain_text";
	private static final String JUDGED_PROGRAM = "judged_program";
	private static final String DECIDED_PROGRAM = "decided_program";
	private static final String PASS_REGULAR = "pass_regular";
	private static final String FAIL_MISSING = "fail_missing";
	private static final String FIRST_OUTCOME = "The annual report is still in progress.";
	private static final String REGULAR_OUTCOME = "The budget report was completed.\nIt covers revenue and costs.";

	/**
	 * Creates the test fixture.
	 */
	public AssertionChecksTest()
	{
	}

	/**
	 * Verifies that the selector keeps both examples of a tool-free assertion and a failed tool result under
	 * {@code judge}. It skips tool decisions.
	 */
	@Test
	public void selectsExamplesThatNeedTheJudge()
	{
		// Requirement: R-L16
		// Requirement: R-L19
		DerivedProposal proposal = proposal();
		List<AssertionExampleCheck> checks = proposal.exampleChecks();

		List<JudgedExample> selected = AssertionChecks.selectExamples(proposal.contract(), checks);

		requireThat(selected.stream().map(JudgedExample::check).toList(), "selectedChecks").
			isEqualTo(List.of(checks.get(0), checks.get(1), checks.get(3), checks.get(6), checks.get(7)));
		JudgedExample response = selected.getFirst();
		requireThat(response.assertion().scope(), "responseScope").isEqualTo(EvidenceScope.RESPONSE);
		requireThat(response.assertion().requirement(), "responseRequirement").
			isEqualTo(proposal.contract().requirements().getFirst());
		JudgedExample actions = selected.get(3);
		requireThat(actions.assertion().scope(), "actionsScope").isEqualTo(EvidenceScope.ACTIONS);
		requireThat(actions.test().id(), "actionsTest").isEqualTo(SECOND_TEST);
	}

	/**
	 * Verifies that a caller can supply only the examples of tests that passed mechanical checks.
	 */
	@Test
	public void selectsFromMechanicallyCleanTests()
	{
		// Requirement: R-L16
		DerivedProposal proposal = proposal();
		List<AssertionExampleCheck> checks = proposal.exampleChecks().stream().
			filter(check -> check.testId().equals(SECOND_TEST)).toList();

		List<JudgedExample> selected = AssertionChecks.selectExamples(proposal.contract(), checks);

		requireThat(selected.stream().map(JudgedExample::check).toList(), "selectedChecks").isEqualTo(checks);
		requireThat(selected.getFirst().test().id(), "selectedTest").isEqualTo(SECOND_TEST);
	}

	/**
	 * Verifies that the failing examples that a check program fails go to the judge when its failures do, and that
	 * its examples stay with the program otherwise or when it passes them.
	 */
	@Test
	public void selectsJudgedCheckProgramFailures()
	{
		// Requirement: R-L16
		String tool = "check-program checks/Check.java";
		MappedAssertion judged = new MappedAssertion(new Assertion(JUDGED_PROGRAM, AssertionKind.EFFECT, tool,
			OnToolFailure.JUDGE, "The agent updated the report.", null, EvidenceScope.ACTIONS), "r1");
		MappedAssertion decided = new MappedAssertion(new Assertion(DECIDED_PROGRAM, AssertionKind.EFFECT, tool,
			OnToolFailure.FAIL, "The agent kept the report's title."), "r1");
		List<PromptTest> tests = List.of(promptTest(FORMATTING, List.of(judged, decided)));
		List<Requirement> requirements = List.of(new Requirement("r1", "Answer the user's question clearly.",
			"CLAUDE.md", null, List.of("unclear")));
		Contract contract = new Contract(requirements, tests, ContractFixtures.profiles(requirements, tests),
			ContractFixtures.judge(), List.of(), Contract.DEFAULT_MINIMUM_PASS_RATE, ContractFixtures.SALT);
		List<AssertionExampleCheck> checks = List.of(
			new AssertionExampleCheck(FORMATTING, JUDGED_PROGRAM, PASS_REGULAR, "check-program", true),
			new AssertionExampleCheck(FORMATTING, JUDGED_PROGRAM, FAIL_MISSING, "check-program", false),
			new AssertionExampleCheck(FORMATTING, DECIDED_PROGRAM, PASS_REGULAR, "check-program", true),
			new AssertionExampleCheck(FORMATTING, DECIDED_PROGRAM, FAIL_MISSING, "check-program", false));

		List<JudgedExample> selected = AssertionChecks.selectExamples(contract, checks);

		requireThat(selected.stream().map(JudgedExample::check).toList(), "selectedChecks").
			isEqualTo(List.of(checks.get(1)));
		requireThat(selected.getFirst().assertion().scope(), "scope").isEqualTo(EvidenceScope.ACTIONS);
	}

	/**
	 * Returns the expected disposition and diagnostic wording of each example and verdict pairing.
	 *
	 * @return the rows
	 */
	@DataProvider(name = "exampleVerdicts")
	public static Object[][] exampleVerdicts()
	{
		return new Object[][]
			{
				{PASS_REGULAR, Verdict.PASS, Disposition.ACCEPTED, ""},
				{FAIL_MISSING, Verdict.FAIL, Disposition.ACCEPTED, ""},
				{FAIL_MISSING, Verdict.PASS, Disposition.REFUSAL, "the judge passed a failing example. "},
				{PASS_REGULAR, Verdict.FAIL, Disposition.WARNING, "the judge failed a passing example. "}
			};
	}

	/**
	 * Verifies that the judge's example verdict and rationale are retained. A mismatch names the test, assertion
	 * and example in author-facing terms.
	 *
	 * @param exampleId   the example's identifier
	 * @param label       the verdict that the judge returned
	 * @param disposition the expected effect on the proposal
	 * @param message     the explanation of a mismatch, or empty for an accepted verdict
	 */
	@Test(dataProvider = "exampleVerdicts")
	public void interpretsExampleVerdict(String exampleId, Verdict label, Disposition disposition, String message)
	{
		// Requirement: R-L16
		DerivedProposal proposal = proposal();
		JudgedExample example = AssertionChecks.selectExamples(proposal.contract(), proposal.exampleChecks()).stream().
			filter(candidate -> candidate.test().id().equals(FORMATTING) &&
				candidate.check().assertionId().equals(READABLE) && candidate.check().exampleId().equals(exampleId)).
			findFirst().orElseThrow();
		String behavior = null;
		String rationale = "The reply names the requested subject.";
		if (label == Verdict.FAIL)
		{
			behavior = "unclear";
			rationale = "The reply leaves the subject ambiguous.";
		}
		AssertionVerdict verdict = new AssertionVerdict(READABLE, behavior, rationale);

		ExampleResult result = new ExampleResult(example, verdict);

		requireThat(result.example(), "resultExample").isEqualTo(example);
		requireThat(result.verdict(), "assertionVerdict").isEqualTo(verdict);
		requireThat(result.disposition(), "disposition").isEqualTo(disposition);
		Optional<String> diagnostic = Optional.empty();
		if (!message.isEmpty())
		{
			diagnostic = Optional.of("For prompt test `formatting`, assertion `readable`, example `" + exampleId +
				"`, " + message + rationale);
		}
		requireThat(result.diagnostic(), "diagnostic").isEqualTo(diagnostic);

		int casePosition = 1;
		String expectedOutcome = FIRST_OUTCOME;
		if (exampleId.equals(PASS_REGULAR))
		{
			casePosition = 2;
			expectedOutcome = REGULAR_OUTCOME;
		}
		PreparedExample prepared = prepare(example, casePosition);
		Optional<String> diagnosticWithCase = Optional.empty();
		if (diagnostic.isPresent())
		{
			diagnosticWithCase = Optional.of(diagnostic.orElseThrow() + "\nValues file case " + casePosition +
				" has this expected outcome.\n" + expectedOutcome);
		}
		requireThat(result.diagnostic(prepared), "diagnosticWithCase").isEqualTo(diagnosticWithCase);
	}

	/**
	 * Verifies that a case prepared for another prompt test cannot be used for this result, even when both tests
	 * declare the same assertion and example identifiers.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsCaseFromAnotherTest()
	{
		// Requirement: R-L16
		DerivedProposal proposal = proposal();
		List<JudgedExample> examples = AssertionChecks.selectExamples(proposal.contract(), proposal.exampleChecks());
		JudgedExample original = examples.getFirst();
		JudgedExample otherTest = examples.stream().filter(candidate -> candidate.test().id().equals(SECOND_TEST) &&
			candidate.check().exampleId().equals(original.check().exampleId())).findFirst().orElseThrow();
		PreparedExample prepared = prepare(otherTest, 2);
		ExampleResult result = new ExampleResult(original, new AssertionVerdict(READABLE, null, "The reply is clear."));

		result.diagnostic(prepared);
	}

	/**
	 * Binds an example to a case at the supplied position in the fixture's values file.
	 *
	 * @param example      the example that was judged
	 * @param casePosition the case's one-based position
	 * @return the example and the selected authored case
	 */
	private static PreparedExample prepare(JudgedExample example, int casePosition)
	{
		PromptTest test = example.test();
		Path directory = Path.of("draft").resolve(test.id());
		Scenario scenario = new Scenario(test.variables(), test.turns(),
			test.assertions().stream().map(MappedAssertion::assertion).toList());
		PromptTestFiles files = new PromptTestFiles(directory, directory, scenario, List.of(), Map.of(), Map.of(),
			test.testCases());
		return new PreparedExample(example.check(), test, files.testCases().get(casePosition - 1), casePosition,
			files, Set.of());
	}

	/**
	 * Creates a proposal that has two tests using the same assertion identifier, one with a mix of tool policies.
	 *
	 * @return the derived proposal and its example checks
	 */
	private static DerivedProposal proposal()
	{
		MappedAssertion readable = new MappedAssertion(new Assertion(READABLE, AssertionKind.EFFECT, null,
			OnToolFailure.FAIL, "The reply names the requested subject.", "The judge evaluates the wording.",
			EvidenceScope.RESPONSE), "r1");
		MappedAssertion mentionsReport = new MappedAssertion(new Assertion(REPORT_MENTIONED, AssertionKind.EFFECT,
			"pattern Report", OnToolFailure.JUDGE, "The reply identifies the report.", null, EvidenceScope.RESPONSE),
			"r1");
		MappedAssertion readsReport = new MappedAssertion(new Assertion(READABLE, AssertionKind.EFFECT, null,
			OnToolFailure.FAIL, "The agent consulted the relevant report.", "The judge identifies the relevant report.",
			EvidenceScope.ACTIONS), "r1");
		MappedAssertion plain = new MappedAssertion(new Assertion(PLAIN_TEXT, AssertionKind.EFFECT, "exact-text Yes.",
			OnToolFailure.FAIL, "The reply says Yes."), "r1");
		List<PromptTest> tests = List.of(promptTest(FORMATTING, List.of(readable, mentionsReport, plain)),
			promptTest(SECOND_TEST, List.of(readsReport)));
		List<Requirement> requirements = List.of(new Requirement("r1", "Answer the user's question clearly.",
			"CLAUDE.md", null, List.of("unclear")));
		Contract contract = new Contract(requirements, tests, ContractFixtures.profiles(requirements, tests),
			ContractFixtures.judge(), List.of(), Contract.DEFAULT_MINIMUM_PASS_RATE, ContractFixtures.SALT);
		List<AssertionExampleCheck> checks = List.of(
			new AssertionExampleCheck(FORMATTING, READABLE, PASS_REGULAR, null, false),
			new AssertionExampleCheck(FORMATTING, READABLE, FAIL_MISSING, null, false),
			new AssertionExampleCheck(FORMATTING, REPORT_MENTIONED, PASS_REGULAR, "pattern", true),
			new AssertionExampleCheck(FORMATTING, REPORT_MENTIONED, FAIL_MISSING, "pattern", false),
			new AssertionExampleCheck(FORMATTING, PLAIN_TEXT, PASS_REGULAR, "exact-text", true),
			new AssertionExampleCheck(FORMATTING, PLAIN_TEXT, FAIL_MISSING, "exact-text", false),
			new AssertionExampleCheck(SECOND_TEST, READABLE, PASS_REGULAR, null, false),
			new AssertionExampleCheck(SECOND_TEST, READABLE, FAIL_MISSING, null, false));
		return new DerivedProposal(contract, List.of(), List.of(), List.of(), "none", "{}", Path.of("draft"),
			Map.of(), checks);
	}

	/**
	 * Creates a test of the fixture's requirement.
	 *
	 * @param id         the test identifier
	 * @param assertions the test's declared assertions
	 * @return the prompt test
	 */
	private static PromptTest promptTest(String id, List<MappedAssertion> assertions)
	{
		return new PromptTest(id, new RiskRank(2), List.of("r1"), List.of(),
			"The user asks about a report.", List.of("Was {{report}} completed?"), Duration.ofMinutes(1),
			"The reply identifies the report and states its status.", List.of(),
			List.of(new VariableDeclaration("report", "The report the user asks about.")), List.of(),
			List.of(ContractFixtures.testCase(FIRST_OUTCOME, "in_progress", Map.of("report", "the annual report")),
				ContractFixtures.testCase(REGULAR_OUTCOME, "regular", Map.of("report", "the budget report"))),
			assertions, List.of(), List.of());
	}
}
