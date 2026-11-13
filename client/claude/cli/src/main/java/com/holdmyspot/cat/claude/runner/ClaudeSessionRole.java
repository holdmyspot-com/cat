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
	TRIAL(List.of(), "5m", true),
	/**
	 * A session that reads one trial's evidence and returns a verdict. It reads files and searches them, and nothing
	 * else, so its sandbox lists no write root, and its prompt cache lives five minutes.
	 */
	JUDGE(List.of("Read", "Grep", "Glob"), "5m", false);

	/**
	 * The runner option whose value is a role's {@link #optionValue() option value}.
	 */
	public static final String OPTION = "--session-role";

	private final List<String> tools;
	private final String promptCacheTtl;
	private final boolean writes;

	/**
	 * Creates a role.
	 *
	 * @param tools          the only tools that the session's model is offered, or none to keep every tool
	 * @param promptCacheTtl the lifetime of the session's prompt cache, in Claude Code's notation
	 * @param writes         {@code true} if the session's sandbox lets its commands write the roots of its policy,
	 *                       {@code false} if the sandbox lists none
	 */
	ClaudeSessionRole(List<String> tools, String promptCacheTtl, boolean writes)
	{
		this.tools = tools;
		this.promptCacheTtl = promptCacheTtl;
		this.writes = writes;
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
	 * Indicates whether the session's sandbox lists the write roots of its policy. A session that has no tool that
	 * writes, and no command, needs none, and the protected-path denials that Claude Code generates for each root
	 * would only lengthen its prompt.
	 *
	 * @return {@code true} if the sandbox lets the session's commands write the roots of its policy
	 */
	public boolean writes()
	{
		return writes;
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
