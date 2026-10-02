package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * The parameter validation and evidence reading that the kinds of {@link CheckCriterion} share.
 */
final class CheckSupport
{
	/**
	 * The number of characters of evidence that an observed value quotes.
	 */
	private static final int QUOTE_LIMIT = 300;
	/**
	 * The text that stands in for a variation value when a parameter is validated before any value is drawn.
	 */
	private static final String SAMPLE_VALUE = "value";

	/**
	 * One reply that a check examines.
	 *
	 * @param turn the turn that the reply answered, from 1
	 * @param text the reply
	 */
	record Reply(int turn, String text)
	{
		/**
		 * Returns the reply's position for an observed value.
		 *
		 * @return the description
		 */
		String where()
		{
			return "the reply to turn " + turn;
		}
	}

	/**
	 * Returns the replies that a check examines.
	 *
	 * @param replies the final reply to each turn
	 * @param turn the turn that the check examines, from 1, or {@code null} for every turn
	 * @return the examined replies, empty if the trial gave none of them
	 */
	static List<Reply> examined(List<String> replies, Integer turn)
	{
		List<Reply> examined = new ArrayList<>();
		if (turn == null)
		{
			for (int i = 0; i < replies.size(); ++i)
				examined.add(new Reply(i + 1, replies.get(i)));
		}
		else if (turn <= replies.size())
			examined.add(new Reply(turn, replies.get(turn - 1)));
		return examined;
	}

	/**
	 * Returns the observed value of a trial that gave no examined reply.
	 *
	 * @param turn the turn that the check examines, or {@code null} for every turn
	 * @return the observed value
	 */
	static String noReply(Integer turn)
	{
		if (turn == null)
			return "the trial gave no reply";
		return "the trial gave no reply to turn " + turn;
	}

	/**
	 * Returns which replies a check examines, for its description.
	 *
	 * @param turn the turn that the check examines, or {@code null} for every turn
	 * @return the description
	 */
	static String subject(Integer turn)
	{
		if (turn == null)
			return "every reply";
		return "the reply to turn " + turn;
	}

	/**
	 * Quotes evidence for an observed value, shortening it to {@value #QUOTE_LIMIT} characters.
	 *
	 * @param text the evidence
	 * @return the quotation
	 */
	static String quote(String text)
	{
		if (text.length() <= QUOTE_LIMIT)
			return "\"" + text + "\"";
		return "\"" + text.substring(0, QUOTE_LIMIT) + "\" (" + (text.length() - QUOTE_LIMIT) + " more characters)";
	}

	/**
	 * Rejects a turn number below 1.
	 *
	 * @param turn the turn, or {@code null} for every turn
	 * @throws IllegalArgumentException if {@code turn} is less than 1
	 */
	static void requireTurn(Integer turn)
	{
		if (turn != null && turn < 1)
			throw new IllegalArgumentException("turn must be at least 1, but it is " + turn);
	}

	/**
	 * Rejects a regular expression that does not compile once its placeholders hold a value.
	 *
	 * @param regex the regular expression
	 * @param name the parameter's name
	 * @throws NullPointerException if {@code regex} is null
	 * @throws IllegalArgumentException if {@code regex} is blank or is not a valid Java regular expression
	 */
	static void requireRegex(String regex, String name)
	{
		requireText(regex, name);
		try
		{
			Pattern.compile(Placeholders.fillLiterally(regex, sample(regex)));
		}
		catch (PatternSyntaxException e)
		{
			throw new IllegalArgumentException(name + " is not a valid Java regular expression: " +
				e.getDescription(), e);
		}
	}

	/**
	 * Rejects a path that is not a normalized project-relative path once its placeholders hold a value.
	 *
	 * @param path the path
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is blank or is not a normalized project-relative path
	 */
	static void requirePath(String path)
	{
		requireText(path, "path");
		if (!ProjectPaths.isNormalized(Placeholders.fill(path, sample(path))))
		{
			throw new IllegalArgumentException("path '" + path + "' must be a normalized path that is relative to " +
				"the project and uses / as the separator");
		}
	}

	/**
	 * Rejects a missing or blank text parameter.
	 *
	 * @param text the parameter's value
	 * @param name the parameter's name
	 * @throws NullPointerException if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is blank
	 */
	static void requireText(String text, String name)
	{
		if (text == null)
			throw new NullPointerException(name + " may not be null");
		if (text.isBlank())
			throw new IllegalArgumentException(name + " may not be blank");
	}

	/**
	 * Returns a stand-in value for every placeholder of a parameter.
	 *
	 * @param parameter the parameter
	 * @return the stand-in text of each dimension it names
	 */
	private static Map<String, String> sample(String parameter)
	{
		Set<String> names = Placeholders.names(parameter);
		return names.stream().collect(Collectors.toMap(name -> name, _ -> SAMPLE_VALUE));
	}

	/**
	 * Prevents instantiation.
	 */
	private CheckSupport()
	{
	}
}
