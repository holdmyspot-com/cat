package io.github.cowwoc.cat.codex.runner;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Checks final-answer selection without launching a native process or retaining files. */
public final class CodexTurnResponseTest
{
	/** Creates a test instance. */
	public CodexTurnResponseTest()
	{
	}

	/**
	 * Only the last final answer supplies the response, with an empty response when none exists.
	 *
	 * @param turn native turn object
	 * @param expected expected published response
	 */
	@Test(dataProvider = "turns")
	public void selectsPublishedFinalAnswer(String turn, String expected)
	{
		Assert.assertEquals(CodexTurnResponse.read(new JsonMapper().readTree(turn)), expected);
	}

	/** Null native input cannot be decoded as an empty answer. */
	@Test
	public void rejectsNullTurn()
	{
		Assert.expectThrows(NullPointerException.class, () -> CodexTurnResponse.read(null));
	}

	/**
	 * Supplies only native items needed to distinguish final answers from other turn content.
	 *
	 * @return native turns and their responses
	 */
	@DataProvider
	public Object[][] turns()
	{
		return new Object[][]
		{
			{"{}", ""},
			{"""
				{"items":[{"type":"agentMessage","phase":"commentary","text":"progress"}]}
				""", ""},
			{"""
				{"items":[{"type":"agentMessage","phase":"final_answer","text":""}]}
				""", ""},
			{"""
				{"items":[{"type":"agentMessage","phase":"final_answer","text":"earlier"},
				{"type":"agentMessage","phase":"commentary","text":"progress"},
				{"type":"agentMessage","phase":"final_answer","text":"answer"},
				{"type":"commandExecution","phase":"final_answer","text":"command"}]}
				""", "answer"}
		};
	}
}
