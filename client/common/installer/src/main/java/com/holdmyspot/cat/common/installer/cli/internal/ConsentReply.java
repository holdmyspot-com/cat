package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Locale;
import java.util.Optional;

/**
 * What the user said to a question that install asked in the terminal.
 */
public enum ConsentReply
{
	/**
	 * The user answered yes.
	 */
	ALLOWED,
	/**
	 * The user answered anything but yes, including nothing, which takes the default, no.
	 */
	DECLINED,
	/**
	 * The terminal did not ask the user, because the installer has no interactive terminal.
	 */
	UNASKED;

	/**
	 * Interprets an answer.
	 *
	 * @param answer what {@link Terminal#ask(String)} returned
	 * @return the reply; only {@code y} and {@code yes}, in any letter case and surrounded by any white space, allow
	 * @throws NullPointerException if {@code answer} is null
	 */
	public static ConsentReply of(Optional<String> answer)
	{
		requireThat(answer, "answer").isNotNull();
		if (answer.isEmpty())
			return UNASKED;
		String reply = answer.get().strip().toLowerCase(Locale.ROOT);
		if (reply.equals("y") || reply.equals("yes"))
			return ALLOWED;
		return DECLINED;
	}
}
