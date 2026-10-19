package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The rules for a harness's primary agent, split for a session that keeps its rules in a file.
 *
 * @param readingGuidance the harness rule that tells the agent how to read a file whole, which the agent needs before
 *                        it can read the file that holds the other rules, or an empty value when the
 *                        plugin lacks that rule
 * @param rules           every other rule, in injection order
 */
public record SessionRules(String readingGuidance, String rules)
{
	/**
	 * Creates the rules for one session.
	 *
	 * @param readingGuidance the harness rule that tells the agent how to read a file whole, which the agent needs
	 *                        before it can read the file that holds the other rules, or an empty value when the
	 *                        plugin lacks that rule
	 * @param rules           every other rule, in injection order
	 * @throws NullPointerException if any argument is null
	 */
	public SessionRules
	{
		requireThat(readingGuidance, "readingGuidance").isNotNull();
		requireThat(rules, "rules").isNotNull();
	}
}
