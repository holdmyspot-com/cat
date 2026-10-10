package io.github.cowwoc.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.claude.optimizer.StreamJsonPromptSources;
import io.github.cowwoc.cat.claude.secrets.StreamJsonPromptSourcesSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

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
		requireThat(entries.contains("plugin:cc-plugin-agents-md cc-plugin-agents-md@builtin builtin"), "builtin").
			isTrue();
		requireThat(value.contains("/runs/"), "leaksRunPath").isFalse();
		requireThat(value.contains("mup8an8w"), "leaksRunId").isFalse();
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
