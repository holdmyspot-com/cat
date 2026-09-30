package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests C3 immutable evidence access through a pinned C1 campaign binding. */
public final class CandidateEvidenceRepositoryTest
{
	/** The repository publishes and rereads evidence in only its pinned campaign. */
	@Test
	public void readsThroughPinnedCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-evidence-"));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			CandidateEvidenceRepository repository = new CandidateEvidenceRepository(store.directory(), binding);
			ConfirmedSourceBaseline baseline = new ConfirmedSourceBaseline(1, List.of(new KnownSourceState(
				Path.of("prompts/lazy.md"), KnownSourcePresence.ABSENT, Optional.empty())), "0".repeat(64));

			EvidenceRef reference = repository.publish("confirmed-source-baselines", baseline);
			ConfirmedSourceBaseline restored = new CandidateEvidenceRepository(store.directory(), binding).read(reference,
				"confirmed-source-baselines", ConfirmedSourceBaseline.class);

			requireThat(restored, "restoredBaseline").isEqualTo(baseline);
		}
	}

	/** A foreign campaign binding is rejected before evidence access. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*binding.*")
	public void rejectsForeignCampaignBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-evidence-"));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			new CandidateEvidenceRepository(store.directory(), new CampaignBinding("foreign", store.directory()));
		}
	}
}
