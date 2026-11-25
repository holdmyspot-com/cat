package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.RiskRank;
import com.holdmyspot.cat.common.optimizer.contract.Scenario;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Finding;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Kind;
import com.holdmyspot.cat.common.optimizer.execution.AssertionVerdict;
import com.holdmyspot.cat.common.optimizer.execution.prompt.JudgePrompt;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.Disposition;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.ExampleResult;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.JudgedExample;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionChecks.PreparedExample;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionExampleCheck;
import com.holdmyspot.cat.common.optimizer.proposal.JudgeTemplate;
import com.holdmyspot.cat.common.optimizer.proposal.JudgedAssertionChecks;
import com.holdmyspot.cat.common.optimizer.store.test.InMemoryCampaignStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests proposal assertion checks with a model-free judge callback.
 */
public final class JudgedAssertionChecksTest
{
	private static final String CAMPAIGN = "campaign";
	private static final String ASSERTION_ID = "clear_subject";
	private static final String TEST_ID = "report";
	private static final String EXAMPLE_ID = "fail_missing";
	private static final String EXPECTED_OUTCOME = "The private Budget report is complete.";
	private static final String REPLY = "The reply omits Budget.";
	private static final String RATIONALE = "The judge found the subject clear.";
	private static final Sha256 EXAMPLE_KEY = Sha256.of("example".getBytes(UTF_8));
	private static final Sha256 REVIEW_KEY = Sha256.of("review".getBytes(UTF_8));
	private static final Requirement REQUIREMENT = new Requirement("clarity", "Report the requested subject.",
		"AGENTS.md", null, List.of("unclear"));
	private static final Map<String, Requirement> REQUIREMENTS = Map.of(REQUIREMENT.id(), REQUIREMENT);
	private static final MappedAssertion RESPONSE = new MappedAssertion(new Assertion(ASSERTION_ID,
		AssertionKind.EFFECT, null, OnToolFailure.FAIL,
		"The reply identifies the subject and gives this outcome. {{expected_outcome}}", "Meaning needs a judge.",
		EvidenceScope.RESPONSE), REQUIREMENT.id());
	private static final MappedAssertion ACTIONS = new MappedAssertion(new Assertion(ASSERTION_ID,
		AssertionKind.EFFECT, null, OnToolFailure.FAIL, "The report contains the requested subject.",
		"Meaning needs a judge.", EvidenceScope.ACTIONS), REQUIREMENT.id());
	private static final MappedAssertion MECHANICAL = new MappedAssertion(new Assertion("exact",
		AssertionKind.EFFECT, "exact-text Yes.", "The reply says Yes."), REQUIREMENT.id());
	private static final TestCase CASE = ContractFixtures.testCase(EXPECTED_OUTCOME, "complete",
		Map.of("subject", "Budget"));
	private static final String PASS = """
		{"assertions":[{"id":"clear_subject","verdict":"pass","behavior":null,
		"rationale":"The judge found the subject clear."}]}
		""";
	private static final String FINDING = """
		{"reviews":[{"assertion_id":"clear_subject","kind":"response_depends_on_actions",
		"rationale":"Project evidence is needed.","tool":null}]}
		""";
	private static final String NO_FINDING = """
		{"reviews":[{"assertion_id":"clear_subject","kind":"no_finding","rationale":"The scope fits.","tool":null}]}
		""";
	private final JsonMapper json = JsonMapper.builder().build();
	private final JudgeProfile profile = new JudgeTemplate(json, new TrialProfile("frozen-judge", "high")).
		profile("Check the required subject.", "Pass only when the evidence supports the assertion.");

	/**
	 * Creates the test.
	 */
	public JudgedAssertionChecksTest()
	{
	}

	/**
	 * Runs one held-out example, persists the rendered reply and returned verdict, and reuses that result.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 * @throws InterruptedException if the check is interrupted
	 */
	@Test
	public void recordsAndReusesTheExample() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("judged-assertion-checks"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path scratch = Files.createDirectory(root.resolve("scratch"));
			PreparedExample prepared = prepare(test(List.of(RESPONSE)), project);
			JudgedExample example = selected(prepared);
			InMemoryCampaignStore store = new InMemoryCampaignStore();
			AtomicInteger requests = new AtomicInteger();
			JudgedAssertionChecks checks = new JudgedAssertionChecks(json, new JudgePrompt(json, root), store,
				CAMPAIGN, request ->
				{
					requests.incrementAndGet();
					assertHeldOut(request);
					requireThat(request.readOnly(), "readOnly").isEmpty();
					requireThat(request.turns().getFirst(), "turn").contains("Find Budget.").contains(REPLY).
						contains("The reply identifies the subject and gives this outcome. " + EXPECTED_OUTCOME).
						doesNotContain(EXAMPLE_ID).doesNotContain("Report activity");
					return completed(PASS);
				});

			ExampleResult result = checks.example(EXAMPLE_KEY, profile, prepared, example, project, scratch).
				orElseThrow();
			requireThat(result.verdict(), "verdict").isEqualTo(new AssertionVerdict(ASSERTION_ID, null, RATIONALE));
			requireThat(result.disposition(), "disposition").isEqualTo(Disposition.REFUSAL);
			ObjectNode stored = store.readAssertionCheck(CAMPAIGN, EXAMPLE_KEY).orElseThrow();
			requireThat(stored.toString(), "stored").contains(REPLY).contains(EXPECTED_OUTCOME).contains(RATIONALE);
			requireThat(checks.example(EXAMPLE_KEY, profile, prepared, example, project, scratch), "reused").
				isEqualTo(Optional.of(result));
			requireThat(requests.get(), "requests").isEqualTo(1);
		}
	}

	/**
	 * Persists the action evidence of the filled example with no dependency on its temporary copies.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 * @throws InterruptedException if the check is interrupted
	 */
	@Test
	public void retainsFilledActionEvidence() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("judged-assertion-checks"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path scratch = Files.createDirectory(root.resolve("scratch"));
			PreparedExample prepared = prepare(test(List.of(ACTIONS)), project);
			InMemoryCampaignStore store = new InMemoryCampaignStore();
			JudgedAssertionChecks checks = new JudgedAssertionChecks(json, new JudgePrompt(json, root), store,
				CAMPAIGN, request ->
				{
					assertHeldOut(request);
					requireThat(request.evidencePrompt().orElseThrow().transcript(), "activity").isRegularFile();
					requireThat(Files.readString(scratch.resolve("project").resolve("Budget.txt")), "project").
						isEqualTo("Filled report for Budget.");
					return completed(PASS);
				});

			requireThat(checks.example(EXAMPLE_KEY, profile, prepared, selected(prepared), project, scratch).
				isPresent(), "result").isTrue();
			ObjectNode stored = store.readAssertionCheck(CAMPAIGN, EXAMPLE_KEY).orElseThrow();
			requireThat(stored.toString(), "stored").contains("Report activity for Budget.").contains("Budget.txt").
				contains(RATIONALE).doesNotContain(scratch.toString());
		}
	}

	/**
	 * Runs one review with no case or evidence access, retaining a complete review even if it has no findings.
	 *
	 * @param output the complete judge result
	 * @param findings the findings that result represents
	 * @throws IOException if the review cannot be read or written
	 * @throws InterruptedException if the check is interrupted
	 */
	@Test(dataProvider = "completeReviews")
	public void recordsAndReusesTheReview(String output, List<Finding> findings)
		throws IOException, InterruptedException
	{
		Path directory = Path.of("judge");
		InMemoryCampaignStore store = new InMemoryCampaignStore();
		AtomicInteger requests = new AtomicInteger();
		JudgedAssertionChecks checks = new JudgedAssertionChecks(json, new JudgePrompt(json, directory), store,
			CAMPAIGN, request ->
			{
				requests.incrementAndGet();
				assertHeldOut(request);
				requireThat(request.readOnly(), "readOnly").isEmpty();
				requireThat(request.turns().getFirst(), "turn").contains(MECHANICAL.assertion().tool()).
					contains(REQUIREMENT.description()).doesNotContain(EXPECTED_OUTCOME).doesNotContain(REPLY).
					doesNotContain(EXAMPLE_ID);
				return completed(output);
			});
		PromptTest test = test(List.of(RESPONSE, MECHANICAL));

		requireThat(checks.review(REVIEW_KEY, profile, test, REQUIREMENTS, directory), "findings").
			isEqualTo(Optional.of(findings));
		requireThat(store.readAssertionCheck(CAMPAIGN, REVIEW_KEY).isPresent(), "recorded").isTrue();
		requireThat(checks.review(REVIEW_KEY, profile, test, REQUIREMENTS, directory), "reused").
			isEqualTo(Optional.of(findings));
		requireThat(requests.get(), "requests").isEqualTo(1);
	}

	/**
	 * Returns the valid result with a refusal and the valid empty review.
	 *
	 * @return the review cases
	 */
	@DataProvider
	public static Object[][] completeReviews()
	{
		return new Object[][]
			{
				{FINDING, List.of(new Finding(ASSERTION_ID, Kind.RESPONSE_DEPENDS_ON_ACTIONS,
					"Project evidence is needed.", null))},
				{NO_FINDING, List.of()}
			};
	}

	/**
	 * Skips reviews for tests consisting of mechanical assertions.
	 *
	 * @throws IOException if the review record cannot be read
	 * @throws InterruptedException if the check is interrupted
	 */
	@Test
	public void skipsMechanicalOnlyReview() throws IOException, InterruptedException
	{
		Path directory = Path.of("judge");
		InMemoryCampaignStore store = new InMemoryCampaignStore();
		JudgedAssertionChecks checks = new JudgedAssertionChecks(json, new JudgePrompt(json, directory), store,
			CAMPAIGN, _ ->
			{
				throw new AssertionError("The mechanical test cannot start a judge session");
			});

		requireThat(checks.review(REVIEW_KEY, profile, test(List.of(MECHANICAL)), REQUIREMENTS, directory), "result").
			isEqualTo(Optional.of(List.of()));
		requireThat(store.readAssertionCheck(CAMPAIGN, REVIEW_KEY).isEmpty(), "unrecorded").isTrue();
	}

	/**
	 * Leaves no record for a failure, interruption, malformed response, or response without a complete result.
	 *
	 * @param description what made the result incomplete
	 * @param response the incomplete model-free result
	 * @throws IOException if the example or a record cannot be read
	 * @throws InterruptedException if the check is interrupted
	 */
	@Test(dataProvider = "incompleteResults")
	public void leavesIncompleteResultsUnrecorded(String description, SessionResult response)
		throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("judged-assertion-checks"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path scratch = Files.createDirectory(root.resolve("scratch"));
			PromptTest test = test(List.of(RESPONSE));
			PreparedExample prepared = prepare(test, project);
			InMemoryCampaignStore store = new InMemoryCampaignStore();
			AtomicInteger requests = new AtomicInteger();
			JudgedAssertionChecks checks = new JudgedAssertionChecks(json, new JudgePrompt(json, root), store,
				CAMPAIGN, _ ->
				{
					requests.incrementAndGet();
					return response;
				});

			requireThat(checks.example(EXAMPLE_KEY, profile, prepared, selected(prepared), project, scratch).
				isEmpty(), "incompleteExample").withContext(description, "result").isTrue();
			requireThat(checks.review(REVIEW_KEY, profile, test, REQUIREMENTS, root).isEmpty(), "incompleteReview").
				withContext(description, "result").isTrue();
			requireThat(requests.get(), "attempts").isEqualTo(2);
			requireThat(store.readAssertionCheck(CAMPAIGN, EXAMPLE_KEY).isEmpty(), "exampleUnrecorded").isTrue();
			requireThat(store.readAssertionCheck(CAMPAIGN, REVIEW_KEY).isEmpty(), "reviewUnrecorded").isTrue();
		}
	}

	/**
	 * Returns representative unsuccessful judge results.
	 *
	 * @return the incomplete result cases
	 */
	@DataProvider
	public static Object[][] incompleteResults()
	{
		return new Object[][]
			{
				{"runner failure", new SessionResult.Failed("failed", List.of())},
				{"interruption", new SessionResult.Interrupted(List.of())},
				{"malformed response", completed("not JSON")},
				{"absent verdict", completed("{\"assertions\":[],\"reviews\":[]}")},
				{"ended early", new SessionResult.Completed(Path.of("transcript"), List.of(List.of(PASS)),
					Path.of("effects"), Path.of("initial"), null, null, null, List.of(), "none", List.of(), 2, true,
					Map.of())}
			};
	}

	/**
	 * Checks the identity of the judge request.
	 *
	 * @param request the dispatched request
	 */
	private void assertHeldOut(SessionRequest request)
	{
		requireThat(request.role(), "role").isEqualTo(SessionRole.JUDGE);
		requireThat(request.part(), "part").isEqualTo(Part.HELD_OUT);
		requireThat(request.profile(), "profile").isEqualTo(profile.profile());
	}

	/**
	 * Gives the judge one response as though a session finished.
	 *
	 * @param response the final reply
	 * @return the completed result
	 */
	private static SessionResult.Completed completed(String response)
	{
		return new SessionResult.Completed(Path.of("transcript"), List.of(List.of(response)), Path.of("effects"),
			Path.of("initial"), null, null, null, List.of(), "none", List.of());
	}

	/**
	 * Creates the prompt test whose designated example a judge will check.
	 *
	 * @param assertions the test's declared assertions
	 * @return the test
	 */
	private static PromptTest test(List<MappedAssertion> assertions)
	{
		String example = ASSERTION_ID + "/" + EXAMPLE_ID;
		String activity = """
			{"format_version":1,"entries":[],"received":["Report activity for {{subject}}."]}
			""";
		PromptTest.Draft draft = new PromptTest.Draft(Path.of(TEST_ID), "Read the report.", Map.of(),
			Map.of(example + "/replies.json", new FileContent("[[\"The reply omits {{subject}}.\"]]".getBytes(UTF_8)),
				example + "/activity.json", new FileContent(activity.getBytes(UTF_8)),
				example + "/project/{{subject}}.txt", new FileContent("Filled report for {{subject}}.".getBytes(UTF_8))),
			Set.of(ASSERTION_ID, example, example + "/project"));
		return new PromptTest(TEST_ID, new RiskRank(2), List.of(REQUIREMENT.id()), List.of(),
			"The user asks about a report.", List.of("Find {{subject}}."), Duration.ofMinutes(1),
			"Report the requested subject.", List.of(), List.of(new VariableDeclaration("subject", "The subject.")),
			List.of(), List.of(CASE), assertions, List.of(), List.of(), Optional.of(draft));
	}

	/**
	 * Prepares the only case that fills the example.
	 *
	 * @param test the test that owns the example
	 * @param project the project directory
	 * @return the example bound to its case
	 */
	private static PreparedExample prepare(PromptTest test, Path project)
	{
		Scenario scenario = new Scenario(test.variables(), test.turns(),
			test.assertions().stream().map(MappedAssertion::assertion).toList());
		PromptTestFiles files = new PromptTestFiles(project, project, scenario, List.of(), Map.of(), Map.of(),
			List.of(CASE));
		AssertionExampleCheck check = new AssertionExampleCheck(TEST_ID, ASSERTION_ID, EXAMPLE_ID, null, false);
		return new PreparedExample(check, test, CASE, 1, files, Set.of());
	}

	/**
	 * Binds the prepared example to the one judged assertion.
	 *
	 * @param prepared the example bound to its case
	 * @return the selected assertion and example
	 */
	private static JudgedExample selected(PreparedExample prepared)
	{
		Assertion assertion = prepared.test().assertions().getFirst().assertion();
		return new JudgedExample(prepared.test(), new JudgedAssertion(assertion, REQUIREMENT), prepared.check());
	}
}
