package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.claude.policy.ClaudeOption.Arity;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * What the command line of a Claude Code session says about the session's sandbox policy.
 *
 * @param settings        the value of {@code --settings}: a settings file or settings as JSON
 * @param settingSources  the scopes that {@code --setting-sources} lists, or empty if the option is absent
 * @param addDirectories  the values of {@code --add-dir}, in order
 * @param restricted      {@code true} if {@code --restricted} is present
 * @param deniedTools     the permission rules that {@code --disallowedTools} lists, in order
 */
record ClaudeCommandLine(Optional<String> settings, Optional<Set<SettingsScope>> settingSources,
	List<String> addDirectories, boolean restricted, List<String> deniedTools)
{
	/**
	 * The fix of a refusal that the command line causes.
	 */
	private static final String COMMAND_LINE_FIX = "Start Claude Code without that option, then start a new session";
	/**
	 * The options by each of their spellings.
	 */
	private static final Map<String, ClaudeOption> OPTIONS_BY_NAME = optionsByName();

	/**
	 * Creates a command line.
	 *
	 * @param settings       the value of {@code --settings}: a settings file or settings as JSON
	 * @param settingSources the scopes that {@code --setting-sources} lists, or empty if the option is absent
	 * @param addDirectories the values of {@code --add-dir}, in order
	 * @param restricted     {@code true} if {@code --restricted} is present
	 * @param deniedTools    the permission rules that {@code --disallowedTools} lists, in order
	 * @throws NullPointerException if any of the arguments are null
	 */
	ClaudeCommandLine
	{
		requireThat(settings, "settings").isNotNull();
		requireThat(settingSources, "settingSources").isNotNull();
		settingSources = settingSources.map(Set::copyOf);
		addDirectories = List.copyOf(addDirectories);
		deniedTools = List.copyOf(deniedTools);
	}

	/**
	 * Returns the options by each of their spellings.
	 *
	 * @return the options
	 */
	private static Map<String, ClaudeOption> optionsByName()
	{
		Map<String, ClaudeOption> byName = new HashMap<>();
		for (ClaudeOption option : ClaudeRelease.OPTIONS)
		{
			for (String name : option.names())
			{
				ClaudeOption previous = byName.put(name, option);
				assert previous == null : name;
			}
		}
		return Map.copyOf(byName);
	}

	/**
	 * Parses the arguments of a Claude Code process as the release's command-line parser does.
	 *
	 * @param arguments the arguments after the program
	 * @return the command line
	 * @throws NullPointerException if {@code arguments} is null or contains null
	 * @throws PolicyRefusal        if an argument is an option that the release does not list, an option whose effect
	 *                              on the sandbox CAT cannot read, or an option without its value
	 */
	static ClaudeCommandLine parse(List<String> arguments) throws PolicyRefusal
	{
		Parser parser = new Parser(List.copyOf(arguments));
		parser.run();
		return new ClaudeCommandLine(Optional.ofNullable(parser.settings), Optional.ofNullable(parser.settingSources),
			parser.addDirectories, parser.restricted, parser.deniedTools);
	}

	/**
	 * The state of parsing one command line.
	 */
	private static final class Parser
	{
		/**
		 * The arguments.
		 */
		private final List<String> arguments;
		/**
		 * The index of the next argument.
		 */
		private int next;
		/**
		 * The value of {@code --settings}, or null.
		 */
		private String settings;
		/**
		 * The scopes of {@code --setting-sources}, or null.
		 */
		private Set<SettingsScope> settingSources;
		/**
		 * The values of {@code --add-dir}.
		 */
		private final List<String> addDirectories = new ArrayList<>();
		/**
		 * Whether {@code --restricted} is present.
		 */
		private boolean restricted;
		/**
		 * The values of {@code --disallowedTools}.
		 */
		private final List<String> deniedTools = new ArrayList<>();

		/**
		 * Creates a parser.
		 *
		 * @param arguments the arguments
		 */
		Parser(List<String> arguments)
		{
			this.arguments = arguments;
		}

		/**
		 * Parses every argument.
		 *
		 * @throws PolicyRefusal if the command line is refused
		 */
		void run() throws PolicyRefusal
		{
			while (next < arguments.size())
			{
				String argument = arguments.get(next);
				++next;
				if (argument.equals("--"))
					return;
				if (!argument.startsWith("-") || argument.equals("-"))
					continue;
				ClaudeOption option = OPTIONS_BY_NAME.get(argument);
				if (option != null)
				{
					apply(argument, option, null);
					continue;
				}
				if (argument.startsWith("--"))
				{
					int equals = argument.indexOf('=');
					if (equals < 0)
						throw unknown(argument);
					String name = argument.substring(0, equals);
					option = OPTIONS_BY_NAME.get(name);
					if (option == null)
						throw unknown(name);
					if (option.arity() == Arity.FLAG)
						throw invalid(name, "takes no value, but the command line gives it one");
					apply(name, option, argument.substring(equals + 1));
					continue;
				}
				parseShortOptions(argument);
			}
		}

		/**
		 * Parses an argument that combines short options, such as {@code -pc}, or gives a short option its value,
		 * such as {@code -nname}.
		 *
		 * @param argument the argument
		 * @throws PolicyRefusal if the command line is refused
		 */
		private void parseShortOptions(String argument) throws PolicyRefusal
		{
			for (int i = 1; i < argument.length(); ++i)
			{
				String name = "-" + argument.charAt(i);
				ClaudeOption option = OPTIONS_BY_NAME.get(name);
				if (option == null)
					throw unknown(name);
				if (option.arity() != Arity.FLAG && i + 1 < argument.length())
				{
					apply(name, option, argument.substring(i + 1));
					return;
				}
				apply(name, option, null);
			}
		}

		/**
		 * Applies an option.
		 *
		 * @param name        the spelling of the option on the command line
		 * @param option      the option
		 * @param inlineValue the value that the same argument gives the option, or null
		 * @throws PolicyRefusal if the command line is refused
		 */
		private void apply(String name, ClaudeOption option, String inlineValue) throws PolicyRefusal
		{
			List<String> values = new ArrayList<>();
			if (inlineValue != null)
				values.add(inlineValue);
			switch (option.arity())
			{
				case FLAG ->
				{
				}
				case VALUE ->
				{
					if (inlineValue == null)
					{
						if (next >= arguments.size())
							throw invalid(name, "has no value");
						values.add(arguments.get(next));
						++next;
					}
				}
				case OPTIONAL_VALUE ->
				{
					if (inlineValue == null && next < arguments.size() && !arguments.get(next).startsWith("-"))
					{
						values.add(arguments.get(next));
						++next;
					}
				}
				case VARIADIC ->
				{
					while (next < arguments.size() && !arguments.get(next).startsWith("-"))
					{
						values.add(arguments.get(next));
						++next;
					}
					if (values.isEmpty())
						throw invalid(name, "has no value");
				}
			}
			switch (option.effect())
			{
				case NONE ->
				{
				}
				case SETTINGS ->
				{
					if (settings != null)
						throw invalid(name, "appears more than once");
					settings = values.getFirst();
				}
				case SETTING_SOURCES ->
				{
					if (settingSources != null)
						throw invalid(name, "appears more than once");
					settingSources = settingSources(name, values.getFirst());
				}
				case ADD_DIRECTORIES -> addDirectories.addAll(values);
				case RESTRICTED -> restricted = true;
				case DENIED_TOOLS ->
				{
					// The release splits each value at commas and spaces that lie outside parentheses
					for (String value : values)
						deniedTools.addAll(splitRules(value));
				}
				case UNSUPPORTED -> throw new PolicyRefusal("Claude Code was started with " + name + ", whose " +
					"effect on the session's sandbox CAT cannot read", COMMAND_LINE_FIX);
			}
		}

		/**
		 * Returns the scopes that a value of {@code --setting-sources} lists.
		 *
		 * @param name  the spelling of the option
		 * @param value the value
		 * @return the scopes
		 * @throws PolicyRefusal if the value names a scope that the release does not know
		 */
		private static Set<SettingsScope> settingSources(String name, String value) throws PolicyRefusal
		{
			Set<SettingsScope> scopes = EnumSet.noneOf(SettingsScope.class);
			for (String entry : value.split(","))
			{
				String trimmed = entry.strip();
				if (trimmed.isEmpty())
					continue;
				SettingsScope scope = ClaudeRelease.SETTING_SOURCE_NAMES.get(trimmed);
				if (scope == null)
					throw invalid(name, "names the unknown settings source \"" + trimmed + "\"");
				scopes.add(scope);
			}
			return scopes;
		}

		/**
		 * Returns the refusal of an option that the release does not list.
		 *
		 * @param name the spelling of the option
		 * @return the refusal
		 */
		private static PolicyRefusal unknown(String name)
		{
			return new PolicyRefusal("Claude Code was started with " + name + ", an option that Claude Code " +
				ClaudeRelease.VERSION + " does not list", COMMAND_LINE_FIX);
		}

		/**
		 * Returns the refusal of an option that the command line gives in a form that the release rejects or that
		 * CAT cannot read.
		 *
		 * @param name    the spelling of the option
		 * @param problem what is wrong, as a predicate whose subject is the option
		 * @return the refusal
		 */
		private static PolicyRefusal invalid(String name, String problem)
		{
			return new PolicyRefusal("Claude Code's option " + name + " " + problem, COMMAND_LINE_FIX);
		}
	}

	/**
	 * Splits a list of permission rules at the commas and spaces that lie outside parentheses.
	 *
	 * @param value the list
	 * @return the rules
	 */
	static List<String> splitRules(String value)
	{
		List<String> rules = new ArrayList<>();
		StringBuilder rule = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < value.length(); ++i)
		{
			char c = value.charAt(i);
			if (c == '(')
				++depth;
			else if (c == ')' && depth > 0)
				--depth;
			if (depth == 0 && (c == ',' || Character.isWhitespace(c)))
			{
				if (!rule.isEmpty())
					rules.add(rule.toString());
				rule.setLength(0);
				continue;
			}
			rule.append(c);
		}
		if (!rule.isEmpty())
			rules.add(rule.toString());
		return rules;
	}
}
