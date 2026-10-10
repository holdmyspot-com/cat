package io.github.cowwoc.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.optimizer.AppServerPromptSources;
import io.github.cowwoc.cat.codex.secrets.AppServerPromptSourcesSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link AppServerPromptSources} against harness lines that the Codex runner recorded.
 */
public final class AppServerPromptSourcesTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String RUN = "/runs/codex/mup8h3ri-14who5rhrw";

	/**
	 * Creates the test.
	 */
	public AppServerPromptSourcesTest()
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
		try (InputStream stream = AppServerPromptSourcesTest.class.getResourceAsStream("/optimizer/codex-hooks.jsonl"))
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
		AppServerPromptSources sources = AppServerPromptSourcesSecrets.access().newAppServerPromptSources(JSON,
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
		for (String line : lines)
		{
			// The subagentStart shape follows the sessionStart notifications in codex-hooks.jsonl.
			String later = line.replace("sessionStart", "subagentStart").replace("session-start:", "subagent-start:");
			List<String> withLater = new ArrayList<>(lines);
			withLater.add(later);
			requireThat(observe(withLater, RUN), "withLater").isEqualTo(sessionStart);
		}
	}

	/**
	 * Verifies that either session-start notification records its hook and that changing the hook changes the value.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	// Requirement: R-M1
	@Test
	public void recordsSessionStartHooks() throws IOException
	{
		for (String line : recorded())
		{
			String value = observe(List.of(line), RUN);
			requireThat(value, "value").
				isEqualTo("hook:sessionStart plugin <run>/home/plugins/cache/cat/cat/1.0/hooks/hooks.json");
			String other = line.replace("hooks/hooks.json", "hooks/other.json");
			requireThat(observe(List.of(other), RUN), "other").isNotEqualTo(value);
		}
	}

	/**
	 * Verifies that the value lists the session-start hook once although Codex reports it as started and as completed,
	 * and that its source path appears relative to a placeholder.
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
		AppServerPromptSources sources = AppServerPromptSourcesSecrets.access().newAppServerPromptSources(JSON, Map.of());
		sources.accept("{\"method\":\"item/started\"}");
		requireThat(sources.value(), "value").isEqualTo("none");
	}
}
