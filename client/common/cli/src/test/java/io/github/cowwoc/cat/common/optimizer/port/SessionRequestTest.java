package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies which parts each session role may request.
 */
public final class SessionRequestTest
{
	/**
	 * Creates the test.
	 */
	public SessionRequestTest()
	{
	}

	/**
	 * Returns a request.
	 *
	 * @param role the session's role
	 * @param part the part that the session may read
	 * @return the request
	 */
	private static SessionRequest request(SessionRole role, Part part)
	{
		return new SessionRequest(role, part, Path.of("/staged"), List.of("turn"), List.of(), Duration.ofMinutes(1),
			TrialProfile.HARNESS_DEFAULT);
	}

	/**
	 * Returns the role and part combinations that a request accepts.
	 *
	 * @return each role with a part that it may request
	 */
	@DataProvider
	public static Object[][] accepted()
	{
		return new Object[][]
		{
			{SessionRole.TRIAL, Part.DEVELOPMENT},
			{SessionRole.TRIAL, Part.HELD_OUT},
			{SessionRole.JUDGE, Part.HELD_OUT},
			{SessionRole.TRIAGE, Part.DEVELOPMENT},
			{SessionRole.AUTHOR, Part.DEVELOPMENT},
			{SessionRole.DERIVER, Part.DEVELOPMENT}
		};
	}

	/**
	 * Returns the role and part combinations that a request refuses.
	 *
	 * @return each role with a part that it may not request
	 */
	@DataProvider
	public static Object[][] refused()
	{
		return new Object[][]
		{
			{SessionRole.JUDGE, Part.DEVELOPMENT},
			{SessionRole.TRIAGE, Part.HELD_OUT},
			{SessionRole.AUTHOR, Part.HELD_OUT},
			{SessionRole.DERIVER, Part.HELD_OUT}
		};
	}

	/**
	 * Verifies that a trial may request either part, that a judge requests the held-out part, and that triage,
	 * authors and the deriver request the development part.
	 *
	 * @param role the session's role
	 * @param part the part that the session may read
	 */
	@Test(dataProvider = "accepted")
	public void acceptsPartOfRole(SessionRole role, Part part)
	{
		// Requirement: R-E24
		requireThat(request(role, part).part(), "part").isEqualTo(part);
	}

	/**
	 * Verifies that a judge cannot request the development part, and that triage, authors and the deriver cannot
	 * request the held-out part.
	 *
	 * @param role the session's role
	 * @param part the part that the session may not read
	 */
	@Test(dataProvider = "refused", expectedExceptions = IllegalArgumentException.class)
	public void refusesPartOutsideRole(SessionRole role, Part part)
	{
		// Requirement: R-E24
		request(role, part);
	}
}
