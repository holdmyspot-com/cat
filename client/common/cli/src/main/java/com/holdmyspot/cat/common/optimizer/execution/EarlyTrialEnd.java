package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.TurnObserver;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

/** Decides whether built-in assertions have fixed verdicts after a completed turn. */
public final class EarlyTrialEnd implements TurnObserver
{
	private final PromptTest test;
	private final TestCase testCase;
	private final SessionPort sessions;
	private final Map<String, Integer> settledAssertions = new LinkedHashMap<>();

	/**
	 * Creates an observer for a trial's built-in assertions.
	 *
	 * @param test the prompt test whose assertions are evaluated
	 * @param testCase the case being evaluated
	 * @param sessions the session archive and project store
	 */
	private EarlyTrialEnd(PromptTest test, TestCase testCase, SessionPort sessions)
	{
		this.test = test;
		this.testCase = testCase;
		this.sessions = sessions;
	}

	/**
	 * Creates an observer for the given test case.
	 *
	 * @param test the prompt test whose assertions are evaluated
	 * @param testCase the case being evaluated
	 * @param sessions the session archive and project store
	 * @return an observer that tracks settled assertions
	 */
	public static EarlyTrialEnd forTest(PromptTest test, TestCase testCase, SessionPort sessions)
	{
		return new EarlyTrialEnd(test, testCase, sessions);
	}

	@Override
	public boolean endAfter(int turn, SessionResult.Completed completed) throws IOException
	{
		TrialEvidence evidence = TrialEvidence.read(sessions, completed);
		Map<String, BuiltInToolResult> results = new HashMap<>();
		try
		{
			for (BuiltInToolResult result : test.decide(testCase, evidence))
				results.put(result.assertion().id(), result);
		}
		catch (UndecidableToolException _)
		{
			return false;
		}
		for (Assertion assertion : test.allAssertions())
		{
			if (assertion.builtInTool().isEmpty())
				continue;
			BuiltInTool tool = assertion.builtInTool().orElseThrow();
			BuiltInToolResult result = results.get(assertion.id());
			if (result != null && settledWithEvidence(tool, result, turn, evidence))
				settledAssertions.putIfAbsent(assertion.id(), turn);
		}
		return settledAssertions.size() == test.allAssertions().size();
	}

	@Override
	public Map<String, Integer> settledAssertions()
	{
		return Map.copyOf(settledAssertions);
	}

	/**
	 * Checks settlement using the trial evidence when the pass/fail result alone is insufficient.
	 *
	 * @param tool the tool being evaluated
	 * @param result its result on the completed turns
	 * @param turn the completed turn number
	 * @param evidence the record of those turns
	 * @return {@code true} if later turns cannot change the verdict
	 */
	private boolean settledWithEvidence(BuiltInTool tool, BuiltInToolResult result, int turn,
		TrialEvidence evidence)
	{
		if (tool instanceof BuiltInTool.Count count && count.turn() == null)
		{
			if (result.leftToJudge() || evidence.replies().isEmpty())
				return false;
			// Later replies can repair a count below the minimum, but cannot undo matches above the maximum.
			BuiltInTool.Count maximumOnly = new BuiltInTool.Count(null, count.regex(), 0, count.maximum());
			return !maximumOnly.decide(evidence, PromptTest.textByName(testCase)).passed();
		}
		return isSettled(tool, result, turn);
	}

	/**
	 * Checks whether a built-in tool result alone establishes that its verdict cannot change on a later turn.
	 * An unscoped count requires the trial evidence to distinguish too few matches from too many, so its boolean
	 * result alone never establishes settlement.
	 *
	 * @param tool the assertion tool
	 * @param result the tool's result
	 * @param turn the completed turn number
	 * @return {@code true} if the result establishes that later turns cannot change the verdict
	 */
	public static boolean isSettled(BuiltInTool tool, BuiltInToolResult result, int turn)
	{
		if (result.leftToJudge())
			return false;
		if (tool instanceof BuiltInTool.ExactText || tool instanceof BuiltInTool.ForbiddenText ||
			tool instanceof BuiltInTool.PatternMatch || tool instanceof BuiltInTool.Count)
		{
			Integer scopedTurn = tool.turn();
			if (scopedTurn != null)
				return scopedTurn <= turn;
			return !(tool instanceof BuiltInTool.Count) && !result.passed();
		}
		if (tool instanceof BuiltInTool.ToolUsed)
			return result.passed();
		if (tool instanceof BuiltInTool.ToolUnused || tool instanceof BuiltInTool.NoToolCall)
			return !result.passed();
		if (tool instanceof BuiltInTool.ReadInFull)
			return result.passed();
		return tool instanceof BuiltInTool.ReadAtMost && !result.passed();
	}
}
