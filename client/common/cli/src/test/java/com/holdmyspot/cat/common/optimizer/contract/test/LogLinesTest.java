package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.LogLines;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tests {@link LogLines}.
 */
public final class LogLinesTest
{
	/**
	 * A log line: a UTC timestamp with milliseconds, a padded level, a logger and a message.
	 */
	private static final Pattern LINE = Pattern.compile(
		"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z (INFO |DEBUG|WARN |ERROR) [a-z]+\\.[A-Z][A-Za-z]+ - \\S.*");

	/**
	 * Creates the test fixture.
	 */
	public LogLinesTest()
	{
	}

	/**
	 * Expands version 1 of the generator.
	 *
	 * @param seed  the seed
	 * @param count the number of lines
	 * @return the generated text
	 * @throws IOException if the writer fails
	 */
	private static String expand(long seed, long count) throws IOException
	{
		StringWriter out = new StringWriter();
		LogLines.INSTANCE.write(1, seed, Map.of("count", count), out);
		return out.toString();
	}

	/**
	 * Returns the problem with a version and parameters.
	 *
	 * @param version    the version
	 * @param parameters the parameters
	 * @return the exception's message
	 */
	private static String refusal(int version, Map<String, Long> parameters)
	{
		try
		{
			LogLines.INSTANCE.validate(version, parameters);
		}
		catch (IllegalArgumentException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("Accepted version " + version + " with " + parameters);
	}

	/**
	 * Verifies that a seed and version expand to the same text every time, and that another seed expands to other
	 * text.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void sameSeedSameText() throws IOException
	{
		// Requirement: R-C21
		String first = expand(42, 500);
		requireThat(expand(42, 500), "repeated").isEqualTo(first);
		requireThat(expand(43, 500), "otherSeed").isNotEqualTo(first);
	}

	/**
	 * Verifies that version 1's output for one seed never changes: a committed values file must rebuild the same
	 * fixture on every later CAT.
	 *
	 * @throws IOException              if the writer fails
	 * @throws NoSuchAlgorithmException if the JDK lacks SHA-256
	 */
	@Test
	public void versionOneIsFrozen() throws IOException, NoSuchAlgorithmException
	{
		// Requirement: R-C21
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(expand(20_251_010L, 1000).
			getBytes(StandardCharsets.UTF_8));
		requireThat(HexFormat.of().formatHex(digest), "digest").isEqualTo(
			"80628be2940553ec1bf1c2c55b2bc0790ee69fafaee5ecc9c41310e739c8de0c");
	}

	/**
	 * Verifies that the output holds the requested number of log lines, each ending in {@code \n}.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void writesRequestedLines() throws IOException
	{
		// Requirement: R-C21
		String text = expand(7, 2000);
		requireThat(text, "text").endsWith("\n");
		List<String> lines = text.lines().toList();
		requireThat(lines.size(), "lines.size()").isEqualTo(2000);
		for (String line : lines)
			requireThat(line, "line").matches(LINE);
		requireThat(expand(7, 1).lines().count(), "single").isEqualTo(1L);
	}

	/**
	 * Verifies that timestamps increase and lie within 2025 for several seeds, including the extreme ones.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void timestampsIncreaseWithin2025() throws IOException
	{
		// Requirement: R-C21
		Instant first = Instant.parse("2025-01-01T00:00:00Z");
		Instant end = Instant.parse("2026-01-01T00:00:00Z");
		for (long seed : new long[]{0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 123_456_789})
		{
			Instant previous = first;
			for (String line : expand(seed, 3000).lines().toList())
			{
				Instant timestamp = Instant.parse(line.substring(0, line.indexOf(' ')));
				requireThat(timestamp, "timestamp").isGreaterThanOrEqualTo(previous).isLessThan(end);
				previous = timestamp;
			}
		}
	}

	/**
	 * Verifies that the generator lists the words that its lines may contain, so that a contract author can write
	 * facts that the filler never holds.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void vocabularyCoversOutput() throws IOException
	{
		// Requirement: R-C21
		for (String line : expand(99, 3000).lines().toList())
		{
			String message = line.substring(line.indexOf(' ') + 1);
			for (String word : message.split("[^A-Za-z]+"))
			{
				if (word.length() > 1)
					requireThat(LogLines.INSTANCE.vocabulary(), "vocabulary").contains(word);
			}
		}
	}

	/**
	 * Verifies that the description in the contract author's instructions names the call, the year of the timestamps
	 * and every word that a line may contain, so that the contract author can write facts that the filler never holds.
	 */
	@Test
	public void descriptionListsWordsAndDates()
	{
		// Requirement: R-C21
		String description = LogLines.INSTANCE.describe();
		requireThat(description, "description").contains("{\"generator\": \"log_lines\", \"count\": ").
			contains("within 2025").
			contains("Words: " + String.join(", ", LogLines.INSTANCE.vocabulary()) + ".");
	}

	/**
	 * Verifies that every number in a line's message lies within the range that the description states.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void numbersStayWithinDescribedRange() throws IOException
	{
		// Requirement: R-C21
		requireThat(LogLines.INSTANCE.describe(), "description").contains("between 0 and 9000999");
		for (String line : expand(7, 3000).lines().toList())
		{
			String message = line.substring(line.indexOf(' ') + 1);
			for (String number : message.split("[^0-9]+"))
			{
				if (!number.isEmpty())
					requireThat(Long.parseLong(number), "number").isBetween(0L, true, 9_000_999L, true);
			}
		}
	}

	/**
	 * Verifies that an unknown version, a missing or unknown parameter and a count out of range are refused.
	 */
	@Test
	public void refusesInvalidCalls()
	{
		// Requirement: R-C21
		requireThat(refusal(2, Map.of("count", 10L)), "version").contains("version 2");
		requireThat(refusal(1, Map.of()), "missing").contains("count");
		requireThat(refusal(1, Map.of("count", 10L, "width", 80L)), "unknown").contains("width");
		requireThat(refusal(1, Map.of("count", 0L)), "zero").contains("count");
		requireThat(refusal(1, Map.of("count", LogLines.MAXIMUM_COUNT + 1)), "tooMany").contains("count");
	}
}
