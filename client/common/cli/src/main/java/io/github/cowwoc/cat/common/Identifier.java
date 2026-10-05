package io.github.cowwoc.cat.common;

import java.util.regex.Pattern;

/**
 * The spelling of an identifier that CAT, its users or its models name, such as a requirement, behavior, category,
 * test, dimension, value label, topic output or review candidate.
 */
public final class Identifier
{
	/**
	 * Lowercase letters and digits in words joined by single underscores, such as {@code wrong_tone}.
	 */
	public static final Pattern SNAKE_CASE = Pattern.compile("[a-z0-9]+(_[a-z0-9]+)*");

	/**
	 * Prevents instantiation.
	 */
	private Identifier()
	{
	}
}
