package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{dimension}}} placeholders that a test's turns and check parameters use to name a variation value.
 */
final class Placeholders
{
	/**
	 * Matches {@code {{dimension}}}, capturing the dimension name.
	 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

	/**
	 * Returns the dimensions that a text names.
	 *
	 * @param text the text
	 * @return the names of its placeholders, in order of first appearance
	 */
	static Set<String> names(String text)
	{
		Set<String> names = new LinkedHashSet<>();
		Matcher placeholder = PLACEHOLDER.matcher(text);
		while (placeholder.find())
			names.add(placeholder.group(1));
		return names;
	}

	/**
	 * Replaces every placeholder with its dimension's text.
	 *
	 * @param text the text
	 * @param textByDimension the replacement text of each dimension that {@code text} names
	 * @return the text with the values inserted
	 */
	static String fill(String text, Map<String, String> textByDimension)
	{
		return PLACEHOLDER.matcher(text).
			replaceAll(placeholder -> Matcher.quoteReplacement(textByDimension.get(placeholder.group(1))));
	}

	/**
	 * Replaces every placeholder in a regular expression with its dimension's text, matched literally.
	 *
	 * @param regex the regular expression
	 * @param textByDimension the replacement text of each dimension that {@code regex} names
	 * @return the regular expression with the values inserted
	 */
	static String fillLiterally(String regex, Map<String, String> textByDimension)
	{
		return PLACEHOLDER.matcher(regex).replaceAll(placeholder ->
			Matcher.quoteReplacement(Pattern.quote(textByDimension.get(placeholder.group(1)))));
	}

	/**
	 * Prevents instantiation.
	 */
	private Placeholders()
	{
	}
}
