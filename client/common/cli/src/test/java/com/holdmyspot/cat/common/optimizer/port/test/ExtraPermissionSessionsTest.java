package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.ExtraPermissionSessions;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests that a port which applies extra permissions asks for them in every session it starts and otherwise behaves
 * as the port it wraps.
 */
public final class ExtraPermissionSessionsTest
{
	/**
	 * Creates the test.
	 */
	public ExtraPermissionSessionsTest()
	{
	}

	/**
	 * Returns a request that asks for no extra permissions.
	 *
	 * @param role the role of the session
	 * @param part the part of the contract that the session reads
	 * @return the request
	 */
	private static SessionRequest request(SessionRole role, Part part)
	{
		return new SessionRequest(role, part, Path.of("/staged").toAbsolutePath(), List.of("turn"), List.of(),
			Duration.ofMinutes(1), new TrialProfile("model-a", "low"));
	}

	/**
	 * Verifies that every session that the port starts, whatever its role, asks for the extra permissions, and that
	 * the wrapped port answers the port's other questions.
	 *
	 * @throws IOException if a session fails to start
	 */
	@Test
	public void everySessionAsksForExtraPermissions() throws IOException
	{
		FakeSessionPort wrapped = new FakeSessionPort();
		ExtraPermissions extra = new ExtraPermissions(List.of(Path.of("/cache/npm").toAbsolutePath()),
			Set.of("registry.example.com"));
		SessionPort port = new ExtraPermissionSessions(wrapped, extra);

		port.start(request(SessionRole.TRIAL, Part.DEVELOPMENT));
		port.start(request(SessionRole.JUDGE, Part.HELD_OUT));

		requireThat(wrapped.sessions().size(), "sessions.size()").isEqualTo(2);
		for (FakeSessionPort.FakeSession session : wrapped.sessions())
			requireThat(session.request().extraPermissions(), "extraPermissions").isEqualTo(extra);
		requireThat(port.judgeVersion(), "judgeVersion").isEqualTo(FakeSessionPort.JUDGE_VERSION);
	}

	/**
	 * Verifies that a port without extra permissions starts sessions that ask for none.
	 *
	 * @throws IOException if a session fails to start
	 */
	@Test
	public void noExtraPermissionsAskForNothing() throws IOException
	{
		FakeSessionPort wrapped = new FakeSessionPort();
		SessionPort port = new ExtraPermissionSessions(wrapped, ExtraPermissions.NONE);

		port.start(request(SessionRole.TRIAGE, Part.DEVELOPMENT));

		requireThat(wrapped.sessions().getFirst().request().extraPermissions(), "extraPermissions").
			isEqualTo(ExtraPermissions.NONE);
	}
}
