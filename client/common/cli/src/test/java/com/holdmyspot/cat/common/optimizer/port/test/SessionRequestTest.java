package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies which parts each session role may request.
 */
public final class SessionRequestTest
{
	/**
	 * The model and reasoning effort of a session whose profile the test does not examine.
	 */
	private static final TrialProfile SESSION_PROFILE = new TrialProfile("model-a", "low");
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
		return new SessionRequest(role, part, Path.of("/staged").toAbsolutePath(), List.of("turn"), List.of(),
			Duration.ofMinutes(1), SESSION_PROFILE);
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
			{SessionRole.PROMPT_AUTHOR, Part.DEVELOPMENT},
			{SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT}
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
			{SessionRole.PROMPT_AUTHOR, Part.HELD_OUT},
			{SessionRole.CONTRACT_AUTHOR, Part.HELD_OUT}
		};
	}

	/**
	 * Verifies that a trial may request either part, that a judge requests the held-out part, and that triage,
	 * prompt authors and the contract author request the development part.
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
	 * Verifies that a judge cannot request the development part, and that triage, prompt authors and the contract
	 * author cannot request the held-out part.
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

	/**
	 * Verifies that only a trial's workspace may receive fixture files.
	 */
	@Test
	public void refusesFixturesOutsideTrial()
	{
		// Requirement: R-C21
		TestWorkspace files = new TestWorkspace(Map.of("notes.md", new WorkspaceFile("notes.md", "notes")),
			Map.of(), "outcome");
		SessionRequest trial = new SessionRequest(SessionRole.TRIAL, Part.DEVELOPMENT, Path.of("/staged").toAbsolutePath(),
			List.of("turn"), List.of(), Duration.ofMinutes(1), SESSION_PROFILE, files);
		requireThat(trial.files(), "files").isEqualTo(files);
		try
		{
			new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, Path.of("/staged").toAbsolutePath(), List.of("turn"),
				List.of(), Duration.ofMinutes(1), SESSION_PROFILE, files);
			throw new AssertionError("A judge received fixture files");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("JUDGE");
		}
	}

	/**
	 * Verifies that the contract author's workspace may receive files, the draft directory that it writes prompt tests
	 * into.
	 */
	@Test
	public void acceptsContractAuthorFiles()
	{
		TestWorkspace files = new TestWorkspace(Map.of("notes.md", new WorkspaceFile("notes.md", "notes")),
			Map.of(), "outcome");
		SessionRequest request = new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT,
			Path.of("/staged").toAbsolutePath(), List.of("turn"), List.of(), Duration.ofMinutes(1),
			SESSION_PROFILE, files);
		requireThat(request.files(), "files").isEqualTo(files);
	}
}
