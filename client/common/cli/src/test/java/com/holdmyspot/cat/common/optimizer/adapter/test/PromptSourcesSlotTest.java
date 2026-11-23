package com.holdmyspot.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that the prompt sources a trial is observed loading name what it loaded, never which slot it ran in.
 */
public final class PromptSourcesSlotTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The directory that holds the nested sessions' results copies, which a campaign's driver reads.
	 */
	private static final Path RESULTS_COPY = Path.of("/campaign/optimizer-nested-sessions/claude/claude/629e9453");
	private static final String PLUGIN_BELOW_HOME = "/home/.claude/plugins/cat-marketplace/plugins/cat";

	/**
	 * Creates the test.
	 */
	public PromptSourcesSlotTest()
	{
	}

	/**
	 * Observes a trial that ran in a slot.
	 *
	 * @param slot the slot's nested session folder
	 * @param skills the skills that the trial loaded
	 * @return the observed value
	 */
	private static String observeIn(String slot, List<String> skills)
	{
		return observeIn(slot, PLUGIN_BELOW_HOME, skills);
	}

	/**
	 * Observes a trial that ran in a slot.
	 *
	 * @param slot the slot's nested session folder
	 * @param pluginBelowSlot the path of the loaded plugin below the slot's nested session folder
	 * @param skills the skills that the trial loaded
	 * @return the observed value
	 */
	private static String observeIn(String slot, String pluginBelowSlot, List<String> skills)
	{
		PromptSources sources = new PluginPathSources(JSON, Map.of("session", RESULTS_COPY));
		sources.accept("{\"plugin\":\"cat\",\"path\":\"" + (slot + pluginBelowSlot).replace("\\", "\\\\") + "\"}");
		for (String skill : skills)
			sources.accept("{\"skill\":\"" + skill + "\"}");
		return sources.value();
	}

	/**
	 * Returns pairs of nested session folders in different slots.
	 *
	 * @return the pairs
	 */
	@DataProvider
	public Object[][] slotPairs()
	{
		String session = "/campaign/.cat/sessions/39a1cbf9/slots";
		return new Object[][]
			{
				{session + "/d1/claude/s-1", session + "/d3/claude/s-3"},
				{session + "/d1/claude/s-1", session + "/h2/claude/s-2"},
				{session + "/d2/claude/s-2", "/another/host/session/slots/d2/claude/s-2"},
				{"C:\\campaign\\.cat\\sessions\\39a1cbf9\\slots\\d1\\claude\\s-1",
					"C:\\campaign\\.cat\\sessions\\39a1cbf9\\slots\\d3\\claude\\s-3"},
				{"/campaign/.cat/sessions/39a1cbf9/slots/d1/codex/s-1",
					"/campaign/.cat/sessions/39a1cbf9/slots/d9/codex/s-9"}
			};
	}

	/**
	 * Verifies that two trials that loaded the same sources in different slots yield the same value.
	 *
	 * @param first the first trial's nested session folder
	 * @param second the second trial's nested session folder
	 */
	@Test(dataProvider = "slotPairs")
	public void sameSourcesInAnySlotGiveOneValue(String first, String second)
	{
		List<String> skills = List.of("cat:help", "cat:git-squash");
		String value = observeIn(first, skills);

		requireThat(observeIn(second, skills), "inOtherSlot").isEqualTo(value);
		requireThat(value, "value").doesNotContain("slots");
	}

	/**
	 * Verifies that the value of a trial in a slot equals the value of one that ran in a session folder of the
	 * campaign's results folder, so that a value recorded before trials ran in slots still compares equal.
	 */
	@Test
	public void slotAndResultsCopyGiveOneValue()
	{
		List<String> skills = List.of("cat:help");

		requireThat(observeIn("/campaign/.cat/sessions/39a1cbf9/slots/d1/claude/s-1", skills), "inSlot").
			isEqualTo(observeIn(RESULTS_COPY.toString(), skills));
	}

	/**
	 * Verifies that a source that one trial loaded and another did not still changes the value in another slot.
	 */
	@Test
	public void aChangedSourceStillChangesTheValue()
	{
		String slot = "/campaign/.cat/sessions/39a1cbf9/slots/d1/claude/s-1";
		String otherSlot = "/campaign/.cat/sessions/39a1cbf9/slots/d3/claude/s-3";
		String value = observeIn(slot, List.of("cat:help", "cat:git-squash"));

		requireThat(observeIn(otherSlot, List.of("cat:help")), "withoutSkill").isNotEqualTo(value);
		requireThat(observeIn(otherSlot, "/home/.claude/plugins/cat-marketplace/plugins/other",
			List.of("cat:help", "cat:git-squash")), "otherPlugin").isNotEqualTo(value);
	}

	/**
	 * Verifies that a path outside every slot is not rewritten, so that a plugin loaded from a directory that the
	 * trial did not stage still appears where it is.
	 */
	@Test
	public void pathOutsideSlotsAppearsUnchanged()
	{
		PromptSources sources = new PluginPathSources(JSON, Map.of("session", RESULTS_COPY));
		sources.accept("{\"plugin\":\"cat\",\"path\":\"/opt/plugins/cat\"}");
		sources.accept("{\"plugin\":\"other\",\"path\":\"/campaign/slots/d1/claude/other-1/home\"}");

		requireThat(sources.value(), "value").isEqualTo(
			"plugin:cat /opt/plugins/cat\nplugin:other /campaign/slots/d1/claude/other-1/home");
	}
}
