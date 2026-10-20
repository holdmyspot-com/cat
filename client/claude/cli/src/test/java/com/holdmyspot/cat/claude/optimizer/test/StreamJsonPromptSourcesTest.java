package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.StreamJsonPromptSources;
import com.holdmyspot.cat.claude.secrets.StreamJsonPromptSourcesSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
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
	private static final String RUN = "/runs/claude/mup8an8w-14snlqq4k6";

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
	 * @param run the run directory that the lines mention
	 * @return the observed value
	 */
	private static String observe(List<String> lines, String run)
	{
		StreamJsonPromptSources sources = StreamJsonPromptSourcesSecrets.access().newStreamJsonPromptSources(JSON,
			Map.of("<run>", Path.of(run)));
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
		String sessionStart = observe(lines, RUN);
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
				requireThat(observe(withLater, RUN), "withLater").isEqualTo(sessionStart);
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
			String value = observe(List.of(line), RUN);
			requireThat(value, "value").isEqualTo("hook:SessionStart SessionStart:startup");
			String other = line.replace("SessionStart:startup", "SessionStart:other");
			requireThat(observe(List.of(other), RUN), "other").isNotEqualTo(value);
		}
	}

	/**
	 * Verifies that the value lists the skills, agents, plugins and session-start hooks that loaded, and that a path
	 * inside the run directory appears relative to a placeholder.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void listsLoadedSourcesWithoutRunPaths() throws IOException
	{
		// Requirement: R-M1
		String value = observe(recorded(), RUN);
		List<String> entries = value.lines().toList();
		requireThat(entries.contains("skill:cat:git-squash"), "skill").isTrue();
		requireThat(entries.contains("agent:cat:claude:optimize-medium"), "agent").isTrue();
		requireThat(entries.contains("hook:SessionStart SessionStart:startup"), "hook").isTrue();
		requireThat(entries.contains(
			"plugin:cat cat@cat <run>/home/.claude/plugins/cat-marketplace/plugins/cat"), "plugin").isTrue();
		requireThat(value.contains("/runs/"), "leaksRunPath").isFalse();
		requireThat(value.contains("mup8an8w"), "leaksRunId").isFalse();
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
		List<String> entries = observe(recorded(), RUN).lines().toList();
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

			List<String> entries = observe(lines, RUN).lines().toList();
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
	 * Verifies that the value does not depend on where the run's files were staged, so that two candidates measured
	 * in different run directories yield the same value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void ignoresWhereTheRunWasStaged() throws IOException
	{
		// Requirement: R-M1
		String other = "/elsewhere/stage-7f3a/claude/zz99";
		List<String> relocated = recorded().stream().map(line -> line.replace(RUN, other)).toList();
		requireThat(observe(relocated, other), "relocated").isEqualTo(observe(recorded(), RUN));
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
		String once = observe(lines, RUN);
		List<String> twice = new ArrayList<>(lines);
		twice.addAll(lines.reversed());
		requireThat(observe(twice, RUN), "twice").isEqualTo(once);
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
		requireThat(observe(without, RUN), "without").isNotEqualTo(observe(recorded(), RUN));
	}

	/**
	 * Verifies that a session that loaded nothing that the events show is recorded as an explicit value.
	 */
	@Test
	public void recordsExplicitValueWhenNothingSeen()
	{
		// Requirement: R-M1
		StreamJsonPromptSources sources = StreamJsonPromptSourcesSecrets.access().newStreamJsonPromptSources(JSON,
			Map.of());
		sources.accept("{\"type\":\"assistant\"}");
		requireThat(sources.value(), "value").isEqualTo("none");
	}
}
