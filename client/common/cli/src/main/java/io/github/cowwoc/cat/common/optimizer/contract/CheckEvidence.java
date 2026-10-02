package io.github.cowwoc.cat.common.optimizer.contract;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The recorded evidence that a {@link DeterministicCheck} examines. It comes from a trial or from a control: a
 * supplied example that the check must accept or reject. A check never runs a command or changes a file; it reads
 * only what the evidence recorded (design &sect;1.2).
 */
public interface CheckEvidence
{
	/**
	 * Returns the final reply to each turn.
	 *
	 * @return the replies, in turn order; a trial cut off by its time limit may have fewer replies than turns
	 */
	List<String> replies();

	/**
	 * Indicates whether a file or directory exists in the project after the trial.
	 *
	 * @param path a normalized project-relative path that uses {@code /} as the separator
	 * @return {@code true} if it exists
	 * @throws NullPointerException if {@code path} is null
	 * @throws IOException if the evidence cannot be read
	 */
	boolean exists(String path) throws IOException;

	/**
	 * Returns the content of a file in the project after the trial.
	 *
	 * @param path a normalized project-relative path that uses {@code /} as the separator
	 * @return the file's text, or empty if no regular file has that path
	 * @throws NullPointerException if {@code path} is null
	 * @throws IOException if the evidence cannot be read
	 */
	Optional<String> content(String path) throws IOException;

	/**
	 * Returns the tool calls that the trial made.
	 *
	 * @return the calls in the order they were made, or empty if the evidence does not record tool calls
	 */
	Optional<List<ToolCall>> toolCalls();
}
