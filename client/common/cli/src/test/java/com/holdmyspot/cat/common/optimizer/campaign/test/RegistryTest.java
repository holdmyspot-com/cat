package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Registry.Opening;
import com.holdmyspot.cat.common.optimizer.campaign.Registry.OwnedHeader;
import com.holdmyspot.cat.common.optimizer.campaign.Registry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests {@link Registry}.
 */
public final class RegistryTest
{
	private static final Path ROOT = Path.of("/project").toAbsolutePath();
	private static final Path TARGET = Path.of("/project/CLAUDE.md").toAbsolutePath();
	/**
	 * The session that asks.
	 */
	private static final String SESSION = "3d9a7f2e-1c4b-4e8a-9f6d-2b5c8e1a7d40";
	/**
	 * A session other than the one that asks.
	 */
	private static final String OTHER_SESSION = "8c2e5a1f-7d3b-4f9e-a6c1-4e7b2d9f5a83";

	/**
	 * Creates the test.
	 */
	public RegistryTest()
	{
	}

	/**
	 * Returns a header for the project's target, owned by the session that asks.
	 *
	 * @param id the campaign's identifier
	 * @param harness the starting harness
	 * @param lifecycle the lifecycle state
	 * @return the header
	 */
	private static OwnedHeader header(String id, Harness harness, Lifecycle lifecycle)
	{
		return header(SESSION, id, harness, lifecycle);
	}

	/**
	 * Returns a header for the project's target.
	 *
	 * @param sessionId the ID of the session that owns the campaign
	 * @param id the campaign's identifier
	 * @param harness the starting harness
	 * @param lifecycle the lifecycle state
	 * @return the header
	 */
	private static OwnedHeader header(String sessionId, String id, Harness harness, Lifecycle lifecycle)
	{
		return new OwnedHeader(sessionId, new Header(id, ROOT, TARGET, harness, lifecycle));
	}

	/**
	 * Verifies that the registry selects creation when no resumable campaign exists for the root and target.
	 */
	@Test
	public void offersCreationWhenNoneExists()
	{
		// Requirement: R-L1
		requireThat(Registry.open(List.of(), SESSION, ROOT, TARGET, Harness.CLAUDE), "empty").
			isEqualTo(new Opening.Create());
		OwnedHeader otherTarget = new OwnedHeader(SESSION, new Header("c1", ROOT,
			Path.of("/project/AGENTS.md").toAbsolutePath(), Harness.CLAUDE, Lifecycle.ACTIVE));
		requireThat(Registry.open(List.of(otherTarget), SESSION, ROOT, TARGET, Harness.CLAUDE), "otherTarget").
			isEqualTo(new Opening.Create());
		OwnedHeader otherRoot = new OwnedHeader(OTHER_SESSION, new Header("c1", Path.of("/other").toAbsolutePath(),
			TARGET, Harness.CLAUDE, Lifecycle.ACTIVE));
		requireThat(Registry.open(List.of(otherRoot), SESSION, ROOT, TARGET, Harness.CLAUDE), "otherRoot").
			isEqualTo(new Opening.Create());
	}

	/**
	 * Verifies that the registry selects an active or cancelled campaign for its owning session and starting harness
	 * to resume.
	 */
	@Test
	public void offersResumeWithStartingHarness()
	{
		// Requirement: R-L1
		for (Lifecycle lifecycle : List.of(Lifecycle.ACTIVE, Lifecycle.CANCELLED))
		{
			OwnedHeader header = header("c1", Harness.CODEX, lifecycle);
			requireThat(Registry.open(List.of(header), SESSION, ROOT, TARGET, Harness.CODEX), lifecycle.name()).
				isEqualTo(new Opening.Resume(header.header()));
		}
	}

	/**
	 * Verifies that the other harness is refused and shown the owning campaign.
	 */
	@Test
	public void refusesOtherHarness()
	{
		// Requirement: R-L1
		OwnedHeader header = header("c1", Harness.CLAUDE, Lifecycle.CANCELLED);
		requireThat(Registry.open(List.of(header), SESSION, ROOT, TARGET, Harness.CODEX), "opening").
			isEqualTo(new Opening.Refuse(header));
	}

	/**
	 * Verifies that a session other than the owning one is refused and shown the owning session, even when it runs in
	 * the starting harness.
	 */
	@Test
	public void refusesOtherSession()
	{
		// Requirement: R-L1
		for (Lifecycle lifecycle : List.of(Lifecycle.ACTIVE, Lifecycle.CANCELLED))
		{
			OwnedHeader header = header(OTHER_SESSION, "c1", Harness.CLAUDE, lifecycle);
			Opening opening = Registry.open(List.of(header), SESSION, ROOT, TARGET, Harness.CLAUDE);
			requireThat(opening, lifecycle.name()).isEqualTo(new Opening.Refuse(header));
			requireThat(((Opening.Refuse) opening).owner().sessionId(), "sessionId").isEqualTo(OTHER_SESSION);
		}
	}

	/**
	 * Verifies that another session's ended campaign does not block selecting creation.
	 */
	@Test
	public void otherSessionEndedAllowsCreation()
	{
		// Requirement: R-L1
		List<OwnedHeader> headers = List.of(header(OTHER_SESSION, "c1", Harness.CLAUDE, Lifecycle.CLOSED),
			header(OTHER_SESSION, "c2", Harness.CLAUDE, Lifecycle.FINISHED));
		requireThat(Registry.open(headers, SESSION, ROOT, TARGET, Harness.CLAUDE), "opening").
			isEqualTo(new Opening.Create());
	}

	/**
	 * Verifies that a closed or finished campaign does not block selecting creation.
	 */
	@Test
	public void endedCampaignAllowsCreation()
	{
		// Requirement: R-L1
		List<OwnedHeader> headers = List.of(header("c1", Harness.CLAUDE, Lifecycle.CLOSED),
			header("c2", Harness.CODEX, Lifecycle.FINISHED));
		requireThat(Registry.open(headers, SESSION, ROOT, TARGET, Harness.CLAUDE), "opening").
			isEqualTo(new Opening.Create());
		OwnedHeader active = header("c3", Harness.CLAUDE, Lifecycle.ACTIVE);
		requireThat(Registry.open(List.of(headers.get(0), active), SESSION, ROOT, TARGET, Harness.CLAUDE),
			"withActive").isEqualTo(new Opening.Resume(active.header()));
	}

	/**
	 * Verifies that two resumable campaigns for one root and target are reported as corrupt, even when two sessions
	 * own them.
	 */
	@Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*Header\\[.*")
	public void twoResumableCampaignsAreRejected()
	{
		// Requirement: R-L1
		Registry.open(List.of(header("c1", Harness.CLAUDE, Lifecycle.ACTIVE),
			header(OTHER_SESSION, "c2", Harness.CLAUDE, Lifecycle.CANCELLED)), SESSION, ROOT, TARGET, Harness.CLAUDE);
	}

	/**
	 * Verifies that a target's campaign is found in any session: its resumable campaign first, or else the latest to
	 * start.
	 */
	@Test
	public void findsTargetInEverySession()
	{
		// Requirement: R-L1
		OwnedHeader closed = header("20261001T000000Z-aaaaaa", Harness.CLAUDE, Lifecycle.CLOSED);
		OwnedHeader later = header(OTHER_SESSION, "20261002T000000Z-bbbbbb", Harness.CLAUDE, Lifecycle.FINISHED);
		requireThat(Registry.ofTarget(List.of(closed, later), ROOT, TARGET), "latest").
			isEqualTo(Optional.of(later));
		OwnedHeader active = header(OTHER_SESSION, "20260930T000000Z-cccccc", Harness.CODEX, Lifecycle.ACTIVE);
		requireThat(Registry.ofTarget(List.of(closed, later, active), ROOT, TARGET), "resumable").
			isEqualTo(Optional.of(active));
		requireThat(Registry.ofTarget(List.of(), ROOT, TARGET), "none").isEqualTo(Optional.empty());
	}
}
