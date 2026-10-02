package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests how warnings about unauthorized edits make the campaign wait for a user choice.
 */
public final class AuthorityWarningsTest
{
	private static final Path TARGET = Path.of("/external/shared.md");
	private static final Path OTHER_TARGET = Path.of("/external/other.md");
	private static final List<AuthorityWarning.Choice> WITH_AUTHORIZATION = List.of(
		AuthorityWarning.Choice.AUTHORIZE_TARGET, AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
		AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN);
	private static final List<AuthorityWarning.Choice> WITHOUT_AUTHORIZATION = List.of(
		AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
		AuthorityWarning.Choice.REVISE_CAMPAIGN);

	/**
	 * Creates the test.
	 */
	public AuthorityWarningsTest()
	{
	}

	/**
	 * Returns a warning that a content edit through a link reaches an external target that is read-only.
	 *
	 * @param link the link path
	 * @param target the link's resolved target
	 * @return the warning
	 */
	private static AuthorityWarning readOnly(String link, Path target)
	{
		return new AuthorityWarning("R1", Path.of(link), target, Limit.EXTERNAL_TARGET_READ_ONLY, WITH_AUTHORIZATION);
	}

	/**
	 * Returns a warning that deleting an external target through a link is a structural edit.
	 *
	 * @param link the link path
	 * @param target the link's resolved target
	 * @return the warning
	 */
	private static AuthorityWarning structural(String link, Path target)
	{
		return new AuthorityWarning("R1", Path.of(link), target, Limit.EXTERNAL_STRUCTURE_FIXED,
			WITHOUT_AUTHORIZATION);
	}

	/**
	 * Verifies that a warning becomes the pending decision and offers authorizing the target, using an authorized
	 * source and stopping.
	 */
	@Test
	public void warningAwaitsTheUser()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).build();
		List<AuthorityWarning> warnings = List.of(readOnly("shared.md", TARGET));

		Campaign warned = CampaignTransitions.apply(campaign, new CampaignEvent.WarnAuthority(warnings));

		requireThat(warned.pending(), "pending").isEqualTo(new PendingDecision.AuthorityWarnings(warnings));
		requireThat(warned.choices(), "choices").
			containsExactly(List.of(Choice.AUTHORIZE_TARGET, Choice.USE_AUTHORIZED_SOURCE, Choice.STOP));
		requireThat(warned.authorizedTargets(), "authorizedTargets").isEmpty();
	}

	/**
	 * Verifies that a warning whose limit authorization cannot lift does not offer authorizing the target.
	 */
	@Test
	public void structuralWarningOffersNoAuthorization()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).
			pending(new PendingDecision.AuthorityWarnings(List.of(structural("shared.md", TARGET)))).build();

		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP));
	}

	/**
	 * Verifies that authorizing the warned target records it in the campaign and answers the decision.
	 */
	@Test
	public void authorizeRecordsTheTarget()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).
			pending(new PendingDecision.AuthorityWarnings(List.of(readOnly("shared.md", TARGET)))).build();

		Campaign authorized = CampaignTransitions.apply(campaign, CampaignEvent.Choose.authorize(TARGET));

		requireThat(authorized.authorizedTargets(), "authorizedTargets").isEqualTo(Set.of(TARGET));
		requireThat(authorized.pending(), "pending").isNull();
		requireThat(authorized.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
	}

	/**
	 * Verifies that authorizing one target leaves the warnings about other targets pending.
	 */
	@Test
	public void authorizeKeepsTheOtherWarnings()
	{
		// Requirement: R-W4
		AuthorityWarning other = readOnly("other.md", OTHER_TARGET);
		Campaign campaign = new CampaignBuilder().confirmed().working(1).
			pending(new PendingDecision.AuthorityWarnings(List.of(readOnly("shared.md", TARGET), other))).build();

		Campaign authorized = CampaignTransitions.apply(campaign, CampaignEvent.Choose.authorize(TARGET));

		requireThat(authorized.authorizedTargets(), "authorizedTargets").isEqualTo(Set.of(TARGET));
		requireThat(authorized.pending(), "pending").isEqualTo(new PendingDecision.AuthorityWarnings(List.of(other)));
	}

	/**
	 * Verifies that authorizing a target that no pending warning names is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void authorizeRefusesUnwarnedTarget()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).
			pending(new PendingDecision.AuthorityWarnings(List.of(readOnly("shared.md", TARGET)))).build();

		CampaignTransitions.apply(campaign, CampaignEvent.Choose.authorize(OTHER_TARGET));
	}

	/**
	 * Verifies that authorizing an already authorized target again cannot permit a structural edit: the warning about
	 * deleting it does not offer authorization, and choosing it anyway is refused.
	 */
	@Test
	public void reauthorizingKeepsStructureFixed()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).authorizedTarget(TARGET).
			pending(new PendingDecision.AuthorityWarnings(List.of(structural("shared.md", TARGET)))).build();
		requireThat(campaign.choices(), "choices").doesNotContain(Choice.AUTHORIZE_TARGET);

		IllegalArgumentException refusal = null;
		try
		{
			CampaignTransitions.apply(campaign, CampaignEvent.Choose.authorize(TARGET));
		}
		catch (IllegalArgumentException e)
		{
			refusal = e;
		}
		requireThat(refusal, "refusal").isNotNull();
	}

	/**
	 * Verifies that using an authorized source answers the decision and leaves the rest of the campaign unchanged.
	 */
	@Test
	public void useAuthorizedSourceContinuesUnchanged()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).build();
		Campaign warned = CampaignTransitions.apply(campaign,
			new CampaignEvent.WarnAuthority(List.of(readOnly("shared.md", TARGET))));

		Campaign answered = CampaignTransitions.apply(warned, CampaignEvent.Choose.of(Choice.USE_AUTHORIZED_SOURCE));

		requireThat(answered, "answered").isEqualTo(campaign);
	}

	/**
	 * Verifies that stopping at a warning cancels the campaign and keeps the warning, and that revising clears it.
	 */
	@Test
	public void stopKeepsWarningAndRevisionClearsIt()
	{
		// Requirement: R-W4
		PendingDecision.AuthorityWarnings pending = new PendingDecision.AuthorityWarnings(
			List.of(readOnly("shared.md", TARGET)));
		Campaign campaign = new CampaignBuilder().confirmed().working(1).pending(pending).build();

		Campaign stopped = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.STOP));
		requireThat(stopped.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CANCELLED);
		requireThat(stopped.pending(), "stopped.pending").isEqualTo(pending);

		Campaign revised = CampaignTransitions.apply(campaign, new CampaignEvent.Revise());
		requireThat(revised.revisionRequested(), "revisionRequested").isTrue();
		requireThat(revised.pending(), "revised.pending").isNull();
	}

	/**
	 * Verifies that only an authorize-target choice names a target, and that it must be absolute and normalized.
	 */
	@Test
	public void onlyAuthorizationNamesATarget()
	{
		// Requirement: R-W4
		for (Runnable invalid : List.<Runnable>of(
			() -> new CampaignEvent.Choose(Choice.AUTHORIZE_TARGET, 0, 0, null),
			() -> new CampaignEvent.Choose(Choice.STOP, 0, 0, TARGET),
			() -> CampaignEvent.Choose.authorize(Path.of("shared.md")),
			() -> CampaignEvent.Choose.authorize(Path.of("/external/../shared.md")),
			() -> CampaignEvent.Choose.of(Choice.AUTHORIZE_TARGET)))
		{
			RuntimeException failure = null;
			try
			{
				invalid.run();
			}
			catch (NullPointerException | IllegalArgumentException e)
			{
				failure = e;
			}
			requireThat(failure, "failure").isNotNull();
		}
	}

	/**
	 * Verifies that a warning is refused while the campaign waits for a contract revision.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void warningRequiresNoPendingRevision()
	{
		// Requirement: R-W4
		Campaign campaign = new CampaignBuilder().confirmed().working(1).revisionRequested().build();

		CampaignTransitions.apply(campaign, new CampaignEvent.WarnAuthority(List.of(readOnly("shared.md", TARGET))));
	}
}
