package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.ExpectedOutcomeTool;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;
import com.holdmyspot.cat.common.optimizer.store.TrialRecord;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests how {@link Driver} consumes a completed session that ended after all assertions settled. The session port
 * supplies the early-end result defined by {@link SessionResult.Completed}; these tests do not run or stop a session.
 */
public final class EarlyEndDriverTest
{
	/**
	 * The fixture test whose case assignments provide both development and held-out parts.
	 */
	private static final String TEST = "formatting";
	/**
	 * The mapped assertion that checks the first reply, alongside CAT's added expected-outcome assertion.
	 */
	private static final String FIRST_REPLY = "first_reply";

	/**
	 * A passing early-ended session enters development evidence as one ordinary pass, without a judge, infrastructure
	 * retry or interruption, and its persisted outcome records the skipped turn and assertion settlement turns.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void passingResultEntersDevelopment() throws IOException
	{
		// Requirement: R-E25
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("early-end-driver")),
				new Builder().confirmed(contract(), 1).working(1).readyBaseline().
					development(TEST, 10, 0).window(1).build());
			harness.trials(_ -> earlyResult(harness, "pass"));
			TrialKey key;
			Campaign adopted;
			try (Driver driver = harness.open())
			{
				Campaign assigned = harness.runUntil(driver, state -> !state.trials().isEmpty());
				key = assigned.trials().getFirst().key();
				adopted = harness.runUntil(driver, state -> state.development().count(TEST).outcomes() > 10);
			}

			requireThat(adopted.development().count(TEST).passes(), "passes").isEqualTo(11);
			requireThat(adopted.development().count(TEST).failures(), "failures").isEqualTo(0);
			TrialRecord record = harness.store().readRecord(DriverHarness.CAMPAIGN, key).orElseThrow();
			requireThat(record.infrastructureAttempts(), "infrastructureAttempts").isEmpty();
			requireThat(record.sessions().size(), "finishedSessions").isEqualTo(1);
			requireThat(record.outcome().verdict(), "verdict").isEqualTo(Verdict.PASS);
			requireThat(record.outcome().endedEarly(), "endedEarly").isTrue();
			requireThat(record.outcome().responses(), "responses").isEqualTo(List.of(List.of("pass")));
			requireThat(record.outcome().judge(), "judge").isEmpty();
			requireThat(record.outcome().rationale(), "rationale").startsWith("Passed built-in tools:");
			requireEndNote(record.outcome().rationale());
			requireNoJudge(harness);
		}
	}

	/**
	 * A failing early-ended session produces built-in failures rather than an infrastructure attempt. Its rationale
	 * identifies the skipped turn and settlement turns in both the triage request and the prompt author's failure.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void failedResultReachesFailureConsumers() throws IOException
	{
		// Requirement: R-E25
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("early-end-driver")),
				new Builder().confirmed(contract(), 1).working(1).readyBaseline().
					development(TEST, 10, 0).window(1).build());
			harness.trials(_ -> earlyResult(harness, "fail"));
			harness.triages(_ -> harness.triaged(Impact.BLOCKING));
			TrialKey key;
			try (Driver driver = harness.open())
			{
				Campaign assigned = harness.runUntil(driver, state -> !state.trials().isEmpty());
				key = assigned.trials().getFirst().key();
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
			}

			TrialRecord record = harness.store().readRecord(DriverHarness.CAMPAIGN, key).orElseThrow();
			requireThat(record.infrastructureAttempts(), "infrastructureAttempts").isEmpty();
			requireThat(record.sessions().size(), "finishedSessions").isEqualTo(1);
			requireThat(record.outcome().verdict(), "verdict").isEqualTo(Verdict.FAIL);
			requireThat(record.outcome().endedEarly(), "endedEarly").isTrue();
			PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
			Failure failure = brief.developmentFailures().getFirst();
			requireThat(failure.failedAssertions(), "failedAssertions").isEqualTo(List.of(
				new FailedAssertion(FIRST_REPLY, "reply_not_exact"),
				new FailedAssertion(TestCase.EXPECTED_OUTCOME, "reply_not_exact")));
			requireThat(failure.responses(), "responses").isEqualTo(List.of(List.of("fail")));
			requireThat(failure.rationale(), "rationale").startsWith("Failed built-in tools:");
			requireEndNote(failure.rationale());
			FakeSession triage = harness.sessions().sessions().stream().
				filter(session -> session.request().role() == SessionRole.TRIAGE).findFirst().orElseThrow();
			requireEndNote(triage.request().turns().getFirst());
			requireNoJudge(harness);
		}
	}

	/**
	 * Returns one two-turn test whose mapped assertion and added expected-outcome assertion inspect only turn one.
	 * The fixture's variable assignments provide distinct development and held-out cases without replaying baseline.
	 *
	 * @return the fully tool-decided contract
	 */
	private static Contract contract()
	{
		Contract fixture = ContractFixtures.contract();
		PromptTest original = fixture.findTest(TEST).orElseThrow();
		Requirement requirement = new Requirement(FIRST_REPLY, "The first reply is pass.", Requirement.GOAL,
			null, List.of("reply_not_exact"));
		List<TestCase> cases = original.testCases().stream().map(testCase -> new TestCase(testCase.values(),
			"The first reply is pass for the input " + testCase.describedValues() + ".")).toList();
		PromptTest test = new PromptTest(original.id(), original.riskRank(), List.of(requirement.id()), List.of(),
			"The user asks for a fixed first reply before a follow-up.",
			List.of("Reply pass in {{tone}} style.", "Now address {{audience}} reader."), original.timeLimit(),
			"The first reply is pass; no assertion depends on the follow-up.", List.of(), original.variables(),
			List.of(), cases, List.of(new MappedAssertion(new Assertion(FIRST_REPLY, AssertionKind.EFFECT,
				"exact-text turn=1 pass", "The first reply is pass."), requirement.id())), List.of(), List.of(),
			Optional.empty(), Optional.of(new ExpectedOutcomeTool("exact-text turn=1 pass")));
		List<Requirement> requirements = List.of(requirement);
		List<PromptTest> tests = List.of(test);
		return new Contract(requirements, tests, ContractFixtures.profiles(requirements, tests), fixture.judge(),
			fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
	}

	/**
	 * Supplies the documented early-ended port result: one completed reply out of two requested turns, with every
	 * assertion settled at turn one. The driver still decides both tools from the reply itself.
	 *
	 * @param harness the test's session and evidence fixture
	 * @param reply   the completed first-turn reply
	 * @return the completed result with early-end metadata
	 */
	private static SessionResult.Completed earlyResult(DriverHarness harness, String reply)
	{
		SessionResult.Completed completed = harness.completed(reply, DriverHarness.MODEL);
		return new SessionResult.Completed(completed.transcript(), completed.responses(), completed.effects(),
			completed.initialProject(), completed.model(), completed.reasoning(), completed.usage(), completed.windows(),
			completed.promptSources(), completed.toolCalls(), 2, true,
			Map.of(FIRST_REPLY, 1, TestCase.EXPECTED_OUTCOME, 1));
	}

	/**
	 * Checks the caller-visible early-end note without relying on the settlement map's iteration order.
	 *
	 * @param text the persisted rationale or consumer request
	 */
	private static void requireEndNote(String text)
	{
		requireThat(text, "earlyEndNote").contains("Trial ended after turn 1 of 2; turns not run: 2;").
			contains("assertion settlement turns:").contains(FIRST_REPLY + "=turn 1").
			contains(TestCase.EXPECTED_OUTCOME + "=turn 1");
	}

	/**
	 * Checks that tools decided every trial without starting a judge session.
	 *
	 * @param harness the test's recorded session requests
	 */
	private static void requireNoJudge(DriverHarness harness)
	{
		List<FakeSession> judges = harness.sessions().sessions().stream().
			filter(session -> session.request().role() == SessionRole.JUDGE).toList();
		requireThat(judges, "judgeSessions").isEmpty();
	}
}
