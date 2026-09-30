package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Tests publication and exact-reference validation of a development decision index. */
public final class DevelopmentDecisionIndexTest
{
	private static final String DIGEST = "a".repeat(64);

	/** Creates the test instance. */
	public DevelopmentDecisionIndexTest()
	{
	}

	/** Publishing a retained choice lets the production index reader accept its exact pointer. */
	@Test
	public void acceptsMatchingIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			EvidenceRef reference = fixture.service().retainDecision(fixture.decision());
			fixture.service().publishDecisionIndex(reference, fixture.decision());

			fixture.service().requirePublishedDecisionIndex(reference, fixture.decision(), fixture.campaign());
		}
	}

	/** The production index reader rejects a retained choice whose key has no index. */
	@Test
	public void rejectsMissingIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			EvidenceRef reference = fixture.service().retainDecision(fixture.decision());

			Assert.expectThrows(IOException.class, () -> fixture.service().requirePublishedDecisionIndex(reference,
				fixture.decision(), fixture.campaign()));
		}
	}

	/** The production index reader rejects another retained choice under the same logical key. */
	@Test
	public void rejectsForeignIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			EvidenceRef reference = fixture.service().retainDecision(fixture.decision());
			DevelopmentCategoryDecision foreignDecision = decision(
				new EvidenceRef("development-evidence-revisions", "b".repeat(64)));
			EvidenceRef foreignReference = fixture.service().retainDecision(foreignDecision);
			fixture.service().publishDecisionIndex(foreignReference, foreignDecision);

			IOException failure = Assert.expectThrows(IOException.class, () -> fixture.service().
				requirePublishedDecisionIndex(reference, fixture.decision(), fixture.campaign()));
			requireThat(failure.getMessage(), "foreignDecisionIndexDiagnostic").
				isEqualTo("Development category choice differs from its published index");
		}
	}

	/**
	 * Creates a prospective campaign, lineage, service, and valid choice record.
	 *
	 * @param cleanup owner of the temporary campaign directory
	 * @return isolated index fixture
	 * @throws IOException if campaign fixture evidence cannot be written
	 */
	private static Fixture fixture(TemporaryDirectories cleanup) throws IOException
	{
		Path directory = cleanup.add(Files.createTempDirectory("development-decision-index-")).toRealPath().
			resolve("campaign-campaign");
		CampaignStore store = new CampaignStore(directory);
		ProspectiveCampaignState.initialize(store, "campaign");
		Campaign campaign = ProspectiveCampaignState.addLineage(store, DIGEST);
		return new Fixture(store, campaign, new DevelopmentCategoryDecisionService(
			new CampaignLocator(directory.getParent()), store), decision(
				new EvidenceRef("development-evidence-revisions", "c".repeat(64))));
	}

	/**
	 * Creates a valid typed decision for one category and one post-coverage batch.
	 *
	 * @param sourceRevisionRef retained source identity
	 * @return a decision with the supplied source identity
	 */
	private static DevelopmentCategoryDecision decision(EvidenceRef sourceRevisionRef)
	{
		return new DevelopmentCategoryDecision(DevelopmentCategoryDecision.SCHEMA_VERSION, "campaign",
			new LineageKey("lineage-1-" + DIGEST, DIGEST), DIGEST,
			new EvidenceRef("measurement-epochs", "d".repeat(64)), sourceRevisionRef,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, List.of("category"), List.of(), "category",
			EvidencePurpose.DEVELOPMENT, 1,
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.COVERAGE, 0),
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 0), List.of());
	}

	private record Fixture(CampaignStore store, Campaign campaign,
		DevelopmentCategoryDecisionService service, DevelopmentCategoryDecision decision)
	{
	}
}
