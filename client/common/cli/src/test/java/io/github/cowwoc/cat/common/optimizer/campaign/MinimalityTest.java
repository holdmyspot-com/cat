package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunks;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Verifies that a passing attempt enters minimality before it becomes a candidate.
 */
public final class MinimalityTest
{
	/**
	 * Creates the test.
	 */
	public MinimalityTest()
	{
	}

	/**
	 * Verifies that passing promotion trials preserve the old working candidate until reduction finishes.
	 */
	@Test
	public void passingAttemptWaitsForReduction()
	{
		// Requirement: R-D11
		TrialInstance failure = CampaignBuilder.targeted("safety", "cache");
		Sha256 snapshot = CampaignBuilder.snapshot("attempt");
		Campaign campaign = new CampaignBuilder().confirmed().working(1).failure(failure).
			round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.submit(campaign, "safety", snapshot, List.of(
			new FileDelta(Path.of("CLAUDE.md"), new FileContent("before".getBytes(UTF_8)),
				new FileContent("after".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
		for (int run = 0; run < Promotion.TRIGGERING_RUNS; ++run)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, snapshot, failure, Verdict.PASS);
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
		requireThat(campaign.development().author(), "attempt").isNotNull();
		requireThat(campaign.attempts(), "allowance").isEmpty();
	}

	/**
	 * Verifies that failed coarse removals refine to single hunks and preserve the two necessary changes.
	 */
	@Test
	public void refinesUntilNoHunkCanBeRemoved()
	{
		// Requirement: R-D11
		FileDelta file = new FileDelta(Path.of("CLAUDE.md"),
			new FileContent("a\nx\nb\ny\nc\nz\nd\n".getBytes(UTF_8)),
			new FileContent("needed-a\nx\nunneeded-b\ny\nneeded-c\nz\nunneeded-d\n".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
		Overlay working = Overlay.of(new FileInventory(Path.of("/project"), Map.of(file.path(),
			new RegularFile(file.before().hash(), file.permissions()))));
		PromotionRun pass = new PromotionRun(PromotionRun.Role.TRIGGERING,
			CampaignBuilder.targeted("safety", "cache"),
			new TrialKey(Purpose.PROMOTION, 1, CampaignBuilder.snapshot("full"), "safety", 1), Verdict.PASS);
		Minimality search = Minimality.start(List.of(file), CampaignBuilder.snapshot("full")).
			advance(CampaignBuilder.snapshot("full"), List.of(pass), Promotion.Decision.PROMOTE);
		for (int iteration = 0; iteration < 20 && search.testing() != null; ++iteration)
		{
			Overlay reduced = Hunks.apply(working, search.files(), Set.copyOf(search.testing()));
			FileContent content = file.before();
			if (reduced.entries().get(file.path()) instanceof Write write)
				content = write.content();
			List<String> lines = new String(content.bytes(), UTF_8).lines().toList();
			Promotion.Decision decision = Promotion.Decision.REJECT;
			if (lines.contains("needed-a") && lines.contains("needed-c"))
				decision = Promotion.Decision.PROMOTE;
			search = search.advance(reduced.identity(), List.of(pass), decision);
		}
		requireThat(search.testing(), "finished").isNull();
		requireThat(search.removed(), "removed").isEqualTo(List.of("CLAUDE.md#2", "CLAUDE.md#4"));
		Write retained = (Write) Hunks.apply(working, search.files(), Set.copyOf(search.retained())).entries().
			get(file.path());
		requireThat(new String(retained.content().bytes(), UTF_8), "minimalPrompt").
			isEqualTo("needed-a\nx\nb\ny\nneeded-c\nz\nd\n");
	}
}
