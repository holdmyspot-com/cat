package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * What a trial did, normalized from its harness events and its files: the source of every built-in tool's verdict.
 * <p>
 * The record is complete, so a built-in tool can establish that something did not happen: an agent tool that no entry
 * names was never called.
 */
public interface TrialRecord
{
	/**
	 * Returns every turn's reply.
	 *
	 * @return one list per turn, holding that turn's assistant messages in order and ending with its final response
	 */
	List<List<String>> replies();

	/**
	 * Returns every tool call of the trial, of the main agent and of each child agent, in the order that the harness
	 * recorded them.
	 *
	 * @return the tool calls, each with its complete input and output
	 */
	List<SessionEvidence.Tool> tools();

	/**
	 * Indicates whether a path exists in the trial's workspace after the trial.
	 *
	 * @param path a normalized path relative to the workspace, which uses {@code /} as the separator
	 * @return {@code true} if a file, directory or link exists at the path
	 * @throws NullPointerException if {@code path} is null
	 * @throws IOException if the workspace cannot be read
	 */
	boolean exists(String path) throws IOException;

	/**
	 * Returns the text of a file in the trial's workspace after the trial.
	 *
	 * @param path a normalized path relative to the workspace, which uses {@code /} as the separator
	 * @return the file's text, or empty if the path is not a regular file of the workspace
	 * @throws NullPointerException if {@code path} is null
	 * @throws IOException if the file cannot be read
	 */
	Optional<String> content(String path) throws IOException;

	/**
	 * Returns the text of a file as it was when the trial started, before its first turn.
	 *
	 * @param path a normalized path relative to the workspace, which uses {@code /} as the separator
	 * @return the file's text, or empty if the path was not a regular file of the workspace when the trial started
	 * @throws NullPointerException if {@code path} is null
	 * @throws IOException if the file cannot be read
	 */
	Optional<String> initial(String path) throws IOException;
}
