package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.InvalidArgumentsException;

import java.util.List;
import java.util.Locale;

/**
 * The roles of the sessions that CAT starts for its measurements, which decide how the session's Claude Code process is
 * launched beyond the sandbox: the tools it offers its model and the lifetime of its prompt cache. A session that names
 * no role keeps Claude Code's defaults for both.
 */
public enum ClaudeSessionRole
{
	/**
	 * A session that runs a test's turns. It keeps every tool, and its prompt cache lives five minutes.
	 */
	TRIAL(List.of(), "5m"),
	/**
	 * A session that reads one trial's evidence and returns a verdict. It reads files and searches them, and nothing
	 * else, and its prompt cache lives five minutes.
	 */
	JUDGE(List.of("Read", "Grep", "Glob"), "5m");

	/**
	 * The runner option whose value is a role's {@link #optionValue() option value}.
	 */
	public static final String OPTION = "--session-role";

	private final List<String> tools;
	private final String promptCacheTtl;

	/**
	 * Creates a role.
	 *
	 * @param tools          the only tools that the session's model is offered, or none to keep every tool
	 * @param promptCacheTtl the lifetime of the session's prompt cache, in Claude Code's notation
	 */
	ClaudeSessionRole(List<String> tools, String promptCacheTtl)
	{
		this.tools = tools;
		this.promptCacheTtl = promptCacheTtl;
	}

	/**
	 * Returns the tools that the session's model is offered.
	 *
	 * @return the tool names, empty if the session keeps every tool
	 */
	public List<String> tools()
	{
		return tools;
	}

	/**
	 * Returns the lifetime of the session's prompt cache.
	 *
	 * @return the lifetime in Claude Code's {@code promptCacheTtl} notation
	 */
	public String promptCacheTtl()
	{
		return promptCacheTtl;
	}

	/**
	 * Returns the value that follows {@link #OPTION} on a runner launch for this role.
	 *
	 * @return the value
	 */
	public String optionValue()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Reads the value of {@link #OPTION}.
	 *
	 * @param value the value
	 * @return the role
	 * @throws NullPointerException      if {@code value} is null
	 * @throws InvalidArgumentsException if {@code value} names no role
	 */
	public static ClaudeSessionRole parse(String value)
	{
		requireThat(value, "value").isNotNull();
		for (ClaudeSessionRole role : values())
		{
			if (role.optionValue().equals(value))
				return role;
		}
		throw new InvalidArgumentsException("Unknown value for " + OPTION + ": " + value);
	}
}
