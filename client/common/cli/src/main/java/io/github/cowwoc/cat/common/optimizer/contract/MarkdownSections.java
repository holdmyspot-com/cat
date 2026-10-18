package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code markdown_sections} value generator: sections of a Markdown document about configuring and running a
 * service, such as a level-2 heading {@code ## Settings for the indexer} followed by a paragraph of four sentences.
 * <p>
 * Each section looks real by itself; the sections as a whole mean nothing. Every section has the same number of lines
 * and a length within documented bounds, so that a contract author can size a workspace file from the number of
 * sections.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class MarkdownSections implements ValueGenerator
{
	/**
	 * The generator.
	 */
	public static final MarkdownSections INSTANCE = new MarkdownSections();
	/**
	 * The most sections that one value may request.
	 */
	public static final long MAXIMUM_COUNT = 100_000;
	/**
	 * The number of sentences in each section's paragraph.
	 */
	private static final int SENTENCES_PER_SECTION = 4;
	/**
	 * The number of lines in each section: a heading, a blank line, one line per sentence and a blank line.
	 */
	public static final int LINES_PER_SECTION = SENTENCES_PER_SECTION + 3;
	/**
	 * The name of the parameter that holds the number of sections.
	 */
	private static final String COUNT = "count";
	/**
	 * The text before each heading.
	 */
	private static final String HEADING_PREFIX = "## ";
	/**
	 * Matches a slot of a template, capturing its name.
	 */
	private static final Pattern SLOT = Pattern.compile("<([a-z]+)>");
	/**
	 * Matches the separators between the words of a section.
	 */
	private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]+");
	/**
	 * The heading templates. A slot such as {@code <component>} is filled when the section is written.
	 */
	private static final List<String> HEADINGS = List.of(
		"Settings for the <component>",
		"How the <component> handles <items>",
		"Troubleshooting the <component>",
		"Monitoring the <component>",
		"Limits on <items>",
		"Scaling the <component>");
	/**
	 * The sentence templates. A section uses {@link #SENTENCES_PER_SECTION} different ones.
	 */
	private static final List<String> SENTENCES = List.of(
		"The <component> keeps up to <count> <items> in memory before it writes them to disk.",
		"Each <item> that the <component> receives is checked against the current <setting>.",
		"When the <component> restarts, it reloads the <setting> from its configuration.",
		"Most teams leave the <setting> at its default unless the <component> handles more than <count> <items> " +
			"per minute.",
		"The dashboard shows how many <items> the <component> processed during the last <minutes> minutes.",
		"If a <item> fails validation, the <component> logs a warning and moves on to the next one.",
		"Changes to the <setting> take effect after the next deployment.",
		"The <component> retries a failed <item> up to <small> times before it gives up.",
		"Raising the <setting> lets the <component> accept more <items>, but it also uses more memory.",
		"Operators can pause the <component> without losing any queued <items>.",
		"A <item> that waits longer than <minutes> minutes is moved to a separate queue for review.",
		"The <component> sends its status every <minutes> minutes to the monitoring service.");
	/**
	 * The words that fill each named slot. Each {@code items} word is the plural of the {@code item} word at the same
	 * position. Every {@code item} starts with a consonant, so that {@code a <item>} reads correctly.
	 */
	private static final Map<String, List<String>> WORDS = Map.of(
		"component", List.of("scheduler", "importer", "exporter", "indexer", "gateway", "worker", "uploader",
			"cache"),
		"item", List.of("record", "request", "job", "file", "message", "batch", "task", "report"),
		"items", List.of("records", "requests", "jobs", "files", "messages", "batches", "tasks", "reports"),
		"setting", List.of("buffer size", "retry limit", "timeout", "queue length", "log level", "flush interval"));
	/**
	 * The range of each numbered slot, as its smallest and largest values.
	 */
	private static final Map<String, List<Integer>> NUMBERS = Map.of(
		"count", List.of(2, 500),
		"small", List.of(2, 9),
		"minutes", List.of(5, 60));
	/**
	 * The smallest number that a section contains.
	 */
	private static final int SMALLEST_NUMBER = NUMBERS.values().stream().mapToInt(List::getFirst).min().
		orElseThrow();
	/**
	 * The largest number that a section contains.
	 */
	private static final int LARGEST_NUMBER = NUMBERS.values().stream().mapToInt(List::getLast).max().
		orElseThrow();
	/**
	 * Every word of two or more letters that a section may contain.
	 */
	private static final SortedSet<String> VOCABULARY = vocabularyOf();
	/**
	 * The fewest characters that a section holds, counting its line breaks.
	 */
	private static final int MINIMUM_SECTION_LENGTH = sectionLength(Comparator.naturalOrder());
	/**
	 * The most characters that a section holds, counting its line breaks.
	 */
	private static final int MAXIMUM_SECTION_LENGTH = sectionLength(Comparator.reverseOrder());

	/**
	 * Creates the generator.
	 */
	private MarkdownSections()
	{
	}

	/**
	 * Collects the words of the templates and slot words.
	 *
	 * @return the words, sorted
	 */
	private static SortedSet<String> vocabularyOf()
	{
		SortedSet<String> words = new TreeSet<>();
		List<String> texts = new ArrayList<>();
		for (String template : HEADINGS)
			texts.add(SLOT.matcher(template).replaceAll(" "));
		for (String template : SENTENCES)
			texts.add(SLOT.matcher(template).replaceAll(" "));
		for (List<String> slotWords : WORDS.values())
			texts.addAll(slotWords);
		for (String text : texts)
		{
			for (String word : NON_LETTERS.split(text))
			{
				if (word.length() > 1)
					words.add(word);
			}
		}
		return Collections.unmodifiableSortedSet(words);
	}

	/**
	 * Returns the shortest or longest length of a section.
	 *
	 * @param preferred orders lengths from the one to return first: {@code naturalOrder()} for the shortest length,
	 *                  {@code reverseOrder()} for the longest
	 * @return the number of characters, counting line breaks
	 */
	private static int sectionLength(Comparator<Integer> preferred)
	{
		int heading = HEADINGS.stream().map(template -> filledLength(template, preferred)).min(preferred).
			orElseThrow();
		List<Integer> sentences = new ArrayList<>(SENTENCES.stream().
			map(template -> filledLength(template, preferred)).toList());
		sentences.sort(preferred);
		int paragraph = 0;
		for (int length : sentences.subList(0, SENTENCES_PER_SECTION))
			paragraph += length + 1;
		// The heading's line break, the blank line after it, and the blank line that ends the section
		return HEADING_PREFIX.length() + heading + 2 + paragraph + 1;
	}

	/**
	 * Returns the shortest or longest length of a template once its slots are filled.
	 *
	 * @param template  the template
	 * @param preferred orders lengths from the one to return first: {@code naturalOrder()} for the shortest length,
	 *                  {@code reverseOrder()} for the longest
	 * @return the number of characters
	 */
	private static int filledLength(String template, Comparator<Integer> preferred)
	{
		Matcher slot = SLOT.matcher(template);
		int length = template.length();
		while (slot.find())
		{
			length -= slot.group().length();
			String name = slot.group(1);
			List<Integer> range = NUMBERS.get(name);
			if (range == null)
			{
				length += WORDS.get(name).stream().map(String::length).min(preferred).orElseThrow();
			}
			else
				length += range.stream().map(number -> String.valueOf(number).length()).min(preferred).orElseThrow();
		}
		return length;
	}

	@Override
	public String name()
	{
		return "markdown_sections";
	}

	@Override
	public int currentVersion()
	{
		return 1;
	}

	@Override
	public String describe()
	{
		return "`" + name() + "`: sections of a Markdown document about configuring and running a service. Call it " +
			"as\n" +
			"`{\"generator\": \"" + name() + "\", \"count\": <sections>}`, with between 1 and " + MAXIMUM_COUNT +
			" sections.\n" +
			"\n" +
			"- Each section is " + LINES_PER_SECTION + " lines: a level-2 heading such as " +
			"`## Settings for the indexer`, a blank line, a paragraph of " + SENTENCES_PER_SECTION + " sentences " +
			"with one sentence per line, and a blank line. The text ends with that blank line.\n" +
			"- Each section holds between " + MINIMUM_SECTION_LENGTH + " and " + MAXIMUM_SECTION_LENGTH +
			" characters, counting its line breaks. Every character is ASCII, so a section's size in bytes is the " +
			"same. To exceed a size, request that size divided by " + MINIMUM_SECTION_LENGTH + ", rounded up.\n" +
			"- Every number is a whole number between " + SMALLEST_NUMBER + " and " + LARGEST_NUMBER + ".\n" +
			"- Words: " + String.join(", ", VOCABULARY) + ".\n" +
			"\n" +
			"A section holds no other word of two or more letters.";
	}

	@Override
	public void validate(int version, Map<String, Long> parameters)
	{
		requireThat(parameters, "parameters").isNotNull();
		if (version != 1)
			throw new IllegalArgumentException(name() + " has no version " + version + ". Versions: 1");
		for (String parameter : new TreeSet<>(parameters.keySet()))
		{
			if (!parameter.equals(COUNT))
			{
				throw new IllegalArgumentException(name() + " version 1 has no parameter '" + parameter +
					"'. Parameters: " + COUNT);
			}
		}
		Long count = parameters.get(COUNT);
		if (count == null)
			throw new IllegalArgumentException(name() + " requires the parameter " + COUNT);
		if (count < 1 || count > MAXIMUM_COUNT)
		{
			throw new IllegalArgumentException(name() + "'s " + COUNT + " must be between 1 and " + MAXIMUM_COUNT +
				", but it is " + count);
		}
	}

	/**
	 * Returns every word of two or more letters that a section may contain, so that a contract author can write facts
	 * that the generated sections never hold.
	 *
	 * @return the words, sorted
	 */
	public SortedSet<String> vocabulary()
	{
		return VOCABULARY;
	}

	/**
	 * Returns the fewest characters that a section holds, counting its line breaks.
	 *
	 * @return the number of characters
	 */
	public int minimumSectionLength()
	{
		return MINIMUM_SECTION_LENGTH;
	}

	/**
	 * Returns the most characters that a section holds, counting its line breaks.
	 *
	 * @return the number of characters
	 */
	public int maximumSectionLength()
	{
		return MAXIMUM_SECTION_LENGTH;
	}

	@Override
	public void write(int version, long seed, Map<String, Long> parameters, Writer out) throws IOException
	{
		requireThat(out, "out").isNotNull();
		validate(version, parameters);
		long count = parameters.get(COUNT);
		// java.util.Random's algorithm is part of its specification, so the same seed yields the same text on every JDK
		Random random = new Random(seed);
		int[] order = new int[SENTENCES.size()];
		for (int i = 0; i < order.length; ++i)
			order[i] = i;
		StringBuilder section = new StringBuilder();
		for (long i = 0; i < count; ++i)
		{
			section.setLength(0);
			// A section is about one component and one kind of item
			List<String> components = WORDS.get("component");
			int item = random.nextInt(WORDS.get("item").size());
			Map<String, String> subject = Map.of("component", components.get(random.nextInt(components.size())),
				"item", WORDS.get("item").get(item), "items", WORDS.get("items").get(item));
			section.append(HEADING_PREFIX);
			fill(random, HEADINGS.get(random.nextInt(HEADINGS.size())), subject, section);
			section.append("\n\n");
			// A partial Fisher-Yates shuffle picks different sentences for the section
			for (int sentence = 0; sentence < SENTENCES_PER_SECTION; ++sentence)
			{
				int swap = sentence + random.nextInt(order.length - sentence);
				int template = order[swap];
				order[swap] = order[sentence];
				order[sentence] = template;
				fill(random, SENTENCES.get(template), subject, section);
				section.append('\n');
			}
			section.append('\n');
			out.append(section);
		}
	}

	/**
	 * Appends a template with every slot filled.
	 *
	 * @param random   the source of randomness
	 * @param template the template
	 * @param subject  the words that fill the section's slots of the same name
	 * @param section  the section that receives the text
	 */
	private static void fill(Random random, String template, Map<String, String> subject, StringBuilder section)
	{
		Matcher slot = SLOT.matcher(template);
		int end = 0;
		while (slot.find())
		{
			section.append(template, end, slot.start());
			String name = slot.group(1);
			List<Integer> range = NUMBERS.get(name);
			if (range != null)
				section.append(range.getFirst() + random.nextInt(range.getLast() - range.getFirst() + 1));
			else if (subject.containsKey(name))
				section.append(subject.get(name));
			else
			{
				List<String> words = WORDS.get(name);
				section.append(words.get(random.nextInt(words.size())));
			}
			end = slot.end();
		}
		section.append(template, end, template.length());
	}
}
