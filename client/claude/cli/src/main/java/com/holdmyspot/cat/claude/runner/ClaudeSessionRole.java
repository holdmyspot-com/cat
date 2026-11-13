package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.InvalidArgumentsException;

import java.util.List;
import java.util.Locale;

/**
 * The roles of the sessions that CAT starts for its measurements, which decide how the session's Claude Code process is
 * launched: whether it runs inside a sandbox, the tools it offers its model and the lifetime of its prompt cache. A
 * session that names no role runs inside a sandbox and keeps Claude Code's defaults for the other two.
 */
public enum ClaudeSessionRole
{
	/**
	 * A session that runs a test's turns. It runs inside a sandbox, keeps every tool, and its prompt cache lives five
	 * minutes.
	 */
	TRIAL(List.of(), "5m", true),
	/**
	 * A session that reads one trial's evidence and returns a verdict. It reads files and searches them, and nothing
	 * else, so it has no command for a sandbox to confine and runs without one; permission rules alone keep its
	 * tools out of the paths that it must not read. Its prompt cache lives five minutes.
	 */
	JUDGE(ClaudeSessionSandbox.READ_ONLY_TOOLS, "5m", false);

	/**
	 * The runner option whose value is a role's {@link #optionValue() option value}.
	 */
	public static final String OPTION = "--session-role";

	private final List<String> tools;
	private final String promptCacheTtl;
	private final boolean sandboxed;

	/**
	 * Creates a role.
	 *
	 * @param tools          the only tools that the session's model is offered, or none to keep every tool
	 * @param promptCacheTtl the lifetime of the session's prompt cache, in Claude Code's notation
	 * @param sandboxed      {@code true} if Claude Code confines the session's commands in a sandbox, {@code false}
	 *                       if the session runs without one
	 */
	ClaudeSessionRole(List<String> tools, String promptCacheTtl, boolean sandboxed)
	{
		this.tools = tools;
		this.promptCacheTtl = promptCacheTtl;
		this.sandboxed = sandboxed;
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
	 * Indicates whether Claude Code confines the session's commands in a sandbox. A role that is not sandboxed is
	 * offered only the tools that {@link ClaudeSessionSandbox#READ_ONLY_TOOLS} lists, and the runner refuses to start
	 * a session that runs without a sandbox and is offered any other.
	 *
	 * @return {@code true} if the session runs inside a sandbox, {@code false} if it runs without one
	 */
	public boolean sandboxed()
	{
		return sandboxed;
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
