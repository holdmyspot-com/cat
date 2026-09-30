package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Journaled selected-snapshot application with capture-first user-path publication. */
final class DeliveryTransactionEngine
{
	private static final Object JVM_MUTEX = new Object();
	private final Path project;
	private final Path campaignDirectory;
	private final PromptSnapshotRepository snapshots;
	private final CandidateEvidenceRepository evidence;
	private final DeliveryWriteAuthority authority;
	private final EvidenceRef confirmedRootsRef;
	private final DeliveryJournal journal;
	private final CandidateFileOperations files;

	/**
	 * Opens a transaction engine only for the matching pinned C4 roots and C1 campaign.
	 *
	 * @param project selected active project
	 * @param campaignDirectory pinned campaign directory
	 * @param binding expected C1 binding
	 * @param snapshots campaign snapshot repository
	 * @param confirmedRootsRef immutable C4 roots
	 * @param authority exact write authority
	 * @param files package-owned file operation boundary
	 * @throws IOException if the authority or campaign does not match
	 */
	DeliveryTransactionEngine(Path project, Path campaignDirectory, CampaignBinding binding,
		PromptSnapshotRepository snapshots, EvidenceRef confirmedRootsRef, DeliveryWriteAuthority authority,
		CandidateFileOperations files) throws IOException
	{
		this.project = Objects.requireNonNull(project, "project").toRealPath();
		this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory").toRealPath();
		this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
		this.authority = Objects.requireNonNull(authority, "authority");
		this.confirmedRootsRef = Objects.requireNonNull(confirmedRootsRef, "confirmedRootsRef");
		this.files = Objects.requireNonNull(files, "files");
		evidence = new CandidateEvidenceRepository(this.campaignDirectory, binding);
		ConfirmedEditableRoots roots = evidence.read(confirmedRootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		if (!roots.projectRoot().equals(this.project) || !roots.roots().equals(authority.roots()))
			throw new IOException("Delivery authority differs from pinned project or confirmed roots");
		ConfirmedSourceVerifier.load(this.campaignDirectory, roots, snapshots);
		journal = new DeliveryJournal(this.campaignDirectory);
	}

	/**
	 * Pins the active Git worktree and exact incumbent before a generation native call starts.
	 *
	 * @param requestedProject selected active worktree
	 * @param incumbentId saved applied incumbent snapshot
	 * @param rootsRef exact confirmed-root evidence
	 * @param logicalCallId fresh generation call identity
	 * @return immutable published live checkpoint
	 * @throws IOException if the Git worktree or incumbent state differs
	 */
	LiveGenerationCheckpoint prepareLiveGeneration(Path requestedProject, String incumbentId, EvidenceRef rootsRef,
		String logicalCallId) throws IOException
	{
		synchronized (JVM_MUTEX)
		{
			Path lockPath = campaignDirectory.resolve(".delivery.lock");
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Delivery lock was not acquired");
				DeliveryRecovery recovery = recoverPendingLocked();
				if (recovery.disposition() == DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED)
					throw new IOException("Concurrent delivery edit forbids generation checkpoint");
				if (!requestedProject.toRealPath().equals(project) || !confirmedRootsRef.equals(rootsRef))
					throw new IOException("Live generation project or confirmed roots differ from delivery authority");
				ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
					ConfirmedEditableRoots.class);
				if (!roots.projectRoot().equals(project) || !roots.roots().equals(authority.roots()))
					throw new IOException("Live generation confirmed roots differ from pinned authority");
				ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots, snapshots);
				ConfirmedSourceVerifier.verifyLive(project, baseline, authority);
				PromptSnapshot incumbent = snapshots.load(incumbentId);
				PromptSnapshot live = PromptSnapshot.capture(project,
					authority.roots().stream().map(EditableRoot::path).toList());
				if (!matchesEditable(live, incumbent))
					throw new CandidateDeliveryException(CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION,
						"Live prompt roots differ from applied incumbent", Optional.empty(),
						authority.roots().stream().map(EditableRoot::path).toList());
				LiveGitInventory inventory = LiveGitInventory.capture(project);
				EvidenceRef inventoryRef = evidence.publish("git-file-inventories", inventory);
				LiveGenerationCheckpoint checkpoint = new LiveGenerationCheckpoint(
					LiveGenerationCheckpoint.SCHEMA_VERSION, UUID.randomUUID().toString(), logicalCallId,
					project, incumbentId, rootsRef, inventoryRef, Optional.empty());
				EvidenceRef checkpointRef = evidence.publish("live-generation-checkpoints", checkpoint);
				if (!checkpoint.checkpointRef().equals(checkpointRef))
					throw new IOException("Live checkpoint content address differs from published evidence");
				return checkpoint;
			}
		}
	}

	/**
	 * Reconciles saved immutable snapshots under both JVM and cross-process campaign locks.
	 *
	 * @param baselineId initial snapshot
	 * @param previousId previously applied snapshot or null
	 * @param selectedId selected snapshot
	 * @return durable receipt-bearing application
	 * @throws IOException if live state differs or a transaction cannot complete
	 */
	CandidateApplication reconcile(String baselineId, String previousId, String selectedId) throws IOException
	{
		return reconcile(baselineId, previousId, selectedId, null);
	}

	/**
	 * Applies an incumbent restoration under a transaction identity derived from the admitted capture.
	 *
	 * @param candidateId captured candidate identity
	 * @param incumbentId original incumbent identity
	 * @param captureRef exact admitted capture
	 * @return receipt-bearing application
	 * @throws IOException if restoration cannot be safely journaled
	 */
	CandidateApplication reconcileRestore(String candidateId, String incumbentId, EvidenceRef captureRef)
		throws IOException
	{
		return reconcile(candidateId, candidateId, incumbentId, restoreTransactionId(captureRef));
	}

	/**
	 * Runs ordinary or capture-bound reconciliation under the same campaign lock.
	 *
	 * @param baselineId initial snapshot identity
	 * @param previousId prior applied snapshot, if any
	 * @param selectedId selected snapshot identity
	 * @param requestedTransactionId capture-bound journal identity, or null for ordinary selection
	 * @return archived application
	 * @throws IOException if reconciliation is unsafe
	 */
	private CandidateApplication reconcile(String baselineId, String previousId, String selectedId,
		String requestedTransactionId) throws IOException
	{
		synchronized (JVM_MUTEX)
		{
			Path lockPath = campaignDirectory.resolve(".delivery.lock");
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Delivery lock was not acquired");
				return reconcileLocked(baselineId, previousId, selectedId, requestedTransactionId);
			}
		}
	}

	/**
	 * Replays the one unfinished campaign transaction under the delivery lock.
	 *
	 * @return archived terminal outcome or NONE
	 */
	DeliveryRecovery recoverPending() throws IOException
	{
		synchronized (JVM_MUTEX)
		{
			Path lockPath = campaignDirectory.resolve(".delivery.lock");
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Delivery lock was not acquired");
				return recoverPendingLocked();
			}
		}
	}

	/**
	 * Finds the one applied transaction that restored a captured candidate to its incumbent.
	 *
	 * @param captureRef exact admitted capture
	 * @param candidateId captured candidate identity
	 * @param incumbentId original incumbent identity
	 * @return matching indexed receipt, if present
	 * @throws IOException if recovery or matching evidence is unsafe
	 */
	Optional<EvidenceRef> findAppliedRestoreReceipt(EvidenceRef captureRef, String candidateId, String incumbentId)
		throws IOException
	{
		synchronized (JVM_MUTEX)
		{
			Path lockPath = campaignDirectory.resolve(".delivery.lock");
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Delivery lock was not acquired");
				recoverPendingLocked();
				String transactionId = restoreTransactionId(captureRef);
				Path transactionDirectory = campaignDirectory.resolve("delivery-transactions").resolve(transactionId);
				if (Files.notExists(transactionDirectory, LinkOption.NOFOLLOW_LINKS))
					return Optional.empty();
				ConfirmedEditableRoots rootsRecord = evidence.read(confirmedRootsRef, "confirmed-editable-roots",
					ConfirmedEditableRoots.class);
				ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, rootsRecord,
					snapshots);
				List<Path> changedPaths = CandidateDiff.validate(snapshots.load(candidateId),
					snapshots.load(incumbentId), authority, baseline).changes().stream().map(CandidateDiff.Change::path).
					toList();
				DeliveryTransaction transaction = journal.readLatest(transactionId);
				if (transaction.phase() != DeliveryPhase.APPLIED ||
					!transaction.baselineId().equals(candidateId) ||
					!transaction.previousAppliedCandidateId().equals(Optional.of(candidateId)) ||
					!transaction.selectedCandidateId().equals(incumbentId) ||
					!transaction.confirmedRoots().equals(authority.roots()) ||
					!transaction.paths().stream().map(DeliveryPath::target).toList().equals(changedPaths))
					throw new IOException("Capture-bound restore transaction is not the expected applied transaction");
				DeliveryReceiptIndex index = readReceiptIndex(transactionId);
				if (index == null)
					throw new IOException("Applied restore has no delivery receipt index");
				verifyReceiptIndex(transaction, index);
				return Optional.of(index.receiptRef());
			}
		}
	}

	/**
	 * Derives the restore journal identity from its exact admitted capture.
	 *
	 * @param captureRef admitted capture reference
	 * @return deterministic restore transaction identity
	 */
	private static String restoreTransactionId(EvidenceRef captureRef)
	{
		if (!captureRef.collection().equals("live-generation-captures"))
			throw new IllegalArgumentException("Restore requires an admitted live capture reference");
		return "restore-" + captureRef.evidenceId();
	}

	/**
	 * Reads every journal before acting, so a malformed sibling cannot be overlooked.
	 *
	 * @return archived terminal outcome or NONE
	 * @throws IOException if a journal or live path is unsafe
	 */
	private DeliveryRecovery recoverPendingLocked() throws IOException
	{
		Path root = campaignDirectory.resolve("delivery-transactions");
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
			return DeliveryRecovery.none();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Delivery transaction root is unsafe: " + root);
		List<DeliveryTransaction> transactions = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(root))
		{
			for (Path entry: stream)
			{
				if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Delivery transaction entry is unsafe: " + entry);
				transactions.add(journal.readLatest(entry.getFileName().toString()));
			}
		}
		List<DeliveryTransaction> pending = new ArrayList<>();
		for (DeliveryTransaction transaction: transactions)
		{
			DeliveryReceiptIndex index = readReceiptIndex(transaction.transactionId());
			if (index != null)
				verifyReceiptIndex(transaction, index);
			if (index == null || hasPrivateArtifacts(transaction) ||
				transaction.phase() != DeliveryPhase.APPLIED && transaction.phase() != DeliveryPhase.ROLLED_BACK &&
				transaction.phase() != DeliveryPhase.CONCURRENT_EDIT_PRESERVED)
				pending.add(transaction);
		}
		if (pending.isEmpty())
			return DeliveryRecovery.none();
		if (pending.size() != 1)
			throw new IOException("More than one unfinished delivery transaction requires recovery");
		DeliveryTransaction transaction = pending.getFirst();
		if (!transaction.confirmedRoots().equals(authority.roots()))
			throw new IOException("Pending delivery roots differ from pinned confirmed roots");
		ConfirmedEditableRoots rootsRecord = evidence.read(confirmedRootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedSourceVerifier.load(campaignDirectory, rootsRecord, snapshots);
		if (transaction.phase() == DeliveryPhase.RECOVERY_REQUIRED)
			throw new IOException("Delivery transaction requires manual recovery: " + transaction.transactionId());
		if (transaction.phase() == DeliveryPhase.APPLIED || transaction.phase() == DeliveryPhase.ROLLED_BACK ||
			transaction.phase() == DeliveryPhase.CONCURRENT_EDIT_PRESERVED)
		{
			DeliveryReceiptOutcome outcome = switch (transaction.phase())
			{
				case APPLIED ->
				{
					if (transaction.kind() == DeliveryKind.NO_OP)
						yield DeliveryReceiptOutcome.ALREADY_APPLIED;
					yield DeliveryReceiptOutcome.APPLIED;
				}
				case ROLLED_BACK -> DeliveryReceiptOutcome.ROLLED_BACK;
				case CONCURRENT_EDIT_PRESERVED -> DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED;
				default -> throw new IllegalStateException("Terminal phase changed during recovery");
			};
			EvidenceRef receipt = publishReceipt(transaction, outcome);
			cleanupPrivate(transaction.paths());
			DeliveryRecovery.Disposition disposition = switch (transaction.phase())
			{
				case APPLIED -> DeliveryRecovery.Disposition.APPLIED;
				case ROLLED_BACK -> DeliveryRecovery.Disposition.ROLLED_BACK;
				case CONCURRENT_EDIT_PRESERVED -> DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED;
				default -> throw new IllegalStateException("Terminal phase changed during recovery");
			};
			return new DeliveryRecovery(disposition, Optional.of(transaction.transactionId()),
				Optional.of(receipt.evidenceId()), transaction.paths().stream().map(DeliveryPath::target).toList());
		}
		rollBack(transaction);
		DeliveryTransaction terminal = journal.readLatest(transaction.transactionId());
		DeliveryReceiptOutcome outcome = DeliveryReceiptOutcome.ROLLED_BACK;
		DeliveryRecovery.Disposition disposition = DeliveryRecovery.Disposition.ROLLED_BACK;
		if (terminal.phase() == DeliveryPhase.CONCURRENT_EDIT_PRESERVED)
		{
			outcome = DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED;
			disposition = DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED;
		}
		EvidenceRef receipt = publishReceipt(terminal, outcome);
		return new DeliveryRecovery(disposition,
			Optional.of(terminal.transactionId()), Optional.of(receipt.evidenceId()),
			terminal.paths().stream().map(DeliveryPath::target).toList());
	}

	/**
	 * Rehashes every indexed receipt before declaring a transaction already archived.
	 *
	 * @param transaction latest validated journal phase
	 * @param index create-once receipt binding
	 * @throws IOException if receipt content or identity conflicts
	 */
	private void verifyReceiptIndex(DeliveryTransaction transaction, DeliveryReceiptIndex index) throws IOException
	{
		DeliveryReceipt receipt = evidence.read(index.receiptRef(), "delivery-receipts", DeliveryReceipt.class);
		String finalHash = PromptSnapshot.sha256(SnapshotJson.mapper().writeValueAsBytes(transaction));
		DeliveryReceiptOutcome expectedOutcome = switch (transaction.phase())
		{
			case APPLIED ->
			{
				if (transaction.kind() == DeliveryKind.NO_OP)
					yield DeliveryReceiptOutcome.ALREADY_APPLIED;
				yield DeliveryReceiptOutcome.APPLIED;
			}
			case ROLLED_BACK -> DeliveryReceiptOutcome.ROLLED_BACK;
			case CONCURRENT_EDIT_PRESERVED -> DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED;
			default -> throw new IOException("Indexed delivery receipt names a nonterminal transaction");
		};
		if (!index.finalTransactionSha256().equals(finalHash) ||
			!receipt.finalTransactionSha256().equals(finalHash) || receipt.outcome() != index.outcome() ||
			index.outcome() != expectedOutcome ||
			!receipt.transactionId().equals(transaction.transactionId()) ||
			!receipt.baselineId().equals(transaction.baselineId()) ||
			!receipt.previousAppliedCandidateId().equals(transaction.previousAppliedCandidateId()) ||
			!receipt.selectedCandidateId().equals(transaction.selectedCandidateId()) ||
			!receipt.paths().equals(pathReceipts(transaction, expectedOutcome)))
			throw new IOException("Indexed delivery receipt conflicts with terminal transaction");
	}

	/**
	 * Detects interrupted cleanup without touching any user target.
	 *
	 * @param transaction terminal or pending journal state
	 * @return whether a transaction-owned private path remains
	 * @throws IOException if a private path is unsafe
	 */
	private boolean hasPrivateArtifacts(DeliveryTransaction transaction) throws IOException
	{
		for (DeliveryPath path: transaction.paths())
		{
			for (Path relative: List.of(path.selectedStage(), path.predecessorCapture(), path.rollbackCapture()))
			{
				if (files.inspect(project.resolve(relative)).presence() == DeliveryPresence.REGULAR)
					return true;
			}
		}
		return false;
	}

	/**
	 * Executes one lock-owned transaction after rehashing all supplied snapshots.
	 *
	 * @param baselineId initial snapshot
	 * @param previousId previously applied snapshot or null
	 * @param selectedId selected snapshot
	 * @param requestedTransactionId capture-bound journal identity, or null for ordinary selection
	 * @return archived application
	 */
	private CandidateApplication reconcileLocked(String baselineId, String previousId, String selectedId,
		String requestedTransactionId)
		throws IOException
	{
		DeliveryRecovery recovery = recoverPendingLocked();
		if (recovery.disposition() == DeliveryRecovery.Disposition.CONCURRENT_EDIT_PRESERVED)
			throw new IOException("Concurrent edit requires explicit resolution before reconciliation");
		PromptSnapshot baseline = snapshots.load(baselineId);
		PromptSnapshot predecessor = baseline;
		if (previousId != null)
			predecessor = snapshots.load(previousId);
		PromptSnapshot selected = snapshots.load(selectedId);
		ConfirmedEditableRoots rootsRecord = evidence.read(confirmedRootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedSourceBaseline sourceBaseline = ConfirmedSourceVerifier.load(campaignDirectory, rootsRecord,
			snapshots);
		ConfirmedSourceVerifier.verifyLive(project, sourceBaseline, authority);
		CandidateDiff diff = CandidateDiff.validate(predecessor, selected, authority, sourceBaseline);
		CandidateDiff.validate(baseline, selected, authority, sourceBaseline);
		List<Path> roots = authority.roots().stream().map(EditableRoot::path).toList();
		PromptSnapshot visible = PromptSnapshot.capture(project, roots);
		if (matchesEditable(visible, selected))
			return archiveNoOp(baselineId, previousId, selectedId, diff, requestedTransactionId);
		if (!matchesEditable(visible, predecessor))
			throw new CandidateDeliveryException(CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION,
				"Prompt worktree is a mixed or stale predecessor state", Optional.empty(),
				diff.changes().stream().map(CandidateDiff.Change::path).toList());
		if (diff.changes().isEmpty())
			throw new IOException("Selected candidate differs outside confirmed editable roots");
		String transactionId = requestedTransactionId;
		if (transactionId == null)
			transactionId = UUID.randomUUID().toString();
		Set<Path> missingParents = new HashSet<>();
		List<DeliveryPath> paths = plan(transactionId, diff, missingParents);
		List<DeliveryDirectory> directories = missingParents.stream().sorted(Comparator.comparingInt(Path::getNameCount).
			thenComparing(Path::toString)).map(path -> new DeliveryDirectory(path, DeliveryDirectoryPhase.PLANNED)).toList();
		DeliveryTransaction current = new DeliveryTransaction(DeliveryTransaction.SCHEMA_VERSION, transactionId, 0,
			DeliveryKind.MUTATING, DeliveryPhase.PREPARING, baselineId, Optional.ofNullable(previousId), selectedId,
			authority.roots(), directories, paths);
		journal.append(current);
		try
		{
			for (int index = 0; index < current.directories().size(); ++index)
			{
				DeliveryDirectory directory = current.directories().get(index);
				Path target = project.resolve(directory.relativePath());
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
				{
					if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))
						throw new IOException("Candidate directory path has an unsupported kind: " + target);
					current = updateDirectory(current, index, DeliveryDirectoryPhase.EXTERNAL_PRESENT);
				}
				else
				{
					files.createDirectoryNew(target);
					files.forceDirectory(target.getParent());
					current = updateDirectory(current, index, DeliveryDirectoryPhase.CAT_CREATED);
				}
			}
			for (int index = 0; index < paths.size(); ++index)
			{
				DeliveryPath path = current.paths().get(index);
				if (path.after().presence() == DeliveryPresence.REGULAR)
				{
					byte[] bytes = selected.entries().get(path.target()).bytes();
					files.writeAndForceNew(project.resolve(path.selectedStage()), bytes);
					files.forceDirectory(project.resolve(path.selectedStage()).getParent());
					if (!inspectState(project.resolve(path.selectedStage())).equals(path.after()))
						throw new IOException("Selected stage differs from candidate snapshot: " + path.target());
					current = updatePath(current, index, DeliveryPathPhase.STAGED, Optional.empty(),
						Optional.empty(), Optional.empty());
				}
			}
			current = updatePhase(current, DeliveryPhase.PREPARED);
			current = updatePhase(current, DeliveryPhase.APPLYING);
			for (int index = 0; index < paths.size(); ++index)
				current = applyPath(current, index);
			verifyEditable(selected, current.paths());
			current = updatePhase(current, DeliveryPhase.APPLIED);
			CandidateApplication application = archive(current, DeliveryReceiptOutcome.APPLIED, diff);
			cleanupPrivate(current.paths());
			return application;
		}
		catch (CandidateDeliveryException failure)
		{
			rollBackAfterFailedReconcile(transactionId, failure);
			throw failure;
		}
		catch (IOException failure)
		{
			rollBackAfterFailedReconcile(transactionId, failure);
			DeliveryPhase phase = journal.readLatest(transactionId).phase();
			if (phase == DeliveryPhase.ROLLED_BACK)
			{
				String detail = failure.getMessage();
				if (detail == null || detail.isBlank())
					detail = "Candidate delivery failed and was rolled back";
				throw new CandidateDeliveryException(CandidateDeliveryException.Reason.IO_FAILURE, detail,
					Optional.of(transactionId), paths.stream().map(DeliveryPath::target).toList(), failure);
			}
			if (phase == DeliveryPhase.RECOVERY_REQUIRED)
				throw new CandidateDeliveryException(CandidateDeliveryException.Reason.RECOVERY_REQUIRED,
					"Candidate delivery requires recovery after filesystem failure: " + failure.getMessage(),
					Optional.of(transactionId), paths.stream().map(DeliveryPath::target).toList(), failure);
			throw failure;
		}
	}

	/**
	 * Rolls back a failed application while retaining any failure of rollback as recovery evidence.
	 *
	 * @param transactionId transaction that failed
	 * @param failure application failure that receives suppressed rollback errors
	 */
	private void rollBackAfterFailedReconcile(String transactionId, IOException failure) throws IOException
	{
		DeliveryTransaction latest = journal.readLatest(transactionId);
		if (latest.phase() == DeliveryPhase.APPLIED || latest.phase() == DeliveryPhase.RECOVERY_REQUIRED)
			return;
		try
		{
			rollBack(latest);
		}
		catch (IOException rollbackFailure)
		{
			failure.addSuppressed(rollbackFailure);
			DeliveryTransaction failed = journal.readLatest(transactionId);
			if (failed.phase() == DeliveryPhase.RECOVERY_REQUIRED || failed.phase() == DeliveryPhase.ROLLED_BACK)
				return;
			try
			{
				updatePhase(failed, DeliveryPhase.RECOVERY_REQUIRED);
			}
			catch (IOException journalFailure)
			{
				failure.addSuppressed(journalFailure);
			}
		}
	}

	/**
	 * Reverses already visible paths in reverse plan order using capture-first private publication.
	 *
	 * @param latest last durable phase after application failed
	 */
	private void rollBack(DeliveryTransaction latest) throws IOException
	{
		try
		{
			requireUnambiguousRecoveryState(latest);
		}
		catch (IOException failure)
		{
			updatePhase(latest, DeliveryPhase.RECOVERY_REQUIRED);
			throw failure;
		}
		DeliveryTransaction current = updatePhase(latest, DeliveryPhase.ROLLING_BACK);
		for (int index = current.paths().size() - 1; index >= 0; --index)
		{
			DeliveryPath path = current.paths().get(index);
			Path target = project.resolve(path.target());
			DeliveryFileState observed = inspectState(target);
			boolean competingSelectedAlias = false;
			if (path.phase() == DeliveryPathPhase.SELECTED_ABSENT ||
				path.phase() == DeliveryPathPhase.SELECTED_LINKED)
			{
				try
				{
					DeliveryFileState stage = inspectState(project.resolve(path.selectedStage()));
					if (observed.presence() == DeliveryPresence.ABSENT && !stage.equals(path.after()))
						throw new IOException("Selected stage is missing or changed while target is absent: " +
							path.target());
					if (observed.presence() == DeliveryPresence.REGULAR &&
						stage.presence() == DeliveryPresence.REGULAR)
					{
						competingSelectedAlias = !files.sameFile(project.resolve(path.selectedStage()), target);
						if (competingSelectedAlias && !stage.equals(path.after()))
							throw new IOException("Selected private stage differs from journal: " +
								path.selectedStage());
						}
				}
				catch (IOException failure)
				{
					updatePhase(current, DeliveryPhase.RECOVERY_REQUIRED);
					throw failure;
				}
			}
			if ((path.phase() == DeliveryPathPhase.PLANNED || path.phase() == DeliveryPathPhase.STAGED) &&
				observed.equals(path.before()))
				continue;
			if (path.phase() == DeliveryPathPhase.RESTORE_ABSENT &&
				observed.presence() == DeliveryPresence.ABSENT)
			{
				current = restorePredecessor(current, index, path, target);
				continue;
			}
			if ((path.phase() == DeliveryPathPhase.RESTORE_ABSENT ||
				path.phase() == DeliveryPathPhase.RESTORE_LINKED) && observed.equals(path.before()) &&
				observed.presence() == DeliveryPresence.REGULAR)
			{
				Path predecessor = project.resolve(path.predecessorCapture());
				DeliveryPresence predecessorPresence = files.inspect(predecessor).presence();
				if (path.phase() == DeliveryPathPhase.RESTORE_LINKED &&
					predecessorPresence == DeliveryPresence.ABSENT)
				{
					files.forceDirectory(predecessor.getParent());
					current = updatePath(current, index, DeliveryPathPhase.RESTORED_DEALIASED,
						path.selectedLinkSha256(), path.before().contentSha256(), Optional.empty());
					current = updatePath(current, index, DeliveryPathPhase.PATH_ROLLED_BACK,
						path.selectedLinkSha256(), path.before().contentSha256(), Optional.empty());
					continue;
				}
				if (predecessorPresence == DeliveryPresence.REGULAR &&
					files.sameFile(predecessor, target))
				{
					files.forceDirectory(target.getParent());
					if (path.phase() == DeliveryPathPhase.RESTORE_ABSENT)
						current = updatePath(current, index, DeliveryPathPhase.RESTORE_LINKED,
							path.selectedLinkSha256(), path.before().contentSha256(), Optional.empty());
					files.unlinkPrivate(predecessor);
					files.forceDirectory(predecessor.getParent());
					current = updatePath(current, index, DeliveryPathPhase.RESTORED_DEALIASED,
						path.selectedLinkSha256(), path.before().contentSha256(), Optional.empty());
					current = updatePath(current, index, DeliveryPathPhase.PATH_ROLLED_BACK,
						path.selectedLinkSha256(), path.before().contentSha256(), Optional.empty());
					continue;
				}
			}
			if (observed.presence() == DeliveryPresence.REGULAR)
			{
				if (!observed.equals(path.after()) || competingSelectedAlias)
				{
					EvidenceRef preserved = preserveCompetingTarget(current.transactionId(), path.target(), observed);
					Path selectedStage = project.resolve(path.selectedStage());
					if (files.inspect(selectedStage).presence() == DeliveryPresence.REGULAR &&
						files.sameFile(selectedStage, target))
					{
						files.unlinkPrivate(selectedStage);
						files.forceDirectory(selectedStage.getParent());
					}
					current = updatePath(current, index, DeliveryPathPhase.CONCURRENT_EDIT_PRESERVED,
						path.selectedLinkSha256(), path.restoreLinkSha256(), Optional.of(preserved.evidenceId()));
					current = updatePhase(current, DeliveryPhase.CONCURRENT_EDIT_PRESERVED);
					publishReceipt(current, DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED);
					cleanupPrivate(current.paths());
					return;
				}
				PathInspection captured = files.captureAtomically(target, project.resolve(path.rollbackCapture()));
				if (!new DeliveryFileState(captured.presence(), captured.kind(), captured.contentSha256()).equals(
					path.after()))
					throw new IOException("Rollback capture differs from selected bytes: " + path.target());
				files.forceDirectory(target.getParent());
				current = updatePath(current, index, DeliveryPathPhase.ROLLBACK_CAPTURED,
					path.selectedLinkSha256(), Optional.empty(), Optional.empty());
			}
			else
				current = updatePath(current, index, DeliveryPathPhase.ROLLBACK_TARGET_ABSENT,
					path.selectedLinkSha256(), Optional.empty(), Optional.empty());
			if (files.inspect(target).presence() != DeliveryPresence.ABSENT)
				throw new IOException("Rollback target is no longer absent: " + path.target());
			current = updatePath(current, index, DeliveryPathPhase.RESTORE_ABSENT,
				path.selectedLinkSha256(), Optional.empty(), Optional.empty());
			current = restorePredecessor(current, index, path, target);
		}
		current = removeOwnedDirectories(current);
		DeliveryReceiptOutcome outcome = DeliveryReceiptOutcome.ROLLED_BACK;
		if (current.directories().stream().anyMatch(directory ->
			directory.phase() == DeliveryDirectoryPhase.CONCURRENT_CONTENT_PRESERVED))
		{
			current = updatePhase(current, DeliveryPhase.CONCURRENT_EDIT_PRESERVED);
			outcome = DeliveryReceiptOutcome.CONCURRENT_EDIT_PRESERVED;
		}
		else
			current = updatePhase(current, DeliveryPhase.ROLLED_BACK);
		publishReceipt(current, outcome);
		cleanupPrivate(current.paths());
	}

	/**
	 * Refuses to archive rollback while a planned directory or private artifact has ambiguous ownership or bytes.
	 *
	 * @param latest latest durable transaction state
	 * @throws IOException if live state cannot be attributed to the transaction
	 */
	private void requireUnambiguousRecoveryState(DeliveryTransaction latest) throws IOException
	{
		for (DeliveryDirectory directory: latest.directories())
		{
			if (directory.phase() != DeliveryDirectoryPhase.PLANNED)
				continue;
			Path target = project.resolve(directory.relativePath());
			if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Delivery directory ownership is ambiguous after interruption: " + target +
					"; inspect the live worktree before retrying");
		}
		for (DeliveryPath path: latest.paths())
		{
			// A linked stage may share the target's inode; rollback handles later user edits at that boundary.
			if (path.phase() == DeliveryPathPhase.PLANNED || path.phase() == DeliveryPathPhase.STAGED)
				inspectPrivateArtifact(path.selectedStage(), path.after());
		}
	}

	/**
	 * Completes restoration from a journaled absent target without recapturing it.
	 *
	 * @param current latest rollback transaction
	 * @param index path plan ordinal
	 * @param path predecessor and selected identities
	 * @param target absent user-visible path
	 * @return transaction with this path fully rolled back
	 * @throws IOException if the captured predecessor or visible target differs
	 */
	private DeliveryTransaction restorePredecessor(DeliveryTransaction current, int index, DeliveryPath path,
		Path target) throws IOException
	{
		if (path.before().presence() == DeliveryPresence.REGULAR)
		{
			Path predecessor = project.resolve(path.predecessorCapture());
			if (!inspectState(predecessor).equals(path.before()))
				throw new IOException("Rollback predecessor capture differs from expected bytes: " + path.target());
			LinkPublication restored = files.linkNew(predecessor, target,
				path.before().contentSha256().orElseThrow());
			files.forceDirectory(target.getParent());
			current = updatePath(current, index, DeliveryPathPhase.RESTORE_LINKED,
				path.selectedLinkSha256(), Optional.of(restored.expectedSha256()), Optional.empty());
			if (!files.sameFile(predecessor, target))
				throw new IOException("Rollback predecessor alias identity is ambiguous: " + path.target());
			files.unlinkPrivate(predecessor);
			files.forceDirectory(predecessor.getParent());
			current = updatePath(current, index, DeliveryPathPhase.RESTORED_DEALIASED,
				path.selectedLinkSha256(), Optional.of(restored.expectedSha256()), Optional.empty());
		}
		if (!inspectState(target).equals(path.before()))
			throw new IOException("Rollback target differs from predecessor: " + path.target());
		return updatePath(current, index, DeliveryPathPhase.PATH_ROLLED_BACK,
			path.selectedLinkSha256(), current.paths().get(index).restoreLinkSha256(), Optional.empty());
	}

	/**
	 * Removes only empty CAT-created parents in reverse order; a competing child stays visible.
	 *
	 * @param current rollback journal phase
	 * @return phase with every owned directory classified
	 * @throws IOException if an owned pathname has an unsupported kind
	 */
	private DeliveryTransaction removeOwnedDirectories(DeliveryTransaction current) throws IOException
	{
		for (int index = current.directories().size() - 1; index >= 0; --index)
		{
			DeliveryDirectory directory = current.directories().get(index);
			if (directory.phase() != DeliveryDirectoryPhase.CAT_CREATED)
				continue;
			Path target = project.resolve(directory.relativePath());
			if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
			{
				current = updateDirectory(current, index, DeliveryDirectoryPhase.REMOVED);
				continue;
			}
			if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))
				throw new IOException("Owned delivery directory has an unsupported kind: " + target);
			try (DirectoryStream<Path> children = Files.newDirectoryStream(target))
			{
				if (children.iterator().hasNext())
				{
					current = updateDirectory(current, index,
						DeliveryDirectoryPhase.CONCURRENT_CONTENT_PRESERVED);
					continue;
				}
			}
			files.deleteEmptyOwnedDirectory(target);
			files.forceDirectory(target.getParent());
			current = updateDirectory(current, index, DeliveryDirectoryPhase.REMOVED);
		}
		return current;
	}

	/**
	 * Retains exact competing bytes through a no-follow file descriptor before classifying a user edit.
	 *
	 * @param transactionId owning transaction
	 * @param relative competing project-relative target
	 * @param expected inspected live state before preservation
	 * @return content-addressed preservation evidence
	 * @throws IOException if bytes change or cannot be retained
	 */
	private EvidenceRef preserveCompetingTarget(String transactionId, Path relative, DeliveryFileState expected)
		throws IOException
	{
		Path target = project.resolve(relative);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
		{
			ByteBuffer buffer = ByteBuffer.allocate(8192);
			while (channel.read(buffer) != -1)
			{
				buffer.flip();
				if (bytes.size() + buffer.remaining() > 16 * 1024 * 1024)
					throw new IOException("Competing target exceeds bounded preservation evidence: " + relative);
				byte[] block = new byte[buffer.remaining()];
				buffer.get(block);
				bytes.write(block);
				buffer.clear();
			}
		}
		byte[] exact = bytes.toByteArray();
		if (!PromptSnapshot.sha256(exact).equals(expected.contentSha256().orElseThrow()) ||
			!inspectState(target).equals(expected))
			throw new IOException("Competing target changed during preservation: " + relative);
		return evidence.publish("delivery-preserved-bytes", new DeliveryPreservedEvidence(
			DeliveryPreservedEvidence.SCHEMA_VERSION, transactionId, relative,
			expected.contentSha256().orElseThrow(), Base64.getEncoder().encodeToString(exact)));
	}

	/**
	 * Archives a zero-path transaction when the selected snapshot is already visible.
	 *
	 * @param baselineId initial snapshot
	 * @param previousId predecessor when present
	 * @param selectedId selected snapshot
	 * @param diff structured predecessor-to-selected change
	 * @param requestedTransactionId capture-bound journal identity, or null for ordinary selection
	 * @return archived no-op application
	 */
	private CandidateApplication archiveNoOp(String baselineId, String previousId, String selectedId,
		CandidateDiff diff, String requestedTransactionId) throws IOException
	{
		String transactionId = requestedTransactionId;
		if (transactionId == null)
			transactionId = UUID.randomUUID().toString();
		DeliveryTransaction current = new DeliveryTransaction(DeliveryTransaction.SCHEMA_VERSION, transactionId, 0,
			DeliveryKind.NO_OP, DeliveryPhase.PREPARING, baselineId, Optional.ofNullable(previousId), selectedId,
			authority.roots(), List.of(), List.of());
		journal.append(current);
		current = updatePhase(current, DeliveryPhase.PREPARED);
		current = updatePhase(current, DeliveryPhase.APPLYING);
		current = updatePhase(current, DeliveryPhase.APPLIED);
		return archive(current, DeliveryReceiptOutcome.ALREADY_APPLIED, diff);
	}

	/**
	 * Preflights deterministic private names and exact current file states.
	 *
	 * @param transactionId new transaction identity
	 * @param diff validated changed paths
	 * @param missingParents collected candidate-created parent directories
	 * @return complete ordered path plan
	 */
	private List<DeliveryPath> plan(String transactionId, CandidateDiff diff, Set<Path> missingParents)
		throws IOException
	{
		List<DeliveryPath> paths = new ArrayList<>();
		for (CandidateDiff.Change change: diff.changes())
		{
			Path target = project.resolve(change.path());
			collectMissingParents(target.getParent(), missingParents);
			DeliveryFileState before = state(change.before());
			DeliveryFileState after = state(change.after());
			if (!inspectState(target).equals(before))
				throw new IOException("Stale predecessor at prompt path: " + change.path());
			int ordinal = paths.size();
			Path stage = DeliveryPath.privateSibling(change.path(), transactionId, ordinal, "stage");
			Path predecessor = DeliveryPath.privateSibling(change.path(), transactionId, ordinal, "predecessor");
			Path rollback = DeliveryPath.privateSibling(change.path(), transactionId, ordinal, "rollback");
			for (Path privatePath: List.of(stage, predecessor, rollback))
			{
				if (files.inspect(project.resolve(privatePath)).presence() != DeliveryPresence.ABSENT)
					throw new IOException("Transaction private sibling already exists: " + privatePath);
			}
			paths.add(new DeliveryPath(ordinal, change.path(), stage, predecessor, rollback, before, after,
				Optional.empty(), Optional.empty(), Optional.empty(), DeliveryPathPhase.PLANNED));
		}
		return List.copyOf(paths);
	}

	/**
	 * Captures one predecessor and publishes selected bytes only at an absent user path.
	 *
	 * @param current latest journal phase
	 * @param index path plan ordinal
	 * @return latest path phase
	 */
	private DeliveryTransaction applyPath(DeliveryTransaction current, int index) throws IOException
	{
		DeliveryPath path = current.paths().get(index);
		Path target = project.resolve(path.target());
		Path predecessor = project.resolve(path.predecessorCapture());
		if (!inspectState(target).equals(path.before()))
			throw new CandidateDeliveryException(CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION,
				"Concurrent modification before target capture: " + path.target(),
				Optional.of(current.transactionId()), List.of(path.target()));
		if (path.before().presence() == DeliveryPresence.REGULAR)
		{
			PathInspection captured;
			try
			{
				captured = files.captureAtomically(target, predecessor);
			}
			catch (AtomicMoveNotSupportedException failure)
			{
				throw classifyCaptureFailure(current, path, target, predecessor,
					CandidateDeliveryException.Reason.UNSUPPORTED_ATOMIC_PATH_OPERATION, failure);
			}
			catch (IOException failure)
			{
				throw classifyCaptureFailure(current, path, target, predecessor,
					CandidateDeliveryException.Reason.IO_FAILURE, failure);
			}
			if (!new DeliveryFileState(captured.presence(), captured.kind(), captured.contentSha256()).equals(
				path.before()))
				throw new IOException("Captured predecessor bytes differ: " + path.target());
			files.forceDirectory(target.getParent());
			current = updatePath(current, index, DeliveryPathPhase.PREDECESSOR_CAPTURED, Optional.empty(),
				Optional.empty(), Optional.empty());
		}
		else
			current = updatePath(current, index, DeliveryPathPhase.PREDECESSOR_ABSENT, Optional.empty(),
				Optional.empty(), Optional.empty());
		if (files.inspect(target).presence() != DeliveryPresence.ABSENT)
			throw new IOException("External target appeared after predecessor capture: " + path.target());
		current = updatePath(current, index, DeliveryPathPhase.SELECTED_ABSENT, Optional.empty(),
			Optional.empty(), Optional.empty());
		if (path.after().presence() == DeliveryPresence.REGULAR)
		{
			Path stage = project.resolve(path.selectedStage());
			LinkPublication linked = files.linkNew(stage, target, path.after().contentSha256().orElseThrow());
			files.forceDirectory(target.getParent());
			current = updatePath(current, index, DeliveryPathPhase.SELECTED_LINKED,
				Optional.of(linked.expectedSha256()), Optional.empty(), Optional.empty());
			if (!files.sameFile(stage, target))
				throw new IOException("Selected stage and target alias identity is ambiguous: " + path.target());
			files.unlinkPrivate(stage);
			files.forceDirectory(stage.getParent());
			current = updatePath(current, index, DeliveryPathPhase.SELECTED_DEALIASED,
				Optional.of(linked.expectedSha256()), Optional.empty(), Optional.empty());
		}
		if (!inspectState(target).equals(path.after()))
			throw new IOException("Selected target bytes differ after publication: " + path.target());
		return updatePath(current, index, DeliveryPathPhase.PATH_APPLIED,
			current.paths().get(index).selectedLinkSha256(), Optional.empty(), Optional.empty());
	}

	/**
	 * Distinguishes a capture that left both pathnames unchanged from a failure after uncertain mutation.
	 *
	 * @param current latest durable transaction
	 * @param path path being captured
	 * @param target visible predecessor path
	 * @param predecessor private capture path
	 * @param beforeMutationReason classified cause if no mutation occurred
	 * @param failure original filesystem failure
	 * @return classified capture failure
	 * @throws IOException if recovery-required state cannot be journaled
	 */
	private CandidateDeliveryException classifyCaptureFailure(DeliveryTransaction current, DeliveryPath path,
		Path target, Path predecessor, CandidateDeliveryException.Reason beforeMutationReason, IOException failure)
		throws IOException
	{
		DeliveryFileState observedTarget;
		DeliveryFileState observedCapture;
		try
		{
			observedTarget = inspectState(target);
			observedCapture = inspectState(predecessor);
		}
		catch (IOException inspectionFailure)
		{
			failure.addSuppressed(inspectionFailure);
			updatePhase(current, DeliveryPhase.RECOVERY_REQUIRED);
			return new CandidateDeliveryException(CandidateDeliveryException.Reason.RECOVERY_REQUIRED,
				"Atomic predecessor capture cannot be verified; inspect the live worktree: " + path.target(),
				Optional.of(current.transactionId()), List.of(path.target()), failure);
		}
		if (!observedTarget.equals(path.before()) || observedCapture.presence() != DeliveryPresence.ABSENT)
		{
			updatePhase(current, DeliveryPhase.RECOVERY_REQUIRED);
			return new CandidateDeliveryException(CandidateDeliveryException.Reason.RECOVERY_REQUIRED,
				"Atomic predecessor capture changed or obscured live state; inspect the worktree: " + path.target(),
				Optional.of(current.transactionId()), List.of(path.target()), failure);
		}
		return new CandidateDeliveryException(beforeMutationReason,
			"Atomic predecessor capture failed before mutation: " + path.target(),
			Optional.of(current.transactionId()), List.of(path.target()), failure);
	}

	/**
	 * Appends a whole-transaction phase while retaining its path plan.
	 *
	 * @param current latest state
	 * @param phase next whole phase
	 * @return durable next state
	 */
	private DeliveryTransaction updatePhase(DeliveryTransaction current, DeliveryPhase phase) throws IOException
	{
		DeliveryTransaction next = new DeliveryTransaction(DeliveryTransaction.SCHEMA_VERSION,
			current.transactionId(), current.journalSequence() + 1, current.kind(), phase, current.baselineId(),
			current.previousAppliedCandidateId(), current.selectedCandidateId(), current.confirmedRoots(),
			current.directories(), current.paths());
		journal.append(next);
		return next;
	}

	/**
	 * Appends one per-path phase without changing the other planned paths.
	 *
	 * @param current latest state
	 * @param index path ordinal
	 * @param phase next path phase
	 * @param selectedHash published selected link hash
	 * @param restoreHash published restore link hash
	 * @param preservedHash immutable divergent-byte hash
	 * @return durable next state
	 */
	private DeliveryTransaction updatePath(DeliveryTransaction current, int index, DeliveryPathPhase phase,
		Optional<String> selectedHash, Optional<String> restoreHash, Optional<String> preservedHash) throws IOException
	{
		List<DeliveryPath> paths = new ArrayList<>(current.paths());
		DeliveryPath prior = paths.get(index);
		paths.set(index, new DeliveryPath(prior.ordinal(), prior.target(), prior.selectedStage(),
			prior.predecessorCapture(), prior.rollbackCapture(), prior.before(), prior.after(), selectedHash,
			restoreHash, preservedHash, phase));
		DeliveryTransaction next = new DeliveryTransaction(DeliveryTransaction.SCHEMA_VERSION,
			current.transactionId(), current.journalSequence() + 1, current.kind(), current.phase(),
			current.baselineId(), current.previousAppliedCandidateId(), current.selectedCandidateId(),
			current.confirmedRoots(), current.directories(), paths);
		journal.append(next);
		return next;
	}

	/**
	 * Journals ownership of one candidate-created or external directory.
	 *
	 * @param current latest state
	 * @param index directory plan index
	 * @param phase next directory phase
	 * @return durable next state
	 */
	private DeliveryTransaction updateDirectory(DeliveryTransaction current, int index,
		DeliveryDirectoryPhase phase) throws IOException
	{
		List<DeliveryDirectory> directories = new ArrayList<>(current.directories());
		DeliveryDirectory prior = directories.get(index);
		directories.set(index, new DeliveryDirectory(prior.relativePath(), phase));
		DeliveryTransaction next = new DeliveryTransaction(DeliveryTransaction.SCHEMA_VERSION,
			current.transactionId(), current.journalSequence() + 1, current.kind(), current.phase(),
			current.baselineId(), current.previousAppliedCandidateId(), current.selectedCandidateId(),
			current.confirmedRoots(), directories, current.paths());
		journal.append(next);
		return next;
	}

	/**
	 * Compares only the complete confirmed editable root subtrees, including structural absence.
	 *
	 * @param live captured live roots
	 * @param snapshot expected complete snapshot
	 * @return whether every editable entry matches
	 */
	private boolean matchesEditable(PromptSnapshot live, PromptSnapshot snapshot)
	{
		Map<Path, PromptSnapshot.Entry> expected = new HashMap<>();
		for (Map.Entry<Path, PromptSnapshot.Entry> entry: snapshot.entries().entrySet())
		{
			if (authority.permits(entry.getKey()))
				expected.put(entry.getKey(), entry.getValue());
		}
		if (!live.entries().keySet().equals(expected.keySet()))
			return false;
		for (Map.Entry<Path, PromptSnapshot.Entry> entry: expected.entrySet())
		{
			PromptSnapshot.Entry actual = live.entries().get(entry.getKey());
			if (!actual.hash().equals(entry.getValue().hash()) || actual.kind() != entry.getValue().kind())
				return false;
		}
		return true;
	}

	/**
	 * Rehashes visible root paths after excluding only this transaction's verified private siblings.
	 *
	 * @param selected selected snapshot
	 * @param paths transaction path plan
	 */
	private void verifyEditable(PromptSnapshot selected, List<DeliveryPath> paths) throws IOException
	{
		List<Path> roots = authority.roots().stream().map(EditableRoot::path).toList();
		PromptSnapshot live = PromptSnapshot.capture(project, roots);
		Set<Path> privatePaths = new HashSet<>();
		for (DeliveryPath path: paths)
		{
			privatePaths.add(path.selectedStage());
			privatePaths.add(path.predecessorCapture());
			privatePaths.add(path.rollbackCapture());
		}
		Map<Path, PromptSnapshot.Entry> expected = new HashMap<>();
		for (Map.Entry<Path, PromptSnapshot.Entry> entry: selected.entries().entrySet())
		{
			if (authority.permits(entry.getKey()))
				expected.put(entry.getKey(), entry.getValue());
		}
		Set<Path> visible = new HashSet<>(live.entries().keySet());
		visible.removeAll(privatePaths);
		if (!visible.equals(expected.keySet()))
			throw new IOException("Confirmed root paths differ from selected snapshot");
		for (Path path: visible)
		{
			PromptSnapshot.Entry actual = live.entries().get(path);
			PromptSnapshot.Entry wanted = expected.get(path);
			if (!actual.hash().equals(wanted.hash()) || actual.kind() != wanted.kind())
				throw new IOException("Confirmed root bytes differ from selected snapshot: " + path);
		}
	}

	/**
	 * Converts a snapshot entry to a constrained delivery state.
	 *
	 * @param entry entry or null for absence
	 * @return durable file state
	 */
	private static DeliveryFileState state(PromptSnapshot.Entry entry)
	{
		if (entry == null)
			return DeliveryFileState.absent();
		return DeliveryFileState.regular(entry.hash());
	}

	/**
	 * Inspects live bytes through the no-follow file-operation boundary.
	 *
	 * @param path target to inspect
	 * @return constrained file state
	 */
	private DeliveryFileState inspectState(Path path) throws IOException
	{
		PathInspection inspection = files.inspect(path);
		return new DeliveryFileState(inspection.presence(), inspection.kind(), inspection.contentSha256());
	}

	/**
	 * Rejects symbolic or unsupported parents from the pinned project down to one target parent.
	 *
	 * @param parent expected real parent
	 * @param missingParents project-relative directories to create after PREPARING
	 */
	private void collectMissingParents(Path parent, Set<Path> missingParents) throws IOException
	{
		Path current = project;
		for (Path component: project.relativize(parent))
		{
			current = current.resolve(component);
			if (Files.isSymbolicLink(current) || Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
				!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Prompt parent is not a real directory: " + current);
			if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS))
			{
				Path relative = project.relativize(current);
				boolean insideDirectoryRoot = authority.roots().stream().anyMatch(root ->
					root.kind() == EditableRoot.Kind.DIRECTORY && relative.startsWith(root.path()) &&
						!relative.equals(root.path()));
				if (!insideDirectoryRoot)
					throw new IOException("Missing parent is outside a confirmed DIRECTORY root: " + relative);
				missingParents.add(relative);
			}
		}
	}

	/**
	 * Publishes a content-addressed receipt after the terminal APPLIED phase.
	 *
	 * @param transaction applied transaction
	 * @param outcome receipt outcome
	 * @param diff selected change
	 * @return receipt-bearing application
	 */
	private CandidateApplication archive(DeliveryTransaction transaction, DeliveryReceiptOutcome outcome,
		CandidateDiff diff) throws IOException
	{
		EvidenceRef receiptRef = publishReceipt(transaction, outcome);
		ApplyDisposition disposition = ApplyDisposition.APPLIED;
		if (outcome == DeliveryReceiptOutcome.ALREADY_APPLIED)
			disposition = ApplyDisposition.ALREADY_APPLIED;
		return new CandidateApplication(disposition, transaction.transactionId(), receiptRef.evidenceId(),
			transaction.paths().stream().map(DeliveryPath::target).toList(), diff);
	}

	/**
	 * Publishes a receipt matching the terminal visible state and final journal hash.
	 *
	 * @param transaction terminal transaction
	 * @param outcome archived outcome
	 * @return immutable receipt reference
	 */
	private EvidenceRef publishReceipt(DeliveryTransaction transaction, DeliveryReceiptOutcome outcome)
		throws IOException
	{
		byte[] finalBytes = SnapshotJson.mapper().writeValueAsBytes(transaction);
		String finalHash = PromptSnapshot.sha256(finalBytes);
		DeliveryReceiptIndex existing = readReceiptIndex(transaction.transactionId());
		if (existing != null)
		{
			verifyReceiptIndex(transaction, existing);
			if (existing.outcome() != outcome)
				throw new IOException("Delivery receipt index conflicts with terminal transaction");
			return existing.receiptRef();
		}
		List<DeliveryPathReceipt> pathReceipts = pathReceipts(transaction, outcome);
		DeliveryReceipt receipt = new DeliveryReceipt(DeliveryReceipt.SCHEMA_VERSION, transaction.transactionId(),
			transaction.baselineId(), transaction.previousAppliedCandidateId(), transaction.selectedCandidateId(),
			outcome, pathReceipts, finalHash);
		EvidenceRef receiptRef = evidence.publish("delivery-receipts", receipt);
		DeliveryReceiptIndex index = new DeliveryReceiptIndex(DeliveryReceiptIndex.SCHEMA_VERSION,
			transaction.transactionId(), receiptRef, finalHash, outcome);
		PromptSnapshotRepository.writeOnce(receiptIndexPath(transaction.transactionId()),
			SnapshotJson.mapper().writeValueAsBytes(index));
		return receiptRef;
	}

	/**
	 * Derives exact per-path receipt semantics from the final journal state.
	 *
	 * @param transaction final transaction
	 * @param outcome final transaction outcome
	 * @return ordered path results
	 * @throws IOException if a preserved final state cannot be verified
	 */
	private List<DeliveryPathReceipt> pathReceipts(DeliveryTransaction transaction, DeliveryReceiptOutcome outcome)
		throws IOException
	{
		List<DeliveryPathReceipt> result = new ArrayList<>();
		for (DeliveryPath path: transaction.paths())
		{
			DeliveryPathReceipt.Outcome pathOutcome = switch (outcome)
			{
				case APPLIED, ALREADY_APPLIED -> DeliveryPathReceipt.Outcome.APPLIED;
				case ROLLED_BACK -> DeliveryPathReceipt.Outcome.ROLLED_BACK;
				case CONCURRENT_EDIT_PRESERVED -> switch (path.phase())
				{
					case PATH_APPLIED -> DeliveryPathReceipt.Outcome.APPLIED;
					case PATH_ROLLED_BACK, PLANNED, STAGED -> DeliveryPathReceipt.Outcome.ROLLED_BACK;
					case CONCURRENT_EDIT_PRESERVED -> DeliveryPathReceipt.Outcome.CONCURRENT_EDIT_PRESERVED;
					default -> throw new IOException("Terminal delivery path phase is unresolved: " + path.phase() +
						" at " + path.target());
				};
			};
			result.add(new DeliveryPathReceipt(path.target(), path.before(), path.after(),
				receiptFinalState(path, outcome), pathOutcome));
		}
		return result;
	}

	/**
	 * Determines the terminal observed path state without reading later user edits.
	 *
	 * @param path terminal path entry
	 * @param outcome terminal transaction outcome
	 * @return journal-proven final path state
	 * @throws IOException if preserved content is missing or contradictory
	 */
	private DeliveryFileState receiptFinalState(DeliveryPath path, DeliveryReceiptOutcome outcome) throws IOException
	{
		if (outcome == DeliveryReceiptOutcome.APPLIED || outcome == DeliveryReceiptOutcome.ALREADY_APPLIED)
			return path.after();
		if (outcome == DeliveryReceiptOutcome.ROLLED_BACK)
			return path.before();
		if (path.phase() == DeliveryPathPhase.CONCURRENT_EDIT_PRESERVED)
		{
			EvidenceRef ref = new EvidenceRef("delivery-preserved-bytes",
				path.preservedEvidenceSha256().orElseThrow());
			DeliveryPreservedEvidence preserved = evidence.read(ref, "delivery-preserved-bytes",
				DeliveryPreservedEvidence.class);
			if (!preserved.target().equals(path.target()))
				throw new IOException("Preserved evidence names another target");
			return DeliveryFileState.regular(preserved.contentSha256());
		}
		if (path.phase() == DeliveryPathPhase.PATH_ROLLED_BACK || path.phase() == DeliveryPathPhase.PLANNED ||
			path.phase() == DeliveryPathPhase.STAGED)
			return path.before();
		if (path.phase() == DeliveryPathPhase.PATH_APPLIED)
			return path.after();
		throw new IOException("Concurrent terminal path state is ambiguous: " + path.target());
	}

	/**
	 * Reads the create-once receipt index, if any, using canonical bounded JSON.
	 *
	 * @param transactionId journal identity
	 * @return immutable index or null when no index was published
	 * @throws IOException if the index is corrupt or unsupported
	 */
	private DeliveryReceiptIndex readReceiptIndex(String transactionId) throws IOException
	{
		Path path = receiptIndexPath(transactionId);
		if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
			return null;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096)
			throw new IOException("Delivery receipt index is unsafe: " + path);
		byte[] bytes = Files.readAllBytes(path);
		try
		{
			DeliveryReceiptIndex index = SnapshotJson.mapper().readValue(bytes, DeliveryReceiptIndex.class);
			if (!Arrays.equals(bytes, SnapshotJson.mapper().writeValueAsBytes(index)) ||
				!index.transactionId().equals(transactionId))
				throw new IOException("Delivery receipt index is noncanonical or foreign");
			return index;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Delivery receipt index is malformed: " + path, e);
		}
	}

	/**
	 * Returns the transaction-owned create-once receipt index pathname.
	 *
	 * @param transactionId journal identity
	 * @return index pathname
	 */
	private Path receiptIndexPath(String transactionId)
	{
		return campaignDirectory.resolve("delivery-receipt-indexes").resolve(transactionId + ".json");
	}

	/**
	 * Removes only verified CAT-owned private captures after the receipt is archived.
	 *
	 * @param paths transaction path plan
	 */
	private void cleanupPrivate(List<DeliveryPath> paths) throws IOException
	{
		for (DeliveryPath path: paths)
		{
			List<Path> privatePaths = List.of(path.selectedStage(), path.predecessorCapture(),
				path.rollbackCapture());
			List<DeliveryFileState> expected = List.of(path.after(), path.before(), path.after());
			for (int index = 0; index < privatePaths.size(); ++index)
			{
				Path relative = privatePaths.get(index);
				Path resolved = project.resolve(relative);
				DeliveryFileState actual = inspectPrivateArtifact(relative, expected.get(index));
				if (actual.presence() == DeliveryPresence.REGULAR)
				{
					if (index == 0 && path.phase() == DeliveryPathPhase.PATH_APPLIED)
						throw new IOException("Applied target retains a selected private alias: " + relative);
					files.deletePrivate(resolved);
					files.forceDirectory(resolved.getParent());
				}
			}
		}
	}

	/**
	 * Rehashes a transaction-owned private path before it can authorize recovery or cleanup.
	 *
	 * @param relative private path from the transaction
	 * @param expected expected regular-file state when present
	 * @return observed path state
	 * @throws IOException if the private path has unexpected bytes or kind
	 */
	private DeliveryFileState inspectPrivateArtifact(Path relative, DeliveryFileState expected) throws IOException
	{
		DeliveryFileState actual = inspectState(project.resolve(relative));
		if (actual.presence() == DeliveryPresence.REGULAR && !actual.equals(expected))
			throw new IOException("Owned private capture differs from journal: " + relative);
		return actual;
	}
}
