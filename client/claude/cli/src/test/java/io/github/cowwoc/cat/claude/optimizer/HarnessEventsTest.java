package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link HarnessEvents} against harness lines that the Claude runner recorded, and against synthetic lines for
 * the shapes that no probe observed.
 */
public final class HarnessEventsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public HarnessEventsTest()
	{
	}

	/**
	 * Reads the lines of a recorded harness event file.
	 *
	 * @param name the resource name below {@code optimizer}
	 * @return the lines
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded(String name) throws IOException
	{
		try (InputStream stream = HarnessEventsTest.class.getResourceAsStream("/optimizer/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Parses recorded lines.
	 *
	 * @param lines the harness event lines
	 * @return the parsed events
	 */
	private static HarnessEvents parse(List<String> lines)
	{
		HarnessEvents events = new HarnessEvents(JSON);
		for (String line : lines)
			events.accept(line);
		return events;
	}

	/**
	 * Verifies that the model is the one that the session's {@code init} record names, not the alias that was
	 * requested, and that an effort the record does not name stays unobserved.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsObservedModelFromInit() throws IOException
	{
		// Requirement: R-E16
		HarnessEvents events = parse(recorded("claude-single-turn.jsonl"));
		requireThat(events.model(), "model").isEqualTo("claude-haiku-4-5-20251001");
		requireThat(events.reasoning(), "reasoning").isNull();
	}

	/**
	 * Verifies that an effort that the {@code init} record names is the observed reasoning configuration.
	 */
	@Test
	public void readsObservedReasoningWhenInitNamesIt()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of(
			"{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"claude-haiku-4-5-20251001\",\"effort\":\"medium\"}"));
		requireThat(events.reasoning(), "reasoning").isEqualTo("medium");
	}

	/**
	 * Verifies that both rate-limit windows come from the {@code rate_limit_event} record, with utilization as a
	 * fraction and the reset time as an instant.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsWindowsFromRateLimitEvent() throws IOException
	{
		// Requirement: R-E6
		HarnessEvents events = parse(recorded("claude-single-turn.jsonl"));
		requireThat(events.windows(), "windows").isEqualTo(List.of(
			new RateLimitWindow(0.3, Instant.ofEpochSecond(1_790_851_200L)),
			new RateLimitWindow(0.04, Instant.ofEpochSecond(1_791_064_800L))));
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that the usage of the last {@code result} record is the session's usage. Claude reports the model's
	 * usage cumulatively, including the usage of background children, while the per-reply {@code usage} object of the
	 * follow-up reply is smaller than the first reply's.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void readsCumulativeUsageFromLastResult() throws IOException
	{
		// Requirement: R-E16
		HarnessEvents single = parse(recorded("claude-single-turn.jsonl"));
		requireThat(single.usage(), "single.usage").isEqualTo(new TokenUsage(56_055, 40_099, 565, 397));

		HarnessEvents background = parse(recorded("claude-background-child.jsonl"));
		requireThat(background.usage(), "background.usage").isEqualTo(new TokenUsage(94_995, 69_217, 566, 349));
		requireThat(background.windows().size(), "background.windows.size()").isEqualTo(2);
	}

	/**
	 * Verifies that a session without events reports nothing, and that lines that are not JSON objects are skipped.
	 */
	@Test
	public void reportsNothingForUnrecognizedLines()
	{
		// Requirement: R-E16
		HarnessEvents events = parse(List.of("", "not json", "[1,2]", "{\"type\":\"assistant\"}"));
		requireThat(events.model(), "model").isNull();
		requireThat(events.usage(), "usage").isNull();
		requireThat(events.windows(), "windows").isEqualTo(List.of());
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.empty());
		requireThat(events.error(), "error").isNull();
	}

	/**
	 * Verifies that a rejected rate-limit event is reported with its reset time.
	 */
	@Test
	public void reportsRejectedRateLimit()
	{
		// Requirement: R-E6
		HarnessEvents events = parse(List.of(
			"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\",\"resetsAt\":1790851200," +
				"\"rateLimitType\":\"five_hour\",\"unifiedWindows\":{\"five_hour\":{\"utilization\":1.0," +
				"\"resetsAt\":1790851200}}}}"));
		requireThat(events.rejection(), "rejection").isEqualTo(
			Optional.of(new HarnessEvents.Rejection(Instant.ofEpochSecond(1_790_851_200L))));
		requireThat(events.windows(), "windows").isEqualTo(List.of(
			new RateLimitWindow(1.0, Instant.ofEpochSecond(1_790_851_200L))));
	}

	/**
	 * Verifies that a rejection without a reset time is still a rejection.
	 */
	@Test
	public void reportsRejectionWithoutResetTime()
	{
		// Requirement: R-E6
		HarnessEvents events = parse(List.of(
			"{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\"}}"));
		requireThat(events.rejection(), "rejection").isEqualTo(Optional.of(new HarnessEvents.Rejection(null)));
	}

	/**
	 * Verifies that an error result is a rate-limit rejection when its status is 429, and otherwise an error that
	 * names the status and terminal reason without including the result text.
	 *
	 * @throws IOException if the recorded lines cannot be read
	 */
	@Test
	public void classifiesErrorResults() throws IOException
	{
		// Requirement: R-E7
		HarnessEvents limited = parse(List.of(
			"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429," +
				"\"terminal_reason\":\"completed\",\"result\":\"limit reached\"}"));
		requireThat(limited.rejection().isPresent(), "limited.rejection.isPresent()").isTrue();
		requireThat(limited.error(), "limited.error").isNull();

		HarnessEvents failed = parse(List.of(
			"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":500," +
				"\"terminal_reason\":\"model_error\",\"result\":\"secret details\"}"));
		requireThat(failed.rejection().isPresent(), "failed.rejection.isPresent()").isFalse();
		requireThat(failed.error(), "failed.error").isEqualTo(
			"Claude reported an error result (api_error_status=500, terminal_reason=model_error)");

		HarnessEvents fine = parse(recorded("claude-single-turn.jsonl"));
		requireThat(fine.error(), "fine.error").isNull();
	}
}
