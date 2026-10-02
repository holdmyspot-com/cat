package io.github.cowwoc.cat.common.optimizer.report;

import java.util.Locale;

/**
 * Converts enum constant names into locale-independent lower case for displayed text and identifiers.
 */
public final class EnumNames
{
	/**
	 * Prevents instantiation.
	 */
	private EnumNames()
	{
	}

	/**
	 * Returns an enum constant's name in locale-independent lower case, preserving underscores.
	 *
	 * @param value the enum constant whose name is converted
	 * @return the lower-case name
	 * @throws NullPointerException if {@code value} is null
	 */
	public static String lowerCase(Enum<?> value)
	{
		return value.name().toLowerCase(Locale.ROOT);
	}
}
