package io.github.cowwoc.cat.common.commercial;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the versioned local Usage analytics allowlist.
 */
public final class UsageAnalyticsEventTest
{
	/**
	 * Creates the test fixture.
	 */
	public UsageAnalyticsEventTest()
	{
	}

	/**
	 * Verifies allowed aggregate fields are retained without adding an identifier.
	 */
	@Test
	public void acceptsAllowedAggregateFields()
	{
		UsageAnalyticsEvent event = UsageAnalyticsEvent.fromExternalValues("usage-analytics-v1", Map.of(
			"file_count", 4, "line_count", 250, "model_identifier", "gpt-5.6-luna", "outcome", "certified"));

		requireThat(event.fields().size(), "fieldCount").isEqualTo(4);
		requireThat(event.fields().containsKey("customer_id"), "customerIdentifier").isFalse();
	}

	/**
	 * Verifies unknown or privacy-sensitive fields are rejected before transmission.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Usage analytics field is not allowed: prompt_text.*")
	public void rejectsUnknownField()
	{
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("file_count", 1);
		values.put("prompt_text", "secret");

		UsageAnalyticsEvent.fromExternalValues("usage-analytics-v1", values);
	}

	/**
	 * Verifies the initial policy is opt-in.
	 */
	@Test
	public void doesNotTransmitWithoutOptIn()
	{
		UsageAnalyticsEvent event = UsageAnalyticsEvent.fromExternalValues("usage-analytics-v1",
			Map.of("file_count", 1));

		requireThat(event.mayTransmit(false), "withoutOptIn").isFalse();
		requireThat(event.mayTransmit(true), "withOptIn").isTrue();
	}
}
