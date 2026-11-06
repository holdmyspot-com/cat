package com.holdmyspot.cat.common.installer.cli.internal;

import java.io.IOException;
import java.util.Optional;

/**
 * The interactive terminal of the user who runs the installer, through which install asks the user a question.
 */
@FunctionalInterface
public interface Terminal
{
	/**
	 * Shows a question to the user and reads one line of answer.
	 *
	 * @param question the question, which ends where the answer starts
	 * @return the answer without its line ending, or empty when the installer has no interactive terminal
	 * @throws NullPointerException if {@code question} is null
	 * @throws IOException          if the terminal cannot be read or written
	 */
	Optional<String> ask(String question) throws IOException;

	/**
	 * Returns whether the installer's standard output shows on this terminal, so that a person reads what the
	 * installer prints there. A terminal that cannot tell reports {@code false}, which makes the installer print its
	 * machine-readable result.
	 *
	 * @return {@code true} if the person who runs the installer reads its standard output on this terminal
	 */
	default boolean isStdoutTerminal()
	{
		return false;
	}
}
