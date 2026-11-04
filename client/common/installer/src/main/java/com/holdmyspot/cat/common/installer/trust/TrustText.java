package com.holdmyspot.cat.common.installer.trust;

/**
 * Shortens untrusted text before a refusal message quotes it, so that a hostile manifest or policy cannot fill the
 * user's terminal or the update log.
 */
final class TrustText
{
	/**
	 * The most characters of an untrusted value that a refusal message quotes.
	 */
	private static final int EXCERPT_LENGTH = 80;

	/**
	 * Returns the start of untrusted text, for quoting in a message.
	 *
	 * @param text the text
	 * @return {@code text}, or its first 80 characters followed by {@code ...} when it is longer
	 */
	static String excerpt(String text)
	{
		if (text.length() <= EXCERPT_LENGTH)
			return text;
		return text.substring(0, EXCERPT_LENGTH) + "...";
	}

	/**
	 * Prevents instantiation.
	 */
	private TrustText()
	{
	}
}
