package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.MarkdownSections;
import io.github.cowwoc.cat.common.optimizer.contract.ValueGenerators;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tests {@link MarkdownSections}.
 */
public final class MarkdownSectionsTest
{
	/**
	 * A section's heading.
	 */
	private static final Pattern HEADING = Pattern.compile("## [A-Z][a-z]+( [a-z]+)+");
	/**
	 * A sentence of a section's paragraph.
	 */
	private static final Pattern SENTENCE = Pattern.compile("[A-Z][a-z]*( [a-z0-9,]+)+\\.");

	/**
	 * Creates the test fixture.
	 */
	public MarkdownSectionsTest()
	{
	}

	/**
	 * Expands version 1 of the generator.
	 *
	 * @param seed  the seed
	 * @param count the number of sections
	 * @return the generated text
	 * @throws IOException if the writer fails
	 */
	private static String expand(long seed, long count) throws IOException
	{
		StringWriter out = new StringWriter();
		MarkdownSections.INSTANCE.write(1, seed, Map.of("count", count), out);
		return out.toString();
	}

	/**
	 * Splits generated text into its sections.
	 *
	 * @param text the generated text
	 * @return each section's text, including its line breaks
	 */
	private static List<String> sectionsOf(String text)
	{
		List<String> lines = text.lines().toList();
		List<String> sections = new ArrayList<>();
		for (int i = 0; i < lines.size(); i += MarkdownSections.LINES_PER_SECTION)
		{
			List<String> section = lines.subList(i, Math.min(lines.size(), i + MarkdownSections.LINES_PER_SECTION));
			sections.add(String.join("\n", section) + "\n");
		}
		return sections;
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
			MarkdownSections.INSTANCE.validate(version, parameters);
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
		String first = expand(42, 200);
		requireThat(first, "first").isNotEmpty();
		requireThat(expand(42, 200), "repeated").isEqualTo(first);
		requireThat(expand(43, 200), "otherSeed").isNotEqualTo(first);
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
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(expand(20_261_014L, 500).
			getBytes(StandardCharsets.UTF_8));
		requireThat(HexFormat.of().formatHex(digest), "digest").isEqualTo(
			"7b9e8c5429f10e36846c5c4fafc102170da7168cd7804f4bee5cfd2c38a3cb53");
	}

	/**
	 * Verifies that the output holds the requested number of sections, each a heading, a blank line, a paragraph of
	 * one sentence per line and a blank line.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void writesRequestedSections() throws IOException
	{
		String text = expand(7, 1000);
		requireThat(text, "text").endsWith("\n\n");
		List<String> lines = text.lines().toList();
		requireThat(lines.size(), "lines.size()").isEqualTo(1000 * MarkdownSections.LINES_PER_SECTION);
		for (int i = 0; i < lines.size(); ++i)
		{
			String line = lines.get(i);
			int position = i % MarkdownSections.LINES_PER_SECTION;
			if (position == 0)
				requireThat(line, "heading").matches(HEADING);
			else if (position == 1 || position == MarkdownSections.LINES_PER_SECTION - 1)
				requireThat(line, "blank").isEmpty();
			else
				requireThat(line, "sentence").matches(SENTENCE);
		}
		requireThat(expand(7, 1).lines().count(), "single").isEqualTo((long) MarkdownSections.LINES_PER_SECTION);
	}

	/**
	 * Verifies that every section's length lies within the bounds that the description states, so that a contract
	 * author can size a fixture from the number of sections.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void sectionLengthsStayWithinDescribedBounds() throws IOException
	{
		int minimum = MarkdownSections.INSTANCE.minimumSectionLength();
		int maximum = MarkdownSections.INSTANCE.maximumSectionLength();
		requireThat(minimum, "minimum").isPositive().isLessThan(maximum);
		requireThat(MarkdownSections.INSTANCE.describe(), "description").
			contains("between " + minimum + " and " + maximum + " characters");
		for (long seed : new long[]{0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 123_456_789})
		{
			String text = expand(seed, 2000);
			requireThat(text.length(), "text.length()").isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
			for (String section : sectionsOf(text))
				requireThat(section.length(), "section.length()").isBetween(minimum, true, maximum, true);
		}
	}

	/**
	 * Verifies that each section is about one component and one kind of item, so that a section reads like part of a
	 * real document.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void sectionKeepsOneSubject() throws IOException
	{
		Set<String> components = Set.of("scheduler", "importer", "exporter", "indexer", "gateway", "worker",
			"uploader", "cache");
		List<String> singulars = List.of("record", "request", "job", "file", "message", "batch", "task", "report");
		List<String> plurals = List.of("records", "requests", "jobs", "files", "messages", "batches", "tasks",
			"reports");
		Map<String, String> items = new HashMap<>();
		for (int i = 0; i < singulars.size(); ++i)
		{
			items.put(singulars.get(i), singulars.get(i));
			items.put(plurals.get(i), singulars.get(i));
		}
		for (String section : sectionsOf(expand(11, 500)))
		{
			Set<String> componentsFound = new HashSet<>();
			Set<String> itemsFound = new HashSet<>();
			for (String word : section.split("[^a-z]+"))
			{
				if (components.contains(word))
					componentsFound.add(word);
				if (items.containsKey(word))
					itemsFound.add(items.get(word));
			}
			requireThat(componentsFound.size(), "components").isEqualTo(1);
			requireThat(itemsFound.size(), "items").isLessThanOrEqualTo(1);
		}
	}

	/**
	 * Verifies that the generator lists the words that its sections may contain, so that a contract author can write
	 * facts that the filler never holds.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void vocabularyCoversOutput() throws IOException
	{
		requireThat(MarkdownSections.INSTANCE.vocabulary(), "vocabulary").isNotEmpty();
		for (String word : expand(99, 2000).split("[^A-Za-z]+"))
		{
			if (word.length() > 1)
				requireThat(MarkdownSections.INSTANCE.vocabulary(), "vocabulary").contains(word);
		}
	}

	/**
	 * Verifies that the description in the contract author's instructions names the call, the size of a section and
	 * every word that a section may contain.
	 */
	@Test
	public void descriptionListsCallSizeAndWords()
	{
		String description = MarkdownSections.INSTANCE.describe();
		requireThat(description, "description").contains("{\"generator\": \"markdown_sections\", \"count\": ").
			contains(MarkdownSections.LINES_PER_SECTION + " lines").
			contains("Words: " + String.join(", ", MarkdownSections.INSTANCE.vocabulary()) + ".");
	}

	/**
	 * Verifies that every number in the output lies within the range that the description states.
	 *
	 * @throws IOException if the writer fails
	 */
	@Test
	public void numbersStayWithinDescribedRange() throws IOException
	{
		requireThat(MarkdownSections.INSTANCE.describe(), "description").contains("between 2 and 500");
		int numbers = 0;
		for (String number : expand(7, 2000).split("[^0-9]+"))
		{
			if (!number.isEmpty())
			{
				requireThat(Long.parseLong(number), "number").isBetween(2L, true, 500L, true);
				++numbers;
			}
		}
		requireThat(numbers, "numbers").isPositive();
	}

	/**
	 * Verifies that an unknown version, a missing or unknown parameter and a count out of range are refused.
	 */
	@Test
	public void refusesInvalidCalls()
	{
		requireThat(refusal(2, Map.of("count", 10L)), "version").contains("version 2");
		requireThat(refusal(1, Map.of()), "missing").contains("count");
		requireThat(refusal(1, Map.of("count", 10L, "width", 80L)), "unknown").contains("width");
		requireThat(refusal(1, Map.of("count", 0L)), "zero").contains("count");
		requireThat(refusal(1, Map.of("count", MarkdownSections.MAXIMUM_COUNT + 1)), "tooMany").contains("count");
	}

	/**
	 * Verifies that a contract author's instructions list the generator, so that the author can call it.
	 */
	@Test
	public void listedForContractAuthors()
	{
		requireThat(ValueGenerators.find("markdown_sections").orElse(null), "found").
			isEqualTo(MarkdownSections.INSTANCE);
	}
}
