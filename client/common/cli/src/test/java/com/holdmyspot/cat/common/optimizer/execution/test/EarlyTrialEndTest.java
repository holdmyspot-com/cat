package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.ExpectedOutcomeTool;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.RiskRank;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import com.holdmyspot.cat.common.optimizer.execution.EarlyTrialEnd;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Checks when built-in assertion verdicts can no longer change. */
public final class EarlyTrialEndTest
{
	/** Checks scoped reply assertions and contradictions that settle an unscoped assertion. */
	// Requirement: R-E25
	@Test
	public void replyChecksSettleByScopeOrContradiction()
	{
		BuiltInTool scoped = new BuiltInTool.ExactText(2, "answer");
		BuiltInTool unscoped = new BuiltInTool.ExactText(null, "answer");
		requireThat(EarlyTrialEnd.isSettled(scoped, result(scoped, true), 1), "scope_turn_2_pending").isFalse();
		requireThat(EarlyTrialEnd.isSettled(scoped, result(scoped, true), 2), "scope_turn_2_settled").isTrue();
		requireThat(EarlyTrialEnd.isSettled(unscoped, result(unscoped, false), 1), "contradiction_settled").isTrue();
		requireThat(EarlyTrialEnd.isSettled(unscoped, result(unscoped, true), 1), "pass_pending").isFalse();
		BuiltInToolResult judgeFallback = new BuiltInToolResult(new Assertion("sample", AssertionKind.EFFECT,
			"exact-text expected", OnToolFailure.JUDGE, "sample assertion"), false, "mismatch");
		requireThat(EarlyTrialEnd.isSettled(unscoped, judgeFallback, 1), "judge_fallback_unsettled").isFalse();
	}

	/** Checks that effect assertions settle only when later turns cannot change their evidence. */
	// Requirement: R-E25
	@Test
	public void effectChecksSettleOnFixedEvidence()
	{
		BuiltInTool used = new BuiltInTool.ToolUsed("Bash", null);
		BuiltInTool unused = new BuiltInTool.ToolUnused("Bash", null);
		BuiltInTool readFull = new BuiltInTool.ReadInFull("notes.md");
		BuiltInTool readAtMost = new BuiltInTool.ReadAtMost("notes.md",
			new java.math.BigDecimal("50"));
		BuiltInTool file = new BuiltInTool.FileExists("answer.txt");
		requireThat(EarlyTrialEnd.isSettled(used, result(used, true), 1), "tool_use_pass_settled").isTrue();
		requireThat(EarlyTrialEnd.isSettled(unused, result(unused, false), 1), "tool_use_failure_settled").isTrue();
		requireThat(EarlyTrialEnd.isSettled(readFull, result(readFull, true), 1), "full_read_pass_settled").isTrue();
		requireThat(EarlyTrialEnd.isSettled(readAtMost, result(readAtMost, false), 1),
			"read_limit_failure_settled").isTrue();
		requireThat(EarlyTrialEnd.isSettled(file, result(file, true), 1), "file_state_may_change").isFalse();
	}

	/** An unscoped count's failure needs evidence; a scoped count settles when its selected turn completes. */
	// Requirement: R-E25
	@Test
	public void countResultNeedsItsCompletedScope()
	{
		BuiltInTool.Count unscoped = new BuiltInTool.Count(null, "answer", 2, 2);
		BuiltInToolResult failed = new BuiltInToolResult(new Assertion("count", AssertionKind.EFFECT,
			"count answer 2 2", "bounded reply count"), false, "1 match");
		requireThat(EarlyTrialEnd.isSettled(unscoped, failed, 1), "recoverable_count_failure").isFalse();
		BuiltInTool.Count scoped = new BuiltInTool.Count(2, "answer", 2, 2);
		BuiltInToolResult scopedFailure = new BuiltInToolResult(new Assertion("count", AssertionKind.EFFECT,
			"count turn=2 answer 2 2", "bounded reply count"), false, "1 match");
		requireThat(EarlyTrialEnd.isSettled(scoped, scopedFailure, 1), "count_scope_pending").isFalse();
		requireThat(EarlyTrialEnd.isSettled(scoped, scopedFailure, 2), "count_scope_complete").isTrue();
	}

	/** A resumed observer keeps earlier settlement turns and receipts its new end decision before returning it. */
	// Requirement: R-E25
	@Test
	public void resumesProgressAndRecordsTheDecision() throws IOException
	{
		PromptTest test = progressTest();
		TestCase testCase = test.testCases().getFirst();
		List<TurnObserver.Progress> receipts = new ArrayList<>();
		List<String> order = new ArrayList<>();
		EarlyTrialEnd observer = EarlyTrialEnd.forTest(test, testCase, sessions(new SessionEvidence(List.of())),
			new TurnObserver.Progress(1, Map.of("reply", 1), false), progress ->
			{
				receipts.add(progress);
				order.add("receipt");
			});

		boolean endedEarly = observer.endAfter(2, completed(List.of(List.of("ok"), List.of("done"))));
		order.add("decision");

		requireThat(endedEarly, "endedEarly").isTrue();
		requireThat(observer.progress(), "progress").isEqualTo(new TurnObserver.Progress(2,
			Map.of("reply", 1, TestCase.EXPECTED_OUTCOME, 2), true));
		requireThat(receipts, "receipts").isEqualTo(List.of(observer.progress()));
		requireThat(order, "order").isEqualTo(List.of("receipt", "decision"));
	}

	/** An undecidable completed turn still advances and receipts progress without changing prior settlements. */
	// Requirement: R-E25
	@Test
	public void receiptsUndecidableTurn() throws IOException
	{
		PromptTest test = exitStatusTest();
		List<TurnObserver.Progress> receipts = new ArrayList<>();
		TurnObserver.Progress previous = new TurnObserver.Progress(2, Map.of("reply", 1), false);
		EarlyTrialEnd observer = EarlyTrialEnd.forTest(test, test.testCases().getFirst(), sessions(new SessionEvidence(
			List.of(new SessionEvidence.Tool(3, "root", "Bash", "true", true, null, "", List.of())))), previous,
			receipts::add);

		boolean endedEarly = observer.endAfter(3, completed(List.of(List.of("one"), List.of("two"), List.of("three"))));

		requireThat(endedEarly, "endedEarly").isFalse();
		requireThat(observer.progress(), "progress").isEqualTo(new TurnObserver.Progress(3,
			Map.of("reply", 1), false));
		requireThat(receipts, "receipts").isEqualTo(List.of(observer.progress()));
	}

	/**
	 * Creates a test with one prior count settlement and an expected outcome settled on the next turn.
	 *
	 * @return a two-turn built-in assertion test
	 */
	private static PromptTest progressTest()
	{
		TestCase testCase = new TestCase(Map.of("request", new TestValue.Text("Reply with ok.")), "done");
		MappedAssertion reply = new MappedAssertion(new Assertion("reply", AssertionKind.EFFECT,
			"count ok 0 0", "There is no ok reply."), "reply_required");
		return new PromptTest("progress", new RiskRank(1), List.of("reply_required"), List.of(),
			"A reply is requested.", List.of("{{request}}", "Reply again."), Duration.ofSeconds(30), "Replies with ok.",
			List.of(), List.of(new VariableDeclaration("request", "The first user turn.")), List.of(), List.of(testCase),
			List.of(reply), List.of(), List.of(), Optional.empty(), Optional.of(new ExpectedOutcomeTool(
				"exact-text turn=2 done")));
	}

	/**
	 * Creates a test whose matching command has no reported exit status in the test evidence.
	 *
	 * @return a test with an undecidable command assertion
	 */
	private static PromptTest exitStatusTest()
	{
		TestCase testCase = new TestCase(Map.of("request", new TestValue.Text("Run true.")), "done");
		MappedAssertion command = new MappedAssertion(new Assertion("command", AssertionKind.EFFECT,
			"exit-status true 0", "The command succeeds."), "command_succeeds");
		MappedAssertion reply = new MappedAssertion(new Assertion("reply", AssertionKind.EFFECT,
			"exact-text turn=1 one", "The first reply is one."), "command_succeeds");
		return new PromptTest("command", new RiskRank(1), List.of("command_succeeds"), List.of(),
			"A command is requested.", List.of("{{request}}", "Continue.", "Continue again."), Duration.ofSeconds(30),
			"Runs true.", List.of(), List.of(new VariableDeclaration("request", "The user turn.")), List.of(),
			List.of(testCase), List.of(command, reply), List.of(), List.of(), Optional.empty(), Optional.empty());
	}

	/**
	 * Creates a completed result with the given replies.
	 *
	 * @param replies the session replies
	 * @return a completed result
	 */
	private static SessionResult.Completed completed(List<List<String>> replies)
	{
		return new SessionResult.Completed(Path.of("transcript"), replies, Path.of("effects"), Path.of("initial"), null,
			null, null, List.of(), "none", List.of(), replies.size(), false, Map.of());
	}

	/**
	 * Creates a session port that returns the supplied activity as its evidence.
	 *
	 * @param evidence the recorded session activity
	 * @return a port that reads the supplied evidence
	 */
	private static SessionPort sessions(SessionEvidence evidence)
	{
		return (SessionPort) Proxy.newProxyInstance(SessionPort.class.getClassLoader(), new Class<?>[]{SessionPort.class},
			(_, method, _) ->
			{
				if (method.getName().equals("readEvidence"))
					return evidence;
				throw new UnsupportedOperationException(method.getName());
			});
	}

	/**
	 * Creates a representative result for a built-in tool.
	 *
	 * @param tool the tool being evaluated
	 * @param passed whether its assertion passed
	 * @return the representative tool result
	 */
	private static BuiltInToolResult result(BuiltInTool tool, boolean passed)
	{
		String line = switch (tool)
		{
			case BuiltInTool.ExactText ignored -> "exact-text expected";
			case BuiltInTool.ToolUsed ignored -> "tool-used Bash";
			case BuiltInTool.ToolUnused ignored -> "tool-unused Bash";
			case BuiltInTool.ReadInFull ignored -> "read-in-full notes.md";
			case BuiltInTool.ReadAtMost ignored -> "read-at-most notes.md 50";
			case BuiltInTool.FileExists ignored -> "file-exists answer.txt";
			default -> throw new AssertionError(tool);
		};
		return new BuiltInToolResult(new Assertion("sample", AssertionKind.EFFECT, line, "sample assertion"), passed,
			"sample evidence");
	}
}
