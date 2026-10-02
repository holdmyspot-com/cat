package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One tool call that a trial made, as its evidence records it.
 *
 * @param tool the tool's name
 * @param command the command that the call ran, or {@code null} if none was recorded
 * @param exitStatus the command's exit status, or {@code null} if none was recorded
 */
public record ToolCall(String tool, String command, Integer exitStatus)
{
	/**
	 * Creates a tool call.
	 *
	 * @param tool the tool's name
	 * @param command the command that the call ran, or {@code null} if none was recorded
	 * @param exitStatus the command's exit status, or {@code null} if none was recorded
	 * @throws NullPointerException if {@code tool} is null
	 * @throws IllegalArgumentException if {@code tool} is blank, {@code command} is blank, or {@code exitStatus} is
	 *   set without a {@code command}
	 */
	public ToolCall
	{
		requireThat(tool, "tool").isNotBlank();
		if (command != null)
			requireThat(command, "command").isNotBlank();
		else if (exitStatus != null)
			throw new IllegalArgumentException("A tool call without a command has no exit status");
	}
}
