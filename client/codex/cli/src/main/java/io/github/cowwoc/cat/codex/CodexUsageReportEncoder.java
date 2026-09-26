package io.github.cowwoc.cat.codex;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Encodes a validated Codex measurement for the shared usage-report outbox.
 */
public final class CodexUsageReportEncoder
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a Codex report encoder.
	 */
	public CodexUsageReportEncoder()
	{
	}

	/**
	 * Builds a report body with the event ID supplied by the durable outbox.
	 *
	 * @param report verified Codex measurement
	 * @return exact JSON body to retain and retry
	 * @throws IOException if the report cannot be encoded
	 */
	public String encode(Report report) throws IOException
	{
		Objects.requireNonNull(report, "report");
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("harness_id", "codex");
		source.put("harness_version", report.harnessVersion().runtimeValue());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("product_id", "cat");
		body.put("turn_id", report.turnId().toString());
		body.put("turn_output_tokens_so_far", report.outputTokensSoFar());
		body.put("event_id", report.eventId().toString());
		body.put("measurement_source", source);
		return json.writeValueAsString(body);
	}

	/**
	 * One validated Codex turn measurement and durable delivery identity.
	 *
	 * @param turnId stable root turn identity
	 * @param eventId stable delivery identity
	 * @param outputTokensSoFar verified cumulative generated output
	 * @param harnessVersion validated Codex runtime version
	 */
	public record Report(UUID turnId, UUID eventId, long outputTokensSoFar,
		CodexMeteringVersion harnessVersion)
	{
		/**
		 * Rejects a report without a stable identity or verified nonnegative count.
		 *
		 * @param turnId stable root turn identity
		 * @param eventId stable delivery identity
		 * @param outputTokensSoFar verified cumulative generated output
		 * @param harnessVersion validated Codex runtime version
		 */
		public Report
		{
			Objects.requireNonNull(turnId, "turnId");
			Objects.requireNonNull(eventId, "eventId");
			Objects.requireNonNull(harnessVersion, "harnessVersion");
			if (outputTokensSoFar < 0)
				throw new IllegalArgumentException("Codex usage report requires a nonnegative count");
		}
	}
}
