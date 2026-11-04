package com.holdmyspot.cat.common.installer.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The version of a CAT release, such as {@code 1.0} or {@code 1.0.1}: two or three dot-separated decimal numbers. The
 * release's tag is {@code v} followed by the version.
 * <p>
 * Versions compare number by number, most significant first, so {@code 1.9} is lower than {@code 1.10}. When two
 * versions agree on every number that both have, the one with fewer numbers is lower, so {@code 1.0} is lower than
 * {@code 1.0.0}. That keeps the order consistent with {@link #equals(Object)}: the two name different release tags.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ReleaseVersion implements Comparable<ReleaseVersion>
{
	/**
	 * The only spelling of a version that the installer accepts. Each number is {@code 0} or starts with a non-zero
	 * digit, so that a number has one spelling, and has at most nine digits, so that it fits in an {@code int}.
	 */
	private static final Pattern SYNTAX = Pattern.compile("(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){1,2}");
	/**
	 * The text that the version was parsed from.
	 */
	private final String text;
	/**
	 * The version's numbers, most significant first.
	 */
	private final List<Integer> numbers;

	/**
	 * Parses a version.
	 *
	 * @param text the version, such as {@code 1.0.1}
	 * @return the version
	 * @throws NullPointerException if {@code text} is null
	 * @throws TrustException       if {@code text} is not two or three dot-separated decimal numbers without leading
	 *                              zeros, signs, suffixes or empty parts
	 */
	public static ReleaseVersion parse(String text) throws TrustException
	{
		requireThat(text, "text").isNotNull();
		if (!SYNTAX.matcher(text).matches())
		{
			throw new TrustException(Reason.MALFORMED_VERSION, "A release version must be two or three " +
				"dot-separated decimal numbers without leading zeros, such as 1.0 or 1.0.1: \"" +
				TrustText.excerpt(text) + "\"");
		}
		List<Integer> numbers = new ArrayList<>();
		for (String number : text.split("\\.", -1))
			numbers.add(Integer.valueOf(number));
		return new ReleaseVersion(text, numbers);
	}

	/**
	 * Creates a version.
	 *
	 * @param text    the version's text
	 * @param numbers the version's numbers, most significant first
	 */
	private ReleaseVersion(String text, List<Integer> numbers)
	{
		this.text = text;
		this.numbers = List.copyOf(numbers);
	}

	/**
	 * Returns the version's first number, which a policy's {@code maximum_major} limits.
	 *
	 * @return the major version
	 */
	public int major()
	{
		return numbers.getFirst();
	}

	/**
	 * Returns the tag of the release that has this version.
	 *
	 * @return {@code v} followed by the version, such as {@code v1.0.1}
	 */
	public String tag()
	{
		return "v" + text;
	}

	@Override
	public int compareTo(ReleaseVersion other)
	{
		int common = Math.min(numbers.size(), other.numbers.size());
		for (int index = 0; index < common; ++index)
		{
			int difference = Integer.compare(numbers.get(index), other.numbers.get(index));
			if (difference != 0)
				return difference;
		}
		return Integer.compare(numbers.size(), other.numbers.size());
	}

	/**
	 * Indicates whether another object is the same version, spelled the same way. {@code 1.0} and {@code 1.0.0} are
	 * different versions, because they name different release tags.
	 *
	 * @param object the other object
	 * @return {@code true} if {@code object} is the same version
	 */
	@Override
	public boolean equals(Object object)
	{
		return object instanceof ReleaseVersion other && other.text.equals(text);
	}

	@Override
	public int hashCode()
	{
		return text.hashCode();
	}

	/**
	 * Returns the version's text.
	 *
	 * @return the version, such as {@code 1.0.1}
	 */
	@Override
	public String toString()
	{
		return text;
	}
}
