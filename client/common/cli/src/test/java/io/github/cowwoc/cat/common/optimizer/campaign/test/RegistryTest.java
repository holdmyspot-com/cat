package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Registry.Opening;
import io.github.cowwoc.cat.common.optimizer.campaign.Registry;

import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link Registry}.
 */
public final class RegistryTest
{
	private static final Path ROOT = Path.of("/project");
	private static final Path TARGET = Path.of("/project/CLAUDE.md");

	/**
	 * Creates the test.
	 */
	public RegistryTest()
	{
	}

	/**
	 * Returns a header for the project's target.
	 *
	 * @param id the campaign's identifier
	 * @param harness the starting harness
	 * @param lifecycle the lifecycle state
	 * @return the header
	 */
	private static Header header(String id, Harness harness, Lifecycle lifecycle)
	{
		return new Header(id, ROOT, TARGET, harness, lifecycle);
	}

	/**
	 * Verifies that the registry selects creation when no resumable campaign exists for the root and target.
	 */
	@Test
	public void offersCreationWhenNoneExists()
	{
		// Requirement: R-L1
		requireThat(Registry.open(List.of(), ROOT, TARGET, Harness.CLAUDE), "empty").
			isEqualTo(new Opening.Create());
		Header otherTarget = new Header("c1", ROOT, Path.of("/project/AGENTS.md"), Harness.CLAUDE,
			Lifecycle.ACTIVE);
		requireThat(Registry.open(List.of(otherTarget), ROOT, TARGET, Harness.CLAUDE), "otherTarget").
			isEqualTo(new Opening.Create());
		Header otherRoot = new Header("c1", Path.of("/other"), TARGET, Harness.CLAUDE,
			Lifecycle.ACTIVE);
		requireThat(Registry.open(List.of(otherRoot), ROOT, TARGET, Harness.CLAUDE), "otherRoot").
			isEqualTo(new Opening.Create());
	}

	/**
	 * Verifies that the registry selects an active or cancelled campaign for its starting harness to resume.
	 */
	@Test
	public void offersResumeWithStartingHarness()
	{
		// Requirement: R-L1
		for (Lifecycle lifecycle : List.of(Lifecycle.ACTIVE, Lifecycle.CANCELLED))
		{
			Header header = header("c1", Harness.CODEX, lifecycle);
			requireThat(Registry.open(List.of(header), ROOT, TARGET, Harness.CODEX), lifecycle.name()).
				isEqualTo(new Opening.Resume(header));
		}
	}

	/**
	 * Verifies that the other harness is refused and shown the owning campaign.
	 */
	@Test
	public void refusesOtherHarness()
	{
		// Requirement: R-L1
		Header header = header("c1", Harness.CLAUDE, Lifecycle.CANCELLED);
		requireThat(Registry.open(List.of(header), ROOT, TARGET, Harness.CODEX), "opening").
			isEqualTo(new Opening.Refuse(header));
	}

	/**
	 * Verifies that a closed or finished campaign does not block selecting creation.
	 */
	@Test
	public void endedCampaignAllowsCreation()
	{
		// Requirement: R-L1
		List<Header> headers = List.of(header("c1", Harness.CLAUDE, Lifecycle.CLOSED),
			header("c2", Harness.CODEX, Lifecycle.FINISHED));
		requireThat(Registry.open(headers, ROOT, TARGET, Harness.CLAUDE), "opening").
			isEqualTo(new Opening.Create());
		Header active = header("c3", Harness.CLAUDE, Lifecycle.ACTIVE);
		requireThat(Registry.open(List.of(headers.get(0), active), ROOT, TARGET, Harness.CLAUDE),
			"withActive").isEqualTo(new Opening.Resume(active));
	}

	/**
	 * Verifies that two resumable campaigns for one root and target are reported as corrupt.
	 */
	@Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*Header\\[.*")
	public void twoResumableCampaignsAreRejected()
	{
		// Requirement: R-L1
		Registry.open(List.of(header("c1", Harness.CLAUDE, Lifecycle.ACTIVE),
			header("c2", Harness.CLAUDE, Lifecycle.CANCELLED)), ROOT, TARGET, Harness.CLAUDE);
	}
}
