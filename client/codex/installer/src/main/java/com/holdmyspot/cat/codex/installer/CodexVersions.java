package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chooses the cache folder that Codex loads a plugin from, by the rule that Codex itself applies.
 * <p>
 * Codex considers the real directories whose names consist only of ASCII letters, digits, {@code .}, {@code +},
 * {@code _} and {@code -}, other than {@code .} and {@code ..}. A directory named {@code local} wins; otherwise the
 * greatest name wins, where two names that are both semantic versions compare by semantic version precedence, build
 * metadata included, and any other pair compares character by character.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class CodexVersions
{
	/**
	 * The folder name that a developer's local installation uses, which Codex loads in preference to any version.
	 */
	static final String LOCAL = "local";
	/**
	 * The characters that Codex allows in a version folder's name.
	 */
	private static final Pattern SEGMENT = Pattern.compile("[0-9A-Za-z._+-]+");
	/**
	 * A semantic version: three numbers without leading zeros, then optional pre-release and build identifiers.
	 */
	private static final Pattern SEMANTIC_VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\." +
		"(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
	/**
	 * An identifier that holds only digits.
	 */
	private static final Pattern NUMERIC = Pattern.compile("[0-9]+");
	/**
	 * Orders folder names as Codex orders them.
	 */
	static final Comparator<String> ORDER = CodexVersions::compare;

	/**
	 * Prevents instantiation.
	 */
	private CodexVersions()
	{
	}

	/**
	 * Returns the name of the folder that Codex loads.
	 *
	 * @param versions the folder whose children are the plugin's version folders
	 * @return the loaded folder's name, or empty if the folder has no version folder
	 * @throws NullPointerException if {@code versions} is null
	 * @throws IOException          if an existing folder cannot be listed
	 */
	static Optional<String> active(Path versions) throws IOException
	{
		requireThat(versions, "versions").isNotNull();
		if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		List<String> names;
		try (Stream<Path> entries = Files.list(versions))
		{
			names = entries.filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)).
				map(entry -> entry.getFileName().toString()).
				filter(CodexVersions::isVersionName).
				toList();
		}
		if (names.contains(LOCAL))
			return Optional.of(LOCAL);
		return names.stream().max(ORDER);
	}

	/**
	 * Returns whether Codex accepts a name as a version folder's name.
	 *
	 * @param name the name
	 * @return true if Codex accepts the name
	 */
	static boolean isVersionName(String name)
	{
		return !name.equals(".") && !name.equals("..") && SEGMENT.matcher(name).matches();
	}

	/**
	 * Compares two folder names as Codex does.
	 *
	 * @param left  a name
	 * @param right another name
	 * @return a negative number, zero or a positive number as {@code left} sorts before, with or after {@code right}
	 */
	static int compare(String left, String right)
	{
		Matcher leftVersion = SEMANTIC_VERSION.matcher(left);
		Matcher rightVersion = SEMANTIC_VERSION.matcher(right);
		if (!leftVersion.matches() || !rightVersion.matches() || !fitsUnsignedLong(leftVersion) ||
			!fitsUnsignedLong(rightVersion) || !validPreRelease(leftVersion.group(4)) ||
			!validPreRelease(rightVersion.group(4)))
		{
			return left.compareTo(right);
		}
		for (int group = 1; group <= 3; ++group)
		{
			int order = compareNumbers(leftVersion.group(group), rightVersion.group(group));
			if (order != 0)
				return order;
		}
		int order = comparePreRelease(leftVersion.group(4), rightVersion.group(4));
		if (order != 0)
			return order;
		return compareBuild(leftVersion.group(5), rightVersion.group(5));
	}

	/**
	 * Returns whether a semantic version's three numbers fit in an unsigned 64-bit integer, as Codex requires.
	 *
	 * @param version the matched semantic version
	 * @return true if every number fits
	 */
	private static boolean fitsUnsignedLong(Matcher version)
	{
		for (int group = 1; group <= 3; ++group)
		{
			try
			{
				Long.parseUnsignedLong(version.group(group));
			}
			catch (NumberFormatException _)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns whether a pre-release has no numeric identifier with a leading zero.
	 *
	 * @param preRelease the pre-release, or null if the version has none
	 * @return true if the pre-release is valid
	 */
	private static boolean validPreRelease(String preRelease)
	{
		if (preRelease == null)
			return true;
		for (String identifier : preRelease.split("\\.", -1))
		{
			if (identifier.length() > 1 && identifier.charAt(0) == '0' && NUMERIC.matcher(identifier).matches())
				return false;
		}
		return true;
	}

	/**
	 * Compares two decimal numbers without leading zeros.
	 *
	 * @param left  a number
	 * @param right another number
	 * @return the numbers' order
	 */
	private static int compareNumbers(String left, String right)
	{
		int order = Integer.compare(left.length(), right.length());
		if (order != 0)
			return order;
		return left.compareTo(right);
	}

	/**
	 * Compares two pre-releases. A version without a pre-release follows every version of the same numbers with one.
	 *
	 * @param left  a pre-release, or null for none
	 * @param right another pre-release, or null for none
	 * @return the pre-releases' order
	 */
	private static int comparePreRelease(String left, String right)
	{
		if (left == null || right == null)
		{
			if (left == null && right == null)
				return 0;
			if (left == null)
				return 1;
			return -1;
		}
		return compareIdentifiers(left, right, false);
	}

	/**
	 * Compares two build metadata strings. A version without build metadata precedes one with it.
	 *
	 * @param left  build metadata, or null for none
	 * @param right other build metadata, or null for none
	 * @return the build metadata's order
	 */
	private static int compareBuild(String left, String right)
	{
		if (left == null || right == null)
		{
			if (left == null && right == null)
				return 0;
			if (left == null)
				return -1;
			return 1;
		}
		return compareIdentifiers(left, right, true);
	}

	/**
	 * Compares dot-separated identifiers one by one. A numeric identifier precedes an alphanumeric one, two numeric
	 * identifiers compare by value, two alphanumeric identifiers compare character by character, and a list that is a
	 * prefix of the other precedes it.
	 *
	 * @param left         identifiers
	 * @param right        other identifiers
	 * @param leadingZeros true if numeric identifiers may have leading zeros, which then break ties by length
	 * @return the identifiers' order
	 */
	private static int compareIdentifiers(String left, String right, boolean leadingZeros)
	{
		String[] leftIdentifiers = left.split("\\.", -1);
		String[] rightIdentifiers = right.split("\\.", -1);
		int common = Math.min(leftIdentifiers.length, rightIdentifiers.length);
		for (int index = 0; index < common; ++index)
		{
			int order = compareIdentifier(leftIdentifiers[index], rightIdentifiers[index], leadingZeros);
			if (order != 0)
				return order;
		}
		return Integer.compare(leftIdentifiers.length, rightIdentifiers.length);
	}

	/**
	 * Compares two identifiers.
	 *
	 * @param left         an identifier
	 * @param right        another identifier
	 * @param leadingZeros true if numeric identifiers may have leading zeros
	 * @return the identifiers' order
	 */
	private static int compareIdentifier(String left, String right, boolean leadingZeros)
	{
		boolean leftNumeric = NUMERIC.matcher(left).matches();
		boolean rightNumeric = NUMERIC.matcher(right).matches();
		if (leftNumeric && rightNumeric)
		{
			if (!leadingZeros)
				return compareNumbers(left, right);
			int order = compareNumbers(stripLeadingZeros(left), stripLeadingZeros(right));
			if (order != 0)
				return order;
			return Integer.compare(left.length(), right.length());
		}
		if (leftNumeric)
			return -1;
		if (rightNumeric)
			return 1;
		return left.compareTo(right);
	}

	/**
	 * Removes a number's leading zeros.
	 *
	 * @param number a decimal number
	 * @return the number without leading zeros, which is empty for zero
	 */
	private static String stripLeadingZeros(String number)
	{
		int start = 0;
		while (start < number.length() && number.charAt(start) == '0')
			++start;
		return number.substring(start);
	}
}
