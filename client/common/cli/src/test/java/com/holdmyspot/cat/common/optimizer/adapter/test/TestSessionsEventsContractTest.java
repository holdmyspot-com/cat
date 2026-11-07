package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.optimizer.adapter.HarnessEvents;
import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.common.optimizer.adapter.contract.test.HarnessEventsContract;
import com.holdmyspot.cat.common.optimizer.adapter.contract.test.PromptSourcesContract;

import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the event reader contracts on {@link TestSessions.Events} and {@link TestSessions.Sources}, the generic
 * harness's readers, so that the tests that use them rely only on behaviour that the contracts check.
 */
public final class TestSessionsEventsContractTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates a test.
	 */
	public TestSessionsEventsContractTest()
	{
	}

	/**
	 * Returns the event reader contract over the generic harness's events.
	 *
	 * @return the contract
	 */
	private static HarnessEventsContract events()
	{
		return new HarnessEventsContract(new HarnessEventsContract.Subject()
		{
			@Override
			public HarnessEvents newEvents()
			{
				return new TestSessions.Events(JSON);
			}

			@Override
			public String modelOutput()
			{
				return TestSessions.OUTPUT_EVENT;
			}

			@Override
			public String withoutOutput()
			{
				return TestSessions.MODEL_EVENT;
			}

			@Override
			public String hookStarted(String id)
			{
				return "{\"type\":\"hook_started\",\"id\":\"" + id + "\"}";
			}

			@Override
			public String hookCompleted(String id)
			{
				return "{\"type\":\"hook_completed\",\"id\":\"" + id + "\"}";
			}

			@Override
			public List<String> rejection()
			{
				return List.of(TestSessions.REJECTED_EVENT);
			}

			@Override
			public List<String> refusal()
			{
				return List.of(TestSessions.REFUSED_EVENT);
			}
		});
	}

	/**
	 * Runs the contract check that model output marks the turn until the next turn.
	 */
	@Test
	public void modelOutputMarksTheTurnUntilTheNextTurn()
	{
		events().modelOutputMarksTheTurnUntilTheNextTurn();
	}

	/**
	 * Runs the contract check that a hook runs from its start until its completion.
	 */
	@Test
	public void aHookRunsFromItsStartUntilItsCompletion()
	{
		events().aHookRunsFromItsStartUntilItsCompletion();
	}

	/**
	 * Runs the contract check that a rate-limit rejection without a reset time is reported as one.
	 */
	@Test
	public void reportsARejectionWithoutAResetTime()
	{
		events().reportsARejectionWithoutAResetTime();
	}

	/**
	 * Runs the contract check that a refused request is reported as a refusal.
	 */
	@Test
	public void reportsARefusal()
	{
		events().reportsARefusal();
	}

	/**
	 * Runs the contract check that a session that loads nothing records the explicit value.
	 */
	@Test
	public void recordsAnExplicitValueWhenNothingLoads()
	{
		new PromptSourcesContract(new PromptSourcesContract.Subject()
		{
			@Override
			public PromptSources newSources()
			{
				return new TestSessions.Sources(JSON, Map.of());
			}

			@Override
			public List<String> loadingNothing()
			{
				return List.of(TestSessions.MODEL_EVENT, TestSessions.OUTPUT_EVENT);
			}
		}).recordsAnExplicitValueWhenNothingLoads();
	}
}
