package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code {{variable}}} placeholders in turns and built-in tool arguments with the text selected for that named
 * input in a trial.
 */
final class Placeholders
{
	/**
	 * Matches {@code {{variable}}}, capturing the variable name.
	 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
	/**
	 * Matches a {@code {{name}}} whose name could be declared, that is lowercase words joined by underscores, capturing
	 * the name. Other text in double braces, such as {@code ${{ github.token }}}, is not a placeholder of a scenario.
	 */
	static final Pattern SCENARIO_PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9]+(?:_[a-z0-9]+)*)\\}\\}");

	/**
	 * Returns the variables that a text names.
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
	 * Returns the names of a scenario's placeholders in a text.
	 *
	 * @param text the text
	 * @return the names that could be declared, in order of first appearance
	 */
	static Set<String> scenarioNames(String text)
	{
		Set<String> names = new LinkedHashSet<>();
		Matcher placeholder = SCENARIO_PLACEHOLDER.matcher(text);
		while (placeholder.find())
			names.add(placeholder.group(1));
		return names;
	}

	/**
	 * Replaces each scenario placeholder that names a given text, leaving every other placeholder unchanged.
	 *
	 * @param text the text
	 * @param textByName the replacement text of each name
	 * @return the text with the values inserted
	 */
	static String fillScenario(String text, Map<String, String> textByName)
	{
		return SCENARIO_PLACEHOLDER.matcher(text).replaceAll(placeholder ->
		{
			String value = textByName.get(placeholder.group(1));
			if (value == null)
				return Matcher.quoteReplacement(placeholder.group());
			return Matcher.quoteReplacement(value);
		});
	}

	/**
	 * Replaces each scenario placeholder in a regular expression that names a given text, matched literally, leaving
	 * every other placeholder unchanged.
	 *
	 * @param regex the regular expression
	 * @param textByName the replacement text of each name
	 * @return the regular expression with the values inserted
	 */
	static String fillScenarioLiterally(String regex, Map<String, String> textByName)
	{
		return SCENARIO_PLACEHOLDER.matcher(regex).replaceAll(placeholder ->
		{
			String value = textByName.get(placeholder.group(1));
			if (value == null)
				return Matcher.quoteReplacement(placeholder.group());
			return Matcher.quoteReplacement(Pattern.quote(value));
		});
	}

	/**
	 * Replaces every placeholder with its variable's text.
	 *
	 * @param text the text
	 * @param textByVariable the replacement text of each variable that {@code text} names
	 * @return the text with the values inserted
	 */
	static String fill(String text, Map<String, String> textByVariable)
	{
		return PLACEHOLDER.matcher(text).
			replaceAll(placeholder -> Matcher.quoteReplacement(textByVariable.get(placeholder.group(1))));
	}

	/**
	 * Replaces every placeholder in a regular expression with its variable's text, matched literally.
	 *
	 * @param regex the regular expression
	 * @param textByVariable the replacement text of each variable that {@code regex} names
	 * @return the regular expression with the values inserted
	 */
	static String fillLiterally(String regex, Map<String, String> textByVariable)
	{
		return PLACEHOLDER.matcher(regex).replaceAll(placeholder ->
			Matcher.quoteReplacement(Pattern.quote(textByVariable.get(placeholder.group(1)))));
	}

	/**
	 * Prevents instantiation.
	 */
	private Placeholders()
	{
	}
}
