package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests delivery recovery when the JVM owning a visible file mutation is killed.
 */
public final class CandidateDeliveryProcessTest
{
	/**
	 * A new JVM rolls back a captured predecessor after the delivery JVM dies, then an equal replay keeps the result.
	 *
	 * @throws Exception if the fixture process or filesystem cannot complete the test
	 */
	@Test
	public void recoversAfterChildDiesDuringCapture() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-process-loss-"));
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(project.resolve("user.txt"), "original user text");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
			runGit(project, "init", "-q");
			runGit(project, "config", "user.name", "CAT Test");
			runGit(project, "config", "user.email", "cat-test@example.invalid");
			runGit(project, "add", ".");
			runGit(project, "commit", "-qm", "baseline");
			Files.writeString(project.resolve("user.txt"), "pre-existing user edit");
			String beforeDiff = runGit(project, "diff", "--binary");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts/rule.md")));
			PromptSnapshot selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts/rule.md")));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(baseline);
			snapshots.save(selected);
			EditableRoot root = new EditableRoot(Path.of("prompts/rule.md"), EditableRoot.Kind.FILE);
			EvidenceRef rootsRef = new CandidateEvidenceRepository(store.directory(), binding).publish(
				"confirmed-editable-roots", new ConfirmedEditableRoots(2, project.toRealPath(),
					CandidateTestRoots.contract(store.directory()),
					CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			Path capturedMarker = parent.resolve("captured.marker");
			String testClasses = Path.of("target/test-classes").toAbsolutePath().normalize().toString();
			String modulePath = String.join(File.pathSeparator, ModuleLayer.boot().configuration().modules().stream().
				map(module -> module.reference().location().orElseThrow()).filter(uri -> uri.getScheme().equals("file")).
				map(uri -> Path.of(uri).toString()).toList());
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			ProcessBuilder builder = new ProcessBuilder(java.toString(), "--module-path", modulePath,
				"--patch-module", "io.github.cowwoc.cat.common.cli=" + testClasses, "--module",
				"io.github.cowwoc.cat.common.cli/" + CandidateDeliveryProcessTest.class.getName(),
				project.toString(), store.directory().toString(), rootsRef.evidenceId(), baseline.id(), selected.id(),
				capturedMarker.toString()).redirectErrorStream(true);
			try (Process child = builder.start())
			{
				try
				{
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
					while (Files.notExists(capturedMarker) && child.isAlive() && System.nanoTime() < deadline)
						Thread.sleep(10);
					if (Files.notExists(capturedMarker))
					{
						child.destroyForcibly();
						if (!child.waitFor(5, TimeUnit.SECONDS))
							throw new AssertionError("Delivery child did not terminate after missing capture");
						throw new AssertionError("Child never captured the predecessor: " +
							new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
					}
					child.destroyForcibly();
					if (!child.waitFor(5, TimeUnit.SECONDS))
						throw new AssertionError("Delivery child did not terminate");
				}
				finally
				{
					if (child.isAlive())
						child.destroyForcibly();
				}
			}
			requireThat(Files.notExists(project.resolve("prompts/rule.md")), "targetAbsentAfterCapture").isTrue();
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(new DeliveryJournal(store.directory()).readLatest(recovery.transactionId().orElseThrow()).phase(),
				"terminalPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
			DeliveryReceipt receipt = new CandidateEvidenceRepository(store.directory(), binding).read(
				new EvidenceRef("delivery-receipts", recovery.receiptId().orElseThrow()), "delivery-receipts",
				DeliveryReceipt.class);
			requireThat(receipt.selectedCandidateId(), "receiptSelectedCandidateId").isEqualTo(selected.id());
			requireThat(receipt.outcome(), "receiptOutcome").isEqualTo(DeliveryReceiptOutcome.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
			requireThat(Files.readString(project.resolve("user.txt")), "userBytes").isEqualTo(
				"pre-existing user edit");
			requireThat(runGit(project, "diff", "--binary"), "gitDiff").isEqualTo(beforeDiff);
			try (Stream<Path> visibleFiles = Files.list(project.resolve("prompts")))
			{
				requireThat(visibleFiles.noneMatch(path -> path.getFileName().toString().startsWith(
					".cat-delivery-")), "privateCleanupComplete").isTrue();
			}
			requireThat(restarted.recoverPending().disposition(), "secondRecovery").isEqualTo(
				DeliveryRecovery.Disposition.NONE);
			CandidateApplication applied = restarted.reconcile(baseline.id(), null, selected.id());
			requireThat(applied.disposition(), "appliedDisposition").isEqualTo(ApplyDisposition.APPLIED);
			String appliedDiff = runGit(project, "diff", "--binary");
			ProcessBuilder replayBuilder = new ProcessBuilder(java.toString(), "--module-path", modulePath,
				"--patch-module", "io.github.cowwoc.cat.common.cli=" + testClasses, "--module",
				"io.github.cowwoc.cat.common.cli/" + CandidateDeliveryProcessTest.class.getName(),
				"replay", project.toString(), store.directory().toString(), rootsRef.evidenceId(),
				baseline.id(), selected.id()).redirectErrorStream(true);
			try (Process replay = replayBuilder.start())
			{
				if (!replay.waitFor(10, TimeUnit.SECONDS))
				{
					replay.destroyForcibly();
					throw new AssertionError("Equal replay child did not terminate");
				}
				String output = new String(replay.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				requireThat(replay.exitValue(), "replayExit").isEqualTo(0);
				requireThat(output.strip(), "replayDisposition").isEqualTo("ALREADY_APPLIED");
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedBytesAfterReplay").isEqualTo(
				"after");
			requireThat(Files.readString(project.resolve("user.txt")), "userBytesAfterReplay").isEqualTo(
				"pre-existing user edit");
			requireThat(runGit(project, "diff", "--binary"), "gitDiffAfterReplay").isEqualTo(appliedDiff);
		}
	}

	/**
	 * Stops a child delivery after atomic predecessor capture, or replays a completed delivery in a new JVM.
	 *
	 * @param args project, campaign, root and snapshot identities, and capture marker; or replay mode and identities
	 * @throws IOException if child setup, delivery, or replay fails
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 6)
			throw new IllegalArgumentException("Expected project, campaign, roots, baseline, selected and marker");
		if (args[0].equals("replay"))
		{
			Path replayProject = Path.of(args[1]);
			Path replayCampaign = Path.of(args[2]);
			CampaignBinding replayBinding = CampaignStore.open(replayCampaign).binding();
			EvidenceRef replayRoots = new EvidenceRef("confirmed-editable-roots", args[3]);
			CandidateDelivery replayDelivery = new CandidateDelivery(replayProject, replayCampaign, replayBinding,
				new PromptSnapshotRepository(replayCampaign), replayRoots);
			System.out.println(replayDelivery.reconcile(args[4], null, args[5]).disposition());
			return;
		}
		Path project = Path.of(args[0]);
		Path campaign = Path.of(args[1]);
		CampaignBinding binding = CampaignStore.open(campaign).binding();
		EvidenceRef rootsRef = new EvidenceRef("confirmed-editable-roots", args[2]);
		CandidateDelivery delivery = new CandidateDelivery(project, campaign, binding,
			new PromptSnapshotRepository(campaign), rootsRef, new PauseAfterCaptureOperations(Path.of(args[5])));
		delivery.reconcile(args[3], null, args[4]);
		throw new AssertionError("Delivery returned instead of pausing after capture");
	}

	/**
	 * Runs one test-owned Git operation and returns its output.
	 *
	 * @param project test-owned Git worktree
	 * @param arguments Git subcommand and arguments
	 * @return Git output
	 * @throws IOException if Git fails
	 * @throws InterruptedException if Git is interrupted
	 */
	private static String runGit(Path project, String... arguments) throws IOException, InterruptedException
	{
		List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start())
		{
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor() != 0)
				throw new IOException("Git fixture failed: " + output);
			return output;
		}
	}

	/**
	 * Wraps the real file operations so the parent can kill this JVM after a visible capture.
	 *
	 * @param marker file observed by the parent after the target has been moved
	 */
	private record PauseAfterCaptureOperations(Path marker) implements CandidateFileOperations
	{
		private static final CandidateFileOperations DELEGATE = new JdkCandidateFileOperations();

		@Override
		public void writeAndForceNew(Path privateStage, byte[] bytes) throws IOException
		{
			DELEGATE.writeAndForceNew(privateStage, bytes);
		}

		@Override
		public PathInspection captureAtomically(Path observedTarget, Path absentPrivateSibling) throws IOException
		{
			PathInspection captured = DELEGATE.captureAtomically(observedTarget, absentPrivateSibling);
			Files.writeString(marker, "captured");
			try
			{
				Thread.sleep(Long.MAX_VALUE);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Capture probe was interrupted", e);
			}
			return captured;
		}

		@Override
		public LinkPublication linkNew(Path privateSource, Path absentTarget, String expectedSha256)
			throws IOException
		{
			return DELEGATE.linkNew(privateSource, absentTarget, expectedSha256);
		}

		@Override
		public boolean sameFile(Path first, Path second) throws IOException
		{
			return DELEGATE.sameFile(first, second);
		}

		@Override
		public PathInspection inspect(Path path) throws IOException
		{
			return DELEGATE.inspect(path);
		}

		@Override
		public void unlinkPrivate(Path privatePath) throws IOException
		{
			DELEGATE.unlinkPrivate(privatePath);
		}

		@Override
		public void deletePrivate(Path privatePath) throws IOException
		{
			DELEGATE.deletePrivate(privatePath);
		}

		@Override
		public void createDirectoryNew(Path absentDirectory) throws IOException
		{
			DELEGATE.createDirectoryNew(absentDirectory);
		}

		@Override
		public void deleteEmptyOwnedDirectory(Path directory) throws IOException
		{
			DELEGATE.deleteEmptyOwnedDirectory(directory);
		}

		@Override
		public void forceDirectory(Path directory) throws IOException
		{
			DELEGATE.forceDirectory(directory);
		}
	}
}
