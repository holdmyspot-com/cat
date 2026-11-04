package com.holdmyspot.cat.common.installation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.regex.Pattern;

/**
 * The version of a CAT plugin: the release version, then {@link #BUILD_SEPARATOR}, then the release commit's first
 * twelve lower-case hexadecimal digits, such as {@code 1.0+b0123456789ab}. A harness installs each plugin version into
 * its own folder, so a release that is published again from another commit installs beside the earlier one.
 * <p>
 * Two plugin versions are equal only when both the release and the commit are equal.
 *
 * @param release the release version, such as {@code 1.0} or {@code 1.0.1}
 * @param commit  the release commit's first twelve lower-case hexadecimal digits
 */
public record PluginVersion(String release, String commit)
{
	/**
	 * The text between the release version and the commit.
	 */
	public static final String BUILD_SEPARATOR = "+b";
	/**
	 * The only spelling of a release version: two or three dot-separated decimal numbers, each {@code 0} or starting
	 * with a non-zero digit.
	 */
	private static final Pattern RELEASE = Pattern.compile("(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){1,2}");
	/**
	 * The only spelling of a commit: twelve lower-case hexadecimal digits. Twelve digits keep the version folder's
	 * paths within the Windows path length limit.
	 */
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{12}");

	/**
	 * Creates a plugin version.
	 *
	 * @param release the release version, such as {@code 1.0} or {@code 1.0.1}
	 * @param commit  the release commit's first twelve lower-case hexadecimal digits
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code release} is not two or three dot-separated decimal numbers without
	 *                                  leading zeros, or {@code commit} is not twelve lower-case hexadecimal digits
	 */
	public PluginVersion
	{
		requireThat(release, "release").matches(RELEASE);
		requireThat(commit, "commit").matches(COMMIT);
	}

	/**
	 * Parses a plugin version.
	 *
	 * @param text the plugin version, such as {@code 1.0+b0123456789ab}
	 * @return the plugin version
	 * @throws NullPointerException     if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is not a release version, {@link #BUILD_SEPARATOR} and twelve
	 *                                  lower-case hexadecimal digits
	 */
	public static PluginVersion parse(String text)
	{
		requireThat(text, "text").isNotNull();
		int separator = text.indexOf(BUILD_SEPARATOR);
		if (separator == -1 || !RELEASE.matcher(text.substring(0, separator)).matches() ||
			!COMMIT.matcher(text.substring(separator + BUILD_SEPARATOR.length())).matches())
		{
			throw new IllegalArgumentException("A plugin version must be a release version such as 1.0, then \"" +
				BUILD_SEPARATOR + "\", then twelve lower-case hexadecimal digits of the release commit, such as 1.0" +
				BUILD_SEPARATOR + "0123456789ab: \"" + text + "\"");
		}
		return new PluginVersion(text.substring(0, separator), text.substring(separator + BUILD_SEPARATOR.length()));
	}

	/**
	 * Returns the plugin version's text, which also names its version folder.
	 *
	 * @return the release version, {@link #BUILD_SEPARATOR} and the commit, such as {@code 1.0+b0123456789ab}
	 */
	@Override
	public String toString()
	{
		return release + BUILD_SEPARATOR + commit;
	}
}
