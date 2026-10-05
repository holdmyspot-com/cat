package io.github.cowwoc.cat.common.optimizer.report;

import java.util.Locale;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Converts enum constant names into locale-independent lower case for displayed text and identifiers, and back.
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

	/**
	 * Returns the constant whose lower-case name equals a text.
	 *
	 * @param <E>  the constant's type
	 * @param text the lower-case name
	 * @param type the constant's type
	 * @return the constant
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if no constant of {@code type} has the lower-case name {@code text}
	 */
	public static <E extends Enum<E>> E parse(String text, Class<E> type)
	{
		requireThat(text, "text").isNotNull();
		for (E constant : type.getEnumConstants())
		{
			if (lowerCase(constant).equals(text))
				return constant;
		}
		throw new IllegalArgumentException(text + " is not a " + type.getSimpleName());
	}
}
