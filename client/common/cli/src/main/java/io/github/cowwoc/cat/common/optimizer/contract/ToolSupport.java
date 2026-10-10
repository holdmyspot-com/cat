package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * The parameter validation and evidence reading that the kinds of {@link BuiltInTool} share.
 */
final class ToolSupport
{
	/**
	 * The number of characters of evidence that an observed value quotes.
	 */
	private static final int QUOTE_LIMIT = 300;
	/**
	 * The text that stands in for a variable value when a parameter is validated before any value is drawn.
	 */
	private static final String SAMPLE_VALUE = "value";

	/**
	 * One reply that a tool examines.
	 *
	 * @param turn the turn that the reply answered, from 1
	 * @param text the reply
	 */
	record Reply(int turn, String text)
	{
		/**
		 * Describes the turn that the reply answered.
		 *
		 * @return the description
		 */
		String describeTurn()
		{
			return "the reply to turn " + turn;
		}
	}

	/**
	 * Returns the replies that a tool examines, joining each turn's messages with a newline.
	 *
	 * @param replies every turn's ordered assistant messages, ending with its final response
	 * @param turn the turn that the tool examines, from 1, or {@code null} for every turn
	 * @return the examined replies, empty if the trial gave none of them
	 */
	static List<Reply> examined(List<List<String>> replies, Integer turn)
	{
		List<Reply> examined = new ArrayList<>();
		if (turn == null)
		{
			for (int i = 0; i < replies.size(); ++i)
				examined.add(new Reply(i + 1, String.join("\n", replies.get(i))));
		}
		else if (turn <= replies.size())
			examined.add(new Reply(turn, String.join("\n", replies.get(turn - 1))));
		return examined;
	}

	/**
	 * Returns the observed value of a trial that gave no examined reply.
	 *
	 * @param turn the turn that the tool examines, or {@code null} for every turn
	 * @return the observed value
	 */
	static String noReply(Integer turn)
	{
		if (turn == null)
			return "the trial gave no reply";
		return "the trial gave no reply to turn " + turn;
	}

	/**
	 * Returns which replies a tool examines, for its description.
	 *
	 * @param turn the turn that the tool examines, or {@code null} for every turn
	 * @return the description
	 */
	static String subject(Integer turn)
	{
		if (turn == null)
			return "every reply";
		return "the reply to turn " + turn;
	}

	/**
	 * Quotes evidence for an observed value. If it exceeds {@value #QUOTE_LIMIT} characters, quotes that prefix and
	 * appends the number of omitted characters.
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
		Objects.requireNonNull(text, name + " may not be null");
		if (text.isBlank())
			throw new IllegalArgumentException(name + " may not be blank");
	}

	/**
	 * Rejects a regular expression that does not compile once its scenario placeholders hold a value.
	 *
	 * @param tool the tool's name
	 * @param regex the regular expression
	 * @throws NullPointerException if {@code regex} is null
	 * @throws IllegalArgumentException if {@code regex} is blank or is not a valid Java regular expression
	 */
	static void requireScenarioRegex(String tool, String regex)
	{
		requireText(regex, tool + "'s regular expression");
		compile(tool, regex, sampleScenario(regex));
	}

	/**
	 * Compiles a regular expression after replacing its scenario placeholders with values matched literally.
	 *
	 * @param tool the tool's name
	 * @param regex the regular expression
	 * @param textByName the text of each placeholder
	 * @return the compiled expression
	 * @throws IllegalArgumentException if the result is not a valid Java regular expression
	 */
	static Pattern compile(String tool, String regex, Map<String, String> textByName)
	{
		try
		{
			return Pattern.compile(Placeholders.fillScenarioLiterally(regex, textByName));
		}
		catch (PatternSyntaxException e)
		{
			throw new IllegalArgumentException(tool + "'s argument \"" + regex + "\" is not a valid Java regular " +
				"expression: " + e.getDescription(), e);
		}
	}

	/**
	 * Rejects a path that is not a normalized project-relative path once its scenario placeholders hold a value.
	 *
	 * @param tool the tool's name
	 * @param path the path
	 * @throws NullPointerException if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is blank or is not a normalized project-relative path
	 */
	static void requireScenarioPath(String tool, String path)
	{
		requireText(path, tool + "'s path");
		fillPath(tool, path, sampleScenario(path));
	}

	/**
	 * Replaces the scenario placeholders of a path.
	 *
	 * @param tool the tool's name
	 * @param path the path
	 * @param textByName the text of each placeholder
	 * @return the filled path
	 * @throws IllegalArgumentException if the filled path is not a normalized project-relative path
	 */
	static String fillPath(String tool, String path, Map<String, String> textByName)
	{
		String filled = Placeholders.fillScenario(path, textByName);
		if (!ProjectPaths.isNormalized(filled))
		{
			throw new IllegalArgumentException(tool + "'s path '" + filled + "' must be a normalized path that is " +
				"relative to the project and uses / as the separator");
		}
		return filled;
	}

	/**
	 * Returns a stand-in value for every scenario placeholder of an argument.
	 *
	 * @param argument the argument
	 * @return the stand-in text of each name it holds
	 */
	private static Map<String, String> sampleScenario(String argument)
	{
		Set<String> names = Placeholders.scenarioNames(argument);
		return names.stream().collect(Collectors.toMap(name -> name, _ -> SAMPLE_VALUE));
	}

	/**
	 * Returns a stand-in value for every placeholder of a parameter.
	 *
	 * @param parameter the parameter
	 * @return the stand-in text of each variable it names
	 */
	private static Map<String, String> sample(String parameter)
	{
		Set<String> names = Placeholders.names(parameter);
		return names.stream().collect(Collectors.toMap(name -> name, _ -> SAMPLE_VALUE));
	}

	/**
	 * Prevents instantiation.
	 */
	private ToolSupport()
	{
	}
}
