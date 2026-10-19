package com.holdmyspot.cat.common.git.replay;

import com.holdmyspot.cat.common.Identifier;

import java.io.IOException;
import java.util.Locale;

/**
 * Validates identifiers that reviewers name in topic-replay evidence, such as topic outputs and candidates.
 */
final class ReplayIdentifiers
{
	/**
	 * Prevents instantiation.
	 */
	private ReplayIdentifiers()
	{
	}

	/**
	 * Rejects a reviewer-named identifier that is not snake_case.
	 *
	 * @param kind     the capitalized name of the identified record, such as {@code Topic-output}
	 * @param id       the non-blank identifier
	 * @param location the artifact that declares the identifier, or an empty string if no artifact applies
	 * @throws IOException if {@code id} is not snake_case
	 */
	static void requireSnakeCase(String kind, String id, String location) throws IOException
	{
		if (Identifier.SNAKE_CASE.matcher(id).matches())
			return;
		StringBuilder message = new StringBuilder(kind).append(" id \"").append(id).append("\" must be snake_case: " +
			"lower-case letters and digits joined by single underscores");
		String suggestion = toSnakeCase(id);
		if (!suggestion.isEmpty())
			message.append(", such as \"").append(suggestion).append('"');
		if (!location.isEmpty())
			message.append(": ").append(location);
		throw new IOException(message.toString());
	}

	/**
	 * Spells an identifier in snake_case by splitting camelCase words and joining every word with one underscore.
	 *
	 * @param id the identifier
	 * @return the snake_case spelling, or an empty string if {@code id} has no letter or digit
	 */
	private static String toSnakeCase(String id)
	{
		String words = id.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
		return words.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
	}
}
