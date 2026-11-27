package com.holdmyspot.cat.codex.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.GlobDenials;
import org.testng.annotations.Test;

/**
 * Tests {@link GlobDenials}.
 */
public final class GlobDenialsTest
{
	/**
	 * Creates a test instance.
	 */
	public GlobDenialsTest()
	{
	}

	/**
	 * Verifies that a pattern that names folders with the separator of Windows reaches the file system's glob matcher
	 * with slashes, because that matcher reads a backslash as the escape of the character after it and so would
	 * never match the folders that the pattern names.
	 */
	@Test
	public void windowsSeparatorsBecomeSlashes()
	{
		requireThat(GlobDenials.matcherPattern("C:\\Users\\someone\\project/secret-*", "\\"), "pattern").
			isEqualTo("C:/Users/someone/project/secret-*");
	}

	/**
	 * Verifies that a pattern keeps its backslashes where the separator is a slash, because there a backslash escapes
	 * the wildcard after it.
	 */
	@Test
	public void slashSeparatorKeepsEscapes()
	{
		requireThat(GlobDenials.matcherPattern("/home/someone/project/secret\\*", "/"), "pattern").
			isEqualTo("/home/someone/project/secret\\*");
	}
}
