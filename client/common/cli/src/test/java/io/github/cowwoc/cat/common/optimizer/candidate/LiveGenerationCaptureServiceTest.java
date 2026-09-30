package io.github.cowwoc.cat.common.optimizer.candidate;

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

/** Tests that a captured candidate cannot be paired with a different Git inventory. */
public final class LiveGenerationCaptureServiceTest
{
	/** The exact C1 indexed reference passes the capture gate. */
	@Test
	public void acceptsExactCompletionIndex() throws IOException
	{
		EvidenceRef completion = new EvidenceRef("native-run-completions", "1".repeat(64));
		LiveGenerationCaptureService.requireIndexedCompletion(Optional.of(completion), completion);
	}

	/** A completion that C1 has not indexed cannot support capture. */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingCompletionIndex() throws IOException
	{
		LiveGenerationCaptureService.requireIndexedCompletion(Optional.empty(),
			new EvidenceRef("native-run-completions", "1".repeat(64)));
	}

	/** A typed completion from a different call cannot support capture. */
	@Test(expectedExceptions = IOException.class)
	public void rejectsUnequalCompletionIndex() throws IOException
	{
		LiveGenerationCaptureService.requireIndexedCompletion(Optional.of(
			new EvidenceRef("native-run-completions", "2".repeat(64))),
			new EvidenceRef("native-run-completions", "1".repeat(64)));
	}

	/** A concurrent write between inventory and snapshot capture cannot be admitted. */
	@Test(expectedExceptions = CandidateDeliveryException.class)
	public void rejectsInventorySnapshotRace() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			LiveGitInventory before = LiveGitInventory.capture(fixture.project);
			Files.writeString(fixture.project.resolve("prompts/rule.md"), "changed after inventory");
			PromptSnapshot after = PromptSnapshot.capture(fixture.project, List.of(Path.of("prompts")));
			fixture.service.verifySnapshotInventory(after, before);
		}
	}

	/** A newly created file omitted by a snapshot also fails closed. */
	@Test(expectedExceptions = CandidateDeliveryException.class)
	public void rejectsMissingSnapshotFile() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			PromptSnapshot before = PromptSnapshot.capture(fixture.project, List.of(Path.of("prompts")));
			Files.writeString(fixture.project.resolve("prompts/new.md"), "new");
			LiveGitInventory after = LiveGitInventory.capture(fixture.project);
			fixture.service.verifySnapshotInventory(before, after);
		}
	}

	/** Owns isolated Git and campaign paths for capture consistency checks. */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path project;
		private final LiveGenerationCaptureService service;

		/** Creates a real Git worktree and campaign-bound service. */
		private Fixture() throws IOException
		{
			Path parent = cleanup.add(Files.createTempDirectory("live-capture-service-"));
			project = Files.createDirectory(parent.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Candidate Test");
			Files.createDirectory(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "incumbent");
			git(project, "add", ".");
			git(project, "commit", "-m", "incumbent");
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			EditableRoot root = new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY);
			DeliveryWriteAuthority authority = new DeliveryWriteAuthority(List.of(root));
			EvidenceRef rootsRef = new CandidateEvidenceRepository(store.directory(), store.binding()).publish(
				"confirmed-editable-roots", new ConfirmedEditableRoots(2, project.toRealPath(),
					CandidateTestRoots.contract(store.directory()),
					CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			service = new LiveGenerationCaptureService(project, store.directory(), store.binding(),
				new PromptSnapshotRepository(store.directory()), rootsRef, authority,
				new JdkCandidateFileOperations());
		}

		/**
		 * Runs one fixture-owned Git command.
		 *
		 * @param directory test Git worktree
		 * @param arguments command arguments
		 * @throws IOException if Git fails
		 */
		private static void git(Path directory, String... arguments) throws IOException
		{
			List<String> command = new java.util.ArrayList<>(List.of("git"));
			command.addAll(List.of(arguments));
			try (Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).
				start())
			{
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				if (process.waitFor() != 0)
					throw new IOException("Git fixture failed: " + output);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Git fixture interrupted", e);
			}
		}

		/** Removes only paths owned by this fixture. */
		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
