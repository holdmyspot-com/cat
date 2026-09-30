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

/** Tests immutable candidate freeze dependencies and first staged-copy verification. */
public final class CandidateFreezeRepositoryTest
{
	/**
	 * A roots record that does not bind the confirmed source baseline cannot freeze a candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsRootsWithoutBoundBaseline() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-freeze-unbound-"));
			Path project = Files.createDirectory(parent.resolve("project"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/edit.md"), "source");
			List<Path> known = List.of(Path.of("prompts/edit.md"));
			PromptSnapshot candidate = PromptSnapshot.capture(project, known);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, known, candidate);
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			new PromptSnapshotRepository(store.directory()).save(candidate);
			new ConfirmedSourceBaselineRepository(store.directory()).save(baseline);
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(), binding);
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", Map.of(
				"schemaVersion", 1,
				"projectRoot", project.toRealPath(),
				"confirmedContractRef", new EvidenceRef("contracts", "0".repeat(64)),
				"roots", List.of(new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE))));
			CandidateFreezeRepository freezer = new CandidateFreezeRepository(store.directory(), binding);
			try
			{
				freezer.freeze(project, candidate.id(), rootsRef);
				throw new AssertionError("Unbound editable roots must not freeze a candidate");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unboundRootsFailure").contains("roots");
			}
		}
	}

	/** A restart rehashes all frozen dependencies before accepting a staged copy. */
	@Test
	public void verifiesFrozenCandidateAfterRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-freeze-"));
			Path project = Files.createDirectory(parent.resolve("project"));
			Path staged = Files.createDirectory(parent.resolve("staged"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/edit.md"), "before");
			Files.writeString(project.resolve("prompts/read-only.md"), "source");
			List<Path> known = List.of(Path.of("prompts/edit.md"), Path.of("prompts/lazy.md"),
				Path.of("prompts/read-only.md"));
			PromptSnapshot initial = PromptSnapshot.capture(project, known);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, known, initial);
			Files.writeString(project.resolve("prompts/edit.md"), "after");
			PromptSnapshot candidate = PromptSnapshot.capture(project, known);
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(initial);
			snapshots.save(candidate);
			String baselineId = new ConfirmedSourceBaselineRepository(store.directory()).save(baseline);
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(), binding);
			EvidenceRef missingContractRootsRef = evidence.publish("confirmed-editable-roots",
				new ConfirmedEditableRoots(2, project.toRealPath(),
					new EvidenceRef("contracts", "0".repeat(64)), baselineId, List.of(
						new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE))));
			CandidateFreezeRepository freezer = new CandidateFreezeRepository(store.directory(), binding);
			try
			{
				freezer.freeze(project, candidate.id(), missingContractRootsRef);
				throw new AssertionError("Missing confirmed contract evidence authorized candidate freeze");
			}
			catch (IOException _)
			{
				requireThat(Files.readString(project.resolve("prompts/edit.md")), "candidateBytesRetained").
					isEqualTo("after");
			}
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), CandidateTestRoots.contract(store.directory()), baselineId, List.of(
					new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE))));
			EvidenceRef freezeRef = freezer.freeze(project, candidate.id(), rootsRef);
			Files.createDirectories(staged.resolve("prompts"));
			Files.writeString(staged.resolve("prompts/edit.md"), "after");
			Files.writeString(staged.resolve("prompts/read-only.md"), "source");

			new CandidateFreezeRepository(store.directory(), binding).verifyStaged(staged, freezeRef);
			requireThat(freezeRef.collection(), "freezeCollection").isEqualTo("candidate-freezes");
		}
	}
}
