package io.github.cowwoc.cat.codex;

import org.testng.annotations.Test;

import java.io.IOException;
import java.util.UUID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks Codex metering-version selection and the body handed to the shared outbox.
 */
public final class CodexUsageReportEncoderTest
{
	/**
	 * Creates the TestNG test class.
	 */
	public CodexUsageReportEncoderTest()
	{
	}

	/**
	 * Accepts a runtime whose metering record format has been validated.
	 */
	@Test
	public void acceptsValidatedMeteringVersion()
	{
		requireThat(CodexMeteringVersion.fromRuntime("0.155.0"), "validatedVersion").
			isEqualTo(CodexMeteringVersion.V_0_155_0);
		requireThat(CodexMeteringVersion.fromRuntime("0.157.0"), "stableVersion").
			isEqualTo(CodexMeteringVersion.V_0_157_0);
	}

	/**
	 * Rejects a runtime without a validated metering record format.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Codex version is unsupported for metering")
	public void rejectsUnverifiedMeteringVersion()
	{
		CodexMeteringVersion.fromRuntime("0.158.0");
	}

	/**
	 * Encodes the validated Codex version and outbox event ID for server reporting.
	 *
	 * @throws IOException if the report cannot be encoded
	 */
	@Test
	public void encodesVerifiedReport() throws IOException
	{
		UUID turnId = UUID.fromString("00000000-0000-0000-0000-000000000123");
		UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000456");
		CodexUsageReportEncoder encoder = new CodexUsageReportEncoder();
		String body = encoder.encode(new CodexUsageReportEncoder.Report(turnId, eventId, 42,
			CodexMeteringVersion.V_0_155_0));
		String expected = "{\"product_id\":\"cat\",\"turn_id\":\"%s\"," +
			"\"turn_output_tokens_so_far\":42,\"event_id\":\"%s\"," +
			"\"measurement_source\":{\"harness_id\":\"codex\",\"harness_version\":\"0.155.0\"}}";
		requireThat(body, "body").isEqualTo(expected.formatted(turnId, eventId));
	}
}
