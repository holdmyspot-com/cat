package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;

import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests how warnings about edits outside the prompt author's authority make the campaign wait for a user choice.
 */
public final class AuthorityWarningsTest
{
	private static final Path TARGET = Path.of("/external/shared.md").toAbsolutePath();
	private static final Path OTHER_TARGET = Path.of("/external/other.md").toAbsolutePath();
	private static final List<AuthorityWarning.Choice> CHOICES = List.of(
		AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
		AuthorityWarning.Choice.REVISE_CAMPAIGN);

	/**
	 * Creates the test.
	 */
	public AuthorityWarningsTest()
	{
	}

	/**
	 * Returns a warning that an edit through a link reaches an external file, which is read-only.
	 *
	 * @param link the link path
	 * @param target the link's resolved target
	 * @return the warning
	 */
	private static AuthorityWarning readOnly(String link, Path target)
	{
		return new AuthorityWarning("R1", Path.of(link), target, Limit.EXTERNAL_TARGET_READ_ONLY, CHOICES);
	}

	/**
	 * Verifies that a warning becomes the pending decision and offers only using an authorized source and stopping.
	 */
	@Test
	public void warningAwaitsTheUser()
	{
		// Requirement: R-W4
		Campaign campaign = new Builder().confirmed().working(1).build();
		List<AuthorityWarning> warnings = List.of(readOnly("shared.md", TARGET));

		Campaign warned = Transitions.apply(campaign, new CampaignEvent.WarnAuthority(warnings));

		requireThat(warned.pending(), "pending").isEqualTo(new PendingDecision.AuthorityWarnings(warnings));
		requireThat(warned.choices(), "choices").
			containsExactly(List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP));
	}

	/**
	 * Verifies that using an authorized source answers the decision, records its warning as answered and leaves the
	 * rest of the campaign unchanged.
	 */
	@Test
	public void useAuthorizedSourceContinuesUnchanged()
	{
		// Requirement: R-W4
		Campaign campaign = new Builder().confirmed().working(1).build();
		AuthorityWarning warning = readOnly("shared.md", TARGET);
		Campaign warned = Transitions.apply(campaign, new CampaignEvent.WarnAuthority(List.of(warning)));

		Campaign answered = Transitions.apply(warned, CampaignEvent.Choose.of(Choice.USE_AUTHORIZED_SOURCE));

		requireThat(answered, "answered").isEqualTo(new Builder().confirmed().working(1).answered(warning).build());
	}

	/**
	 * Verifies that using an authorized source answers every pending warning at once.
	 */
	@Test
	public void useAuthorizedSourceAnswersEveryWarning()
	{
		// Requirement: R-W4
		Campaign campaign = new Builder().confirmed().working(1).
			pending(new PendingDecision.AuthorityWarnings(List.of(readOnly("shared.md", TARGET),
				readOnly("other.md", OTHER_TARGET)))).build();

		Campaign answered = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.USE_AUTHORIZED_SOURCE));

		requireThat(answered.pending(), "pending").isNull();
		requireThat(answered.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
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
		Campaign campaign = new Builder().confirmed().working(1).pending(pending).build();

		Campaign stopped = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.STOP));
		requireThat(stopped.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CANCELLED);
		requireThat(stopped.pending(), "stopped.pending").isEqualTo(pending);

		Campaign revised = Transitions.apply(campaign, new CampaignEvent.Revise());
		requireThat(revised.revisionRequested(), "revisionRequested").isTrue();
		requireThat(revised.pending(), "revised.pending").isNull();
	}

	/**
	 * Verifies that a warning is refused while the campaign waits for a contract revision.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void warningRequiresNoPendingRevision()
	{
		// Requirement: R-W4
		Campaign campaign = new Builder().confirmed().working(1).revisionRequested().build();

		Transitions.apply(campaign, new CampaignEvent.WarnAuthority(List.of(readOnly("shared.md", TARGET))));
	}
}
