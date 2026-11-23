package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.common.optimizer.adapter.test.PluginPathSources;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.execution.Driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that a campaign's measurement identity does not change with the slot that a trial runs in.
 */
public final class SlotIdentityTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Path RESULTS_COPY = Path.of("/campaign/optimizer-nested-sessions/claude/claude/629e9453");
	private static final String SLOTS = "/campaign/.cat/sessions/39a1cbf9/slots";

	/**
	 * Creates the test.
	 */
	public SlotIdentityTest()
	{
	}

	/**
	 * Observes a trial that ran in a slot and loaded the plugin and skills below.
	 *
	 * @param slot the slot's directory name, such as {@code d3}
	 * @param skills the skills that the trial loaded
	 * @return the observed prompt sources
	 */
	private static String observeIn(String slot, List<String> skills)
	{
		PromptSources sources = new PluginPathSources(JSON, Map.of("session", RESULTS_COPY));
		sources.accept("{\"plugin\":\"cat\",\"path\":\"" + SLOTS + "/" + slot + "/claude/s-" + slot.substring(1) +
			"/home/.claude/plugins/cat-marketplace/plugins/cat\"}");
		for (String skill : skills)
			sources.accept("{\"skill\":\"" + skill + "\"}");
		return sources.value();
	}

	/**
	 * Returns a builder whose campaign recorded the given prompt sources.
	 *
	 * @param promptSources the recorded prompt sources
	 * @return the builder
	 */
	private static Builder recording(String promptSources)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(Builder.observed("model-a").values());
		values.put(MeasurementCondition.PROMPT_SOURCES, promptSources);
		return new Builder().identity(new MeasurementIdentity(values));
	}

	/**
	 * Runs one round of formal trials that all observe the same prompt sources.
	 *
	 * @param recorded the prompt sources that the campaign recorded
	 * @param observed the prompt sources that the trials observe
	 * @return the campaign after the trials
	 * @throws IOException if the test directory cannot be used
	 */
	private static Campaign runTrials(String recorded, String observed) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				recording(recorded).confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 50, 0).cell("safety", 50, 0).build());
			harness.trials(_ -> harness.completed("pass", DriverHarness.MODEL, observed));
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				return harness.campaign();
			}
		}
	}

	/**
	 * Verifies that a campaign recorded in one slot does not pause when its trials run in another slot.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void trialsInAnotherSlotDoNotPause() throws IOException
	{
		List<String> skills = List.of("cat:help", "cat:git-squash");

		Campaign campaign = runTrials(observeIn("d1", skills), observeIn("d3", skills));

		requireThat(campaign.pending(), "pending").isNull();
	}

	/**
	 * Verifies that a source that a trial loads and the recorded identity does not hold still pauses the campaign,
	 * whichever slot the trial ran in.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void aChangedSourceStillPauses() throws IOException
	{
		Campaign campaign = runTrials(observeIn("d1", List.of("cat:help", "cat:git-squash")),
			observeIn("d3", List.of("cat:help")));

		requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
		requireThat(((PendingDecision.MeasurementChanged) campaign.pending()).changed(), "changed").
			isEqualTo(List.of(MeasurementCondition.PROMPT_SOURCES));
	}
}
