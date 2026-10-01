package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link PromptSources} against native lines that the Claude runner recorded.
 */
public final class PromptSourcesTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String RUN = "/runs/claude/mup8an8w-14snlqq4k6";

	/**
	 * Creates the test.
	 */
	public PromptSourcesTest()
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
		try (InputStream stream = PromptSourcesTest.class.getResourceAsStream("/optimizer/claude-single-turn.jsonl"))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Observes lines.
	 *
	 * @param lines the native event lines
	 * @param run the run directory that the lines mention
	 * @return the observed value
	 */
	private static String observe(List<String> lines, String run)
	{
		PromptSources sources = new PromptSources(JSON, Map.of("<run>", Path.of(run)));
		for (String line : lines)
			sources.accept(line);
		return sources.value();
	}

	/**
	 * Verifies that the value lists the skills, agents, plugins and hooks that loaded, and that a path inside the
	 * run directory appears relative to a placeholder.
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
		PromptSources sources = new PromptSources(JSON, Map.of());
		sources.accept("{\"type\":\"assistant\"}");
		requireThat(sources.value(), "value").isEqualTo("none");
	}
}
