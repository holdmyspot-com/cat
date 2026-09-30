package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates complete roots-bound baseline fixtures for candidate tests.
 */
final class CandidateTestRoots
{
	/**
	 * Prevents construction.
	 */
	private CandidateTestRoots()
	{
	}

	/**
	 * Publishes contract bytes for C3's opaque, campaign-pinned identity check.
	 *
	 * @param campaignDirectory test-owned campaign
	 * @return immutable contract reference
	 * @throws IOException if fixture publication fails
	 */
	static EvidenceRef contract(Path campaignDirectory) throws IOException
	{
		return CampaignStore.open(campaignDirectory).appendImmutableJson("contracts",
			"{}".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Captures the fixture's current editable files as its minimal known-source index.
	 *
	 * @param project test-owned project
	 * @param campaignDirectory test-owned campaign
	 * @param roots editable roots
	 * @return immutable source-baseline identity
	 * @throws IOException if capture or publication fails
	 */
	static String baseline(Path project, Path campaignDirectory, List<EditableRoot> roots) throws IOException
	{
		PromptSnapshot existing = PromptSnapshot.capture(project, roots.stream().map(EditableRoot::path).toList());
		List<Path> known = new ArrayList<>(existing.entries().keySet());
		if (known.isEmpty())
			known.add(Path.of("fixture-absent-source.md"));
		return baselineKnown(project, campaignDirectory, known);
	}

	/**
	 * Captures an explicit known-source union including read-only and absent paths.
	 *
	 * @param project test-owned project
	 * @param campaignDirectory test-owned campaign
	 * @param known source paths
	 * @return immutable source-baseline identity
	 * @throws IOException if capture or publication fails
	 */
	static String baselineKnown(Path project, Path campaignDirectory, List<Path> known) throws IOException
	{
		PromptSnapshot existing = PromptSnapshot.capture(project, known);
		new PromptSnapshotRepository(campaignDirectory).save(existing);
		ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, known, existing);
		return new ConfirmedSourceBaselineRepository(campaignDirectory).save(baseline);
	}
}
