package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.StreamJsonPromptSources;
import com.holdmyspot.cat.claude.secrets.StreamJsonPromptSourcesSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.common.optimizer.adapter.contract.test.PromptSourcesContract;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link StreamJsonPromptSources} against harness lines that the Claude runner recorded.
 */
public final class StreamJsonPromptSourcesTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SESSION_FOLDER = "/results/claude/mup8an8w-14snlqq4k6";

	/**
	 * Creates the test.
	 */
	public StreamJsonPromptSourcesTest()
	{
	}

	/**
	 * Reads the lines of the recorded single-turn session.
	 *
	 * @return the lines
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded() throws IOException
	{
		try (InputStream stream = StreamJsonPromptSourcesTest.class.getResourceAsStream(
			"/optimizer/claude-single-turn.jsonl"))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Observes lines.
	 *
	 * @param lines the harness event lines
	 * @param sessionFolder the session folder that the lines mention
	 * @return the observed value
	 */
	private static String observe(List<String> lines, String sessionFolder)
	{
		StreamJsonPromptSources sources = StreamJsonPromptSourcesSecrets.getStreamJsonPromptSourcesAccess().
			newStreamJsonPromptSources(JSON,
			Map.of("<session>", Path.of(sessionFolder)));
		for (String line : lines)
			sources.accept(line);
		return sources.value();
	}

	/**
	 * Verifies that later-event hooks do not change the sources loaded at session start.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	// Requirement: R-M1
	@Test
	public void ignoresLaterEventHooks() throws IOException
	{
		List<String> lines = recorded();
		String sessionStart = observe(lines, SESSION_FOLDER);
		for (String event : List.of("PreToolUse", "SubagentStart"))
		{
			for (String line : lines)
			{
				if (!JSON.readTree(line).path("hook_event").asString("").equals("SessionStart"))
					continue;
				// Later-event shapes follow the SessionStart records in claude-single-turn.jsonl.
				String later = line.replace("SessionStart", event);
				List<String> withLater = new ArrayList<>(lines);
				withLater.add(later);
				requireThat(observe(withLater, SESSION_FOLDER), "withLater").isEqualTo(sessionStart);
			}
		}
	}

	/**
	 * Verifies that either session-start record names its hook and that changing the hook changes the value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	// Requirement: R-M1
	@Test
	public void recordsSessionStartHooks() throws IOException
	{
		for (String line : recorded())
		{
			if (!JSON.readTree(line).path("hook_event").asString("").equals("SessionStart"))
				continue;
			String value = observe(List.of(line), SESSION_FOLDER);
			requireThat(value, "value").isEqualTo("hook:SessionStart SessionStart:startup");
			String other = line.replace("SessionStart:startup", "SessionStart:other");
			requireThat(observe(List.of(other), SESSION_FOLDER), "other").isNotEqualTo(value);
		}
	}

	/**
	 * Verifies that the value lists the skills, agents, plugins and session-start hooks that loaded, and that a path
	 * inside the session folder appears relative to a placeholder.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void listsLoadedSourcesWithoutSessionPaths() throws IOException
	{
		// Requirement: R-M1
		String value = observe(recorded(), SESSION_FOLDER);
		List<String> entries = value.lines().toList();
		requireThat(entries.contains("skill:cat:git-squash"), "skill").isTrue();
		requireThat(entries.contains("agent:cat:claude:optimize-medium"), "agent").isTrue();
		requireThat(entries.contains("hook:SessionStart SessionStart:startup"), "hook").isTrue();
		requireThat(entries.contains(
			"plugin:cat cat@cat <session>/home/.claude/plugins/cat-marketplace/plugins/cat"), "plugin").isTrue();
		requireThat(value.contains("/results/"), "leaksResultsPath").isFalse();
		requireThat(value.contains("mup8an8w"), "leaksSessionId").isFalse();
	}

	/**
	 * Verifies that the value leaves out the skills, agents and plugins that come with the harness rather than from a
	 * file, including the skills that Claude synchronizes from the account.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void omitsBuiltInSources() throws IOException
	{
		// Requirement: R-M1
		List<String> entries = observe(recorded(), SESSION_FOLDER).lines().toList();
		requireThat(entries, "entries").doesNotContainAny(List.of("skill:debug", "skill:anthropic-skills:pdf",
			"agent:Explore", "plugin:cc-plugin-agents-md cc-plugin-agents-md@builtin builtin"));
		requireThat(entries, "entries").contains("skill:cat:git-squash");
	}

	/**
	 * Verifies that a skill, command or agent without a plugin namespace counts when a file of the session's project
	 * declares its name.
	 *
	 * @throws IOException if the recorded lines cannot be read or the project cannot be written
	 */
	@Test
	public void listsProjectSkillsAndAgents() throws IOException
	{
		// Requirement: R-M1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-prompt-sources-"));
			Path claude = project.resolve(".claude");
			Files.writeString(Files.createDirectories(claude.resolve("skills/probe-skill")).resolve("SKILL.md"),
				"---\ndescription: Probes\n---\nBody\n", UTF_8);
			Files.writeString(Files.createDirectories(claude.resolve("commands")).resolve("design.md"), "Body\n",
				UTF_8);
			Files.writeString(Files.createDirectories(claude.resolve("agents")).resolve("reviewer.md"),
				"---\nname: \"Explore\"\ndescription: Reviews\n---\nBody\n", UTF_8);
			List<String> lines = recorded().stream().map(line -> withProject(line, project)).toList();

			List<String> entries = observe(lines, SESSION_FOLDER).lines().toList();
			requireThat(entries, "entries").containsAll(List.of("skill:probe-skill", "skill:design", "agent:Explore"));
			requireThat(entries, "entries").doesNotContainAny(List.of("skill:debug", "agent:Plan"));
		}
	}

	/**
	 * Sets the project directory of an {@code init} record.
	 *
	 * @param line    a harness event line
	 * @param project the project directory
	 * @return the line, with the {@code cwd} of an {@code init} record replaced
	 */
	private static String withProject(String line, Path project)
	{
		if (!line.contains("\"subtype\":\"init\""))
			return line;
		ObjectNode init = (ObjectNode) JSON.readTree(line);
		init.put("cwd", project.toString());
		return JSON.writeValueAsString(init);
	}

	/**
	 * Verifies that the value does not depend on where the nested session's files were staged, so that two candidates
	 * measured in different session folders yield the same value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void ignoresWhereTheSessionWasStaged() throws IOException
	{
		// Requirement: R-M1
		String other = "/elsewhere/stage-7f3a/claude/zz99";
		List<String> relocated = recorded().stream().map(line -> line.replace(SESSION_FOLDER, other)).toList();
		requireThat(observe(relocated, other), "relocated").isEqualTo(observe(recorded(), SESSION_FOLDER));
	}

	/**
	 * Observes the recorded lines as the session driver does for a trial that ran in a slot: the harness reports paths
	 * inside the slot's nested session folder, while the driver stages only the results copy.
	 *
	 * @param lines the harness event lines
	 * @param slot the slot's nested session folder, which replaces the folder that the lines mention
	 * @return the observed value
	 */
	private static String observeInSlot(List<String> lines, String slot)
	{
		StreamJsonPromptSources sources = StreamJsonPromptSourcesSecrets.getStreamJsonPromptSourcesAccess().
			newStreamJsonPromptSources(JSON, Map.of("session", Path.of("/campaign/results/claude/629e9453")));
		for (String line : lines)
			sources.accept(line.replace(SESSION_FOLDER, slot));
		return sources.value();
	}

	/**
	 * Verifies that two trials that loaded the same sources in different slots yield the same value, which also equals
	 * the value of a trial that ran in a session folder of the results folder.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void sameSourcesInAnySlotGiveOneValue() throws IOException
	{
		String first = observeInSlot(recorded(), "/campaign/.cat/sessions/39a1cbf9/slots/d1/claude/s-1");
		String second = observeInSlot(recorded(), "/campaign/.cat/sessions/39a1cbf9/slots/d3/claude/s-3");
		String heldOut = observeInSlot(recorded(), "/campaign/.cat/sessions/39a1cbf9/slots/h2/claude/s-2");

		requireThat(second, "second").isEqualTo(first);
		requireThat(heldOut, "heldOut").isEqualTo(first);
		requireThat(first, "first").doesNotContain("slots");
		requireThat(first.lines().toList(), "entries").
			contains("plugin:cat cat@cat session/home/.claude/plugins/cat-marketplace/plugins/cat");
		requireThat(observeInSlot(recorded(), "/campaign/results/claude/629e9453"), "inResultsFolder").
			isEqualTo(first);
	}

	/**
	 * Verifies that a source that loads in one slot and not in another still changes the value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void changedSourceChangesValueInAnySlot() throws IOException
	{
		List<String> without = recorded().stream().map(line -> line.replace("\"cat:git-squash\",", "")).toList();
		String first = observeInSlot(recorded(), "/campaign/.cat/sessions/39a1cbf9/slots/d1/claude/s-1");

		requireThat(observeInSlot(without, "/campaign/.cat/sessions/39a1cbf9/slots/d3/claude/s-3"), "without").
			isNotEqualTo(first);
	}

	/**
	 * Verifies that repeated records, such as the second {@code init} that a background child causes, do not change the
	 * value, and that the order of the records does not matter.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void ignoresRepeatedAndReorderedRecords() throws IOException
	{
		// Requirement: R-M1
		List<String> lines = recorded();
		String once = observe(lines, SESSION_FOLDER);
		List<String> twice = new ArrayList<>(lines);
		twice.addAll(lines.reversed());
		requireThat(observe(twice, SESSION_FOLDER), "twice").isEqualTo(once);
	}

	/**
	 * Verifies that a source that loads in one session and not in another changes the value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void changesWhenTheLoadedSourcesChange() throws IOException
	{
		// Requirement: R-M1
		List<String> without = recorded().stream().map(line -> line.replace("\"cat:git-squash\",", "")).toList();
		requireThat(observe(without, SESSION_FOLDER), "without").isNotEqualTo(observe(recorded(), SESSION_FOLDER));
	}

	/**
	 * Runs the contract check that a session that loaded nothing that the events show is recorded as an explicit
	 * value.
	 */
	@Test
	public void recordsExplicitValueWhenNothingSeen()
	{
		// Requirement: R-M1
		new PromptSourcesContract(new PromptSourcesContract.Subject()
		{
			@Override
			public PromptSources newSources()
			{
				return StreamJsonPromptSourcesSecrets.getStreamJsonPromptSourcesAccess().
					newStreamJsonPromptSources(JSON, Map.of());
			}

			@Override
			public List<String> loadingNothing()
			{
				return List.of("{\"type\":\"assistant\"}");
			}
		}).recordsAnExplicitValueWhenNothingLoads();
	}
}
