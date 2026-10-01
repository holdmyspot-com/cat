package io.github.cowwoc.cat.codex.optimizer;

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
 * Tests {@link PromptSources} against native lines that the Codex runner recorded.
 */
public final class PromptSourcesTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String RUN = "/runs/codex/mup8h3ri-14who5rhrw";

	/**
	 * Creates the test.
	 */
	public PromptSourcesTest()
	{
	}

	/**
	 * Reads the lines of the recorded hook notifications.
	 *
	 * @return the lines
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded() throws IOException
	{
		try (InputStream stream = PromptSourcesTest.class.getResourceAsStream("/optimizer/codex-hooks.jsonl"))
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
	 * Verifies that the value lists the hook that ran, once although Codex reports it as started and as completed, and
	 * that its source path appears relative to a placeholder.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void listsHooksWithoutRunPaths() throws IOException
	{
		// Requirement: R-M1
		String value = observe(recorded(), RUN);
		requireThat(value, "value").
			isEqualTo("hook:sessionStart plugin <run>/home/plugins/cache/cat/cat/1.0/hooks/hooks.json");
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
		String other = "/elsewhere/stage-7f3a/codex/zz99";
		List<String> relocated = recorded().stream().map(line -> line.replace(RUN, other)).toList();
		requireThat(observe(relocated, other), "relocated").isEqualTo(observe(recorded(), RUN));
	}

	/**
	 * Verifies that the order of the records does not matter.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void ignoresOrderOfRecords() throws IOException
	{
		// Requirement: R-M1
		List<String> reversed = new ArrayList<>(recorded());
		reversed.addAll(recorded().reversed());
		requireThat(observe(reversed, RUN), "reversed").isEqualTo(observe(recorded(), RUN));
	}

	/**
	 * Verifies that a different hook source changes the value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void changesWhenTheLoadedSourcesChange() throws IOException
	{
		// Requirement: R-M1
		List<String> other = recorded().stream().map(line -> line.replace("hooks/hooks.json", "hooks/other.json")).
			toList();
		requireThat(observe(other, RUN), "other").isNotEqualTo(observe(recorded(), RUN));
	}

	/**
	 * Verifies that a session that loaded nothing that the events show is recorded as an explicit value.
	 */
	@Test
	public void recordsExplicitValueWhenNothingSeen()
	{
		// Requirement: R-M1
		PromptSources sources = new PromptSources(JSON, Map.of());
		sources.accept("{\"method\":\"item/started\"}");
		requireThat(sources.value(), "value").isEqualTo("none");
	}
}
