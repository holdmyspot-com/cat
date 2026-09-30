package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests pinned live-generation setup before native dispatch. */
public final class LiveGenerationCheckpointTest
{
	/**
	 * Delivery rejects confirmed roots whose named baseline cannot be loaded.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMissingBaselineAtConstruction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("live-generation-missing-baseline-"));
			Path project = Files.createDirectory(parent.resolve("project"));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			EditableRoot root = new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE);
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(),
				store.binding());
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), CandidateTestRoots.contract(store.directory()), "1".repeat(64),
				List.of(root)));
			try
			{
				new CandidateDelivery(project, store.directory(), store.binding(),
					new PromptSnapshotRepository(store.directory()), rootsRef);
				throw new AssertionError("Unresolvable confirmed baseline must not authorize delivery");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingBaseline").contains("baseline is missing");
			}
		}
	}

	/** Delivery grants no editable authority when the roots record names missing contract evidence. */
	@Test
	public void rejectsMissingContractAtConstruction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("live-generation-missing-contract-"));
			Path project = Files.createDirectory(parent.resolve("project"));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			EditableRoot root = new EditableRoot(Path.of("prompts/edit.md"), EditableRoot.Kind.FILE);
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(),
				store.binding());
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), new EvidenceRef("contracts", "0".repeat(64)),
				CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			try
			{
				new CandidateDelivery(project, store.directory(), store.binding(),
					new PromptSnapshotRepository(store.directory()), rootsRef);
				throw new AssertionError("Missing confirmed contract authorized candidate delivery");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingContract").contains("contracts");
			}
		}
	}

	/**
	 * A changed regular read-only source or created lazy source forbids a checkpoint while retaining live edits.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if the Git fixture is interrupted
	 */
	@Test
	public void rejectsChangedKnownReadOnlySources() throws IOException, InterruptedException
	{
		for (String changedSource: List.of("regular", "absent"))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path parent = cleanup.add(Files.createTempDirectory("live-generation-known-source-"));
				Path project = Files.createDirectory(parent.resolve("project"));
				git(project, "init");
				git(project, "config", "user.email", "test@example.invalid");
				git(project, "config", "user.name", "Candidate Test");
				Files.createDirectories(project.resolve("prompts"));
				Path editable = Path.of("prompts/edit.md");
				Path regular = Path.of("prompts/read-only.md");
				Path absent = Path.of("prompts/lazy.md");
				Files.writeString(project.resolve(editable), "incumbent");
				Files.writeString(project.resolve(regular), "reviewed");
				git(project, "add", ".");
				git(project, "commit", "-m", "incumbent");
				PromptSnapshot incumbent = PromptSnapshot.capture(project, List.of(editable, regular, absent));
				ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project,
					List.of(editable, regular, absent), incumbent);
				CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
					new ModelProfile("test-model", Optional.empty(), Map.of()));
				PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
				snapshots.save(incumbent);
				String baselineRef = new ConfirmedSourceBaselineRepository(store.directory()).save(baseline);
				EditableRoot root = new EditableRoot(editable, EditableRoot.Kind.FILE);
				CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(),
					store.binding());
				EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
					project.toRealPath(), CandidateTestRoots.contract(store.directory()), baselineRef,
					List.of(root)));
				CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), store.binding(),
					snapshots, rootsRef);
				Path changed = regular;
				if (changedSource.equals("absent"))
					changed = absent;
				Files.writeString(project.resolve(changed), "live edit");
				try
				{
					delivery.prepareLiveGeneration(project, incumbent.id(), rootsRef, "generation-1");
					throw new AssertionError("Changed known source was admitted: " + changed);
				}
				catch (CandidateDeliveryException expected)
				{
					requireThat(expected.getMessage(), "sourceDrift").contains("noneditable source changed");
					requireThat(Files.readString(project.resolve(changed)), "retainedLiveEdit").
						isEqualTo("live edit");
				}
			}
		}
	}

	/** A checkpoint records the exact dirty Git worktree without changing HEAD, index, or user bytes. */
	@Test
	public void preparesActiveWorktreeWithoutMutation() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("live-generation-checkpoint-"));
			Path project = Files.createDirectory(parent.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Candidate Test");
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "incumbent");
			git(project, "add", ".");
			git(project, "commit", "-m", "incumbent");
			Files.writeString(project.resolve("notes.txt"), "user dirt");
			String head = git(project, "rev-parse", "HEAD");
			String index = git(project, "ls-files", "--stage");
			PromptSnapshot incumbent = PromptSnapshot.capture(project, List.of(Path.of("prompts/rule.md")));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(incumbent);
			EditableRoot root = new EditableRoot(Path.of("prompts/rule.md"), EditableRoot.Kind.FILE);
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(), store.binding());
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), CandidateTestRoots.contract(store.directory()),
				CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), store.binding(), snapshots,
				rootsRef);

			LiveGenerationCheckpoint checkpoint = delivery.prepareLiveGeneration(project, incumbent.id(), rootsRef,
				"generation-1");

			requireThat(checkpoint.logicalCallId(), "logicalCallId").isEqualTo("generation-1");
			requireThat(checkpoint.projectRoot(), "projectRoot").isEqualTo(project.toRealPath());
			requireThat(evidence.read(checkpoint.checkpointRef(), "live-generation-checkpoints",
				LiveGenerationCheckpoint.class), "checkpointRoundTrip").isEqualTo(checkpoint);
			requireThat(git(project, "rev-parse", "HEAD"), "unchangedHead").isEqualTo(head);
			requireThat(git(project, "ls-files", "--stage"), "unchangedIndex").isEqualTo(index);
			requireThat(Files.readString(project.resolve("notes.txt")), "userBytes").isEqualTo("user dirt");
		}
	}

	/**
	 * Runs one test-owned Git command without a shell.
	 *
	 * @param project test-owned Git worktree
	 * @param arguments Git command arguments
	 * @return command output
	 * @throws IOException if Git fails
	 * @throws InterruptedException if the Git process is interrupted
	 */
	private static String git(Path project, String... arguments) throws IOException, InterruptedException
	{
		List<String> command = new java.util.ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start())
		{
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			if (process.waitFor() != 0)
				throw new IOException("Git fixture failed: " + output);
			return output;
		}
	}
}
