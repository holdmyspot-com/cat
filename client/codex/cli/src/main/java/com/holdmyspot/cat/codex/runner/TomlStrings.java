package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Writes values into Codex's {@code config.toml} as TOML basic strings.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TomlStrings
{
	/**
	 * Prevents instantiation.
	 */
	private TomlStrings()
	{
	}

	/**
	 * Escapes a value for use between the quotes of a TOML basic string, so that a TOML parser reads back the value.
	 * TOML requires the escape of the quote, the backslash and every control character, which includes the newline
	 * that a POSIX path may contain.
	 *
	 * @param value the value
	 * @return the escaped value, without quotes
	 * @throws NullPointerException if {@code value} is null
	 */
	public static String escape(String value)
	{
		requireThat(value, "value").isNotNull();
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); ++i)
		{
			char character = value.charAt(i);
			switch (character)
			{
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\t' -> escaped.append("\\t");
				case '\n' -> escaped.append("\\n");
				case '\f' -> escaped.append("\\f");
				case '\r' -> escaped.append("\\r");
				default ->
				{
					if (character < 0x20 || character == 0x7F)
						escaped.append("\\u%04X".formatted((int) character));
					else
						escaped.append(character);
				}
			}
		}
		return escaped.toString();
	}
}
