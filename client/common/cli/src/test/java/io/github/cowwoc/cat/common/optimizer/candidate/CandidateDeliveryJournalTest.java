package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Tests journaled candidate delivery through C3's file-operation boundary. */
public final class CandidateDeliveryJournalTest
{
	/** Applying a saved candidate archives a receipt without changing unrelated user bytes. */
	@Test
	public void appliesSelectedSnapshotWithReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-journal-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(project.resolve("user.txt"), "untouched");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots, rootsRef);

			CandidateApplication application = delivery.reconcile(baseline.id(), null, selected.id());

			requireThat(application.disposition(), "disposition").isEqualTo(ApplyDisposition.APPLIED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedBytes").isEqualTo("after");
			requireThat(Files.readString(project.resolve("user.txt")), "unrelatedBytes").isEqualTo("untouched");
			requireThat(application.affectedPaths(), "affectedPaths").isEqualTo(List.of(Path.of("prompts/rule.md")));
			requireThat(application.receiptId(), "receiptId").isNotBlank();
			requireThat(new DeliveryJournal(store.directory()).readLatest(application.transactionId()).phase(),
				"journalPhase").isEqualTo(DeliveryPhase.APPLIED);
			CandidateApplication repeated = delivery.reconcile(baseline.id(), null, selected.id());
			requireThat(repeated.disposition(), "repeatedDisposition").isEqualTo(ApplyDisposition.ALREADY_APPLIED);
			requireThat(repeated.affectedPaths().isEmpty(), "repeatedPathsEmpty").isTrue();
			requireThat(repeated.receiptId(), "repeatedReceiptId").isNotBlank();
			Path receiptIndex = store.directory().resolve("delivery-receipt-indexes").resolve(
				application.transactionId() + ".json");
			Path receiptPath = store.directory().resolve("delivery-receipts").resolve(
				application.receiptId() + ".json");
			byte[] receiptBytes = Files.readAllBytes(receiptPath);
			Files.writeString(receiptPath, "corrupt receipt");
			try
			{
				delivery.recoverPending();
				throw new AssertionError("Expected corrupt indexed receipt rejection");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "corruptReceiptMessage").contains("content address");
			}
			finally
			{
				Files.write(receiptPath, receiptBytes);
			}
			Files.delete(receiptIndex);
			Files.writeString(project.resolve("prompts/rule.md"), "later user edit");

			DeliveryRecovery recoveredApplied = delivery.recoverPending();

			requireThat(recoveredApplied.disposition(), "recoveredAppliedDisposition").isEqualTo(
				DeliveryRecovery.Disposition.APPLIED);
			requireThat(recoveredApplied.receiptId().orElseThrow(), "recoveredReceiptId").isEqualTo(
				application.receiptId());
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "laterUserEdit").isEqualTo(
				"later user edit");
		}
	}

	/** A selected-link failure after capture restores the predecessor from its private capture. */
	@Test
	public void rollsBackFailedSelectedPublication() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-failure-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations());

			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected selected publication failure");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.reason(), "failureKind").isEqualTo(CandidateDeliveryException.Reason.IO_FAILURE);
				requireThat(expected.transactionId().isPresent(), "transactionRecorded").isTrue();
				requireThat(expected.affectedPaths(), "affectedPaths").isEqualTo(List.of(Path.of("prompts/rule.md")));
				requireThat(expected.getCause() instanceof IOException, "filesystemCausePreserved").isTrue();
				requireThat(expected.getMessage(), "failureReason").contains("selected link failure");
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
		}
	}

	/** A stage-write failure leaves the untouched predecessor visible and archives rollback. */
	@Test
	public void rollsBackFailedStageWrite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-stage-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(true));

			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected stage failure");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.reason(), "failureKind").isEqualTo(CandidateDeliveryException.Reason.IO_FAILURE);
				requireThat(expected.transactionId().isPresent(), "transactionRecorded").isTrue();
				requireThat(expected.affectedPaths(), "affectedPaths").isEqualTo(List.of(Path.of("prompts/rule.md")));
				requireThat(expected.getCause() instanceof IOException, "filesystemCausePreserved").isTrue();
				requireThat(expected.getMessage(), "failureReason").contains("stage failure");
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "unchangedBytes").isEqualTo("before");
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				requireThat(new DeliveryJournal(store.directory()).readLatest(transactionId).phase(),
					"rolledBackPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
			}
		}
	}

	/** A confirmed DIRECTORY root admits a newly nested regular file and records created parents. */
	@Test
	public void createsNestedFileUnderDirectoryRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-directory-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts/new/sub"));
			Files.writeString(selectedProject.resolve("prompts/new/sub/rule.md"), "new");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts")));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(baseline);
			snapshots.save(selected);
			EditableRoot root = new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY);
			EvidenceRef rootsRef = new CandidateEvidenceRepository(store.directory(), binding).publish(
				"confirmed-editable-roots", new ConfirmedEditableRoots(2, project.toRealPath(),
					CandidateTestRoots.contract(store.directory()),
					CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			CandidateDelivery failed = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(true));
			try
			{
				failed.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected nested staging failure");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "nestedFailure").contains("stage failure");
			}
			requireThat(Files.exists(project.resolve("prompts/new")), "ownedDirectoryRemoved").isFalse();
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots, rootsRef);

			CandidateApplication application = delivery.reconcile(baseline.id(), null, selected.id());

			requireThat(application.disposition(), "disposition").isEqualTo(ApplyDisposition.APPLIED);
			requireThat(Files.readString(project.resolve("prompts/new/sub/rule.md")), "createdBytes").isEqualTo("new");
			requireThat(new DeliveryJournal(store.directory()).readLatest(application.transactionId()).directories().
				size(), "createdDirectoryCount").isEqualTo(2);
		}
	}

	/**
	 * A restart cannot claim rollback when directory creation completed before ownership was journaled.
	 *
	 * @throws IOException if the test fixture cannot read or write its files
	 */
	@Test
	public void refusesUnjournaledCreatedDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-directory-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts/new"));
			Files.writeString(selectedProject.resolve("prompts/new/rule.md"), "new");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts")));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(baseline);
			snapshots.save(selected);
			EditableRoot root = new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY);
			EvidenceRef rootsRef = new CandidateEvidenceRepository(store.directory(), binding).publish(
				"confirmed-editable-roots", new ConfirmedEditableRoots(2, project.toRealPath(),
					CandidateTestRoots.contract(store.directory()),
					CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.crashAfterDirectory = true;
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected process-loss simulation after directory creation");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashBoundary").contains("directory created");
			}
			Path createdDirectory = project.resolve("prompts/new");
			requireThat(Files.isDirectory(createdDirectory), "createdDirectoryVisible").isTrue();
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);
			try
			{
				restarted.recoverPending();
				throw new AssertionError("Expected ambiguous directory ownership to require recovery");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "recoveryReason").contains("directory");
			}
			requireThat(Files.isDirectory(createdDirectory), "ambiguousDirectoryPreserved").isTrue();
			requireThat(Files.notExists(createdDirectory.resolve("rule.md")), "candidateNotPublished").isTrue();
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				requireThat(new DeliveryJournal(store.directory()).readLatest(transactionId).phase(),
					"recoveryPhase").isEqualTo(DeliveryPhase.RECOVERY_REQUIRED);
			}
		}
	}

	/**
	 * A corrupt stage left by a crash cannot acquire a rollback receipt before its bytes are checked.
	 *
	 * @throws IOException if the test fixture cannot read or write its files
	 */
	@Test
	public void refusesCorruptUnjournaledStage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-stage-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.crashAfterStage = true;
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected process-loss simulation after private stage creation");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashBoundary").contains("stage created");
			}
			DeliveryTransaction transaction;
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				transaction = new DeliveryJournal(store.directory()).readLatest(transactionId);
			}
			Files.writeString(project.resolve(transaction.paths().getFirst().selectedStage()), "tampered");
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);
			try
			{
				restarted.recoverPending();
				throw new AssertionError("Expected corrupt private stage to require recovery");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "recoveryReason").contains("private");
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "visiblePredecessor").isEqualTo(
				"before");
			requireThat(new DeliveryJournal(store.directory()).readLatest(transaction.transactionId()).phase(),
				"recoveryPhase").isEqualTo(DeliveryPhase.RECOVERY_REQUIRED);
			requireThat(Files.notExists(store.directory().resolve("delivery-receipt-indexes").resolve(
				transaction.transactionId() + ".json")), "noRollbackReceipt").isTrue();
		}
	}

	/**
	 * Restart rolls back an earlier applied file after a second predecessor capture interrupts a two-file delivery.
	 *
	 * @throws IOException if fixture I/O or Git execution fails
	 * @throws InterruptedException if Git fixture execution is interrupted
	 */
	@Test
	public void recoversInterruptedSecondPath() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-multi-path-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/first.md"), "first before");
			Files.writeString(project.resolve("prompts/second.md"), "second before");
			Files.writeString(project.resolve("user.txt"), "user before");
			Files.writeString(selectedProject.resolve("prompts/first.md"), "first after");
			Files.writeString(selectedProject.resolve("prompts/second.md"), "second after");
			runGit(project, "init", "-q");
			runGit(project, "config", "user.name", "CAT Test");
			runGit(project, "config", "user.email", "cat-test@example.invalid");
			runGit(project, "add", ".");
			runGit(project, "commit", "-qm", "baseline");
			Files.writeString(project.resolve("user.txt"), "pre-existing user edit");
			String beforeDiff = runGit(project, "diff", "--binary");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts")));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			CampaignBinding binding = store.binding();
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(baseline);
			snapshots.save(selected);
			EditableRoot root = new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY);
			EvidenceRef rootsRef = new CandidateEvidenceRepository(store.directory(), binding).publish(
				"confirmed-editable-roots", new ConfirmedEditableRoots(2, project.toRealPath(),
					CandidateTestRoots.contract(store.directory()),
					CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.allowSelectedLink = true;
			operations.crashAtCapture = 2;
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected process loss after second predecessor capture");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashBoundary").contains("capture completed");
			}
			requireThat(Files.readString(project.resolve("prompts/first.md")), "firstAppliedBeforeCrash").isEqualTo(
				"first after");
			requireThat(Files.notExists(project.resolve("prompts/second.md")), "secondCapturedBeforeCrash").isTrue();
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/first.md")), "firstRestored").isEqualTo(
				"first before");
			requireThat(Files.readString(project.resolve("prompts/second.md")), "secondRestored").isEqualTo(
				"second before");
			requireThat(Files.readString(project.resolve("user.txt")), "userEditPreserved").isEqualTo(
				"pre-existing user edit");
			requireThat(runGit(project, "diff", "--binary"), "gitDiffPreserved").isEqualTo(beforeDiff);
			requireThat(new DeliveryJournal(store.directory()).readLatest(recovery.transactionId().orElseThrow()).phase(),
				"terminalPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
			requireThat(recovery.receiptId().isPresent(), "receiptPublished").isTrue();
		}
	}

	/** A competing second target does not change the first path's applied receipt outcome. */
	@Test
	public void recordsEachPreservedPathOutcome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-mixed-path-outcomes-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			for (String name: List.of("first.md", "second.md"))
			{
				Files.writeString(project.resolve("prompts").resolve(name), name + " before");
				Files.writeString(selectedProject.resolve("prompts").resolve(name), name + " after");
			}
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot selected = PromptSnapshot.capture(selectedProject, List.of(Path.of("prompts")));
			CampaignStore store = CampaignStore.initialize(parent.resolve("campaign"), "campaign",
				new ModelProfile("test-model", Optional.empty(), Map.of()));
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			snapshots.save(baseline);
			snapshots.save(selected);
			EditableRoot root = new EditableRoot(Path.of("prompts"), EditableRoot.Kind.DIRECTORY);
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(), store.binding());
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), CandidateTestRoots.contract(store.directory()),
				CandidateTestRoots.baseline(project, store.directory(), List.of(root)), List.of(root)));
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.allowSelectedLink = true;
			operations.createForeignTargetAtCapture = 2;
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), store.binding(), snapshots,
				rootsRef, operations);
			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected a competing second target");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "competingTarget").contains("External target appeared");
			}
			Path receiptIndexDirectory = store.directory().resolve("delivery-receipt-indexes");
			Path indexPath;
			try (Stream<Path> indexes = Files.list(receiptIndexDirectory))
			{
				indexPath = indexes.toList().getFirst();
			}
			DeliveryReceiptIndex index = SnapshotJson.mapper().readValue(Files.readAllBytes(indexPath),
				DeliveryReceiptIndex.class);
			DeliveryReceipt receipt = evidence.read(index.receiptRef(), "delivery-receipts", DeliveryReceipt.class);
			requireThat(receipt.outcome(), "transactionOutcome").isEqualTo(
				DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED);
			requireThat(receipt.paths().getFirst().outcome(), "firstPathOutcome").isEqualTo(
				DeliveryPathReceipt.Outcome.APPLIED);
			requireThat(receipt.paths().get(1).outcome(), "secondPathOutcome").isEqualTo(
				DeliveryPathReceipt.Outcome.CONCURRENT_EDIT_PRESERVED);
			requireThat(Files.readString(project.resolve("prompts/first.md")), "firstVisible").isEqualTo("first.md after");
			requireThat(Files.readString(project.resolve("prompts/second.md")), "secondVisible").isEqualTo(
				"concurrent user edit");
			DeliveryRecovery replay = new CandidateDelivery(project, store.directory(), store.binding(), snapshots,
				rootsRef).recoverPending();
			requireThat(replay.disposition(), "verifiedRestartReplay").isEqualTo(DeliveryRecovery.Disposition.NONE);
		}
	}

	/**
	 * Unsupported atomic capture before mutation reports its exact cause and leaves the predecessor visible.
	 *
	 * @throws IOException if the test fixture cannot read or write its files
	 */
	@Test
	public void classifiesUnsupportedAtomicCapture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-unsupported-capture-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.unsupportedAtomicBeforeCapture = true;
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected unsupported atomic capture");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.reason(), "failureReason").isEqualTo(
					CandidateDeliveryException.Reason.UNSUPPORTED_ATOMIC_PATH_OPERATION);
				requireThat(expected.getCause() instanceof AtomicMoveNotSupportedException, "atomicCause").isTrue();
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "predecessorBytes").isEqualTo(
				"before");
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				requireThat(new DeliveryJournal(store.directory()).readLatest(transactionId).phase(),
					"rollbackPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
			}
		}
	}

	/** A target changed before predecessor capture is a concurrent edit, not an unclassified I/O failure. */
	@Test
	public void classifiesConcurrentTargetBeforeCapture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-concurrent-capture-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Path target = project.resolve("prompts/rule.md");
			Files.writeString(target, "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.changeTargetBeforeCapture = target;
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected concurrent target edit");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.reason(), "failureReason").isEqualTo(
					CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION);
				requireThat(expected.affectedPaths(), "affectedPaths").isEqualTo(List.of(Path.of("prompts/rule.md")));
			}
			requireThat(Files.readString(target), "concurrentBytesPreserved").isEqualTo("concurrent user edit");
		}
	}

	/** A competing target after atomic predecessor capture remains visible and blocks candidate application. */
	@Test
	public void preservesTargetAfterCaptureRace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-capture-race-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Path target = project.resolve("prompts/rule.md");
			Files.writeString(target, "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.createForeignTargetAfterCapture = true;
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected competing target to stop delivery");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "failureReason").contains("External target appeared");
			}
			requireThat(Files.readString(target), "competingBytesPreserved").isEqualTo("concurrent user edit");
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				requireThat(new DeliveryJournal(store.directory()).readLatest(transactionId).phase(),
					"terminalPhase").isEqualTo(DeliveryPhase.CONCURRENT_EDIT_PRESERVED);
			}
		}
	}

	/** Unsupported rollback capture after visible publication retains the selected bytes for recovery. */
	@Test
	public void keepsUnsupportedRollbackForRecovery() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-unsupported-rollback-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Path target = project.resolve("prompts/rule.md");
			Files.writeString(target, "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.allowSelectedLink = true;
			operations.failAfterSelectedLinkForce = true;
			operations.unsupportedAtomicRollbackCapture = true;
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected rollback capture failure");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.reason(), "failureReason").isEqualTo(
					CandidateDeliveryException.Reason.RECOVERY_REQUIRED);
				requireThat(expected.transactionId().isPresent(), "transactionRecorded").isTrue();
				requireThat(expected.getCause() instanceof IOException, "originalCausePreserved").isTrue();
				requireThat(expected.getCause().getSuppressed().length, "rollbackCauseCount").isEqualTo(1);
				requireThat(expected.getCause().getSuppressed()[0] instanceof AtomicMoveNotSupportedException,
					"atomicRollbackCausePreserved").isTrue();
				requireThat(new DeliveryJournal(store.directory()).readLatest(expected.transactionId().orElseThrow()).phase(),
					"terminalPhase").isEqualTo(DeliveryPhase.RECOVERY_REQUIRED);
			}
			requireThat(Files.readString(target), "visibleSelectedBytes").isEqualTo("after");
		}
	}

	/**
	 * An I/O failure after atomic capture retains the moved predecessor for explicit recovery.
	 *
	 * @throws IOException if the test fixture cannot read or write its files
	 */
	@Test
	public void retainsCaptureAfterPostmutationFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-post-capture-failure-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.failAfterCaptureWithIOException = true;
			CandidateDelivery delivery = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				delivery.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected I/O failure after predecessor capture");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.reason(), "failureReason").isEqualTo(
					CandidateDeliveryException.Reason.RECOVERY_REQUIRED);
				requireThat(expected.getCause().getMessage(), "cause").contains("after capture");
			}
			DeliveryTransaction transaction;
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				transaction = new DeliveryJournal(store.directory()).readLatest(transactionId);
			}
			requireThat(transaction.phase(), "recoveryPhase").isEqualTo(DeliveryPhase.RECOVERY_REQUIRED);
			requireThat(Files.notExists(project.resolve("prompts/rule.md")), "targetNotRecreated").isTrue();
			requireThat(Files.readString(project.resolve(transaction.paths().getFirst().predecessorCapture())),
				"capturedPredecessor").isEqualTo("before");
			requireThat(Files.notExists(store.directory().resolve("delivery-receipt-indexes").resolve(
				transaction.transactionId() + ".json")), "noRollbackReceipt").isTrue();
		}
	}

	/** Restart restores a predecessor captured just before process loss and archives rollback. */
	@Test
	public void recoversAfterCaptureBeforeJournal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-recovery-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("capture");
			}
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "recoveredBytes").isEqualTo("before");
			CandidateDelivery interruptedAgain = new CandidateDelivery(project, store.directory(), binding,
				snapshots, rootsRef, new FailSelectedLinkOperations(false, true));
			try
			{
				interruptedAgain.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected another simulated owner death");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "secondCrashPoint").contains("capture");
			}
			CandidateApplication resumed = restarted.reconcile(baseline.id(), null, selected.id());
			requireThat(resumed.disposition(), "resumedDisposition").isEqualTo(ApplyDisposition.APPLIED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "resumedBytes").isEqualTo("after");
			CandidateDelivery concurrentInterrupted = new CandidateDelivery(project, store.directory(), binding,
				snapshots, rootsRef, new FailSelectedLinkOperations(false, true));
			try
			{
				concurrentInterrupted.reconcile(baseline.id(), selected.id(), baseline.id());
				throw new AssertionError("Expected a third simulated owner death");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "thirdCrashPoint").contains("capture");
			}
			Files.writeString(project.resolve("prompts/rule.md"), "later user edit");

			DeliveryRecovery preserved = restarted.recoverPending();

			requireThat(preserved.disposition(), "preservedDisposition").isEqualTo(
				DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "preservedBytes").isEqualTo(
				"later user edit");
		}
	}

	/** Restart restores the predecessor when publication linked the target before its journal phase. */
	@Test
	public void recoversAfterSelectedLinkBeforeJournal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-linked-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("selected link");
			}
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
			CandidateDelivery interruptedAgain = new CandidateDelivery(project, store.directory(), binding,
				snapshots, rootsRef, new FailSelectedLinkOperations(false, false, true));
			try
			{
				interruptedAgain.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected another linked publication crash");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "secondCrashPoint").contains("selected link");
			}
			Files.writeString(project.resolve("prompts/rule.md"), "user in-place edit");

			DeliveryRecovery concurrent = restarted.recoverPending();

			requireThat(concurrent.disposition(), "concurrentDisposition").isEqualTo(
				DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "inPlaceEdit").isEqualTo(
				"user in-place edit");
			try (Stream<Path> files = Files.list(project.resolve("prompts")))
			{
				requireThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".cat-delivery-")),
					"linkedAliasRemoved").isTrue();
			}
		}
	}

	/** Restart resolves a journaled selected link before the private alias can be unlinked. */
	@Test
	public void recoversJournaledSelectedLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-journaled-selected-link-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Path target = project.resolve("prompts/rule.md");
			Files.writeString(target, "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.allowSelectedLink = true;
			operations.crashBeforeSelectedUnlink = true;
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death before selected unlink");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("selected unlink");
			}
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				DeliveryPath path = new DeliveryJournal(store.directory()).readLatest(transactionId).paths().getFirst();
				requireThat(path.phase(), "journaledPhase").isEqualTo(DeliveryPathPhase.SELECTED_LINKED);
				requireThat(Files.isSameFile(target, project.resolve(path.selectedStage())), "privateAliasVisible").isTrue();
			}
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(target), "restoredBytes").isEqualTo("before");
			requireThat(recovery.receiptId().isPresent(), "receiptPublished").isTrue();
			try (Stream<Path> files = Files.list(project.resolve("prompts")))
			{
				requireThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".cat-delivery-")),
					"privateAliasRemoved").isTrue();
			}
		}
	}

	/**
	 * Recovery retains an unresolved journal when both aliases disappear after a selected link was recorded.
	 *
	 * @throws IOException if fixture I/O or Git execution fails
	 * @throws InterruptedException if Git fixture execution is interrupted
	 */
	@Test
	public void refusesMissingSelectedLinkAliases() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-missing-selected-aliases-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Path target = project.resolve("prompts/rule.md");
			Files.writeString(target, "before");
			Files.writeString(project.resolve("user.txt"), "user before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
			runGit(project, "init", "-q");
			runGit(project, "config", "user.name", "CAT Test");
			runGit(project, "config", "user.email", "cat-test@example.invalid");
			runGit(project, "add", ".");
			runGit(project, "commit", "-qm", "baseline");
			Files.writeString(project.resolve("user.txt"), "pre-existing user edit");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations();
			operations.allowSelectedLink = true;
			operations.crashBeforeSelectedUnlink = true;
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected owner death after the selected link was journaled");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("selected unlink");
			}
			String transactionId;
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				transactionId = transactions.toList().getFirst().getFileName().toString();
			}
			DeliveryPath path = new DeliveryJournal(store.directory()).readLatest(transactionId).paths().getFirst();
			requireThat(path.phase(), "journaledPhase").isEqualTo(DeliveryPathPhase.SELECTED_LINKED);
			Path selectedStage = project.resolve(path.selectedStage());
			// A competing process removes both names after CAT journals the selected link.
			Files.delete(target);
			Files.delete(selectedStage);
			String beforeRecoveryDiff = runGit(project, "diff", "--binary");
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);
			try
			{
				restarted.recoverPending();
				throw new AssertionError("Expected recovery-required refusal for missing aliases");
			}
			catch (IOException _)
			{
				requireThat(new DeliveryJournal(store.directory()).readLatest(transactionId).phase(),
					"journalPhase").isEqualTo(DeliveryPhase.RECOVERY_REQUIRED);
			}
			requireThat(Files.notExists(target), "targetRemainsAbsent").isTrue();
			requireThat(Files.readString(project.resolve("user.txt")), "userEditPreserved").isEqualTo(
				"pre-existing user edit");
			requireThat(runGit(project, "diff", "--binary"), "gitDiffPreserved").isEqualTo(beforeRecoveryDiff);
			requireThat(Files.notExists(store.directory().resolve("delivery-receipt-indexes").resolve(
				transactionId + ".json")), "noRollbackReceipt").isTrue();
		}
	}

	/**
	 * A foreign target replacing a linked selected file remains visible and keeps its exact Git diff after recovery.
	 *
	 * @param replacementBytes content written by the competing user process, including bytes equal to the candidate
	 * @param journaled whether CAT recorded the selected link before the simulated owner death
	 * @throws IOException if fixture I/O or Git execution fails
	 * @throws InterruptedException if Git fixture execution is interrupted
	 */
	@Test(dataProvider = "foreignContents")
	public void preservesForeignTargetAfterSelectedLink(String replacementBytes, boolean journaled) throws IOException,
		InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-foreign-target-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(project.resolve("user.txt"), "user before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "selected");
			runGit(project, "init", "-q");
			runGit(project, "config", "user.name", "CAT Test");
			runGit(project, "config", "user.email", "cat-test@example.invalid");
			runGit(project, "add", ".");
			runGit(project, "commit", "-qm", "baseline");
			Files.writeString(project.resolve("user.txt"), "pre-existing user edit");
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
			FailSelectedLinkOperations operations = new FailSelectedLinkOperations(false, false, !journaled);
			if (journaled)
			{
				operations.allowSelectedLink = true;
				operations.crashBeforeSelectedUnlink = true;
			}
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, operations);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected crash after selected link");
			}
			catch (SimulatedCrash expected)
			{
				String boundary;
				if (journaled)
					boundary = "selected unlink";
				else
					boundary = "selected link";
				requireThat(expected.getMessage(), "crashBoundary").contains(boundary);
			}
			try (Stream<Path> transactions = Files.list(store.directory().resolve("delivery-transactions")))
			{
				String transactionId = transactions.toList().getFirst().getFileName().toString();
				DeliveryPath path = new DeliveryJournal(store.directory()).readLatest(transactionId).paths().getFirst();
				DeliveryPathPhase expectedPhase;
				if (journaled)
					expectedPhase = DeliveryPathPhase.SELECTED_LINKED;
				else
					expectedPhase = DeliveryPathPhase.SELECTED_ABSENT;
				requireThat(path.phase(), "interruptedPhase").isEqualTo(expectedPhase);
			}
			Path target = project.resolve("prompts/rule.md");
			Files.delete(target);
			Files.writeString(target, replacementBytes);
			Path foreignIdentity = Files.createLink(parent.resolve("foreign-identity"), target);
			String beforeRecoveryDiff = runGit(project, "diff", "--binary");
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED);
			requireThat(Files.readString(target), "replacementBytes").isEqualTo(replacementBytes);
			requireThat(Files.isSameFile(target, foreignIdentity), "foreignIdentityPreserved").isTrue();
			requireThat(Files.readString(project.resolve("user.txt")), "userEditPreserved").isEqualTo(
				"pre-existing user edit");
			requireThat(runGit(project, "diff", "--binary"), "gitDiffPreserved").isEqualTo(beforeRecoveryDiff);
			DeliveryReceipt receipt = new CandidateEvidenceRepository(store.directory(), binding).read(
				new EvidenceRef("delivery-receipts", recovery.receiptId().orElseThrow()), "delivery-receipts",
				DeliveryReceipt.class);
			requireThat(receipt.outcome(), "receiptOutcome").isEqualTo(
				DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED);
		}
	}

	/**
	 * Includes a foreign inode whose bytes match the selected file as well as one with different bytes.
	 *
	 * @return competing target contents
	 */
	@DataProvider
	private Object[][] foreignContents()
	{
		return new Object[][]{{"foreign replacement", false}, {"selected", false}, {"selected", true}};
	}

	/**
	 * Recovery restores the predecessor when the selected private link was removed before de-alias journaling.
	 */
	@Test
	public void recoversAfterSelectedPrivateUnlink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-unlink-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death after private unlink");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("selected private unlink");
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedBeforeRecovery").isEqualTo(
				"after");
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
			requireThat(new DeliveryJournal(store.directory()).readLatest(recovery.transactionId().orElseThrow()).phase(),
				"terminalPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
		}
	}

	/**
	 * Recovery recognizes a predecessor restored just before process loss as its own bytes, not a competing edit.
	 */
	@Test
	public void recoversAfterRestoreLinkBeforeJournal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-restore-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, false, false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death after restore link");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("restore link");
			}
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBeforeRecovery").isEqualTo(
				"before");
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
		}
	}

	/**
	 * Recovery completes a journaled predecessor link after process loss before its private alias is removed.
	 */
	@Test
	public void recoversAfterRestoreLinkJournal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-restore-journal-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, false, false, false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death before predecessor unlink");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("predecessor unlink");
			}
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
			requireThat(new DeliveryJournal(store.directory()).readLatest(recovery.transactionId().orElseThrow()).phase(),
				"terminalPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
		}
	}

	/**
	 * Recovery recognizes a journaled restore whose predecessor alias was already removed before process loss.
	 */
	@Test
	public void recoversAfterRestorePrivateUnlink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-restore-unlink-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, false, false, false, false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death after predecessor unlink");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("predecessor private unlink");
			}
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
			requireThat(new DeliveryJournal(store.directory()).readLatest(recovery.transactionId().orElseThrow()).phase(),
				"terminalPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
		}
	}

	/**
	 * Recovery resumes restoration after process loss while the predecessor remains captured and target absent.
	 */
	@Test
	public void recoversBeforeRestoreLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-before-restore-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, false, false, false, false, false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated owner death before predecessor restore link");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("before restore link");
			}
			requireThat(Files.exists(project.resolve("prompts/rule.md")), "targetBeforeRecovery").isFalse();
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.ROLLED_BACK);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "restoredBytes").isEqualTo("before");
			requireThat(new DeliveryJournal(store.directory()).readLatest(recovery.transactionId().orElseThrow()).phase(),
				"terminalPhase").isEqualTo(DeliveryPhase.ROLLED_BACK);
		}
	}

	/** Restart finishes private cleanup after the applied receipt was already indexed. */
	@Test
	public void recoversAfterReceiptBeforeCleanup() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-cleanup-crash-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef, new FailSelectedLinkOperations(false, false, false, true));
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected simulated cleanup loss");
			}
			catch (SimulatedCrash expected)
			{
				requireThat(expected.getMessage(), "crashPoint").contains("cleanup");
			}
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovered = restarted.recoverPending();

			requireThat(recovered.disposition(), "recoveredDisposition").isEqualTo(
				DeliveryRecovery.Disposition.APPLIED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedBytes").isEqualTo("after");
			try (Stream<Path> files = Files.list(project.resolve("prompts")))
			{
				requireThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".cat-delivery-")),
					"privateCleanupComplete").isTrue();
			}
		}
	}

	/**
	 * Restart publishes the original selected-identity receipt after the applied phase could not index it.
	 */
	@Test
	public void recoversAppliedBeforeReceiptIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("candidate-delivery-applied-no-receipt-").toRealPath());
			Path project = Files.createDirectory(parent.resolve("project"));
			Path selectedProject = Files.createDirectory(parent.resolve("selected"));
			Files.createDirectories(project.resolve("prompts"));
			Files.createDirectories(selectedProject.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "before");
			Files.writeString(selectedProject.resolve("prompts/rule.md"), "after");
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
			Path receiptIndexDirectory = store.directory().resolve("delivery-receipt-indexes");
			Files.writeString(receiptIndexDirectory, "blocked");
			CandidateDelivery interrupted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);
			try
			{
				interrupted.reconcile(baseline.id(), null, selected.id());
				throw new AssertionError("Expected receipt-index publication failure");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "failureReason").contains("delivery-receipt-indexes");
			}
			Path transactionRoot = store.directory().resolve("delivery-transactions");
			String transactionId;
			try (Stream<Path> transactions = Files.list(transactionRoot))
			{
				transactionId = transactions.toList().getFirst().getFileName().toString();
			}
			requireThat(new DeliveryJournal(store.directory()).readLatest(transactionId).phase(), "appliedPhase").
				isEqualTo(DeliveryPhase.APPLIED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedBytes").isEqualTo("after");
			Files.delete(receiptIndexDirectory);
			CandidateDelivery restarted = new CandidateDelivery(project, store.directory(), binding, snapshots,
				rootsRef);

			DeliveryRecovery recovery = restarted.recoverPending();

			requireThat(recovery.disposition(), "recoveryDisposition").isEqualTo(
				DeliveryRecovery.Disposition.APPLIED);
			requireThat(recovery.transactionId().orElseThrow(), "recoveredTransactionId").isEqualTo(transactionId);
			requireThat(recovery.receiptId().orElseThrow(), "recoveredReceiptId").isNotBlank();
			DeliveryReceipt receipt = new CandidateEvidenceRepository(store.directory(), binding).read(
				new EvidenceRef("delivery-receipts", recovery.receiptId().orElseThrow()), "delivery-receipts",
				DeliveryReceipt.class);
			requireThat(receipt.selectedCandidateId(), "receiptSelectedCandidateId").isEqualTo(selected.id());
			requireThat(receipt.outcome(), "receiptOutcome").isEqualTo(DeliveryReceiptOutcome.APPLIED);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "selectedBytesAfterRecovery").
				isEqualTo("after");
			try (Stream<Path> visibleFiles = Files.list(project.resolve("prompts")))
			{
				requireThat(visibleFiles.noneMatch(path -> path.getFileName().toString().startsWith(
					".cat-delivery-")), "privateCleanupComplete").isTrue();
			}
			requireThat(restarted.recoverPending().disposition(), "secondRecovery").isEqualTo(
				DeliveryRecovery.Disposition.NONE);
		}
	}

	/** Fails only the selected hard-link operation while permitting rollback restoration. */
	private static final class FailSelectedLinkOperations implements CandidateFileOperations
	{
		private final CandidateFileOperations delegate = new JdkCandidateFileOperations();
		private final boolean failStage;
		private final boolean crashAfterCapture;
		private final boolean crashAfterSelectedLink;
		private final boolean crashDuringCleanup;
		private final boolean crashAfterSelectedUnlink;
		private final boolean crashAfterRestoreLink;
		private final boolean crashBeforeRestoreUnlink;
		private final boolean crashAfterRestoreUnlink;
		private final boolean crashBeforeRestoreLink;
		private boolean crashAfterDirectory;
		private boolean crashAfterStage;
		private boolean allowSelectedLink;
		private boolean unsupportedAtomicBeforeCapture;
		private boolean failAfterCaptureWithIOException;
		private boolean unsupportedAtomicRollbackCapture;
		private boolean failAfterSelectedLinkForce;
		private boolean selectedLinkPublished;
		private boolean selectedLinkForceFailed;
		private boolean crashBeforeSelectedUnlink;
		private boolean createForeignTargetAfterCapture;
		private int createForeignTargetAtCapture;
		private Path changeTargetBeforeCapture;
		private boolean stagedForTargetChange;
		private int crashAtCapture;
		private int captureCount;

		/** Creates a selected-link failure after staging. */
		private FailSelectedLinkOperations()
		{
			this(false, false);
		}

		/**
		 * Chooses whether to fail staging before the first visible mutation.
		 *
		 * @param failStage whether stage creation fails
		 */
		private FailSelectedLinkOperations(boolean failStage)
		{
			this(failStage, false);
		}

		/**
		 * Chooses a simulated process-loss point after atomic predecessor capture.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether to stop after capture before journal publication
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture)
		{
			this(failStage, crashAfterCapture, false);
		}

		/**
		 * Chooses a simulated process-loss point after selected publication.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink)
		{
			this(failStage, crashAfterCapture, crashAfterSelectedLink, false);
		}

		/**
		 * Chooses a simulated process-loss point after receipt publication.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 * @param crashDuringCleanup whether owned-private deletion crashes
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink, boolean crashDuringCleanup)
		{
			this(failStage, crashAfterCapture, crashAfterSelectedLink, crashDuringCleanup, false);
		}

		/**
		 * Chooses a simulated process-loss point after the selected private link is removed.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 * @param crashDuringCleanup whether owned-private deletion crashes
		 * @param crashAfterSelectedUnlink whether private unlink crashes before de-alias journaling
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink, boolean crashDuringCleanup, boolean crashAfterSelectedUnlink)
		{
			this(failStage, crashAfterCapture, crashAfterSelectedLink, crashDuringCleanup,
				crashAfterSelectedUnlink, false);
		}

		/**
		 * Chooses a simulated process-loss point after linking the predecessor during rollback.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 * @param crashDuringCleanup whether owned-private deletion crashes
		 * @param crashAfterSelectedUnlink whether private unlink crashes before de-alias journaling
		 * @param crashAfterRestoreLink whether restore link crashes before journaling
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink, boolean crashDuringCleanup, boolean crashAfterSelectedUnlink,
			boolean crashAfterRestoreLink)
		{
			this(failStage, crashAfterCapture, crashAfterSelectedLink, crashDuringCleanup,
				crashAfterSelectedUnlink, crashAfterRestoreLink, false);
		}

		/**
		 * Chooses a simulated process-loss point after journaling the predecessor restore link.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 * @param crashDuringCleanup whether owned-private deletion crashes
		 * @param crashAfterSelectedUnlink whether private selected unlink crashes before journaling
		 * @param crashAfterRestoreLink whether restore link crashes before journaling
		 * @param crashBeforeRestoreUnlink whether restore crashes before private predecessor unlink
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink, boolean crashDuringCleanup, boolean crashAfterSelectedUnlink,
			boolean crashAfterRestoreLink, boolean crashBeforeRestoreUnlink)
		{
			this(failStage, crashAfterCapture, crashAfterSelectedLink, crashDuringCleanup,
				crashAfterSelectedUnlink, crashAfterRestoreLink, crashBeforeRestoreUnlink, false);
		}

		/**
		 * Chooses a simulated process-loss point after removing the private predecessor alias.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 * @param crashDuringCleanup whether owned-private deletion crashes
		 * @param crashAfterSelectedUnlink whether private selected unlink crashes before journaling
		 * @param crashAfterRestoreLink whether restore link crashes before journaling
		 * @param crashBeforeRestoreUnlink whether restore crashes before private predecessor unlink
		 * @param crashAfterRestoreUnlink whether restore crashes after private predecessor unlink
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink, boolean crashDuringCleanup, boolean crashAfterSelectedUnlink,
			boolean crashAfterRestoreLink, boolean crashBeforeRestoreUnlink, boolean crashAfterRestoreUnlink)
		{
			this(failStage, crashAfterCapture, crashAfterSelectedLink, crashDuringCleanup,
				crashAfterSelectedUnlink, crashAfterRestoreLink, crashBeforeRestoreUnlink, crashAfterRestoreUnlink, false);
		}

		/**
		 * Chooses a simulated process-loss point before linking the captured predecessor.
		 *
		 * @param failStage whether stage creation fails
		 * @param crashAfterCapture whether predecessor capture crashes
		 * @param crashAfterSelectedLink whether selected link crashes before journaling
		 * @param crashDuringCleanup whether owned-private deletion crashes
		 * @param crashAfterSelectedUnlink whether private selected unlink crashes before journaling
		 * @param crashAfterRestoreLink whether restore link crashes before journaling
		 * @param crashBeforeRestoreUnlink whether restore crashes before private predecessor unlink
		 * @param crashAfterRestoreUnlink whether restore crashes after private predecessor unlink
		 * @param crashBeforeRestoreLink whether restore crashes before linking the predecessor
		 */
		private FailSelectedLinkOperations(boolean failStage, boolean crashAfterCapture,
			boolean crashAfterSelectedLink, boolean crashDuringCleanup, boolean crashAfterSelectedUnlink,
			boolean crashAfterRestoreLink, boolean crashBeforeRestoreUnlink, boolean crashAfterRestoreUnlink,
			boolean crashBeforeRestoreLink)
		{
			this.failStage = failStage;
			this.crashAfterCapture = crashAfterCapture;
			this.crashAfterSelectedLink = crashAfterSelectedLink;
			this.crashDuringCleanup = crashDuringCleanup;
			this.crashAfterSelectedUnlink = crashAfterSelectedUnlink;
			this.crashAfterRestoreLink = crashAfterRestoreLink;
			this.crashBeforeRestoreUnlink = crashBeforeRestoreUnlink;
			this.crashAfterRestoreUnlink = crashAfterRestoreUnlink;
			this.crashBeforeRestoreLink = crashBeforeRestoreLink;
		}

		@Override
		public void writeAndForceNew(Path privateStage, byte[] bytes) throws IOException
		{
			if (failStage)
				throw new IOException("stage failure");
			delegate.writeAndForceNew(privateStage, bytes);
			if (crashAfterStage)
				throw new SimulatedCrash("stage created before journal");
		}

		@Override
		public PathInspection captureAtomically(Path observedTarget, Path absentPrivateSibling) throws IOException
		{
			if (unsupportedAtomicBeforeCapture)
				throw new AtomicMoveNotSupportedException(observedTarget.toString(), absentPrivateSibling.toString(),
					"fixture does not support atomic move");
			if (unsupportedAtomicRollbackCapture && absentPrivateSibling.getFileName().toString().endsWith("-rollback"))
				throw new AtomicMoveNotSupportedException(observedTarget.toString(), absentPrivateSibling.toString(),
					"fixture does not support atomic rollback capture");
			PathInspection captured = delegate.captureAtomically(observedTarget, absentPrivateSibling);
			if ((createForeignTargetAfterCapture || createForeignTargetAtCapture == captureCount + 1) &&
				absentPrivateSibling.getFileName().toString().endsWith("-predecessor"))
			{
				// A concurrent user process publishes its own target after CAT moves the predecessor.
				Files.writeString(observedTarget, "concurrent user edit");
			}
			if (failAfterCaptureWithIOException)
				throw new IOException("I/O failed after capture");
			++captureCount;
			if (crashAfterCapture || captureCount == crashAtCapture)
				throw new SimulatedCrash("capture completed before journal");
			return captured;
		}

		@Override
		public LinkPublication linkNew(Path privateSource, Path absentTarget, String expectedSha256) throws IOException
		{
			if (crashBeforeRestoreLink && privateSource.getFileName().toString().endsWith("-predecessor"))
				throw new SimulatedCrash("before restore link");
			if (crashAfterRestoreLink && privateSource.getFileName().toString().endsWith("-predecessor"))
			{
				delegate.linkNew(privateSource, absentTarget, expectedSha256);
				throw new SimulatedCrash("restore link completed before journal");
			}
			if (!failStage && privateSource.getFileName().toString().endsWith("-stage"))
			{
				if (!allowSelectedLink && !crashAfterSelectedLink && !crashDuringCleanup &&
					!crashAfterSelectedUnlink)
					throw new IOException("selected link failure");
				LinkPublication linked = delegate.linkNew(privateSource, absentTarget, expectedSha256);
				if (crashAfterSelectedLink)
					throw new SimulatedCrash("selected link completed before journal");
				selectedLinkPublished = true;
				return linked;
			}
			return delegate.linkNew(privateSource, absentTarget, expectedSha256);
		}

		@Override
		public boolean sameFile(Path first, Path second) throws IOException
		{
			return delegate.sameFile(first, second);
		}

		@Override
		public PathInspection inspect(Path path) throws IOException
		{
			PathInspection inspected = delegate.inspect(path);
			if (path.getFileName().toString().endsWith("-stage") && inspected.presence() == DeliveryPresence.REGULAR)
				stagedForTargetChange = true;
			if (stagedForTargetChange && path.equals(changeTargetBeforeCapture))
			{
				Files.writeString(path, "concurrent user edit");
				stagedForTargetChange = false;
				return delegate.inspect(path);
			}
			return inspected;
		}

		@Override
		public void unlinkPrivate(Path privatePath) throws IOException
		{
			if (crashBeforeSelectedUnlink && privatePath.getFileName().toString().endsWith("-stage"))
				throw new SimulatedCrash("before selected unlink");
			if (crashBeforeRestoreUnlink && privatePath.getFileName().toString().endsWith("-predecessor"))
				throw new SimulatedCrash("predecessor unlink interrupted after restore journal");
			delegate.unlinkPrivate(privatePath);
			if (crashAfterSelectedUnlink && privatePath.getFileName().toString().endsWith("-stage"))
				throw new SimulatedCrash("selected private unlink completed before journal");
			if (crashAfterRestoreUnlink && privatePath.getFileName().toString().endsWith("-predecessor"))
				throw new SimulatedCrash("predecessor private unlink completed before journal");
		}

		@Override
		public void deletePrivate(Path privatePath) throws IOException
		{
			if (crashDuringCleanup)
				throw new SimulatedCrash("cleanup interrupted after receipt");
			delegate.deletePrivate(privatePath);
		}

		@Override
		public void createDirectoryNew(Path absentDirectory) throws IOException
		{
			delegate.createDirectoryNew(absentDirectory);
			if (crashAfterDirectory)
				throw new SimulatedCrash("directory created before journal");
		}

		@Override
		public void deleteEmptyOwnedDirectory(Path directory) throws IOException
		{
			delegate.deleteEmptyOwnedDirectory(directory);
		}

		@Override
		public void forceDirectory(Path directory) throws IOException
		{
			if (selectedLinkPublished && failAfterSelectedLinkForce && !selectedLinkForceFailed)
			{
				selectedLinkForceFailed = true;
				throw new IOException("force failed after selected publication");
			}
			delegate.forceDirectory(directory);
		}
	}

	/**
	 * Runs one Git operation in a test-owned worktree.
	 *
	 * @param project test-owned worktree
	 * @param arguments Git subcommand and arguments
	 * @return standard output from Git
	 * @throws IOException if Git fails
	 * @throws InterruptedException if Git is interrupted
	 */
	private static String runGit(Path project, String... arguments) throws IOException, InterruptedException
	{
		List<String> command = new ArrayList<>();
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

	/** Test-only abrupt process-loss signal that bypasses ordinary IOException rollback. */
	private static final class SimulatedCrash extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates an abrupt failure marker.
		 *
		 * @param message simulated crash point
		 */
		private SimulatedCrash(String message)
		{
			super(message);
		}
	}
}
