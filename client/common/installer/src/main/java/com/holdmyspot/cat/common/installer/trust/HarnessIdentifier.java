package com.holdmyspot.cat.common.installer.trust;

import java.util.regex.Pattern;

/**
 * The spelling of a harness's identifier in release manifests, trust policies and the installer's {@code --harness}
 * option. The installer's core treats it as an opaque name; the harness's activation provider gives it meaning.
 */
public final class HarnessIdentifier
{
	/**
	 * A lowercase letter followed by at most 31 lowercase letters, digits or hyphens, such as {@code harness-a}. Release
	 * asset names and state directory names contain the identifier, so it excludes path separators, dots and spaces.
	 */
	public static final Pattern SYNTAX = Pattern.compile("[a-z][a-z0-9-]{0,31}");

	/**
	 * Prevents instantiation.
	 */
	private HarnessIdentifier()
	{
	}
}
