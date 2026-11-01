package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionRules;
import java.time.ZoneId;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests timezone context in {@link SessionRules}.
 */
public final class SessionRulesTest
{
	/**
	 * Supplies session zones without reading or changing the process's timezone.
	 *
	 * @return region, fixed-offset, and UTC zones
	 */
	@DataProvider
	public static Object[][] timezones()
	{
		return new Object[][] {{"America/Toronto"}, {"+05:45"}, {"UTC"}};
	}

	/**
	 * Verifies an explicitly supplied session zone is stated after the agent's existing rules.
	 *
	 * @param timezone the supplied session zone
	 */
	@Test(dataProvider = "timezones")
	public void statesSuppliedTimezone(String timezone)
	{
		String context = SessionRules.withTimezone("agent guidance", ZoneId.of(timezone));

		requireThat(context, "context").isEqualTo("""
			agent guidance

			# Session Timezone

			The session's effective local timezone is `%s`.
			""".formatted(timezone));
	}
}
