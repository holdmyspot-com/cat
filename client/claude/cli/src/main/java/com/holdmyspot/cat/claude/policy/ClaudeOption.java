package com.holdmyspot.cat.claude.policy;

import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A command-line option of the Claude Code release that CAT reads a session's sandbox policy for.
 *
 * @param names  the spellings of the option, such as {@code -p} and {@code --print}
 * @param arity  how many of the following arguments the option takes
 * @param effect what the option does to the session's sandbox policy
 */
record ClaudeOption(List<String> names, Arity arity, Effect effect)
{
	/**
	 * Creates an option.
	 *
	 * @param names  the spellings of the option, such as {@code -p} and {@code --print}
	 * @param arity  how many of the following arguments the option takes
	 * @param effect what the option does to the session's sandbox policy
	 * @throws NullPointerException     if any of the arguments are null, or {@code names} contains null
	 * @throws IllegalArgumentException if {@code names} is empty or a name does not start with {@code -}
	 */
	ClaudeOption
	{
		requireThat(names, "names").isNotEmpty();
		for (String name : names)
		{
			requireThat(name, "name").startsWith("-");
		}
		names = List.copyOf(names);
		requireThat(arity, "arity").isNotNull();
		requireThat(effect, "effect").isNotNull();
	}

	/**
	 * How many of the arguments that follow an option the option takes, as Claude Code's command-line parser decides.
	 */
	enum Arity
	{
		/**
		 * The option takes no value.
		 */
		FLAG,
		/**
		 * The option takes the next argument as its value, whatever it starts with.
		 */
		VALUE,
		/**
		 * The option takes the next argument as its value unless it starts with {@code -}.
		 */
		OPTIONAL_VALUE,
		/**
		 * The option takes every following argument that does not start with {@code -}, and at least one.
		 */
		VARIADIC
	}

	/**
	 * What an option does to the session's sandbox policy.
	 */
	enum Effect
	{
		/**
		 * The option does not change the sandbox policy.
		 */
		NONE,
		/**
		 * The option names a settings file, or holds settings as JSON, that Claude Code loads above the project
		 * settings.
		 */
		SETTINGS,
		/**
		 * The option lists which of the user, project and local settings Claude Code loads.
		 */
		SETTING_SOURCES,
		/**
		 * The option adds working directories, which the sandbox lets commands write.
		 */
		ADD_DIRECTORIES,
		/**
		 * The option makes Claude Code ignore the user, project and local settings.
		 */
		RESTRICTED,
		/**
		 * The option lists permission rules that deny tools, which may deny paths and hosts to the sandbox.
		 */
		DENIED_TOOLS,
		/**
		 * The option names the session's permission mode, which decides whether a command that runs outside the
		 * sandbox needs an allow rule.
		 */
		PERMISSION_MODE,
		/**
		 * The option changes the sandbox policy in a way that CAT cannot read, so CAT refuses the session.
		 */
		UNSUPPORTED
	}
}
