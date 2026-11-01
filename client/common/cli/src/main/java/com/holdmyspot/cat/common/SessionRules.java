package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.ZoneId;

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
	 * Adds the session's effective local timezone to the rules an agent receives.
	 *
	 * @param rules    the agent's existing rules, which may be empty
	 * @param timezone the session's effective local timezone
	 * @return the existing rules followed by timezone context
	 * @throws NullPointerException if any argument is null
	 */
	public static String withTimezone(String rules, ZoneId timezone)
	{
		requireThat(rules, "rules").isNotNull();
		requireThat(timezone, "timezone").isNotNull();
		return """
			%s

			# Session Timezone

			The session's effective local timezone is `%s`.
			""".formatted(rules, timezone.getId());
	}

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
