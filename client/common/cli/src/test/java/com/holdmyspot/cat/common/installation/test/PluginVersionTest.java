package com.holdmyspot.cat.common.installation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.PluginVersion;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link PluginVersion}.
 */
public final class PluginVersionTest
{
	/**
	 * A plugin version's text parses into its release and commit and formats back unchanged.
	 */
	@Test
	public void parseAndFormatRoundTrip()
	{
		PluginVersion version = PluginVersion.parse("1.0+b0123456789ab");

		requireThat(version.release(), "version.release()").isEqualTo("1.0");
		requireThat(version.commit(), "version.commit()").isEqualTo("0123456789ab");
		requireThat(version.toString(), "version.toString()").isEqualTo("1.0+b0123456789ab");
		requireThat(new PluginVersion("1.0.1", "fedcba987654").toString(), "formatted").
			isEqualTo("1.0.1+bfedcba987654");
	}

	/**
	 * Two builds of the same release are different plugin versions, and the same build is equal.
	 */
	@Test
	public void buildsOfOneReleaseDiffer()
	{
		PluginVersion first = PluginVersion.parse("1.0+b0123456789ab");
		PluginVersion second = PluginVersion.parse("1.0+b0123456789ac");

		requireThat(first, "first").isNotEqualTo(second, "second");
		requireThat(first.release(), "first.release()").isEqualTo(second.release(), "second.release()");
		requireThat(first, "first").isEqualTo(PluginVersion.parse("1.0+b0123456789ab"), "same");
	}

	/**
	 * Returns plugin versions that are malformed.
	 *
	 * @return the malformed texts
	 */
	@DataProvider
	public static Object[][] malformed()
	{
		return new Object[][]
			{
				{"1.0"},
				{"1.0+0123456789ab"},
				{"1.0-b0123456789ab"},
				{"1.0+b0123456789a"},
				{"1.0+b0123456789abc"},
				{"1.0+b0123456789AB"},
				{"1.0+b0123456789ag"},
				{"01.0+b0123456789ab"},
				{"1+b0123456789ab"},
				{"1.0.0.0+b0123456789ab"},
				{"+b0123456789ab"}
			};
	}

	/**
	 * A text whose release or build is malformed is refused.
	 *
	 * @param text the malformed text
	 */
	@Test(dataProvider = "malformed", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "A plugin version must be .*")
	public void refusesMalformedText(String text)
	{
		PluginVersion.parse(text);
	}

	/**
	 * A commit that is not twelve lower-case hexadecimal digits is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesMalformedCommit()
	{
		new PluginVersion("1.0", "0123456789AB");
	}
}
