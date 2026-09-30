package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.ClaimedSlot;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** A call-bound capability for native generation edits. No native argument can widen its root authority. */
public final class GenerationEditSession implements AutoCloseable
{
	private static final Object JVM_MUTEX = new Object();
	private final CandidateEvidenceRepository evidence;
	private final EvidenceReader reader;
	private final Path root;
	private final Path lockPath;
	private final Path journalDirectory;
	private final DeliveryWriteAuthority authority;
	private final EvidenceRef checkpointRef;
	private final EvidenceRef runRequestRef;
	private final EvidenceRef slotClaimRef;
	private final String logicalCallId;
	private final JsonMapper json = SnapshotJson.mapper();
	private final CandidateFileOperations fileOperations;
	private boolean closed;

	/**
	 * Opens a pinned gateway session after rehashing its checkpoint and confirmed authority.
	 *
	 * @param campaignDirectory pinned C1 campaign directory
	 * @param binding expected C1 binding
	 * @param checkpointRef registered live checkpoint
	 * @param runRequestRef retained native request
	 * @param slotClaimRef retained C1 slot claim
	 * @param logicalCallId exact logical call
	 * @throws IOException if the bound evidence is unsafe or inconsistent
	 */
	public GenerationEditSession(Path campaignDirectory, CampaignBinding binding, EvidenceRef checkpointRef,
		EvidenceRef runRequestRef, EvidenceRef slotClaimRef, String logicalCallId) throws IOException
	{
		this(campaignDirectory, binding, checkpointRef, runRequestRef, slotClaimRef, logicalCallId, true,
			new JdkCandidateFileOperations());
	}

	/**
	 * Test seam for a synthetic checkpoint without a C1 scheduler claim.
	 *
	 * @param campaignDirectory pinned C1 campaign directory
	 * @param binding expected C1 binding
	 * @param checkpointRef registered live checkpoint
	 * @param runRequestRef retained native request
	 * @param slotClaimRef retained C1 slot claim
	 * @param logicalCallId exact logical call
	 * @param verifySlotClaim whether to require the real retained C1 claim
	 * @throws IOException if the bound evidence is unsafe or inconsistent
	 */
	GenerationEditSession(Path campaignDirectory, CampaignBinding binding, EvidenceRef checkpointRef,
		EvidenceRef runRequestRef, EvidenceRef slotClaimRef, String logicalCallId, boolean verifySlotClaim)
		throws IOException
	{
		this(campaignDirectory, binding, checkpointRef, runRequestRef, slotClaimRef, logicalCallId,
			verifySlotClaim, new JdkCandidateFileOperations());
	}

	/**
	 * Injection seam for deterministic generation publication faults.
	 *
	 * @param campaignDirectory pinned C1 campaign directory
	 * @param binding expected C1 binding
	 * @param checkpointRef registered live checkpoint
	 * @param runRequestRef retained native request
	 * @param slotClaimRef retained C1 slot claim
	 * @param logicalCallId exact logical call
	 * @param verifySlotClaim whether to require the real retained C1 claim
	 * @param fileOperations injected file operations
	 * @throws IOException if the bound evidence is unsafe or inconsistent
	 */
	GenerationEditSession(Path campaignDirectory, CampaignBinding binding, EvidenceRef checkpointRef,
		EvidenceRef runRequestRef, EvidenceRef slotClaimRef, String logicalCallId, boolean verifySlotClaim,
		CandidateFileOperations fileOperations) throws IOException
	{
		this.fileOperations = Objects.requireNonNull(fileOperations, "fileOperations");
		this.evidence = new CandidateEvidenceRepository(campaignDirectory, binding);
		CampaignStore store = CampaignStore.open(campaignDirectory);
		reader = store.evidenceReader(binding);
		this.checkpointRef = Objects.requireNonNull(checkpointRef, "checkpointRef");
		this.runRequestRef = Objects.requireNonNull(runRequestRef, "runRequestRef");
		this.slotClaimRef = Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		this.logicalCallId = Objects.requireNonNull(logicalCallId, "logicalCallId");
		if (logicalCallId.isBlank() || !runRequestRef.collection().equals("run-requests") ||
			!slotClaimRef.collection().equals("call-slot-claims"))
			throw new IOException("Generation session request or slot identity is invalid");
		if (verifySlotClaim)
		{
			ClaimedSlot claim = store.readCallSlotClaim(binding, logicalCallId);
			if (!claim.slotClaimRef().equals(slotClaimRef) || !claim.slot().requestRef().equals(runRequestRef))
				throw new IOException("Generation session differs from retained C1 call-slot claim");
		}
		LiveGenerationCheckpoint checkpoint = evidence.read(checkpointRef, "live-generation-checkpoints",
			LiveGenerationCheckpoint.class);
		if (!checkpoint.logicalCallId().equals(logicalCallId) || !checkpoint.checkpointRef().equals(checkpointRef))
			throw new IOException("Generation checkpoint differs from the requested call");
		ConfirmedEditableRoots roots = evidence.read(checkpoint.confirmedEditableRootsRef(),
			"confirmed-editable-roots", ConfirmedEditableRoots.class);
		root = checkpoint.projectRoot().toRealPath();
		if (!root.equals(checkpoint.projectRoot()) || !root.equals(roots.projectRoot()))
			throw new IOException("Generation project root differs from confirmed pinned root");
		authority = new DeliveryWriteAuthority(roots.roots());
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots,
			new PromptSnapshotRepository(campaignDirectory));
		ConfirmedSourceVerifier.verifyLive(root, baseline, authority);
		journalDirectory = campaignDirectory.toAbsolutePath().normalize().resolve("generation-edit-journal");
		lockPath = campaignDirectory.toAbsolutePath().normalize().resolve(".generation-edit.lock");
	}

	/**
	 * Retains an equal native call once; a changed native call id fails closed.
	 *
	 * @param nativeRunId native run identity
	 * @param nativeSessionId native session identity
	 * @param nativeTurnId native turn identity
	 * @param nativeCallId unique native callback identity
	 * @param rawArgumentsRef immutable raw callback evidence
	 * @return retained tool-call reference
	 * @throws IOException if publication or replay validation fails
	 */
	public EvidenceRef recordToolCall(String nativeRunId, String nativeSessionId,
		String nativeTurnId, String nativeCallId, EvidenceRef rawArgumentsRef) throws IOException
	{
		return withLock(() -> recordToolCallUnderLock(nativeRunId, nativeSessionId, nativeTurnId,
			nativeCallId, rawArgumentsRef));
	}

	/**
	 * Publishes a verified native callback while the campaign gateway lock is held.
	 *
	 * @param nativeRunId native run identity
	 * @param nativeSessionId native session identity
	 * @param nativeTurnId native turn identity
	 * @param nativeCallId unique callback identity
	 * @param rawArgumentsRef rehashed raw callback evidence
	 * @return retained tool-call reference
	 * @throws IOException if publication or validation fails
	 */
	private EvidenceRef recordToolCallUnderLock(String nativeRunId, String nativeSessionId,
		String nativeTurnId, String nativeCallId, EvidenceRef rawArgumentsRef) throws IOException
	{
		requireOpen();
		reader.read(rawArgumentsRef);
		GenerationToolCall call = new GenerationToolCall(1, checkpointRef, runRequestRef, logicalCallId,
			slotClaimRef, nativeRunId, nativeSessionId, nativeTurnId, nativeCallId, rawArgumentsRef);
		Path index = keyPath("calls", nativeCallId);
		EvidenceRef existing = readIndex(index);
		if (existing != null)
		{
			if (!evidence.read(existing, "generation-tool-calls", GenerationToolCall.class).equals(call))
				throw new IOException("Conflicting native tool-call replay: " + nativeCallId);
			return existing;
		}
		EvidenceRef reference = evidence.publish("generation-tool-calls", call);
		writeIndex(index, reference);
		return reference;
	}

	/**
	 * Applies an edit or persists a call-bound denial. An uncertain journal never edits again.
	 *
	 * @param operation exact native edit request
	 * @param nativeToolCallRef registered call evidence
	 * @return accepted or rejected disposition
	 * @throws IOException if the journal is uncertain or persistence fails
	 */
	public GenerationEditDisposition apply(GenerationEditOperation operation,
		EvidenceRef nativeToolCallRef) throws IOException
	{
		return withLock(() ->
		{
			GenerationToolCall call = registeredCall(nativeToolCallRef);
			GenerationEditDisposition result = applyUnderLock(operation, nativeToolCallRef);
			publishCallTerminal(call, result);
			return result;
		});
	}

	/**
	 * Executes an operation while the campaign gateway lock is held.
	 *
	 * @param operation exact native operation
	 * @param nativeToolCallRef registered native call
	 * @return terminal disposition
	 * @throws IOException if the edit cannot safely complete
	 */
	private GenerationEditDisposition applyUnderLock(GenerationEditOperation operation,
		EvidenceRef nativeToolCallRef) throws IOException
	{
		requireOpen();
		Objects.requireNonNull(operation, "operation");
		registeredCall(nativeToolCallRef);
		if (readRawOutcome(keyPath("raw-rejections", operation.operationId())) != null)
			throw new IOException("Generation operation id already has a raw-path rejection");
		Path index = keyPath("operations", operation.operationId());
		GenerationEditOutcome existing = readOutcome(index);
		if (existing != null)
		{
			if (!existing.operation().equals(operation))
				throw new IOException("Conflicting generation edit replay");
			return readReplay(existing.terminalRef(), operation, nativeToolCallRef);
		}
		Path transactionIndex = keyPath("transactions", operation.operationId());
		EvidenceRef pending = readIndex(transactionIndex);
		if (pending != null)
			return recover(pending, operation, nativeToolCallRef, index);
		List<Path> paths = new ArrayList<>();
		paths.add(operation.sourcePath());
		operation.destinationPath().ifPresent(paths::add);
		List<GenerationPathState> before = new ArrayList<>();
		String rejection = null;
		for (Path path: paths)
		{
			if (!authority.permits(path))
			{
				rejection = "OUT_OF_ROOT";
				break;
			}
			try
			{
				before.add(new GenerationPathState(path, inspect(path)));
			}
			catch (IOException _)
			{
				rejection = "UNSAFE_OR_UNSUPPORTED_PATH";
				break;
			}
		}
		if (rejection == null && (!before.get(0).state().equals(operation.expectedSourceState()) ||
			operation.expectedDestinationState().isPresent() &&
				!before.get(1).state().equals(operation.expectedDestinationState().orElseThrow())))
			throw new IOException("Generation edit stale prestate; live bytes preserved");
		if (rejection != null)
		{
			GenerationEditRejection denied = new GenerationEditRejection(1, checkpointRef, logicalCallId,
				operation.operationId(), nativeToolCallRef, operation.kind(), operation.sourcePath().toString(),
				operation.destinationPath().map(Path::toString), rejection, before);
			EvidenceRef ref = evidence.publish("generation-edit-rejections", denied);
			writeOutcome(index, new GenerationEditOutcome(operation, ref));
			return new GenerationEditDisposition.Rejected(denied);
		}
		List<GenerationPathState> after = afterStates(operation, paths);
		List<EvidenceRef> captures = new ArrayList<>();
		for (GenerationPathState state: before)
		{
			if (state.state().presence() == GenerationExpectedState.Presence.REGULAR)
			{
				byte[] original;
				try (InputStream input = Files.newInputStream(root.resolve(state.path()), LinkOption.NOFOLLOW_LINKS))
				{
					original = input.readAllBytes();
				}
				if (!PromptSnapshot.sha256(original).equals(state.state().contentSha256().orElseThrow()))
					throw new IOException("Generation edit changed before predecessor capture");
				captures.add(evidence.publish("generation-edit-captures", original));
			}
		}
		GenerationEditTransaction transaction = new GenerationEditTransaction(1, checkpointRef, logicalCallId,
			operation, nativeToolCallRef, before, after, captures);
		EvidenceRef transactionRef = evidence.publish("generation-edit-transactions", transaction);
		writeIndex(transactionIndex, transactionRef);
		for (GenerationPathState state: before)
		{
			if (!inspect(state.path()).equals(state.state()))
				throw new IOException("Generation edit changed after journaling; live bytes preserved");
		}
		mutate(operation, before, after);
		return finish(operation, nativeToolCallRef, index, transactionRef, before, after);
	}

	/**
	 * Persists a typed denial when native path text cannot construct a valid operation.
	 *
	 * @param operationId call-local operation identity
	 * @param kind parsed native edit kind
	 * @param rawSourcePath untrusted source pathname text
	 * @param rawDestinationPath untrusted destination pathname text
	 * @param reason precise validation failure
	 * @param nativeToolCallRef registered native callback
	 * @return durable rejected disposition
	 * @throws IOException if the callback or idempotency key conflicts
	 */
	public GenerationEditDisposition.Rejected rejectRawOperation(String operationId, GenerationEditOperation.Kind kind,
		String rawSourcePath, Optional<String> rawDestinationPath, String reason, EvidenceRef nativeToolCallRef)
		throws IOException
	{
		return withLock(() ->
		{
			GenerationToolCall call = registeredCall(nativeToolCallRef);
			GenerationEditDisposition.Rejected result = rejectRawUnderLock(operationId, kind, rawSourcePath,
				rawDestinationPath, reason, nativeToolCallRef);
			publishCallTerminal(call, result);
			return result;
		});
	}

	/**
	 * Seals every native callback against C1's exact indexed typed completion.
	 *
	 * @param completionRef C1 indexed C0RT completion
	 * @param nativeCallbackIndexRef sealed C9 callback index
	 * @return complete C3 call and disposition index
	 * @throws IOException if any identity, callback, or disposition is contradictory
	 */
	public GenerationToolCallIndex sealGenerationToolCalls(EvidenceRef completionRef,
		EvidenceRef nativeCallbackIndexRef) throws IOException
	{
		return withLock(() -> sealUnderLock(completionRef, nativeCallbackIndexRef));
	}

	/**
	 * Rehashes the indexed completion and every callback while the campaign lock is held.
	 *
	 * @param completionRef exact C1 indexed completion
	 * @param nativeCallbackIndexRef C9 callback inventory
	 * @return complete sealed C3 index
	 * @throws IOException if the handoff is contradictory
	 */
	private GenerationToolCallIndex sealUnderLock(EvidenceRef completionRef,
		EvidenceRef nativeCallbackIndexRef) throws IOException
	{
		requireOpen();
		CampaignStore store = CampaignStore.open(journalDirectory.getParent());
		CampaignBinding binding = store.binding();
		ClaimedSlot claim = store.readCallSlotClaim(binding, logicalCallId);
		if (!claim.slotClaimRef().equals(slotClaimRef) || !claim.slot().requestRef().equals(runRequestRef) ||
			!store.readNativeCompletionIndex(binding, logicalCallId).equals(Optional.of(completionRef)))
			throw new IOException("Generation completion differs from C1 indexed native completion");
		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(completionRef, reader);
		if (!completion.campaignId().equals(binding.campaignId()) ||
			!completion.logicalCallId().equals(logicalCallId) ||
			!completion.runRequestRef().equals(runRequestRef) || !completion.slotClaimRef().equals(slotClaimRef) ||
			completion.runId().isEmpty())
			throw new IOException("Generation completion identity differs from retained session");
		GenerationNativeCallbackIndex nativeIndex = evidence.read(nativeCallbackIndexRef,
			"generation-native-callback-indexes", GenerationNativeCallbackIndex.class);
		if (!nativeIndex.logicalCallId().equals(logicalCallId) ||
			!nativeIndex.runRequestRef().equals(runRequestRef) ||
			!nativeIndex.runId().equals(completion.runId().orElseThrow()))
			throw new IOException("Generation native callback index conflicts with completion");
		reader.read(nativeIndex.nativeEventRootRef());
		for (EvidenceRef denial: nativeIndex.orderedNativeWriteDenialRefs())
			reader.read(denial);
		verifyNoOmittedRecordedCalls(nativeIndex);
		List<ToolCallDispositionPair> pairs = new ArrayList<>();
		for (NativeCallEvidence nativeCall: nativeIndex.orderedNativeCallIdAndRawEvidenceRefs())
		{
			reader.read(nativeCall.rawEvidenceRef());
			EvidenceRef callRef = readIndex(keyPath("calls", nativeCall.nativeCallId()));
			if (callRef == null)
				throw new IOException("Native callback has no C3 recorded tool call");
			GenerationToolCall call = registeredCall(callRef);
			if (!call.nativeRunId().equals(nativeIndex.runId()) ||
				!call.rawArgumentsRef().equals(nativeCall.rawEvidenceRef()))
				throw new IOException("Native callback differs from C3 recorded tool call");
			GenerationToolDisposition disposition = dispositionFor(callRef, nativeCall.nativeCallId());
			EvidenceRef dispositionRef = evidence.publish("generation-tool-dispositions", disposition);
			pairs.add(new ToolCallDispositionPair(callRef, dispositionRef));
		}
		GenerationToolCallIndex index = new GenerationToolCallIndex(1, checkpointRef, runRequestRef,
			logicalCallId, slotClaimRef, nativeIndex.runId(), nativeCallbackIndexRef, pairs);
		EvidenceRef indexRef = evidence.publish("generation-tool-call-indexes", index);
		writeIndex(keyPath("seals", completionRef.evidenceId()), indexRef);
		return index;
	}

	/**
	 * Rejects a C9 inventory that omits any C3 recorded callback for this exact call.
	 *
	 * @param nativeIndex complete claimed C9 callback inventory
	 * @throws IOException if a C3 call is omitted or the journal is unsafe
	 */
	private void verifyNoOmittedRecordedCalls(GenerationNativeCallbackIndex nativeIndex) throws IOException
	{
		Set<String> nativeIds = new HashSet<>();
		for (NativeCallEvidence call: nativeIndex.orderedNativeCallIdAndRawEvidenceRefs())
			nativeIds.add(call.nativeCallId());
		Path calls = journalDirectory.resolve("calls");
		if (Files.notExists(calls, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isDirectory(calls, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Generation tool-call journal is unsafe");
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(calls))
		{
			for (Path index: stream)
			{
				EvidenceRef callRef = readIndex(index);
				GenerationToolCall call = evidence.read(callRef, "generation-tool-calls", GenerationToolCall.class);
				if (call.checkpointRef().equals(checkpointRef) && call.logicalCallId().equals(logicalCallId) &&
					!nativeIds.contains(call.nativeCallId()))
					throw new IOException("C9 native callback index omits a recorded C3 call");
			}
		}
	}

	/**
	 * Resolves a recorded callback to its exact accepted, rejected, or unresolved state.
	 *
	 * @param callRef retained C3 call
	 * @param nativeCallId native callback identity
	 * @return exact call disposition
	 * @throws IOException if a terminal record conflicts
	 */
	private GenerationToolDisposition dispositionFor(EvidenceRef callRef, String nativeCallId) throws IOException
	{
		EvidenceRef terminal = readIndex(keyPath("call-terminals", nativeCallId));
		if (terminal == null)
			return new GenerationToolDisposition(1, callRef, GenerationToolDisposition.Status.UNRESOLVED,
				Optional.empty(), Optional.empty(), Optional.of("No terminal generation edit record"));
		if (terminal.collection().equals("generation-edit-receipts"))
		{
			GenerationEditReceipt receipt = evidence.read(terminal, "generation-edit-receipts",
				GenerationEditReceipt.class);
			if (!receipt.nativeToolCallRef().equals(callRef) || !receipt.checkpointRef().equals(checkpointRef) ||
				!receipt.logicalCallId().equals(logicalCallId))
				throw new IOException("Accepted edit receipt differs from recorded call");
			evidence.read(receipt.transactionRef(), "generation-edit-transactions",
				GenerationEditTransaction.class);
			return new GenerationToolDisposition(1, callRef, GenerationToolDisposition.Status.ACCEPTED,
				Optional.of(terminal), Optional.empty(), Optional.empty());
		}
		GenerationEditRejection rejection = evidence.read(terminal, "generation-edit-rejections",
			GenerationEditRejection.class);
		if (!rejection.nativeToolCallRef().equals(callRef) || !rejection.checkpointRef().equals(checkpointRef) ||
			!rejection.logicalCallId().equals(logicalCallId))
			throw new IOException("Rejected edit receipt differs from recorded call");
		return new GenerationToolDisposition(1, callRef, GenerationToolDisposition.Status.REJECTED,
			Optional.empty(), Optional.of(terminal), Optional.empty());
	}

	/**
	 * Binds one native callback to its exact terminal receipt or rejection.
	 *
	 * @param call registered native callback
	 * @param result terminal gateway result
	 * @throws IOException if publication fails
	 */
	private void publishCallTerminal(GenerationToolCall call, GenerationEditDisposition result) throws IOException
	{
		EvidenceRef terminal;
		if (result instanceof GenerationEditDisposition.Accepted accepted)
			terminal = evidence.publish("generation-edit-receipts", accepted.receipt());
		else
			terminal = evidence.publish("generation-edit-rejections",
				((GenerationEditDisposition.Rejected) result).rejection());
		writeIndex(keyPath("call-terminals", call.nativeCallId()), terminal);
	}

	/**
	 * Publishes or returns an equal raw-path rejection under the campaign gateway lock.
	 *
	 * @param operationId call-local operation identity
	 * @param kind parsed native edit kind
	 * @param rawSourcePath untrusted source text
	 * @param rawDestinationPath untrusted destination text
	 * @param reason precise rejection reason
	 * @param nativeToolCallRef registered native call
	 * @return durable rejection
	 * @throws IOException if publication or replay validation fails
	 */
	private GenerationEditDisposition.Rejected rejectRawUnderLock(String operationId,
		GenerationEditOperation.Kind kind, String rawSourcePath, Optional<String> rawDestinationPath,
		String reason, EvidenceRef nativeToolCallRef) throws IOException
	{
		requireOpen();
		registeredCall(nativeToolCallRef);
		if (readOutcome(keyPath("operations", operationId)) != null ||
			readIndex(keyPath("transactions", operationId)) != null)
			throw new IOException("Generation operation id already has another disposition");
		Path index = keyPath("raw-rejections", operationId);
		GenerationRawEditOutcome existing = readRawOutcome(index);
		if (existing != null)
		{
			if (existing.kind() != kind || !existing.rawSourcePath().equals(rawSourcePath) ||
				!existing.rawDestinationPath().equals(rawDestinationPath) || !existing.reason().equals(reason) ||
				!existing.nativeToolCallRef().equals(nativeToolCallRef))
				throw new IOException("Conflicting raw generation operation replay");
			return new GenerationEditDisposition.Rejected(evidence.read(existing.rejectionRef(),
				"generation-edit-rejections", GenerationEditRejection.class));
		}
		GenerationEditRejection rejection = new GenerationEditRejection(1, checkpointRef, logicalCallId,
			operationId, nativeToolCallRef, kind, rawSourcePath, rawDestinationPath, reason, List.of());
		EvidenceRef reference = evidence.publish("generation-edit-rejections", rejection);
		writeRawOutcome(index, new GenerationRawEditOutcome(operationId, kind, rawSourcePath,
			rawDestinationPath, reason, nativeToolCallRef, reference));
		return new GenerationEditDisposition.Rejected(rejection);
	}

	/**
	 * Requires the callback to be in the durable call-id index for this exact session.
	 *
	 * @param nativeToolCallRef registered call reference
	 * @return rehashed call
	 * @throws IOException if the call is foreign
	 */
	private GenerationToolCall registeredCall(EvidenceRef nativeToolCallRef) throws IOException
	{
		GenerationToolCall call = evidence.read(nativeToolCallRef, "generation-tool-calls", GenerationToolCall.class);
		if (!call.checkpointRef().equals(checkpointRef) || !call.runRequestRef().equals(runRequestRef) ||
			!call.slotClaimRef().equals(slotClaimRef) || !call.logicalCallId().equals(logicalCallId) ||
			!nativeToolCallRef.equals(readIndex(keyPath("calls", call.nativeCallId()))))
			throw new IOException("Native tool call is not registered to this generation session");
		return call;
	}

	/**
	 * Executes a gateway operation under one JVM and campaign-local process lock.
	 *
	 * @param action checked gateway action
	 * @param <T> action result type
	 * @return action result
	 * @throws IOException if lock acquisition or action fails
	 */
	private <T> T withLock(IoAction<T> action) throws IOException
	{
		synchronized (JVM_MUTEX)
		{
			if (Files.isSymbolicLink(lockPath))
				throw new IOException("Generation edit lock is a symlink");
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
				StandardOpenOption.WRITE); FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Generation edit lock is unavailable");
				return action.run();
			}
		}
	}

	/**
	 * Checked action run under the generation journal lock.
	 *
	 * @param <T> action result type
	 */
	@FunctionalInterface
	private interface IoAction<T>
	{
		/**
		 * Runs one locked action.
		 *
		 * @return action result
		 * @throws IOException if the action fails
		 */
		T run() throws IOException;
	}

	/**
	 * Resumes only an equal, fully rehashed write-ahead transaction.
	 *
	 * @param transactionRef retained write-ahead transaction
	 * @param operation exact replayed request
	 * @param nativeToolCallRef registered native call
	 * @param outcomeIndex deterministic terminal index
	 * @return recovered disposition
	 * @throws IOException if recovery is unsafe
	 */
	private GenerationEditDisposition recover(EvidenceRef transactionRef, GenerationEditOperation operation,
		EvidenceRef nativeToolCallRef, Path outcomeIndex) throws IOException
	{
		GenerationEditTransaction transaction = evidence.read(transactionRef, "generation-edit-transactions",
			GenerationEditTransaction.class);
		if (!transaction.checkpointRef().equals(checkpointRef) ||
			!transaction.logicalCallId().equals(logicalCallId) ||
			!transaction.operation().equals(operation) ||
			!transaction.nativeToolCallRef().equals(nativeToolCallRef))
			throw new IOException("Generation edit journal conflicts with call-bound operation");
		mutate(operation, transaction.beforeStates(), transaction.afterStates());
		return finish(operation, nativeToolCallRef, outcomeIndex, transactionRef, transaction.beforeStates(),
			transaction.afterStates());
	}

	/**
	 * Verifies the visible result and publishes its terminal receipt exactly once.
	 *
	 * @param operation exact edit request
	 * @param nativeToolCallRef registered native call
	 * @param outcomeIndex deterministic terminal index
	 * @param transactionRef retained write-ahead transaction
	 * @param before original endpoint states
	 * @param after verified endpoint states
	 * @return accepted disposition
	 * @throws IOException if the visible state or publication is unsafe
	 */
	private GenerationEditDisposition finish(GenerationEditOperation operation, EvidenceRef nativeToolCallRef,
		Path outcomeIndex, EvidenceRef transactionRef, List<GenerationPathState> before,
		List<GenerationPathState> after) throws IOException
	{
		for (GenerationPathState state: after)
		{
			if (!inspect(state.path()).equals(state.state()))
				throw new IOException("Generation edit poststate is uncertain; journal retained");
		}
		List<Path> paths = after.stream().map(GenerationPathState::path).toList();
		GenerationEditReceipt receipt = new GenerationEditReceipt(1, checkpointRef, logicalCallId,
			operation.operationId(), nativeToolCallRef, operation.kind(), before, after, paths, transactionRef);
		EvidenceRef receiptRef = evidence.publish("generation-edit-receipts", receipt);
		writeOutcome(outcomeIndex, new GenerationEditOutcome(operation, receiptRef));
		return new GenerationEditDisposition.Accepted(receipt);
	}

	@Override
	@SuppressWarnings("PMD.AvoidSynchronizedAtMethodLevel")
	public synchronized void close()
	{
		closed = true;
	}

	/**
	 * Reads a terminal replay and rejects a changed request.
	 *
	 * @param reference terminal record reference
	 * @param operation replayed operation
	 * @param callRef registered native call
	 * @return equal terminal disposition
	 * @throws IOException if the replay conflicts
	 */
	private GenerationEditDisposition readReplay(EvidenceRef reference, GenerationEditOperation operation,
		EvidenceRef callRef) throws IOException
	{
		if (reference.collection().equals("generation-edit-receipts"))
		{
			GenerationEditReceipt receipt = evidence.read(reference, "generation-edit-receipts",
				GenerationEditReceipt.class);
			GenerationEditTransaction transaction = evidence.read(receipt.transactionRef(),
				"generation-edit-transactions", GenerationEditTransaction.class);
			if (!transaction.operation().equals(operation) || !receipt.nativeToolCallRef().equals(callRef))
				throw new IOException("Conflicting generation edit replay");
			return new GenerationEditDisposition.Accepted(receipt);
		}
		GenerationEditRejection rejection = evidence.read(reference, "generation-edit-rejections",
			GenerationEditRejection.class);
		if (!rejection.nativeToolCallRef().equals(callRef) || rejection.kind() != operation.kind() ||
			!rejection.rawSourcePath().equals(operation.sourcePath().toString()) ||
			!rejection.rawDestinationPath().equals(operation.destinationPath().map(Path::toString)))
			throw new IOException("Conflicting generation edit replay");
		return new GenerationEditDisposition.Rejected(rejection);
	}

	/**
	 * Reads one exact endpoint without following symlinks.
	 *
	 * @param relative project-relative endpoint
	 * @return exact live state
	 * @throws IOException if the path is unsafe
	 */
	private GenerationExpectedState inspect(Path relative) throws IOException
	{
		Path current = root;
		for (Path component: relative)
		{
			current = current.resolve(component);
			if (Files.isSymbolicLink(current))
				throw new IOException("Symlink component in generation edit path");
		}
		Path parent = current.getParent();
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Generation edit parent is not a regular directory");
		PathInspection inspected = fileOperations.inspect(current);
		if (inspected.presence() == DeliveryPresence.ABSENT)
			return GenerationExpectedState.absent();
		return GenerationExpectedState.regular(inspected.contentSha256().orElseThrow());
	}

	/**
	 * Derives exact expected poststates from decoded content and validated prestates.
	 *
	 * @param operation validated operation
	 * @param paths ordered endpoints
	 * @return planned poststates
	 */
	private static List<GenerationPathState> afterStates(GenerationEditOperation operation, List<Path> paths)
	{
		GenerationExpectedState source = switch (operation.kind())
		{
			case CREATE, REPLACE -> GenerationExpectedState.regular(PromptSnapshot.sha256(operation.decodedContent()));
			case DELETE, RENAME -> GenerationExpectedState.absent();
		};
		List<GenerationPathState> result = new ArrayList<>();
		result.add(new GenerationPathState(paths.get(0), source));
		if (operation.kind() == GenerationEditOperation.Kind.RENAME)
			result.add(new GenerationPathState(paths.get(1), operation.expectedSourceState()));
		return result;
	}

	/**
	 * Capture-first publication. Every visible publication is an absent-target hard link.
	 *
	 * @param operation journaled operation
	 * @param before exact inspected prestates
	 * @param after exact planned poststates
	 * @throws IOException if the live state cannot be safely resumed
	 */
	private void mutate(GenerationEditOperation operation, List<GenerationPathState> before,
		List<GenerationPathState> after) throws IOException
	{
		if (!authority.permits(operation.sourcePath()) ||
			operation.destinationPath().isPresent() && !authority.permits(operation.destinationPath().orElseThrow()))
			throw new IOException("Journaled generation path is outside confirmed authority");
		Path source = root.resolve(operation.sourcePath());
		Path capture = privatePath(operation.sourcePath(), operation.operationId(), "predecessor");
		Path destination = operation.destinationPath().map(root::resolve).orElse(source);
		Path stage = privatePath(operation.destinationPath().orElse(operation.sourcePath()),
			operation.operationId(), "stage");
		Path phase = keyPath("phases", operation.operationId());
		GenerationExpectedState sourceBefore = before.get(0).state();
		GenerationExpectedState sourceAfter = after.get(0).state();
		GenerationExpectedState destinationAfter = after.getLast().state();
		if (operation.kind() == GenerationEditOperation.Kind.CREATE ||
			operation.kind() == GenerationEditOperation.Kind.REPLACE)
		{
			if (fileOperations.inspect(stage).presence() == DeliveryPresence.ABSENT &&
				inspect(operation.destinationPath().orElse(operation.sourcePath())).equals(destinationAfter) &&
				!phasePublished(phase))
				throw new IOException("Generation result has no durable publication proof");
			if (fileOperations.inspect(stage).presence() == DeliveryPresence.ABSENT &&
				!inspect(operation.destinationPath().orElse(operation.sourcePath())).equals(destinationAfter))
				fileOperations.writeAndForceNew(stage, operation.decodedContent());
			if (fileOperations.inspect(stage).presence() == DeliveryPresence.REGULAR &&
				!fileOperations.inspect(stage).contentSha256().orElseThrow().equals(
					destinationAfter.contentSha256().orElseThrow()))
				throw new IOException("Generation private stage has conflicting bytes");
		}
		if (sourceBefore.presence() == GenerationExpectedState.Presence.REGULAR)
		{
			if (fileOperations.inspect(capture).presence() == DeliveryPresence.ABSENT &&
				!inspect(operation.sourcePath()).equals(sourceBefore) && !phasePublished(phase))
				throw new IOException("Generation predecessor has no durable capture proof");
			if (fileOperations.inspect(capture).presence() == DeliveryPresence.ABSENT &&
				inspect(operation.sourcePath()).equals(sourceBefore))
			{
				PathInspection captured = fileOperations.captureAtomically(source, capture);
				if (!captured.contentSha256().orElseThrow().equals(sourceBefore.contentSha256().orElseThrow()))
					throw new IOException("Generation predecessor changed during capture");
			}
			if (fileOperations.inspect(capture).presence() == DeliveryPresence.REGULAR &&
				!fileOperations.inspect(capture).contentSha256().orElseThrow().equals(
					sourceBefore.contentSha256().orElseThrow()))
				throw new IOException("Generation private predecessor has conflicting bytes");
		}
		if (inspect(operation.destinationPath().orElse(operation.sourcePath())).presence() ==
			GenerationExpectedState.Presence.ABSENT && destinationAfter.presence() ==
			GenerationExpectedState.Presence.REGULAR)
		{
			Path privateSource;
			if (operation.kind() == GenerationEditOperation.Kind.RENAME)
				privateSource = capture;
			else
				privateSource = stage;
			fileOperations.linkNew(privateSource, destination, destinationAfter.contentSha256().orElseThrow());
			fileOperations.forceDirectory(destination.getParent());
		}
		if (destinationAfter.presence() == GenerationExpectedState.Presence.REGULAR &&
			!inspect(operation.destinationPath().orElse(operation.sourcePath())).equals(destinationAfter))
			throw new IOException("Generation publication target changed; preserving live bytes");
		if (fileOperations.inspect(stage).presence() == DeliveryPresence.REGULAR &&
			!fileOperations.sameFile(stage, destination))
			throw new IOException("Generation stage alias proof failed; preserving live bytes");
		if (operation.kind() == GenerationEditOperation.Kind.RENAME &&
			fileOperations.inspect(capture).presence() == DeliveryPresence.REGULAR &&
			!fileOperations.sameFile(capture, destination))
			throw new IOException("Generation rename alias proof failed; preserving live bytes");
		publishPhase(phase);
		if (fileOperations.inspect(stage).presence() == DeliveryPresence.REGULAR)
		{
			fileOperations.unlinkPrivate(stage);
			fileOperations.forceDirectory(stage.getParent());
		}
		if (fileOperations.inspect(capture).presence() == DeliveryPresence.REGULAR)
		{
			fileOperations.deletePrivate(capture);
			fileOperations.forceDirectory(capture.getParent());
		}
		if (!inspect(operation.sourcePath()).equals(sourceAfter))
			throw new IOException("Generation source poststate changed; preserving live bytes");
	}

	/**
	 * Checks a durable publication marker without accepting malformed marker bytes.
	 *
	 * @param phase marker pathname
	 * @return whether exact publication evidence exists
	 * @throws IOException if the marker is malformed
	 */
	private boolean phasePublished(Path phase) throws IOException
	{
		if (Files.notExists(phase, LinkOption.NOFOLLOW_LINKS))
			return false;
		if (!Files.isRegularFile(phase, LinkOption.NOFOLLOW_LINKS) ||
			!java.util.Arrays.equals(Files.readAllBytes(phase), "PUBLISHED".getBytes(
				java.nio.charset.StandardCharsets.US_ASCII)))
			throw new IOException("Generation publication marker is malformed");
		return true;
	}

	/**
	 * Records the linked/captured state before private alias cleanup.
	 *
	 * @param phase marker pathname
	 * @throws IOException if publication fails
	 */
	private void publishPhase(Path phase) throws IOException
	{
		PromptSnapshotRepository.writeOnce(phase, "PUBLISHED".getBytes(
			java.nio.charset.StandardCharsets.US_ASCII));
	}

	/**
	 * Derives a deterministic CAT-owned sibling for an operation endpoint.
	 *
	 * @param relative authorized endpoint
	 * @param operationId call-local operation identity
	 * @param role private sibling role
	 * @return deterministic private pathname
	 */
	private Path privatePath(Path relative, String operationId, String role)
	{
		String key = checkpointRef.evidenceId() + "\n" + logicalCallId + "\n" + operationId;
		String hash = PromptSnapshot.sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return root.resolve(relative).resolveSibling(".cat-generation-" + hash + "-" + role);
	}

	/**
	 * Derives a campaign-local, call-bound index pathname.
	 *
	 * @param category index category
	 * @param value call-local identity
	 * @return deterministic index pathname
	 */
	private Path keyPath(String category, String value)
	{
		return indexPath(journalDirectory.getParent(), checkpointRef, logicalCallId, category, value);
	}

	/**
	 * Shared deterministic journal index path for session and post-turn sealer.
	 *
	 * @param campaignDirectory pinned campaign directory
	 * @param checkpointRef registered checkpoint
	 * @param logicalCallId retained call identity
	 * @param category index category
	 * @param value category-local identity
	 * @return deterministic journal path
	 */
	static Path indexPath(Path campaignDirectory, EvidenceRef checkpointRef, String logicalCallId,
		String category, String value)
	{
		String key = checkpointRef.evidenceId() + "\n" + logicalCallId + "\n" + value;
		String hash = PromptSnapshot.sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return campaignDirectory.toAbsolutePath().normalize().resolve("generation-edit-journal").
			resolve(category).resolve(hash + ".json");
	}

	/**
	 * Reads one create-once index if present.
	 *
	 * @param path index pathname
	 * @return indexed evidence or null
	 * @throws IOException if the index is unsafe
	 */
	static EvidenceRef readIndex(Path path) throws IOException
	{
		if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
			return null;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096)
			throw new IOException("Generation journal index is unsafe: " + path);
		return SnapshotJson.mapper().readValue(Files.readAllBytes(path), EvidenceRef.class);
	}

	/**
	 * Publishes one create-once index.
	 *
	 * @param path index pathname
	 * @param reference immutable reference
	 * @throws IOException if publication fails
	 */
	private void writeIndex(Path path, EvidenceRef reference) throws IOException
	{
		PromptSnapshotRepository.writeOnce(path, json.writeValueAsBytes(reference));
	}

	/**
	 * Reads a complete operation outcome when one is published.
	 *
	 * @param path outcome index pathname
	 * @return terminal outcome or null
	 * @throws IOException if the index is unsafe
	 */
	private GenerationEditOutcome readOutcome(Path path) throws IOException
	{
		if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
			return null;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32 * 1024 * 1024)
			throw new IOException("Generation outcome index is unsafe: " + path);
		return json.readValue(Files.readAllBytes(path), GenerationEditOutcome.class);
	}

	/**
	 * Publishes one exact operation outcome.
	 *
	 * @param path outcome index pathname
	 * @param outcome complete terminal mapping
	 * @throws IOException if publication fails
	 */
	private void writeOutcome(Path path, GenerationEditOutcome outcome) throws IOException
	{
		PromptSnapshotRepository.writeOnce(path, json.writeValueAsBytes(outcome));
	}

	/**
	 * Reads a durable raw-path rejection index.
	 *
	 * @param path index pathname
	 * @return exact outcome or null
	 * @throws IOException if the index is malformed
	 */
	private GenerationRawEditOutcome readRawOutcome(Path path) throws IOException
	{
		if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
			return null;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
			throw new IOException("Raw generation rejection index is unsafe");
		return json.readValue(Files.readAllBytes(path), GenerationRawEditOutcome.class);
	}

	/**
	 * Publishes one complete raw-path rejection index.
	 *
	 * @param path index pathname
	 * @param outcome complete denial mapping
	 * @throws IOException if publication fails
	 */
	private void writeRawOutcome(Path path, GenerationRawEditOutcome outcome) throws IOException
	{
		PromptSnapshotRepository.writeOnce(path, json.writeValueAsBytes(outcome));
	}

	/** Rejects calls after the capability is revoked. */
	private void requireOpen() throws IOException
	{
		if (closed)
			throw new IOException("Generation edit session is closed");
	}
}
