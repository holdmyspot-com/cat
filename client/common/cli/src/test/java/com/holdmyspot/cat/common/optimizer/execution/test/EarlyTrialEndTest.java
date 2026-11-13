package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.execution.EarlyTrialEnd;
import org.testng.annotations.Test;

/** Checks when built-in assertion verdicts can no longer change. */
public final class EarlyTrialEndTest
{
	/** Checks scoped reply assertions and contradictions that settle an unscoped assertion. */
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
