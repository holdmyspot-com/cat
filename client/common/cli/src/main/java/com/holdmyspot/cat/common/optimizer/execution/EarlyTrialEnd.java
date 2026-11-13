package com.holdmyspot.cat.common.optimizer.execution;

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

	private EarlyTrialEnd(PromptTest test, TestCase testCase, SessionPort sessions)
	{
		this.test = test;
		this.testCase = testCase;
		this.sessions = sessions;
	}

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
		catch (UndecidableToolException e)
		{
			return false;
		}
		for (var assertion : test.allAssertions())
		{
			if (assertion.builtInTool().isEmpty())
				continue;
			BuiltInTool tool = assertion.builtInTool().orElseThrow();
			BuiltInToolResult result = results.get(assertion.id());
			if (result != null && isSettled(tool, result, turn))
				settledAssertions.putIfAbsent(assertion.id(), turn);
		}
		return settledAssertions.size() == test.allAssertions().size();
	}

	@Override
	public Map<String, Integer> settledAssertions()
	{
		return Map.copyOf(settledAssertions);
	}

	public static boolean isSettled(BuiltInTool tool, BuiltInToolResult result, int turn)
	{
		if (result.leftToJudge())
			return false;
		if (tool instanceof BuiltInTool.ExactText || tool instanceof BuiltInTool.ForbiddenText ||
			tool instanceof BuiltInTool.PatternMatch || tool instanceof BuiltInTool.Count)
		{
			Integer scopedTurn = tool.turn();
			return scopedTurn != null ? scopedTurn <= turn : !result.passed();
		}
		if (tool instanceof BuiltInTool.ToolUsed)
			return result.passed();
		if (tool instanceof BuiltInTool.ToolUnused || tool instanceof BuiltInTool.NoToolCall)
			return !result.passed();
		if (tool instanceof BuiltInTool.ReadInFull)
			return result.passed();
		if (tool instanceof BuiltInTool.ReadAtMost)
			return !result.passed();
		return false;
	}
}
