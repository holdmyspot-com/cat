package com.holdmyspot.cat.common.updater.trust.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link ReleaseVersion}: which release versions the updater accepts and how it orders them.
 */
public final class ReleaseVersionTest
{
	/**
	 * Creates a test instance.
	 */
	public ReleaseVersionTest()
	{
	}

	/**
	 * Two- and three-number versions parse, give their major number and their tag, and compare number by number.
	 *
	 * @throws TrustException if a version is refused
	 */
	@Test
	public void parsesAndComparesNumerically() throws TrustException
	{
		ReleaseVersion version = ReleaseVersion.parse("10.0.1");

		requireThat(version.major(), "major").isEqualTo(10);
		requireThat(version.tag(), "tag").isEqualTo("v10.0.1");
		requireThat(ReleaseVersion.parse("1.9").compareTo(ReleaseVersion.parse("1.10")), "oneNineVersusOneTen").
			isNegative();
		requireThat(ReleaseVersion.parse("2.0").compareTo(ReleaseVersion.parse("1.99.99")), "twoVersusOneNinetyNine").
			isPositive();
		requireThat(ReleaseVersion.parse("1.0").compareTo(ReleaseVersion.parse("1.0.1")), "twoNumbersVersusThree").
			isNegative();
		requireThat(ReleaseVersion.parse("0.0").compareTo(ReleaseVersion.parse("0.0")), "zeroVersusZero").isZero();
	}

	/**
	 * Every spelling other than two or three dot-separated decimal numbers without leading zeros is refused.
	 */
	@Test
	public void refusesOtherForms()
	{
		List<String> malformed = List.of("1", "1.2.3.40.5", "01.0", "1.00", "1..0", ".1.0", "1.0.", "+1.0", "-1.0",
			"1.0-beta", "1.0a", "v1.0", " 1.0", "1.0\n", "", "1.0x0", "1234567890.0", "١.0");
		for (String text : malformed)
		{
			try
			{
				ReleaseVersion.parse(text);
				throw new AssertionError("Accepted \"" + text + "\"");
			}
			catch (TrustException e)
			{
				requireThat(e.reason(), "reason").isEqualTo(Reason.MALFORMED_VERSION);
			}
		}
	}
}
