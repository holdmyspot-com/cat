package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.regex.Pattern;

/**
 * The spelling of an identifier that a contract names, such as a requirement, behavior, category, test, dimension or
 * value label.
 */
final class Identifier
{
	/**
	 * Lowercase letters and digits in words joined by single underscores, such as {@code wrong_tone}.
	 */
	static final Pattern SNAKE_CASE = Pattern.compile("[a-z0-9]+(_[a-z0-9]+)*");

	/**
	 * Prevents instantiation.
	 */
	private Identifier()
	{
	}
}
