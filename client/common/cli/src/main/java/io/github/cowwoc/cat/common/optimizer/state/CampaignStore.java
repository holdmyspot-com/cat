package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidate;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.StoredProposal;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.trial.TrialSampler;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Atomically persists one campaign summary and its content-addressed immutable evidence. */
public final class CampaignStore
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Pattern COLLECTION_NAME = Pattern.compile("[a-z][a-z0-9-]*");
	private static final Pattern EVIDENCE_ID = Pattern.compile("[0-9a-f]{64}");

	private final Path directory;
	private final Path campaignFile;
	private volatile Object pinnedDirectoryKey;

	/**
	 * Initializes one production campaign in a pinned directory exactly once.
	 *
	 * @param requestedDirectory campaign directory or symlink alias
	 * @param campaignId stable campaign identity
	 * @param optimizationProfile immutable model profile
	 * @return initialized pinned store
	 * @throws IOException if the directory or campaign cannot be initialized
	 */
	public static CampaignStore initialize(Path requestedDirectory, String campaignId,
		ModelProfile optimizationProfile) throws IOException
	{
		CampaignStore store = new CampaignStore(requestedDirectory);
		store.create(Campaign.create(campaignId, optimizationProfile));
		return store;
	}

	/**
	 * Opens a verified campaign at its pinned real directory.
	 *
	 * @param requestedDirectory campaign directory or symlink alias
	 * @return pinned store
	 * @throws IOException if the state is absent or corrupt
	 */
	public static CampaignStore open(Path requestedDirectory) throws IOException
	{
		CampaignStore store = new CampaignStore(requestedDirectory);
		store.load();
		return store;
	}

	/**
	 * Creates a store below one optimizer-owned campaign directory.
	 *
	 * @param directory the campaign directory
	 */
	public CampaignStore(Path directory)
	{
		if (directory == null)
			throw new NullPointerException("directory");
		Path requested = directory.toAbsolutePath().normalize();
		if (Files.notExists(requested))
			this.directory = ActivationPath.canonical(requested.getParent()).resolve(requested.getFileName());
		else
			this.directory = ActivationPath.canonical(requested);
		campaignFile = this.directory.resolve("campaign.json");
		if (Files.exists(this.directory, LinkOption.NOFOLLOW_LINKS))
		{
			try
			{
				pinnedDirectoryKey = Files.readAttributes(this.directory, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS).fileKey();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException("Cannot pin campaign directory: " + this.directory, e);
			}
		}
	}

	/**
	 * Returns the campaign directory.
	 *
	 * @return the optimizer-owned directory
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Returns the exact binding of the opened campaign.
	 *
	 * @return pinned campaign binding
	 * @throws IOException if campaign state is missing or corrupt
	 */
	public CampaignBinding binding() throws IOException
	{
		return new CampaignBinding(load().campaignId(), directory);
	}

	/**
	 * Opens a reader bound to this pinned campaign.
	 *
	 * @param expectedBinding exact campaign binding
	 * @return immutable JSON evidence reader
	 * @throws IOException if the binding or evidence is invalid
	 */
	public EvidenceReader evidenceReader(CampaignBinding expectedBinding) throws IOException
	{
		verifyBinding(expectedBinding);
		return reference ->
		{
			verifyBinding(expectedBinding);
			if (reference == null)
				throw new IOException("Evidence reference is missing");
			return readImmutableJson(reference);
		};
	}

	/**
	 * Reads only the immutable claim retained by one slot of this pinned campaign.
	 *
	 * @param binding exact pinned campaign
	 * @param logicalCallId retained slot identity
	 * @return rehashed claim and slot, without scheduling authority
	 * @throws IOException if the slot, claim, request, or binding is missing or contradictory
	 */
	public ClaimedSlot readCallSlotClaim(CampaignBinding binding, String logicalCallId) throws IOException
	{
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Logical call id must be non-blank");
		Campaign campaign = lockedSnapshot(binding);
		for (CallSlot slot : campaign.scheduler().slots())
			if (slot.logicalCallId().equals(logicalCallId))
				return verifySlotClaim(binding, campaign, slot);
		throw new IOException("Logical call slot is missing: " + logicalCallId);
	}

	/**
	 * Publishes the create-once native completion index for a retained call slot.
	 *
	 * @param binding pinned campaign
	 * @param logicalCallId retained call
	 * @param completionRef typed completion evidence
	 * @return published reference
	 * @throws IOException if the evidence or index conflicts
	 */
	public EvidenceRef publishNativeCompletionIndex(CampaignBinding binding, String logicalCallId,
		EvidenceRef completionRef) throws IOException
	{
		if (completionRef == null)
			throw new NullPointerException("completionRef");
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			CallSlot slot = findClaimedSlot(binding, campaign, logicalCallId);
			verifyNativeCompletion(binding, slot, completionRef);
			verifyCollectionDirectory("native-completion-index", true);
			Path file = nativeCompletionIndexPath(binding, slot);
			NativeCompletionIndexEntry expected = new NativeCompletionIndexEntry(1, binding.campaignId(),
				logicalCallId, slot.requestRef(), slot.slotClaimRef(), completionRef);
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			{
				if (!expected.equals(readNativeCompletionEntry(file)))
					throw new IOException("Native completion index collision: " + file);
				return completionRef;
			}
			writeCreateOnce(file, JSON.writeValueAsBytes(expected));
			return completionRef;
		});
	}

	/**
	 * Reads the create-once native completion index for a retained call slot.
	 *
	 * @param binding pinned campaign
	 * @param logicalCallId retained call
	 * @return indexed evidence if present
	 * @throws IOException if the index or evidence conflicts
	 */
	public Optional<EvidenceRef> readNativeCompletionIndex(CampaignBinding binding, String logicalCallId)
		throws IOException
	{
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			CallSlot slot = findClaimedSlot(binding, campaign, logicalCallId);
			return readNativeCompletionIndexLocked(binding, slot);
		});
	}

	/**
	 * Reads a completion index while the caller already owns the state lock.
	 *
	 * @param binding pinned campaign
	 * @param slot retained slot
	 * @return indexed evidence if present
	 * @throws IOException if the index or evidence conflicts
	 */
	Optional<EvidenceRef> readNativeCompletionIndexLocked(CampaignBinding binding, CallSlot slot)
		throws IOException
	{
		try
		{
			Files.readAttributes(directory.resolve("native-completion-index"), BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		verifyCollectionDirectory("native-completion-index", false);
		Path file = nativeCompletionIndexPath(binding, slot);
		try
		{
			Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		NativeCompletionIndexEntry entry = readNativeCompletionEntry(file);
		if (!entry.campaignId().equals(binding.campaignId()) ||
			!entry.logicalCallId().equals(slot.logicalCallId()) ||
			!entry.runRequestRef().equals(slot.requestRef()) ||
			!entry.slotClaimRef().equals(slot.slotClaimRef()))
			throw new IOException("Native completion index has a foreign call identity");
		verifyNativeCompletion(binding, slot, entry.completionRef());
		return Optional.of(entry.completionRef());
	}

	/**
	 * Publishes one pre-start record for a claimed call, including calls before contract confirmation.
	 *
	 * @param binding pinned campaign
	 * @param logicalCallId claimed scheduler call
	 * @param preStartRef immutable pre-start record
	 * @return the equal indexed record reference
	 * @throws IOException if the call, record, owner attempt or index conflicts
	 */
	public EvidenceRef publishCallPreStartIndex(CampaignBinding binding, String logicalCallId,
		EvidenceRef preStartRef) throws IOException
	{
		if (preStartRef == null)
			throw new NullPointerException("preStartRef");
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			CallSlot slot = findClaimedSlot(binding, campaign, logicalCallId);
			if (slot.state() != CallSlot.State.CLAIMED || slot.preStartRef().isPresent())
				throw new IOException("Call pre-start index requires an unstarted CLAIMED slot");
			CallPreStartRecord record = verifyCallPreStart(binding, campaign, slot, preStartRef);
			if (campaign.measurementState() != null && record.producerAttemptKind() !=
				ProducerAttemptKind.INSPECTION)
			{
				verifyBeforeCallObservation(binding, campaign, logicalCallId);
				if (!record.beforeCallObservationRef().equals(campaign.measurementState().baselineRootRef()))
					throw new IOException("Call pre-start does not name the current observed baseline");
			}
			verifyCollectionDirectory("call-pre-start-index", true);
			Path file = callPreStartIndexPath(binding, slot);
			CallPreStartIndexEntry expected = new CallPreStartIndexEntry(1, binding.campaignId(),
				logicalCallId, slot.requestRef(), slot.slotClaimRef(), preStartRef, preStartRef.evidenceId());
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			{
				if (!expected.equals(readCallPreStartIndexEntry(file)))
					throw new IOException("Call pre-start index collision: " + file);
				return preStartRef;
			}
			writeCreateOnce(file, JSON.writeValueAsBytes(expected));
			return preStartRef;
		});
	}

	/**
	 * Reads the indexed pre-start record for a claimed or started call.
	 *
	 * @param binding pinned campaign
	 * @param logicalCallId scheduler call
	 * @return the verified record, or empty if it has not been published
	 * @throws IOException if the call, record, owner attempt or index conflicts
	 */
	public Optional<EvidenceRef> readCallPreStartIndex(CampaignBinding binding, String logicalCallId)
		throws IOException
	{
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			CallSlot slot = findClaimedSlot(binding, campaign, logicalCallId);
			return readCallPreStartIndexLocked(binding, campaign, slot);
		});
	}

	/**
	 * Reads the exact indexed pre-start authority, including its native run identity, after restart.
	 *
	 * @param binding pinned campaign
	 * @param logicalCallId scheduler call identity
	 * @return the validated record, or empty before pre-start publication
	 * @throws IOException if the campaign, index, record, or producer evidence conflicts
	 */
	public Optional<CallPreStartRecord> readCallPreStartRecord(CampaignBinding binding, String logicalCallId)
		throws IOException
	{
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			CallSlot slot = findClaimedSlot(binding, campaign, logicalCallId);
			Optional<EvidenceRef> reference = readCallPreStartIndexLocked(binding, campaign, slot);
			if (reference.isEmpty())
				return Optional.empty();
			return Optional.of(verifyCallPreStart(binding, campaign, slot, reference.orElseThrow()));
		});
	}

	/**
	 * Reads pre-start authority while the scheduler already holds the campaign lock.
	 *
	 * @param binding pinned campaign
	 * @param campaign current locked campaign
	 * @param slot retained scheduler slot
	 * @return the verified record, or empty if it has not been published
	 * @throws IOException if the index or referenced evidence conflicts
	 */
	Optional<EvidenceRef> readCallPreStartIndexLocked(CampaignBinding binding, Campaign campaign,
		CallSlot slot) throws IOException
	{
		try
		{
			Files.readAttributes(directory.resolve("call-pre-start-index"), BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		verifyCollectionDirectory("call-pre-start-index", false);
		Path file = callPreStartIndexPath(binding, slot);
		try
		{
			Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		CallPreStartIndexEntry entry = readCallPreStartIndexEntry(file);
		if (!entry.campaignId().equals(binding.campaignId()) ||
			!entry.logicalCallId().equals(slot.logicalCallId()) ||
			!entry.runRequestRef().equals(slot.requestRef()) ||
			!entry.slotClaimRef().equals(slot.slotClaimRef()) ||
			slot.preStartRef().isPresent() && !slot.preStartRef().orElseThrow().equals(entry.preStartRef()))
			throw new IOException("Call pre-start index has a foreign call identity");
		verifyCallPreStart(binding, campaign, slot, entry.preStartRef());
		return Optional.of(entry.preStartRef());
	}

	/**
	 * Rehashes the pre-start record and its producer's common identity before native dispatch.
	 *
	 * @param binding pinned campaign
	 * @param campaign current locked campaign
	 * @param slot retained scheduler slot
	 * @param preStartRef record named by the index
	 * @return the verified pre-start record
	 * @throws IOException if the record or producer attempt is foreign or malformed
	 */
	CallPreStartRecord verifyCallPreStart(CampaignBinding binding, Campaign campaign, CallSlot slot,
		EvidenceRef preStartRef) throws IOException
	{
		if (!preStartRef.collection().equals("call-pre-starts"))
			throw new IOException("Call pre-start record has the wrong collection");
		ClaimedSlot claimed = verifySlotClaim(binding, campaign, slot);
		try
		{
			byte[] bytes = readImmutableJson(preStartRef);
			CallPreStartRecord record = JSON.readValue(bytes, CallPreStartRecord.class);
			if (record == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(record)) ||
				!record.logicalCallId().equals(slot.logicalCallId()) ||
				!record.requestRef().equals(slot.requestRef()) ||
				!record.slotClaimRef().equals(slot.slotClaimRef()) ||
				!record.preparationOwnerRequestRef().equals(claimed.claim().preparationOwnerRequestRef()) ||
				!record.preparationCycleRef().equals(claimed.claim().preparationCycleRef()))
				throw new IOException("Call pre-start record conflicts with its retained slot claim");
			verifyPreStartRunnerObservation(binding, campaign, slot, record);
			JsonNode attempt = JSON.readTree(readImmutableJson(record.producerAttemptRef()));
			if (attempt == null || !attempt.isObject() || attempt.get("header") == null)
				throw new IOException("Producer attempt has no common header");
			ProducerAttemptHeader header = JSON.readValue(JSON.writeValueAsBytes(attempt.get("header")),
				ProducerAttemptHeader.class);
			if (header == null || header.kind() != record.producerAttemptKind() ||
				!header.logicalCallId().equals(slot.logicalCallId()) ||
				!header.runRequestRef().equals(slot.requestRef()) ||
				!header.slotClaimRef().equals(slot.slotClaimRef()) ||
				!header.ownerRequestRef().equals(record.ownerRequestRef()) ||
				!header.preparationCycleRef().equals(record.preparationCycleRef()))
				throw new IOException("Producer attempt header conflicts with its retained slot claim");
			requireOwnerAttemptIndexed(binding, campaign, slot, record, attempt);
			return record;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Call pre-start record or producer attempt is malformed", exception);
		}
	}

	/**
	 * Verifies the durable before-call source of a confirmed runner identity.
	 *
	 * @param binding pinned campaign
	 * @param campaign current campaign snapshot
	 * @param slot claimed or started call
	 * @param record retained pre-start authority
	 * @throws IOException if the observation, index, or known runner conflicts
	 */
	private void verifyPreStartRunnerObservation(CampaignBinding binding, Campaign campaign,
		CallSlot slot, CallPreStartRecord record) throws IOException
	{
		if (record.beforeCallObservationRef().isEmpty())
		{
			if (campaign.measurementState() != null && record.producerAttemptKind() !=
				ProducerAttemptKind.INSPECTION)
				throw new IOException("Confirmed call pre-start lacks a before-call observation");
			return;
		}
		EvidenceRef observationRef = record.beforeCallObservationRef().orElseThrow();
		MeasurementObservation observation = readMeasurementObservation(observationRef);
		if (!observation.campaignId().equals(binding.campaignId()) ||
			!observation.lineageId().equals(campaign.activeLineageId()) ||
			!observation.logicalCallId().equals(slot.logicalCallId()) ||
			observation.stage() != MeasurementObservation.Stage.BEFORE_CALL)
			throw new IOException("Call pre-start has a foreign before-call observation");
		MeasurementEpoch epoch = JSON.readValue(readImmutableJson(observation.epochRef()),
			MeasurementEpoch.class);
		if (!epoch.campaignId().equals(binding.campaignId()) ||
			!epoch.lineageId().equals(observation.lineageId()))
			throw new IOException("Call pre-start observation has a foreign epoch");
		LineageKey lineage = new LineageKey(observation.lineageId(), epoch.contractRef().evidenceId());
		String key = MeasurementObservation.callIndexKey(observation.epochRef(), slot.logicalCallId());
		EvidenceRef indexed = readCreateOnceIndex("measurement-before-call-index", key, lineage).evidence();
		if (!indexed.equals(observationRef))
			throw new IOException("Call pre-start observation is not indexed for this call");
		MeasurementObservation.Field runner = observation.fields().stream().filter(field ->
			field.kind() == MeasurementConditions.Kind.HARNESS_RUNNER).findFirst().orElseThrow();
		if (runner.valueKind() == MeasurementObservation.ValueKind.KNOWN)
		{
			String observed = JSON.readValue(readImmutableJson(runner.evidenceRef()), String.class);
			if (!record.runtimeManifestSha256().equals(observed))
				throw new IOException("Call pre-start runner does not match the observed runner");
		}
	}

	/**
	 * Requires the original producer's exact create-once attempt index before a native call can start.
	 *
	 * @param binding pinned campaign
	 * @param campaign locked campaign snapshot
	 * @param slot retained claimed slot
	 * @param record pre-start record tied to the slot
	 * @param attempt rehashed owner attempt
	 * @throws IOException if the owner request or index does not authorize this attempt
	 */
	private void requireOwnerAttemptIndexed(CampaignBinding binding, Campaign campaign, CallSlot slot,
		CallPreStartRecord record, JsonNode attempt) throws IOException
	{
		ProducerAttemptKind kind = record.producerAttemptKind();
		if (kind == ProducerAttemptKind.INSPECTION)
		{
			requireInspectionAttemptIndexed(binding, slot, record);
			return;
		}
		if (!record.preparationOwnerRequestRef().isEmpty() ||
			!record.preparationCycleRef().isEmpty() ||
			!record.ownerRequestRef().collection().equals("prepared-lineage-calls"))
			throw new IOException("Owner attempt index requires a lineage owner request");
		byte[] ownerBytes = readImmutableJson(record.ownerRequestRef());
		PreparedLineageCall owner = JSON.readValue(ownerBytes, PreparedLineageCall.class);
		if (owner == null || !Arrays.equals(ownerBytes, JSON.writeValueAsBytes(owner)) ||
			owner.kind() != kind || !owner.campaignId().equals(binding.campaignId()) ||
			!owner.lineage().lineageId().equals(campaign.activeLineageId()) ||
			!owner.logicalCallId().equals(slot.logicalCallId()) ||
			!owner.runRequestRef().equals(slot.requestRef()))
			throw new IOException("Owner attempt request conflicts with the active claimed call");
		verifyLineage(campaign, owner.lineage());
		readImmutableJson(owner.semanticRequestRef());
		readImmutableJson(owner.runRequestRef());
		if (kind == ProducerAttemptKind.TRIAL)
			requireTrialAttemptOwner(binding, slot, owner);
		if (kind == ProducerAttemptKind.CANDIDATE)
			requireCandidateAttemptOwner(binding, slot, owner);
		JsonNode requestNode = attempt.get("requestRef");
		JsonNode ordinalNode = attempt.get("attemptOrdinal");
		if (requestNode == null || ordinalNode == null || !ordinalNode.isIntegralNumber() ||
			!ordinalNode.canConvertToInt() || ordinalNode.intValue() < 0 ||
			!owner.semanticRequestRef().equals(JSON.readValue(JSON.writeValueAsBytes(requestNode),
				EvidenceRef.class)))
			throw new IOException("Owner attempt request or ordinal conflicts with the prepared call");
		requireMatchingLineageAttemptIndex(binding, owner.lineage(), slot, record, ordinalNode.intValue());
	}

	/**
	 * Checks the exact create-once attempt index after its owner, call, and ordinal have been validated.
	 *
	 * @param binding validated campaign binding
	 * @param lineage validated owner lineage
	 * @param slot validated claimed call
	 * @param record pre-start record validated against that call and owner
	 * @param ordinal validated nonnegative transport attempt ordinal
	 * @throws IOException if the index is missing, redirected, corrupt, or identifies another attempt
	 */
	void requireMatchingLineageAttemptIndex(CampaignBinding binding, LineageKey lineage, CallSlot slot,
		CallPreStartRecord record, int ordinal) throws IOException
	{
		ProducerAttemptKind kind = record.producerAttemptKind();
		String logicalKey = OwnerAttemptIndexKey.deriveLineage(kind, record.ownerRequestRef(),
			slot.requestRef(), slot.slotClaimRef(), ordinal);
		String indexName = switch (kind)
		{
			case GENERATOR_VALIDATION -> "generator-validation-attempts";
			case CANDIDATE -> "candidate-transport-attempts";
			case EVALUATOR -> "evaluator-execution-attempts";
			case TRIAL -> "trial-execution-attempts";
			case INSPECTION, DERIVATION, ADVISOR ->
				throw new IOException("Owner attempt index is unavailable for " + kind);
		};
		Path file = lineageIndexPath(indexName, logicalKey);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("owner attempt index is missing: " + file);
		verifyCollectionDirectory(indexName, false);
		LineageEvidenceRef indexed = readClaimBoundAttempt(file, indexName, logicalKey,
			binding.campaignId(), lineage);
		if (!indexed.evidence().equals(record.producerAttemptRef()))
			throw new IOException("Owner attempt index identifies another attempt");
	}

	/**
	 * Binds a trial attempt to the assigned trial and its current category decision.
	 *
	 * @param binding pinned campaign
	 * @param slot original claimed call
	 * @param owner prepared trial owner
	 * @throws IOException if the request or assignment conflicts
	 */
	private void requireTrialAttemptOwner(CampaignBinding binding, CallSlot slot,
		PreparedLineageCall owner) throws IOException
	{
		byte[] requestBytes = readImmutableJson(slot.requestRef());
		InitialTrialRunRequest request = JSON.readValue(requestBytes, InitialTrialRunRequest.class);
		if (request == null || !Arrays.equals(requestBytes, JSON.writeValueAsBytes(request)) ||
			!request.campaignId().equals(binding.campaignId()) ||
			!request.logicalCallId().equals(slot.logicalCallId()) ||
			!request.assignmentRef().equals(owner.semanticRequestRef()))
			throw new IOException("Trial attempt owner conflicts with the assigned native request");
		TrialAssignment assignment = readTrialAssignment(owner.semanticRequestRef());
		if (!assignment.lineage().equals(owner.lineage()) ||
			!assignment.logicalTrialId().equals(slot.logicalCallId()))
			throw new IOException("Trial attempt owner conflicts with its assignment");
		requireIndexedCategoryDecision(assignment);
	}

	/**
	 * Binds a candidate attempt to the indexed generation request and its native private-worktree call.
	 *
	 * @param binding pinned campaign
	 * @param slot retained claimed call
	 * @param owner prepared candidate owner
	 * @throws IOException if the owner differs from its source-backed generation request
	 */
	private void requireCandidateAttemptOwner(CampaignBinding binding, CallSlot slot,
		PreparedLineageCall owner) throws IOException
	{
		CampaignLocator locator = new CampaignLocator(directory.getParent());
		CandidateGenerationRunRequestService.SourceBoundRequest sourceBound =
			new CandidateGenerationRunRequestService(locator, this).readWithSources(slot.requestRef());
		CandidateGenerationRunRequest request = sourceBound.request();
		CandidateGenerationRequest semantic = sourceBound.semantic();
		if (!request.semanticRequestRef().equals(owner.semanticRequestRef()))
			semantic = new CandidateGenerationRequestService(locator, this).read(owner.semanticRequestRef());
		if (!request.campaignId().equals(binding.campaignId()) ||
			!request.logicalCallId().equals(slot.logicalCallId()) ||
			!request.semanticRequestRef().equals(owner.semanticRequestRef()) ||
			!semantic.lineage().equals(owner.lineage()) ||
			!semantic.logicalCallId().equals(slot.logicalCallId()))
			throw new IOException("Candidate attempt owner conflicts with its native generation request");
	}

	/**
	 * Verifies the inspection owner, preparation cycle, original claim, and create-once attempt index.
	 *
	 * @param binding pinned campaign
	 * @param slot retained claimed slot
	 * @param record pre-start record tied to the slot
	 * @throws IOException if the inspection evidence does not authorize the attempt
	 */
	private void requireInspectionAttemptIndexed(CampaignBinding binding, CallSlot slot,
		CallPreStartRecord record) throws IOException
	{
		if (!record.preparationOwnerRequestRef().equals(Optional.of(record.ownerRequestRef())) ||
			record.preparationCycleRef().isEmpty() ||
			!record.ownerRequestRef().collection().equals("contract-preparation-owners") ||
			!record.producerAttemptRef().collection().equals("contract-inspection-attempts"))
			throw new IOException("Inspection attempt requires its retained preparation owner and cycle");
		byte[] ownerBytes = readImmutableJson(record.ownerRequestRef());
		ContractInspectionOwner owner = JSON.readValue(ownerBytes, ContractInspectionOwner.class);
		byte[] cycleBytes = readImmutableJson(owner.preparationCycleRef());
		ContractPreparationCycle cycle = JSON.readValue(cycleBytes, ContractPreparationCycle.class);
		byte[] attemptBytes = readImmutableJson(record.producerAttemptRef());
		ContractInspectionAttempt attempt = JSON.readValue(attemptBytes, ContractInspectionAttempt.class);
		if (!Arrays.equals(ownerBytes, JSON.writeValueAsBytes(owner)) ||
			!Arrays.equals(cycleBytes, JSON.writeValueAsBytes(cycle)) ||
			!Arrays.equals(attemptBytes, JSON.writeValueAsBytes(attempt)) ||
			!owner.campaignId().equals(binding.campaignId()) ||
			!cycle.campaignId().equals(binding.campaignId()) ||
			!owner.preparationCycleRef().equals(record.preparationCycleRef().orElseThrow()) ||
			!owner.sourceCaptureRef().equals(cycle.sourceCaptureRef()) ||
			!owner.logicalCallId().equals(slot.logicalCallId()) ||
			!owner.runRequestRef().equals(slot.requestRef()) ||
			!attempt.requestRef().equals(owner.sourceCaptureRef()) ||
			!attempt.header().ownerRequestRef().equals(record.ownerRequestRef()) ||
			!attempt.header().preparationCycleRef().equals(record.preparationCycleRef()) ||
			!attempt.header().slotClaimRef().equals(slot.slotClaimRef()))
			throw new IOException("Inspection attempt conflicts with its owner, cycle, or slot");
		readImmutableJson(owner.sourceCaptureRef());
		readImmutableJson(owner.runRequestRef());
		String ownerKey = PreparationIndexKey.derive(cycle.authority()) + ":inspection:" +
			owner.inspectionOrdinal();
		if (!readPreconfirmationIndexLocked(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_OWNER,
			ownerKey).equals(Optional.of(record.ownerRequestRef())))
			throw new IOException("Inspection owner has no matching create-once index");
		String attemptKey = OwnerAttemptIndexKey.deriveInspection(record.ownerRequestRef(),
			slot.requestRef(), slot.slotClaimRef(), attempt.attemptOrdinal());
		if (!readPreconfirmationIndexLocked(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
			attemptKey).equals(Optional.of(record.producerAttemptRef())))
			throw new IOException("Inspection attempt has no matching create-once index");
	}

	/**
	 * Resolves a domain-separated, length-prefixed key under the fixed pre-start index collection.
	 *
	 * @param binding pinned campaign
	 * @param slot retained scheduler slot
	 * @return pinned index path
	 */
	private Path callPreStartIndexPath(CampaignBinding binding, CallSlot slot)
	{
		byte[] campaign = binding.campaignId().getBytes(StandardCharsets.UTF_8);
		byte[] call = slot.logicalCallId().getBytes(StandardCharsets.UTF_8);
		byte[] claim = slot.slotClaimRef().evidenceId().getBytes(StandardCharsets.UTF_8);
		ByteBuffer key = ByteBuffer.allocate(12 + campaign.length + call.length + claim.length);
		key.putInt(campaign.length).put(campaign);
		key.putInt(call.length).put(call);
		key.putInt(claim.length).put(claim);
		byte[] domain = "call-pre-start-index\u0000".getBytes(StandardCharsets.UTF_8);
		byte[] separated = Arrays.copyOf(domain, domain.length + key.position());
		System.arraycopy(key.array(), 0, separated, domain.length, key.position());
		return directory.resolve("call-pre-start-index").resolve(sha256(separated) + ".json");
	}

	/**
	 * Reads one canonical pre-start index entry without following a redirected file.
	 *
	 * @param file pinned index file
	 * @return validated index entry
	 * @throws IOException if the entry is corrupt
	 */
	private CallPreStartIndexEntry readCallPreStartIndexEntry(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Call pre-start index is redirected: " + file);
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			CallPreStartIndexEntry entry = JSON.readValue(bytes, CallPreStartIndexEntry.class);
			if (entry == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(entry)))
				throw new IOException("Call pre-start index is noncanonical: " + file);
			return entry;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Call pre-start index is malformed: " + file, exception);
		}
	}

	/**
	 * Rehashes native completion evidence and checks it against the exact retained slot.
	 * The campaign binding is validated once for this operation; each evidence read still verifies its content address.
	 *
	 * @param binding pinned campaign
	 * @param slot retained slot
	 * @param completionRef typed completion evidence
	 * @return validated typed completion
	 * @throws IOException if the completion conflicts
	 */
	NativeRunCompletionEvidence verifyNativeCompletion(CampaignBinding binding, CallSlot slot,
		EvidenceRef completionRef) throws IOException
	{
		Campaign campaign = verifyBinding(binding);
		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(completionRef,
			this::readImmutableJson);
		Optional<String> expectedRunId = slot.runId();
		if (slot.state() == CallSlot.State.STARTING && expectedRunId.isEmpty())
		{
			CallPreStartRecord preStart = verifyCallPreStart(binding, campaign, slot,
				slot.preStartRef().orElseThrow(() -> new IOException("Starting call has no pre-start authority")));
			if (completion.runId().isPresent())
				expectedRunId = Optional.of(preStart.runId());
		}
		if (!completion.campaignId().equals(binding.campaignId()) ||
			!completion.logicalCallId().equals(slot.logicalCallId()) ||
			!completion.runRequestRef().equals(slot.requestRef()) ||
			!completion.slotClaimRef().equals(slot.slotClaimRef()) || !completion.runId().equals(expectedRunId))
			throw new IOException("Native completion conflicts with slot claim, request, call, campaign or run");
		return completion;
	}

	/**
	 * Finds and rehashes a retained slot under the state lock.
	 *
	 * @param binding pinned campaign
	 * @param campaign current campaign
	 * @param logicalCallId retained call
	 * @return verified slot
	 * @throws IOException if the slot is missing or corrupt
	 */
	private CallSlot findClaimedSlot(CampaignBinding binding, Campaign campaign, String logicalCallId)
		throws IOException
	{
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Logical call id must be non-blank");
		for (CallSlot slot : campaign.scheduler().slots())
			if (slot.logicalCallId().equals(logicalCallId))
			{
				verifySlotClaim(binding, campaign, slot);
				return slot;
			}
		throw new IOException("Logical call slot is missing: " + logicalCallId);
	}

	/**
	 * Resolves the domain-separated path for one complete call key.
	 *
	 * @param binding pinned campaign
	 * @param slot retained slot
	 * @return pinned index path
	 * @throws IOException if key serialization fails
	 */
	private Path nativeCompletionIndexPath(CampaignBinding binding, CallSlot slot) throws IOException
	{
		byte[] key = JSON.writeValueAsBytes(List.of(binding.campaignId(), slot.logicalCallId(),
			slot.requestRef(), slot.slotClaimRef()));
		byte[] domain = "native-completion-index\u0000".getBytes(StandardCharsets.UTF_8);
		byte[] separated = Arrays.copyOf(domain, domain.length + key.length);
		System.arraycopy(key, 0, separated, domain.length, key.length);
		return directory.resolve("native-completion-index").resolve(sha256(separated) + ".json");
	}

	/**
	 * Reads and validates one canonical native completion index entry.
	 *
	 * @param file pinned index file
	 * @return validated entry
	 * @throws IOException if the entry is corrupt
	 */
	private NativeCompletionIndexEntry readNativeCompletionEntry(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Native completion index is redirected: " + file);
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			NativeCompletionIndexEntry entry = JSON.readValue(bytes, NativeCompletionIndexEntry.class);
			if (entry == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(entry)))
				throw new IOException("Native completion index is noncanonical: " + file);
			return entry;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Native completion index is malformed: " + file, e);
		}
	}

	/**
	 * Rehashes the exact claim retained by a slot, never an orphan collection entry.
	 *
	 * @param binding exact pinned campaign
	 * @param campaign authoritative campaign snapshot
	 * @param slot retained slot
	 * @return verified claim
	 * @throws IOException if any identity or evidence conflicts
	 */
	ClaimedSlot verifySlotClaim(CampaignBinding binding, Campaign campaign, CallSlot slot) throws IOException
	{
		if (binding == null || !directory.equals(binding.realDirectory()) ||
			!campaign.campaignId().equals(binding.campaignId()))
			throw new IOException("Call-slot claim belongs to another pinned campaign");
		if (!slot.slotClaimRef().collection().equals("call-slot-claims"))
			throw new IOException("Call slot has the wrong claim collection");
		try
		{
			byte[] bytes = readImmutableJson(slot.slotClaimRef());
			CallSlotClaimRecord claim = JSON.readValue(bytes, CallSlotClaimRecord.class);
			if (claim == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(claim)) ||
				!claim.campaignId().equals(binding.campaignId()) ||
				!claim.logicalCallId().equals(slot.logicalCallId()) ||
				!claim.runRequestRef().equals(slot.requestRef()) ||
				claim.claimFence() > slot.fence() || claim.claimFence() > campaign.scheduler().fence() ||
				campaign.scheduler().invocations().stream().noneMatch(registration ->
					registration.invocationId().equals(claim.invocationId()) &&
					registration.binding().equals(binding)))
				throw new IOException("Call slot conflicts with its immutable claim");
			byte[] requestBytes = readImmutableJson(claim.runRequestRef());
			Object requestCallId = JSON.readValue(requestBytes, Map.class).get("logicalCallId");
			if (!claim.logicalCallId().equals(requestCallId))
				throw new IOException("Call-slot claim conflicts with its C0R run request");
			if (claim.preparationOwnerRequestRef().isPresent())
			{
				readImmutableJson(claim.preparationOwnerRequestRef().orElseThrow());
				readImmutableJson(claim.preparationCycleRef().orElseThrow());
			}
			if (slot.state() == CallSlot.State.ADOPTED)
				readAdoptedCall(binding, slot);
			return new ClaimedSlot(slot.slotClaimRef(), slot, claim);
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Call-slot claim is malformed", exception);
		}
	}

	/**
	 * Revalidates an adopted slot's immutable envelope and complete producer record without reopening campaign state.
	 *
	 * @param binding pinned campaign identity
	 * @param slot adopted call slot
	 * @return validated adoption envelope
	 * @throws IOException if the envelope or its producer is missing, corrupt, or inconsistent with the slot
	 */
	CallAdoptionRecord readAdoptedCall(CampaignBinding binding, CallSlot slot) throws IOException
	{
		if (slot.state() != CallSlot.State.ADOPTED)
			throw new IOException("Call is not adopted: " + slot.logicalCallId());
		EvidenceRef envelopeRef = slot.adoptionRef().orElseThrow();
		if (!envelopeRef.collection().equals("call-adoptions"))
			throw new IOException("Adopted call envelope has the wrong collection: " + envelopeRef);
		byte[] bytes;
		try
		{
			bytes = readImmutableJson(envelopeRef);
		}
		catch (IOException exception)
		{
			throw new IOException("Adopted call envelope is missing or corrupt: " + envelopeRef, exception);
		}
		try
		{
			CallAdoptionRecord envelope = JSON.readValue(bytes, CallAdoptionRecord.class);
			if (envelope == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(envelope)) ||
				!envelope.campaignId().equals(binding.campaignId()) ||
				!envelope.logicalCallId().equals(slot.logicalCallId()) ||
				!envelope.runRequestRef().equals(slot.requestRef()) ||
				!envelope.slotClaimRef().equals(slot.slotClaimRef()) ||
				!envelope.completionRef().equals(slot.completionRef().orElseThrow()))
				throw new IOException("Adopted call envelope conflicts with its retained slot: " + envelopeRef);
			readImmutableJson(envelope.producerRecordRef());
			return envelope;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Adopted call envelope is malformed: " + envelopeRef, exception);
		}
	}

	/**
	 * Creates an initial campaign exactly once.
	 *
	 * @param campaign the initial campaign
	 * @throws IOException if state already exists or cannot be persisted
	 */
	private void create(Campaign campaign) throws IOException
	{
		if (campaign == null)
			throw new NullPointerException("campaign");
		Campaign initial = Campaign.create(campaign.campaignId(), campaign.optimizationProfile());
		if (!campaign.equals(initial))
			throw new IllegalArgumentException("Campaign creation requires an initial unconfirmed state");
		withLock(() ->
		{
			if (Files.exists(campaignFile))
				throw new IOException("Campaign already exists: " + campaignFile);
			writeCampaign(campaign);
			return null;
		});
	}

	/**
	 * Loads the verified current campaign.
	 *
	 * @return the durable campaign
	 * @throws IOException if state is missing or malformed
	 */
	public Campaign load() throws IOException
	{
		return readCampaign(TrialRecordRetention.DISCARD).campaign();
	}

	/**
	 * Reads one campaign and retains its already decoded trial records for the consuming read operation.
	 *
	 * @return campaign and exact-reference assignment and outcome records from the same validation
	 * @throws IOException if state or its evidence is missing, malformed, or contradictory
	 */
	TrialEvidenceRead readTrialEvidence() throws IOException
	{
		return readCampaign(TrialRecordRetention.RETAIN);
	}

	/**
	 * Applies the same campaign validation while retaining trial records only for a requesting consumer.
	 *
	 * @param retention whether this operation has an immediate consumer of decoded trial records
	 * @return validated campaign and any requested records
	 * @throws IOException if the campaign or its retained evidence is invalid
	 */
	private TrialEvidenceRead readCampaign(TrialRecordRetention retention) throws IOException
	{
		verifyPinnedDirectory();
		if (Files.notExists(campaignFile))
			throw new IOException("Campaign state is missing: " + campaignFile);
		try
		{
			String serialized = Files.readString(campaignFile);
			JsonNode document = JSON.readTree(serialized);
			if (document == null || document.get("optimizationProfile") == null ||
				document.get("optimizationProfile").isNull() ||
				document.get("activeFormalFailureTriggerRef") == null ||
				document.get("measurementState") == null || document.get("terminalResults") == null ||
				document.get("acknowledgedInterruptionStopRef") == null)
			{
				throw new IOException("Campaign state is malformed: required field is missing or null");
			}
			Campaign campaign = JSON.treeToValue(document, Campaign.class);
			if (campaign == null)
				throw new IOException("Campaign state is malformed: empty document");
			CampaignBinding binding = new CampaignBinding(campaign.campaignId(), directory);
			for (CallSlot slot : campaign.scheduler().slots())
				verifySlotClaim(binding, campaign, slot);
			verifyInterruptionReceipts(binding, campaign);
			verifyConfirmationReceipts(binding, campaign);
			verifyInitialCandidateReceipts(binding, campaign);
			Map<EvidenceRef, TrialAssignment> assignments = verifyTrialAssignmentReceipts(binding, campaign);
			Map<EvidenceRef, TrialOutcomeRecord> outcomes = verifySemanticOutcomeReceipts(binding, campaign,
				assignments, retention);
			verifyMeasurementReceipts(binding, campaign);
			verifyPauseCancellationReceipts(binding, campaign);
			verifyBridgeEvidence(campaign);
			verifyAcknowledgedInterruption(binding, campaign);
			return switch (retention)
			{
				case RETAIN -> new TrialEvidenceRead(binding, campaign, assignments, outcomes);
				case DISCARD -> new TrialEvidenceRead(binding, campaign, Map.of(), Map.of());
			};
		}
		catch (Exception e)
		{
			throw new IOException("Campaign state is malformed: " + e.getMessage(), e);
		}
	}

	/** Whether the immediate caller consumes decoded trial records after campaign validation. */
	private enum TrialRecordRetention
	{
		DISCARD,
		RETAIN
	}

	/**
	 * Carries canonical records from one campaign validation into its immediate evidence consumer.
	 *
	 * @param binding exact owning campaign and store
	 * @param campaign fully validated campaign snapshot
	 * @param assignments assignments checked against their retained trial receipts
	 * @param outcomes outcomes checked against their indexed semantic receipts
	 */
	record TrialEvidenceRead(CampaignBinding binding, Campaign campaign, Map<EvidenceRef, TrialAssignment> assignments,
		Map<EvidenceRef, TrialOutcomeRecord> outcomes)
	{
		/**
		 * Preserves an immutable result without retaining state in the owning store.
		 *
		 * @param binding exact owner of the decoded records
		 * @param campaign validated snapshot from this read
		 * @param assignments exact-reference assignment records from this read
		 * @param outcomes exact-reference semantic records from this read
		 */
		TrialEvidenceRead
		{
			Objects.requireNonNull(binding, "binding");
			Objects.requireNonNull(campaign, "campaign");
			if (!binding.campaignId().equals(campaign.campaignId()))
				throw new IllegalArgumentException("Read campaign differs from its source binding");
			assignments = Map.copyOf(assignments);
			outcomes = Map.copyOf(outcomes);
		}
	}

	/**
	 * Validates every embedded interruption producer and its retained stop on a cold read.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign state
	 * @throws IOException if an interruption receipt or its source evidence is missing or corrupt
	 */
	private void verifyInterruptionReceipts(CampaignBinding binding, Campaign campaign) throws IOException
	{
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.INTERRUPTION)
				continue;
			if (!receipt.binding().equals(binding) ||
				!receipt.producerRecordRef().collection().equals("campaign-interruption-records") ||
				receipt.result().phase() != CampaignStatus.INTERRUPTED)
				throw new IOException("Embedded interruption receipt has a foreign producer or phase");
			InterruptionRecord producer = InterruptionRecord.decode(readImmutableJson(
				receipt.producerRecordRef()));
			if (!producer.binding().equals(binding) ||
				producer.expectedCounter() != receipt.expectedCounter() ||
				producer.reason() != InterruptionRecord.Reason.HOST_OR_TOKEN_STOP)
				throw new IOException("Embedded interruption producer conflicts with its receipt");
			verifyRetainedInterruptionStop(campaign, binding, producer);
			if (producer.logicalCallId().isPresent() && campaign.scheduler().slots().stream().noneMatch(slot ->
				slot.logicalCallId().equals(producer.logicalCallId().orElseThrow()) &&
				slot.fence() <= producer.adoptingFence()))
				throw new IOException("Embedded interruption lost its call slot");
		}
	}

	/**
	 * Revalidates the complete source-backed contract behind each committed confirmation.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign
	 * @throws IOException if a confirmed contract or lineage is missing or foreign
	 */
	private void verifyConfirmationReceipts(CampaignBinding binding, Campaign campaign) throws IOException
	{
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.CONFIRMED_CONTRACT)
				continue;
			if (!receipt.binding().equals(binding) ||
				!receipt.producerRecordRef().collection().equals("contracts") ||
				receipt.result().phase() != CampaignStatus.DEVELOPING)
				throw new IOException("Embedded confirmation receipt has a foreign contract or phase");
			ConfirmedContract contract = new ContractService(this).loadConfirmedContract(
				receipt.producerRecordRef().evidenceId());
			if (campaign.lineages().stream().noneMatch(lineage ->
				lineage.lineageId().equals(receipt.result().lineageId()) &&
					lineage.confirmedContractId().equals(contract.contractId())))
				throw new IOException("Embedded confirmation lost its verified lineage");
			if (receipt.result().lineageId().equals(campaign.activeLineageId()))
			{
				MeasurementState state = campaign.measurementState();
				if (state == null)
					throw new IOException("Active confirmed lineage has no measurement epoch");
				byte[] bytes = readImmutableJson(state.epochRef());
				MeasurementEpoch epoch = JSON.readValue(bytes, MeasurementEpoch.class);
				StoredProposal source = new ContractService(this).loadProposal(contract.sourceProposalId());
				if (epoch == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(epoch)) ||
					!epoch.campaignId().equals(campaign.campaignId()) ||
					!epoch.lineageId().equals(receipt.result().lineageId()) ||
					!epoch.contractRef().equals(receipt.producerRecordRef()) ||
					!epoch.loadingEvidenceRef().equals(new EvidenceRef("prompt-loading",
						source.loadingEvidenceId())))
					throw new IOException("Active confirmation has a foreign measurement epoch");
			}
		}
	}

	/**
	 * Revalidates each selected first candidate against its retained producer and snapshot.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign
	 * @throws IOException if a first-candidate receipt lost its source or lineage
	 */
	private void verifyInitialCandidateReceipts(CampaignBinding binding, Campaign campaign) throws IOException
	{
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.INITIAL_CANDIDATE)
				continue;
			InitialCandidate candidate = readInitialCandidate(receipt.producerRecordRef());
			if (!receipt.binding().equals(binding) ||
				receipt.result().phase() != CampaignStatus.DEVELOPING ||
				!candidate.campaignId().equals(binding.campaignId()) ||
				!candidate.lineageId().equals(receipt.result().lineageId()) ||
				!candidate.candidateSnapshotId().equals(receipt.result().candidateId()) ||
				campaign.lineages().stream().noneMatch(lineage ->
					lineage.lineageId().equals(candidate.lineageId()) &&
					lineage.confirmedContractId().equals(candidate.contractId())))
				throw new IOException("Initial candidate receipt conflicts with its confirmed lineage");
			new PromptSnapshotRepository(directory).load(candidate.candidateSnapshotId());
		}
	}

	/**
	 * Reads one canonical immutable first-candidate producer.
	 *
	 * @param reference exact producer reference
	 * @return validated candidate
	 * @throws IOException if its collection, bytes, or record is invalid
	 */
	private InitialCandidate readInitialCandidate(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("initial-candidates"))
			throw new IOException("Initial candidate producer has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			JsonMapper mapper = SnapshotJson.mapper();
			InitialCandidate candidate = mapper.readValue(bytes, InitialCandidate.class);
			if (candidate == null || !Arrays.equals(bytes, mapper.writeValueAsBytes(candidate)))
				throw new IOException("Initial candidate producer is noncanonical or empty");
			return candidate;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Initial candidate producer is malformed", exception);
		}
	}

	/**
	 * Revalidates retained trial selections against their immutable confirmed contracts.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign
	 * @return assignments checked against their retained trials, keyed by their producer references
	 * @throws IOException if a selection has a foreign producer or no retained trial
	 */
	private Map<EvidenceRef, TrialAssignment> verifyTrialAssignmentReceipts(CampaignBinding binding,
		Campaign campaign) throws IOException
	{
		Map<String, ConfirmedContract> contracts = new HashMap<>();
		Map<String, TrialState> retainedTrials = new HashMap<>();
		Map<EvidenceRef, TrialAssignment> assignments = new HashMap<>();
		Map<String, EvidenceRef> confirmedLoadingRefs = new HashMap<>();
		Map<EvidenceRef, MeasurementEpoch> epochs = new HashMap<>();
		Map<EvidenceRef, InitialCategoryDecision> decisions = new HashMap<>();
		DevelopmentCategoryDecisionService.ValidationSession developmentDecisions =
			new DevelopmentCategoryDecisionService.ValidationSession(campaign);
		Set<EvidenceRef> verifiedSources = new HashSet<>();
		for (TrialState trial : campaign.trials())
			retainedTrials.put(trial.logicalTrialId(), trial);
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.TRIAL_ASSIGNMENT)
				continue;
			TrialAssignment assignment = readTrialAssignment(receipt.producerRecordRef());
			TrialState trial = retainedTrials.get(assignment.logicalTrialId());
			if (!receipt.binding().equals(binding) ||
				receipt.result().phase() != CampaignStatus.DEVELOPING ||
				!assignment.campaignId().equals(binding.campaignId()) ||
				!assignment.lineage().lineageId().equals(receipt.result().lineageId()) ||
				!assignment.candidateId().equals(receipt.result().candidateId()) ||
				trial == null || !trial.lineageId().equals(assignment.lineage().lineageId()) ||
				!trial.candidateId().equals(assignment.candidateId()) ||
				!trial.selectionEvidence().equals(receipt.producerRecordRef()))
				throw new IOException("Embedded trial assignment conflicts with its retained trial");
			if (trial.purpose() != assignment.instance().purpose())
				throw new IOException("Embedded trial purpose conflicts with its assigned draw");
			ConfirmedContract contract = contracts.get(assignment.lineage().contractId());
			if (contract == null)
			{
				contract = new ContractService(this).loadConfirmedContract(assignment.lineage().contractId());
				contracts.put(assignment.lineage().contractId(), contract);
			}
			MeasurementEpoch epoch = epochs.get(assignment.measurementEpochRef());
			if (epoch == null)
			{
				byte[] epochBytes = readImmutableJson(assignment.measurementEpochRef());
				epoch = JSON.readValue(epochBytes, MeasurementEpoch.class);
				if (epoch == null || !Arrays.equals(epochBytes, JSON.writeValueAsBytes(epoch)))
					throw new IOException("Embedded trial assignment has a foreign measurement epoch");
				epochs.put(assignment.measurementEpochRef(), epoch);
			}
			if (!epoch.campaignId().equals(binding.campaignId()) ||
				!epoch.lineageId().equals(assignment.lineage().lineageId()) ||
				!epoch.contractRef().equals(new EvidenceRef("contracts", contract.contractId())))
				throw new IOException("Embedded trial assignment has a foreign measurement epoch");
			verifyCategoryDecision(assignment, contract, campaign, confirmedLoadingRefs, decisions,
				developmentDecisions, verifiedSources);
			try
			{
				TrialSampler.verifySelection(contract, assignment.instance());
			}
			catch (IllegalArgumentException exception)
			{
				throw new IOException("Embedded trial assignment has a foreign selection", exception);
			}
			assignments.put(receipt.producerRecordRef(), assignment);
		}
		return assignments;
	}

	/**
	 * Rechecks each counted semantic outcome against its indexed producer and retained trial.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign
	 * @param assignments assignments already checked against this campaign
	 * @param retention whether a subsequent consumer needs the decoded outcomes
	 * @return exact-reference outcomes when requested, otherwise an empty map
	 * @throws IOException if a counted outcome, index, or trial state is missing or contradictory
	 */
	private Map<EvidenceRef, TrialOutcomeRecord> verifySemanticOutcomeReceipts(CampaignBinding binding, Campaign campaign,
		Map<EvidenceRef, TrialAssignment> assignments, TrialRecordRetention retention) throws IOException
	{
		Map<EvidenceRef, TrialOutcomeRecord> outcomes = new HashMap<>();
		Map<String, TrialState> trials = new HashMap<>();
		Map<String, Integer> counted = new HashMap<>();
		for (TrialState trial : campaign.trials())
			trials.put(trial.logicalTrialId(), trial);
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.SEMANTIC_OUTCOME)
				continue;
			TrialOutcomeRecord outcome = readTrialOutcome(receipt.producerRecordRef());
			TrialAssignment assignment = assignments.get(outcome.assignmentRef());
			if (assignment == null)
				assignment = readTrialAssignment(outcome.assignmentRef());
			TrialState trial = trials.get(assignment.logicalTrialId());
			TrialStatus expectedStatus = switch (outcome.response().label())
			{
				case COMPLIANT -> TrialStatus.COMPLIANT;
				case NONCOMPLIANT -> TrialStatus.NONCOMPLIANT;
			};
			if (!receipt.binding().equals(binding) ||
				receipt.result().phase() != CampaignStatus.DEVELOPING ||
				!outcome.campaignId().equals(binding.campaignId()) ||
				!outcome.lineage().equals(assignment.lineage()) ||
				!assignment.campaignId().equals(binding.campaignId()) ||
				!assignment.lineage().lineageId().equals(receipt.result().lineageId()) ||
				!assignment.candidateId().equals(receipt.result().candidateId()) ||
				!readCreateOnceIndexForCampaign("trial-outcome-index", outcome.assignmentRef().evidenceId(),
					outcome.lineage(), campaign).evidence().equals(receipt.producerRecordRef()) ||
				trial == null || trial.status() != expectedStatus ||
				!trial.selectionEvidence().equals(outcome.assignmentRef()) ||
				!Objects.equals(trial.outcomeEvidence(), receipt.producerRecordRef()))
				throw new IOException("Embedded semantic outcome conflicts with its indexed trial outcome");
			counted.merge(assignment.logicalTrialId(), 1, Integer::sum);
			if (retention == TrialRecordRetention.RETAIN)
				outcomes.put(receipt.producerRecordRef(), outcome);
		}
		for (TrialState trial : campaign.trials())
			if (trial.purpose() == EvidencePurpose.DEVELOPMENT &&
				trial.selectionEvidence().collection().equals("trial-assignments") &&
				(trial.status() == TrialStatus.COMPLIANT || trial.status() == TrialStatus.NONCOMPLIANT) &&
				counted.getOrDefault(trial.logicalTrialId(), 0) != 1)
				throw new IOException("Terminal semantic trial lacks exactly one outcome receipt");
		return outcomes;
	}

	/**
	 * Reads one canonical category choice published before an initial draw.
	 *
	 * @param reference immutable category decision reference
	 * @return validated category decision
	 * @throws IOException if the reference or record is missing or malformed
	 */
	InitialCategoryDecision readInitialCategoryDecision(EvidenceRef reference) throws IOException
	{
		if (reference == null || !reference.collection().equals("category-decisions"))
			throw new IOException("Initial category decision has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			InitialCategoryDecision decision = JSON.readValue(bytes, InitialCategoryDecision.class);
			if (decision == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(decision)))
				throw new IOException("Initial category decision is noncanonical or empty");
			return decision;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Initial category decision is malformed", exception);
		}
	}

	/**
	 * Reads the canonical counted-outcome revision that authorizes the next first-coverage batch.
	 *
	 * @param reference immutable completion reference
	 * @return validated batch completion
	 * @throws IOException if the reference or record is missing or malformed
	 */
	InitialCoverageCompletion readInitialCoverageCompletion(EvidenceRef reference) throws IOException
	{
		if (reference == null || !reference.collection().equals("coverage-completions"))
			throw new IOException("Initial coverage completion has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			InitialCoverageCompletion completion = JSON.readValue(bytes, InitialCoverageCompletion.class);
			if (completion == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(completion)))
				throw new IOException("Initial coverage completion is noncanonical or empty");
			return completion;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Initial coverage completion is malformed", exception);
		}
	}

	/**
	 * Revalidates the decision cited by a retained assignment against its confirmed source and draw.
	 *
	 * @param assignment retained trial assignment
	 * @param contract exact confirmed contract
	 * @param campaign campaign containing the decision's counted predecessor outcomes
	 * @param confirmedLoadingRefs loading evidence verified for each contract during this campaign read
	 * @param decisions category choices already verified during this campaign read
	 * @param developmentDecisions post-coverage choices verified during this campaign read
	 * @param verifiedSources source revisions already checked in this campaign read
	 * @throws IOException if the cited choice is missing, stale, or foreign
	 */
	private void verifyCategoryDecision(TrialAssignment assignment, ConfirmedContract contract,
		Campaign campaign, Map<String, EvidenceRef> confirmedLoadingRefs,
		Map<EvidenceRef, InitialCategoryDecision> decisions,
		DevelopmentCategoryDecisionService.ValidationSession developmentDecisions,
		Set<EvidenceRef> verifiedSources) throws IOException
	{
		if (assignment.categoryDecisionRef().collection().equals("development-category-decisions"))
		{
			DevelopmentCategoryDecision selected = new DevelopmentCategoryDecisionService(
				new CampaignLocator(directory.getParent()), this).read(assignment.categoryDecisionRef(),
					developmentDecisions);
			if (!selected.campaignId().equals(assignment.campaignId()) ||
				!selected.lineage().equals(assignment.lineage()) ||
				!selected.candidateId().equals(assignment.candidateId()) ||
				!selected.measurementEpochRef().equals(assignment.measurementEpochRef()) ||
				selected.allocationPolicy() != contract.developmentAllocationPolicy() ||
				!selected.categoryId().equals(assignment.instance().definition().categoryId()) ||
				assignment.ordinal() >= selected.allocationPolicy().batchSize())
				throw new IOException("Trial assignment conflicts with the published development category decision");
			return;
		}
		InitialCategoryDecision decision = decisions.get(assignment.categoryDecisionRef());
		if (decision == null)
		{
			decision = readInitialCategoryDecision(assignment.categoryDecisionRef());
			decisions.put(assignment.categoryDecisionRef(), decision);
		}
		if (!decision.campaignId().equals(assignment.campaignId()) ||
			!decision.lineage().equals(assignment.lineage()) ||
			!decision.candidateId().equals(assignment.candidateId()) ||
			!decision.measurementEpochRef().equals(assignment.measurementEpochRef()) ||
			decision.allocationPolicy() != contract.developmentAllocationPolicy() ||
			!decision.eligibleCategories().equals(contract.categories().stream().map(Category::id).toList()) ||
			!decision.categoryId().equals(assignment.instance().definition().categoryId()) ||
			assignment.ordinal() >= decision.allocationPolicy().batchSize())
			throw new IOException("Trial assignment conflicts with the published category decision");
		if (decision.batchNumber() == 0)
		{
			EvidenceRef loadingRef = confirmedLoadingRefs.get(contract.contractId());
			if (loadingRef == null)
			{
				String loadingEvidenceId = new ContractService(this).loadProposal(contract.sourceProposalId()).
					loadingEvidenceId();
				loadingRef = new EvidenceRef("prompt-loading", loadingEvidenceId);
				confirmedLoadingRefs.put(contract.contractId(), loadingRef);
			}
			if (!decision.sourceEvidenceRef().equals(loadingRef))
				throw new IOException("Initial category decision has stale confirmed loading evidence");
		}
		else if (verifiedSources.add(decision.sourceEvidenceRef()))
			verifyInitialCoverageCompletion(decision, campaign);
	}

	/**
	 * Checks that the next first-coverage choice cites a complete counted preceding batch.
	 *
	 * @param decision choice authorized by preceding semantic outcomes
	 * @param campaign campaign whose transitions count those outcomes
	 * @throws IOException if the source revision is absent, stale, or incomplete
	 */
	private void verifyInitialCoverageCompletion(InitialCategoryDecision decision, Campaign campaign)
		throws IOException
	{
		InitialCoverageCompletion completion = readInitialCoverageCompletion(decision.sourceEvidenceRef());
		int precedingBatch = decision.batchNumber() - 1;
		String precedingCategory = decision.eligibleCategories().get(precedingBatch);
		EvidenceRef indexedPrevious = readCreateOnceIndexForCampaign("initial-category-decision-index",
			InitialCategoryDecisionService.key(decision.lineage(), decision.measurementEpochRef(), precedingBatch),
			decision.lineage(), campaign).evidence();
		InitialCategoryDecision previous = readInitialCategoryDecision(indexedPrevious);
		if (!completion.campaignId().equals(decision.campaignId()) ||
			!completion.lineage().equals(decision.lineage()) ||
			!completion.candidateId().equals(decision.candidateId()) ||
			!completion.measurementEpochRef().equals(decision.measurementEpochRef()) ||
			!completion.previousDecisionRef().equals(indexedPrevious) ||
			!completion.completedCategoryId().equals(precedingCategory) ||
			completion.outcomeRefs().size() != decision.allocationPolicy().batchSize() ||
			previous.batchNumber() != precedingBatch || !previous.categoryId().equals(precedingCategory) ||
			!previous.lineage().equals(decision.lineage()) ||
			!previous.candidateId().equals(decision.candidateId()) ||
			!previous.measurementEpochRef().equals(decision.measurementEpochRef()) ||
			previous.allocationPolicy() != decision.allocationPolicy() ||
			!previous.eligibleCategories().equals(decision.eligibleCategories()))
			throw new IOException("Initial coverage completion conflicts with its preceding choice");
		Map<String, TrialState> trials = new HashMap<>();
		for (TrialState trial : campaign.trials())
			trials.put(trial.logicalTrialId(), trial);
		for (int ordinal = 0; ordinal < completion.outcomeRefs().size(); ++ordinal)
			verifyCountedInitialCoverageOutcome(completion, ordinal, trials, campaign);
	}

	/**
	 * Matches one ordered source outcome to its assignment and committed campaign trial.
	 *
	 * @param completion retained preceding batch completion
	 * @param ordinal expected assignment ordinal
	 * @param trials current campaign trials by logical identity
	 * @param campaign campaign containing semantic outcome receipts
	 * @throws IOException if the outcome was not counted for the preceding decision
	 */
	private void verifyCountedInitialCoverageOutcome(InitialCoverageCompletion completion, int ordinal,
		Map<String, TrialState> trials, Campaign campaign) throws IOException
	{
		EvidenceRef outcomeRef = completion.outcomeRefs().get(ordinal);
		TrialOutcomeRecord outcome = readTrialOutcome(outcomeRef);
		TrialAssignment assignment = readTrialAssignment(outcome.assignmentRef());
		TrialState trial = trials.get(assignment.logicalTrialId());
		TrialStatus expectedStatus = switch (outcome.response().label())
		{
			case COMPLIANT -> TrialStatus.COMPLIANT;
			case NONCOMPLIANT -> TrialStatus.NONCOMPLIANT;
		};
		if (!outcome.campaignId().equals(completion.campaignId()) ||
			!outcome.lineage().equals(completion.lineage()) ||
			!assignment.campaignId().equals(completion.campaignId()) ||
			!assignment.lineage().equals(completion.lineage()) ||
			!assignment.candidateId().equals(completion.candidateId()) ||
			!assignment.measurementEpochRef().equals(completion.measurementEpochRef()) ||
			!assignment.categoryDecisionRef().equals(completion.previousDecisionRef()) ||
			!assignment.instance().definition().categoryId().equals(completion.completedCategoryId()) ||
			assignment.ordinal() != ordinal || trial == null || trial.status() != expectedStatus ||
			!trial.selectionEvidence().equals(outcome.assignmentRef()) ||
			!outcomeRef.equals(trial.outcomeEvidence()) ||
			campaign.transitionReceipts().stream().noneMatch(receipt ->
				receipt.kind() == TransitionKind.SEMANTIC_OUTCOME &&
				receipt.producerRecordRef().equals(outcomeRef)))
			throw new IOException("Initial coverage completion cites an uncounted outcome");
	}

	/**
	 * Requires the retained assignment to cite its indexed category authority.
	 *
	 * @param assignment committed or proposed trial assignment
	 * @throws IOException if the category decision index is missing, foreign, or corrupt
	 */
	void requireIndexedCategoryDecision(TrialAssignment assignment) throws IOException
	{
		requireIndexedCategoryDecision(assignment, load());
	}

	/**
	 * Checks a category choice against an already verified campaign snapshot.
	 *
	 * @param assignment draw that cites the published choice
	 * @param campaign verified campaign containing the draw
	 * @throws IOException if the choice or its index is invalid
	 */
	private void requireIndexedCategoryDecision(TrialAssignment assignment, Campaign campaign) throws IOException
	{
		if (assignment.categoryDecisionRef().collection().equals("development-category-decisions"))
		{
			new DevelopmentCategoryDecisionService(new CampaignLocator(directory.getParent()), this).
				read(assignment.categoryDecisionRef(), campaign);
			return;
		}
		InitialCategoryDecision decision;
		try
		{
			decision = readInitialCategoryDecision(assignment.categoryDecisionRef());
		}
		catch (IOException exception)
		{
			throw new IOException("Trial category decision evidence cannot be verified", exception);
		}
		EvidenceRef indexed;
		try
		{
			indexed = readCreateOnceIndexForCampaign("initial-category-decision-index",
				InitialCategoryDecisionService.key(assignment.lineage(), assignment.measurementEpochRef(),
					decision.batchNumber()),
				assignment.lineage(), campaign).evidence();
		}
		catch (IOException exception)
		{
			throw new IOException("Trial category decision index cannot be verified", exception);
		}
		if (!indexed.equals(assignment.categoryDecisionRef()))
			throw new IOException("Trial category decision index cites another choice");
	}

	/**
	 * Reads one canonical concrete selection.
	 *
	 * @param reference immutable selection reference
	 * @return validated assignment
	 * @throws IOException if the bytes or record are invalid
	 */
	TrialAssignment readTrialAssignment(EvidenceRef reference) throws IOException
	{
		if (reference == null || !reference.collection().equals("trial-assignments"))
			throw new IOException("Trial assignment producer has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			TrialAssignment assignment = JSON.readValue(bytes, TrialAssignment.class);
			if (assignment == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(assignment)))
				throw new IOException("Trial assignment producer is noncanonical or empty");
			return assignment;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Trial assignment producer is malformed", exception);
		}
	}

	/**
	 * Reads one canonical semantic judgment without applying it to campaign state.
	 *
	 * @param reference retained trial outcome
	 * @return validated outcome record
	 * @throws IOException if the reference or record is invalid
	 */
	TrialOutcomeRecord readTrialOutcome(EvidenceRef reference) throws IOException
	{
		if (reference == null || !reference.collection().equals("trial-outcomes"))
			throw new IOException("Trial outcome producer has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			TrialOutcomeRecord outcome = JSON.readValue(bytes, TrialOutcomeRecord.class);
			if (outcome == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(outcome)))
				throw new IOException("Trial outcome producer is noncanonical or empty");
			return outcome;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Trial outcome producer is malformed", exception);
		}
	}

	/**
	 * Recomputes known-change receipts and validates the active admission pause on a cold read.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign state
	 * @throws IOException if a change or pause is foreign or inconsistent
	 */
	private void verifyMeasurementReceipts(CampaignBinding binding, Campaign campaign) throws IOException
	{
		EvidenceRef latestActiveEpochRef = null;
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() == TransitionKind.NEW_MEASUREMENT)
			{
				verifyNewMeasurementReceipt(binding, campaign, receipt);
				if (receipt.result().lineageId().equals(campaign.activeLineageId()))
					latestActiveEpochRef = readMeasurementObservation(receipt.producerRecordRef()).epochRef();
				continue;
			}
			if (receipt.kind() == TransitionKind.MEASUREMENT_RESTORED)
			{
				verifyMeasurementRestorationReceipt(binding, campaign, receipt);
				continue;
			}
			if (receipt.kind() != TransitionKind.MEASUREMENT_CHANGED)
				continue;
			if (!receipt.binding().equals(binding) ||
				!receipt.producerRecordRef().collection().equals("measurement-changes"))
				throw new IOException("Measurement transition has a foreign producer");
			MeasurementChange change = readMeasurementChange(receipt.producerRecordRef());
			MeasurementObservation baseline = readMeasurementObservation(change.baselineRef());
			MeasurementObservation changed = readMeasurementObservation(change.changedRef());
			if (!change.campaignId().equals(campaign.campaignId()) ||
				!change.lineageId().equals(receipt.result().lineageId()) ||
				!change.epochRef().equals(baseline.epochRef()) ||
				!change.epochRef().equals(changed.epochRef()) ||
				!baseline.campaignId().equals(change.campaignId()) ||
				!changed.campaignId().equals(change.campaignId()) ||
				!baseline.lineageId().equals(change.lineageId()) ||
				!changed.lineageId().equals(change.lineageId()) ||
				!baseline.conditions().compare(changed.conditions()).equals(change.comparison()))
				throw new IOException("Measurement change conflicts with its retained observations");
		}
		MeasurementState state = campaign.measurementState();
		if (latestActiveEpochRef != null &&
			(state == null || !state.epochRef().equals(latestActiveEpochRef)))
			throw new IOException("Active measurement epoch differs from its latest new-identity receipt");
		if (state != null && state.pause().isPresent() &&
			state.pause().orElseThrow().origin() == MeasurementPause.Origin.ADMISSION &&
			campaign.transitionReceipts().stream().noneMatch(receipt ->
				receipt.kind() == TransitionKind.MEASUREMENT_CHANGED &&
					receipt.producerRecordRef().equals(state.pause().orElseThrow().changeRef()) &&
					receipt.result().lineageId().equals(campaign.activeLineageId())))
			throw new IOException("Active measurement pause has no committed change receipt");
	}

	/**
	 * Rechecks a new epoch against the change it resolved and its first accepted observation.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign retained campaign history
	 * @param receipt new measurement transition
	 * @throws IOException if the epoch, predecessor, change, or observation is invalid
	 */
	private void verifyNewMeasurementReceipt(CampaignBinding binding, Campaign campaign,
		TransitionReceipt receipt) throws IOException
	{
		if (!receipt.binding().equals(binding) ||
			!receipt.producerRecordRef().collection().equals("dynamic-baseline-roots"))
			throw new IOException("New measurement has a foreign activation producer");
		MeasurementObservation observed;
		try
		{
			observed = readMeasurementObservation(receipt.producerRecordRef());
		}
		catch (IOException exception)
		{
			throw new IOException("New measurement activation observation is missing or invalid", exception);
		}
		MeasurementEpoch epoch = readMeasurementEpoch(observed.epochRef());
		EvidenceRef predecessorRef = epoch.predecessorRef().orElseThrow(() ->
			new IOException("New measurement epoch has no predecessor"));
		EvidenceRef changeRef = epoch.sourceChangeRef().orElseThrow(() ->
			new IOException("New measurement epoch has no source change"));
		MeasurementEpoch predecessor = readMeasurementEpoch(predecessorRef);
		MeasurementChange change = readMeasurementChange(changeRef);
		MeasurementObservation changed = readMeasurementObservation(change.changedRef());
		if (!epoch.campaignId().equals(campaign.campaignId()) ||
			!epoch.lineageId().equals(receipt.result().lineageId()) ||
			!epoch.contractRef().equals(predecessor.contractRef()) ||
			!epoch.loadingEvidenceRef().equals(predecessor.loadingEvidenceRef()) ||
			!predecessor.campaignId().equals(epoch.campaignId()) ||
			!predecessor.lineageId().equals(epoch.lineageId()) ||
			!change.campaignId().equals(epoch.campaignId()) ||
			!change.lineageId().equals(epoch.lineageId()) ||
			!change.epochRef().equals(predecessorRef) ||
			!observed.campaignId().equals(epoch.campaignId()) ||
			!observed.lineageId().equals(epoch.lineageId()) ||
			observed.stage() != MeasurementObservation.Stage.BEFORE_CALL ||
			campaign.transitionReceipts().stream().noneMatch(previous ->
				previous.kind() == TransitionKind.MEASUREMENT_CHANGED &&
				previous.producerRecordRef().equals(changeRef) &&
				previous.resultingCounter() == receipt.expectedCounter() &&
				previous.result().phase() == receipt.result().phase() &&
				Objects.equals(previous.result().candidateId(), receipt.result().candidateId())))
			throw new IOException("New measurement conflicts with its retained change or observation");
		requirePendingMeasurementCall(changed, observed.logicalCallId());
	}

	/**
	 * Reads and checks one canonical measurement epoch record.
	 *
	 * @param reference immutable epoch reference
	 * @return validated epoch
	 * @throws IOException if its bytes, schema, or reference are invalid
	 */
	private MeasurementEpoch readMeasurementEpoch(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("measurement-epochs"))
			throw new IOException("Measurement epoch has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		MeasurementEpoch epoch = JSON.readValue(bytes, MeasurementEpoch.class);
		if (epoch == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(epoch)))
			throw new IOException("Measurement epoch is noncanonical or empty");
		return epoch;
	}

	/**
	 * Rechecks each paused-campaign cancellation against its retained change and receipt history.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign decoded campaign state
	 * @throws IOException if cancellation evidence or the active closed state conflicts
	 */
	private void verifyPauseCancellationReceipts(CampaignBinding binding, Campaign campaign)
		throws IOException
	{
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.CANCELLATION ||
				!receipt.producerRecordRef().collection().equals("measurement-pause-cancellations"))
				continue;
			MeasurementPauseCancellation producer = readMeasurementPauseCancellation(
				receipt.producerRecordRef());
			MeasurementChange change = readMeasurementChange(producer.changeRef());
			if (!receipt.binding().equals(binding) || !producer.binding().equals(binding) ||
				producer.expectedCounter() != receipt.expectedCounter() ||
				receipt.result().phase() != CampaignStatus.CANCELLED ||
				!producer.lineageId().equals(receipt.result().lineageId()) ||
				!producer.lineageId().equals(change.lineageId()) ||
				!producer.epochRef().equals(change.epochRef()) ||
				campaign.transitionReceipts().stream().noneMatch(previous ->
					previous.kind() == TransitionKind.MEASUREMENT_CHANGED &&
					previous.producerRecordRef().equals(producer.changeRef()) &&
					previous.result().phase() == producer.sourcePhase() &&
					previous.resultingCounter() <= receipt.expectedCounter()))
				throw new IOException("Measurement-pause cancellation conflicts with its original change");
			if (receipt.resultingCounter() == campaign.transitionCounter() &&
				(campaign.status() != CampaignStatus.CANCELLED ||
					campaign.closedPhase() != producer.sourcePhase() ||
					!producer.lineageId().equals(campaign.activeLineageId()) ||
					campaign.measurementState() == null ||
					!producer.epochRef().equals(campaign.measurementState().epochRef()) ||
					!campaign.measurementState().pause().map(pause ->
						pause.changeRef().equals(producer.changeRef())).orElse(false)))
				throw new IOException("Cancelled campaign lost its retained measurement pause");
		}
	}

	/**
	 * Decodes canonical paused-campaign cancellation evidence.
	 *
	 * @param reference immutable cancellation evidence
	 * @return validated cancellation decision
	 * @throws IOException if the record is missing, malformed, or noncanonical
	 */
	private MeasurementPauseCancellation readMeasurementPauseCancellation(EvidenceRef reference)
		throws IOException
	{
		if (!reference.collection().equals("measurement-pause-cancellations"))
			throw new IOException("Measurement-pause cancellation has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			MeasurementPauseCancellation producer = JSON.readValue(bytes,
				MeasurementPauseCancellation.class);
			if (producer == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(producer)))
				throw new IOException("Measurement-pause cancellation is noncanonical or empty");
			return producer;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Measurement-pause cancellation is malformed", exception);
		}
	}

	/**
	 * Recomputes an embedded restoration receipt from its original change and observed conditions.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign current campaign with its retained receipt history
	 * @param receipt restoration receipt to verify
	 * @throws IOException if the producer, comparison, or predecessor is missing or inconsistent
	 */
	private void verifyMeasurementRestorationReceipt(CampaignBinding binding, Campaign campaign,
		TransitionReceipt receipt) throws IOException
	{
		if (!receipt.binding().equals(binding) ||
			!receipt.producerRecordRef().collection().equals("measurement-restorations"))
			throw new IOException("Measurement restoration transition has a foreign producer");
		MeasurementRestoration restoration;
		try
		{
			restoration = readMeasurementRestoration(receipt.producerRecordRef());
		}
		catch (IOException exception)
		{
			throw new IOException("Measurement restoration evidence is missing or invalid", exception);
		}
		MeasurementChange change = readMeasurementChange(restoration.changeRef());
		MeasurementObservation baseline = readMeasurementObservation(change.baselineRef());
		MeasurementObservation restored = readMeasurementObservation(restoration.restoredRef());
		MeasurementObservation changed = readMeasurementObservation(change.changedRef());
		if (!restoration.campaignId().equals(campaign.campaignId()) ||
			!restoration.lineageId().equals(receipt.result().lineageId()) ||
			!restoration.epochRef().equals(change.epochRef()) ||
			!restored.campaignId().equals(restoration.campaignId()) ||
			!restored.lineageId().equals(restoration.lineageId()) ||
			!restored.epochRef().equals(restoration.epochRef()) ||
			restored.stage() != MeasurementObservation.Stage.BEFORE_CALL ||
			!baseline.conditions().compare(restored.conditions()).equals(restoration.comparison()) ||
			campaign.transitionReceipts().stream().noneMatch(previous ->
				previous.kind() == TransitionKind.MEASUREMENT_CHANGED &&
				previous.producerRecordRef().equals(restoration.changeRef()) &&
				previous.resultingCounter() <= receipt.expectedCounter()))
			throw new IOException("Measurement restoration conflicts with its change or observation");
		requireRestoredConditions(change, changed, restored, restoration.comparison());
	}

	/**
	 * Requires every known changed field to regain its original value under the same pending call.
	 *
	 * @param change committed material change
	 * @param changed observation that exposed the change
	 * @param restored proposed restored before-call observation
	 * @param comparison baseline-to-restored comparison
	 * @throws IOException if conditions or call identity remain changed or unknown
	 */
	private static void requireRestoredConditions(MeasurementChange change,
		MeasurementObservation changed, MeasurementObservation restored,
		MeasurementConditions.Comparison comparison) throws IOException
	{
		if (changed.stage() == MeasurementObservation.Stage.BEFORE_CALL &&
			!changed.logicalCallId().equals(restored.logicalCallId()) ||
			changed.stage() == MeasurementObservation.Stage.AFTER_CALL &&
				changed.logicalCallId().equals(restored.logicalCallId()))
			throw new IOException("Restoration identifies another pending logical call");
		if (comparison.hasKnownChange() || change.comparison().fields().entrySet().stream().anyMatch(field ->
			field.getValue() == MeasurementConditions.Status.CHANGED &&
				comparison.fields().get(field.getKey()) != MeasurementConditions.Status.UNCHANGED))
			throw new IOException("Known material conditions are not restored");
	}

	/**
	 * Reads a canonical retained material-change comparison.
	 *
	 * @param reference change evidence
	 * @return validated change
	 * @throws IOException if the change is missing, malformed, or noncanonical
	 */
	MeasurementChange readMeasurementChange(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("measurement-changes"))
			throw new IOException("Measurement change has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			MeasurementChange change = JSON.readValue(bytes, MeasurementChange.class);
			if (change == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(change)))
				throw new IOException("Measurement change is noncanonical or empty");
			return change;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Measurement change is malformed", exception);
		}
	}

	/**
	 * Reads a canonical restoration proof from its immutable reference.
	 *
	 * @param reference restoration evidence
	 * @return validated restoration
	 * @throws IOException if the proof is absent, malformed, or noncanonical
	 */
	private MeasurementRestoration readMeasurementRestoration(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("measurement-restorations"))
			throw new IOException("Measurement restoration has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			MeasurementRestoration restoration = JSON.readValue(bytes, MeasurementRestoration.class);
			if (restoration == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(restoration)))
				throw new IOException("Measurement restoration is noncanonical or empty");
			return restoration;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Measurement restoration is malformed", exception);
		}
	}

	/**
	 * Rehashes every immutable reference owned by the campaign's durable result fields.
	 *
	 * @param campaign campaign read from the current state file
	 * @throws IOException if a referenced immutable record is missing or corrupt
	 */
	private void verifyBridgeEvidence(Campaign campaign) throws IOException
	{
		if (campaign.activeFormalFailureTriggerRef() != null)
			readImmutableJson(campaign.activeFormalFailureTriggerRef());
		MeasurementState measurement = campaign.measurementState();
		if (measurement != null)
		{
			readImmutableJson(measurement.epochRef());
			if (measurement.baselineRootRef().isPresent())
				readImmutableJson(measurement.baselineRootRef().orElseThrow());
			if (measurement.pause().isPresent())
				readImmutableJson(measurement.pause().orElseThrow().changeRef());
		}
		for (TerminalResultRef terminal : campaign.terminalResults())
		{
			readImmutableJson(terminal.terminalRootRef());
			if (terminal.measurementResultRef().isPresent())
				readImmutableJson(terminal.measurementResultRef().orElseThrow());
		}
		if (campaign.acknowledgedInterruptionStopRef() != null)
			readImmutableJson(campaign.acknowledgedInterruptionStopRef());
	}

	/**
	 * Validates the acknowledged stop against the same committed evidence required at ACK.
	 *
	 * @param binding pinned campaign identity
	 * @param campaign campaign read from the pinned state file
	 * @throws IOException if the stop or committed interruption evidence is inconsistent
	 */
	private void verifyAcknowledgedInterruption(CampaignBinding binding, Campaign campaign) throws IOException
	{
		EvidenceRef stopRef = campaign.acknowledgedInterruptionStopRef();
		if (stopRef == null)
		{
			if (campaign.status() == CampaignStatus.INTERRUPTED)
			{
				for (TransitionReceipt receipt : campaign.transitionReceipts())
				{
					if (receipt.kind() == TransitionKind.INTERRUPTION &&
						receipt.resultingCounter() == campaign.transitionCounter() &&
						campaign.scheduler().stops().stream().anyMatch(stop ->
							stop.status() == StopRequest.Status.ACKNOWLEDGED &&
							stop.interruptionReceiptId().equals(Optional.of(receipt.result().receiptId()))))
					{
						throw new IOException("Acknowledged scheduler stop has no active pointer");
					}
				}
			}
			return;
		}
		StopRequest matched = null;
		for (StopRequest stop : campaign.scheduler().stops())
			if (stop.stopRef().equals(stopRef))
			{
				if (matched != null)
					throw new IOException("Acknowledged stop reference is duplicated");
				matched = stop;
			}
		if (matched == null || matched.status() != StopRequest.Status.ACKNOWLEDGED)
			throw new IOException("Acknowledged stop pointer has no matching acknowledged scheduler stop");
		CampaignStopInbox.verifyCommittedInterruption(this, binding, campaign, matched,
			matched.interruptionReceiptId().orElseThrow(), matched.logicalCallId());
	}

	/**
	 * Appends immutable content under a content-addressed evidence collection.
	 *
	 * @param collection the evidence collection name
	 * @param content the immutable evidence bytes
	 * @return the evidence file addressed by content hash
	 * @throws IOException if persistence fails or existing bytes differ
	 */
	public Path appendImmutable(String collection, byte[] content) throws IOException
	{
		EvidenceRef reference = appendImmutableJson(collection, content);
		return directory.resolve(reference.collection()).resolve(reference.evidenceId() + ".json");
	}

	/**
	 * Appends immutable binary content under a content-addressed evidence collection.
	 *
	 * @param collection the evidence collection name
	 * @param content the immutable evidence bytes
	 * @return the binary evidence file addressed by content hash
	 * @throws IOException if persistence fails or existing bytes differ
	 */
	public EvidenceRef appendImmutableBytes(String collection, byte[] content) throws IOException
	{
		return appendImmutableRef(collection, content, ".blob");
	}

	/**
	 * Reads and verifies immutable binary evidence.
	 *
	 * @param collection the evidence collection name
	 * @param evidenceId the content hash
	 * @return verified evidence bytes
	 * @throws IOException if evidence is missing or does not match its content address
	 */
	public byte[] readImmutableBytes(String collection, String evidenceId) throws IOException
	{
		return readImmutable(collection, evidenceId, ".blob");
	}

	/**
	 * Reads typed immutable binary evidence.
	 *
	 * @param evidence immutable evidence reference
	 * @return verified evidence bytes
	 * @throws IOException if evidence is missing or corrupt
	 */
	public byte[] readImmutableBytes(EvidenceRef evidence) throws IOException
	{
		return readImmutableBytes(evidence.collection(), evidence.evidenceId());
	}

	/**
	 * Appends immutable JSON content under a content-addressed evidence collection.
	 *
	 * @param collection the evidence collection name
	 * @param canonicalJson the immutable canonical JSON bytes
	 * @return the JSON evidence file addressed by content hash
	 * @throws IOException if persistence fails or existing bytes differ
	 */
	public EvidenceRef appendImmutableJson(String collection, byte[] canonicalJson) throws IOException
	{
		return appendImmutableRef(collection, canonicalJson, ".json");
	}

	/**
	 * Reads and verifies immutable JSON evidence.
	 *
	 * @param collection the evidence collection name
	 * @param evidenceId the content hash
	 * @return verified JSON bytes
	 * @throws IOException if evidence is missing or does not match its content address
	 */
	public byte[] readImmutableJson(String collection, String evidenceId) throws IOException
	{
		return readImmutable(collection, evidenceId, ".json");
	}

	/**
	 * Reads typed immutable JSON evidence.
	 *
	 * @param evidence immutable evidence reference
	 * @return verified JSON bytes
	 * @throws IOException if evidence is missing or corrupt
	 */
	public byte[] readImmutableJson(EvidenceRef evidence) throws IOException
	{
		return readImmutableJson(evidence.collection(), evidence.evidenceId());
	}

	/**
	 * Reads an immutable evidence item using its retained JSON or binary encoding.
	 *
	 * @param evidence the content address of the retained item
	 * @return the verified bytes
	 * @throws IOException if neither encoding exists or the selected bytes fail verification
	 */
	public byte[] readImmutableEvidence(EvidenceRef evidence) throws IOException
	{
		return readImmutableEvidence(evidence, "Immutable evidence is missing: ");
	}

	/**
	 * Selects the retained encoding while preserving the caller's missing-evidence context.
	 *
	 * @param evidence the immutable content address
	 * @param missingPrefix the diagnostic prefix for a missing item
	 * @return the verified bytes
	 * @throws IOException if the item is missing or corrupt
	 */
	private byte[] readImmutableEvidence(EvidenceRef evidence, String missingPrefix) throws IOException
	{
		Objects.requireNonNull(evidence, "evidence");
		Path parent = directory.resolve(evidence.collection());
		Path jsonFile = parent.resolve(evidence.evidenceId() + ".json");
		Path blobFile = parent.resolve(evidence.evidenceId() + ".blob");
		if (Files.exists(jsonFile, LinkOption.NOFOLLOW_LINKS))
			return readImmutableJson(evidence);
		if (Files.exists(blobFile, LinkOption.NOFOLLOW_LINKS))
			return readImmutableBytes(evidence);
		throw new IOException(missingPrefix + evidence);
	}

	/**
	 * Publishes a lineage-bound index only after its immutable target has been rehashed.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param target lineage and immutable evidence
	 * @return the published target
	 * @throws IOException if an existing entry conflicts or evidence is missing or corrupt
	 */
	public LineageEvidenceRef appendCreateOnceIndex(String indexName, String logicalKey,
		LineageEvidenceRef target) throws IOException
	{
		return appendCreateOnceIndex(indexName, logicalKey, target, Optional.empty());
	}

	/**
	 * Publishes an index only while the campaign still matches the snapshot that selected its target.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param target lineage and immutable evidence selected from the snapshot
	 * @param expected campaign snapshot used to select the target
	 * @return the published target
	 * @throws IOException if the campaign changed or the index or target is invalid
	 */
	LineageEvidenceRef appendCreateOnceIndexIfCurrent(String indexName, String logicalKey,
		LineageEvidenceRef target, Campaign expected) throws IOException
	{
		return appendCreateOnceIndex(indexName, logicalKey, target,
			Optional.of(Objects.requireNonNull(expected, "expected")));
	}

	/**
	 * Rechecks optional campaign identity under the same lock that creates the index.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param target lineage and immutable evidence
	 * @param expected campaign snapshot when publication must be current
	 * @return the published target
	 * @throws IOException if current state differs from the required snapshot or publication fails
	 */
	private LineageEvidenceRef appendCreateOnceIndex(String indexName, String logicalKey,
		LineageEvidenceRef target, Optional<Campaign> expected) throws IOException
	{
		if (!validCollection(indexName) || logicalKey == null || logicalKey.isBlank() || target == null)
			throw new IllegalArgumentException("Index name, key, or target is invalid");
		validateIndexCollection(indexName, target.evidence());
		return withLock(() ->
		{
			verifyCollectionDirectory(indexName, true);
			Campaign campaign = load();
			if (expected.isPresent() && !campaign.equals(expected.orElseThrow()))
				throw new IOException("Campaign changed before create-once index publication");
			verifyLineage(campaign, target.lineage());
			verifyEvidence(target.evidence());
			Path file = lineageIndexPath(indexName, logicalKey);
			if (claimBoundAttemptIndex(indexName))
				return appendClaimBoundAttempt(file, indexName, logicalKey, campaign.campaignId(), target);
			LineageIndexEntry entry = new LineageIndexEntry(1, indexName, logicalKey, campaign.campaignId(),
				target.lineage(), target.evidence(), target.evidence().evidenceId());
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			{
				LineageIndexEntry existing = readLineageIndex(file);
				if (!entry.equals(existing))
					throw new IOException("Create-once index collision at " + file);
				return target;
			}
			writeCreateOnce(file, JSON.writeValueAsBytes(entry));
			return target;
		});
	}

	/**
	 * Reads and verifies a lineage-bound index and its immutable evidence.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param expectedLineage expected lineage and contract
	 * @return verified indexed evidence
	 * @throws IOException if the entry or target is missing, foreign, or corrupt
	 */
	public LineageEvidenceRef readCreateOnceIndex(String indexName, String logicalKey,
		LineageKey expectedLineage) throws IOException
	{
		if (!validCollection(indexName) || logicalKey == null || logicalKey.isBlank() || expectedLineage == null)
			throw new IllegalArgumentException("Index name, key, or lineage is invalid");
		verifyPinnedDirectory();
		Campaign campaign = load();
		return readCreateOnceIndexForCampaign(indexName, logicalKey, expectedLineage, campaign);
	}

	/**
	 * Reads an index against an already decoded campaign without recursively loading that campaign.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param expectedLineage expected lineage and contract
	 * @param campaign already validated campaign snapshot
	 * @return verified indexed evidence
	 * @throws IOException if the index or its target is missing or inconsistent
	 */
	LineageEvidenceRef readCreateOnceIndexForCampaign(String indexName, String logicalKey,
		LineageKey expectedLineage, Campaign campaign) throws IOException
	{
		verifyCollectionDirectory(indexName, false);
		verifyLineage(campaign, expectedLineage);
		Path file = lineageIndexPath(indexName, logicalKey);
		if (claimBoundAttemptIndex(indexName))
			return readClaimBoundAttempt(file, indexName, logicalKey, campaign.campaignId(), expectedLineage);
		LineageIndexEntry entry = readLineageIndex(file);
		if (!entry.indexName().equals(indexName) || !entry.logicalKey().equals(logicalKey) ||
			!entry.campaignId().equals(campaign.campaignId()) || !entry.lineage().equals(expectedLineage))
			throw new IOException("Create-once index has a foreign key, campaign, or lineage: " + file);
		validateIndexCollection(indexName, entry.evidence());
		if (!entry.contentSha256().equals(entry.evidence().evidenceId()))
			throw new IOException("Create-once index content hash disagrees with target: " + file);
		verifyEvidence(entry.evidence());
		return new LineageEvidenceRef(entry.lineage(), entry.evidence());
	}

	/**
	 * Finds a published lineage index while preserving errors for malformed entries.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param expectedLineage expected confirmed lineage
	 * @return verified indexed evidence, or empty before publication
	 * @throws IOException if an existing index or its evidence is invalid
	 */
	Optional<LineageEvidenceRef> findCreateOnceIndex(String indexName, String logicalKey,
		LineageKey expectedLineage) throws IOException
	{
		if (!validCollection(indexName) || logicalKey == null || logicalKey.isBlank() || expectedLineage == null)
			throw new IllegalArgumentException("Index name, key, or lineage is invalid");
		verifyPinnedDirectory();
		Path indexDirectory = directory.resolve(indexName);
		if (Files.notExists(indexDirectory, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		verifyCollectionDirectory(indexName, false);
		if (Files.notExists(lineageIndexPath(indexName, logicalKey), LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		return Optional.of(readCreateOnceIndex(indexName, logicalKey, expectedLineage));
	}

	/**
	 * Finds an index using a campaign snapshot already validated by this store.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @param expectedLineage expected confirmed lineage
	 * @param campaign already validated campaign snapshot
	 * @return verified indexed evidence, or empty before publication
	 * @throws IOException if an existing index or its evidence is invalid
	 */
	Optional<LineageEvidenceRef> findCreateOnceIndex(String indexName, String logicalKey,
		LineageKey expectedLineage, Campaign campaign) throws IOException
	{
		if (!validCollection(indexName) || logicalKey == null || logicalKey.isBlank() || expectedLineage == null)
			throw new IllegalArgumentException("Index name, key, or lineage is invalid");
		Objects.requireNonNull(campaign, "campaign");
		verifyPinnedDirectory();
		Path indexDirectory = directory.resolve(indexName);
		if (Files.notExists(indexDirectory, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		verifyCollectionDirectory(indexName, false);
		if (Files.notExists(lineageIndexPath(indexName, logicalKey), LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		return Optional.of(readCreateOnceIndexForCampaign(indexName, logicalKey, expectedLineage, campaign));
	}

	/**
	 * Finds a retained generation intent when its call index is absent, so a scheduler claim cannot treat that
	 * call as unrelated generic work.
	 *
	 * @param logicalCallId generation call identity to find in retained intent evidence
	 * @return the matching intent reference, or empty when no intent has been retained for the call
	 * @throws IOException if the evidence collection or an intent is malformed or redirected
	 */
	Optional<EvidenceRef> findRetainedGenerationIntent(String logicalCallId) throws IOException
	{
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Generation call identity must be non-blank");
		verifyPinnedDirectory();
		String collection = "generation-attempt-intents";
		Path collectionPath = directory.resolve(collection);
		if (Files.notExists(collectionPath, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		verifyCollectionDirectory(collection, false);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(collectionPath))
		{
			for (Path entry : entries)
			{
				String filename = entry.getFileName().toString();
				if (!filename.endsWith(".json") || !validEvidenceId(filename.substring(0,
					filename.length() - ".json".length())))
					throw new IOException("Generation intent evidence has an invalid filename: " + entry);
				EvidenceRef reference = new EvidenceRef(collection, filename.substring(0,
					filename.length() - ".json".length()));
				byte[] bytes = readImmutableJson(reference);
				GenerationAttemptIntent intent;
				try
				{
					intent = JSON.readValue(bytes, GenerationAttemptIntent.class);
					if (intent == null)
						throw new IOException("Generation intent evidence is empty: " + entry);
				}
				catch (RuntimeException exception)
				{
					throw new IOException("Generation intent evidence is malformed: " + entry, exception);
				}
				if (intent.logicalCallId().equals(logicalCallId))
					return Optional.of(reference);
			}
		}
		return Optional.empty();
	}

	/**
	 * Publishes the canonical flat attempt-index schema.
	 *
	 * @param file pinned index pathname
	 * @param indexName index namespace
	 * @param logicalKey full logical key
	 * @param campaignId campaign identity
	 * @param target lineage-bound attempt
	 * @return published target
	 * @throws IOException if a published entry conflicts or is corrupt
	 */
	private LineageEvidenceRef appendClaimBoundAttempt(Path file, String indexName, String logicalKey,
		String campaignId, LineageEvidenceRef target) throws IOException
	{
		ClaimBoundAttemptIndex entry = new ClaimBoundAttemptIndex(1, indexName, logicalKey, campaignId,
			target.lineage().lineageId(), target.lineage().contractId(), target.evidence(),
			target.evidence().evidenceId());
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
		{
			ClaimBoundAttemptIndex existing = readClaimBoundAttemptEntry(file);
			if (!entry.equals(existing))
				throw new IOException("Create-once index collision at " + file);
			return target;
		}
		writeCreateOnce(file, JSON.writeValueAsBytes(entry));
		return target;
	}

	/**
	 * Rehashes the exact indexed attempt and validates every claim field.
	 *
	 * @param file pinned index pathname
	 * @param indexName index namespace
	 * @param logicalKey full logical key
	 * @param campaignId expected campaign identity
	 * @param expectedLineage expected lineage and contract
	 * @return verified lineage-bound attempt
	 * @throws IOException if an entry or its evidence is invalid
	 */
	private LineageEvidenceRef readClaimBoundAttempt(Path file, String indexName, String logicalKey,
		String campaignId, LineageKey expectedLineage) throws IOException
	{
		ClaimBoundAttemptIndex entry = readClaimBoundAttemptEntry(file);
		if (!entry.indexName().equals(indexName) || !entry.logicalKey().equals(logicalKey) ||
			!entry.campaignId().equals(campaignId) || !entry.lineageId().equals(expectedLineage.lineageId()) ||
			!entry.contractId().equals(expectedLineage.contractId()))
			throw new IOException("Claim-bound attempt has a foreign key, campaign, or lineage: " + file);
		validateIndexCollection(indexName, entry.attemptRef());
		if (!entry.contentSha256().equals(entry.attemptRef().evidenceId()))
			throw new IOException("Claim-bound attempt hash disagrees with target: " + file);
		verifyEvidence(entry.attemptRef());
		return new LineageEvidenceRef(expectedLineage, entry.attemptRef());
	}

	/**
	 * Strictly parses a flat claim-bound attempt entry.
	 *
	 * @param file pinned index pathname
	 * @return canonical entry
	 * @throws IOException if entry bytes are absent or malformed
	 */
	private ClaimBoundAttemptIndex readClaimBoundAttemptEntry(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Claim-bound attempt index is missing: " + file);
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			ClaimBoundAttemptIndex entry = JSON.readValue(bytes, ClaimBoundAttemptIndex.class);
			if (entry == null || entry.schemaVersion() != 1 || !Arrays.equals(bytes, JSON.writeValueAsBytes(entry)))
				throw new IOException("Claim-bound attempt index is malformed: " + file);
			return entry;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Claim-bound attempt index is malformed: " + file, e);
		}
	}

	/**
	 * Identifies the fixed attempt-index namespaces.
	 *
	 * @param indexName index namespace
	 * @return whether the flat attempt schema is required
	 */
	private static boolean claimBoundAttemptIndex(String indexName)
	{
		return switch (indexName)
		{
			case "generator-validation-attempts", "candidate-transport-attempts",
				"evaluator-execution-attempts", "trial-execution-attempts",
				"advisor-dispatch-attempts" -> true;
			default -> false;
		};
	}

	private record ClaimBoundAttemptIndex(int schemaVersion, String indexName, String logicalKey,
		String campaignId, String lineageId, String contractId, EvidenceRef attemptRef, String contentSha256)
	{
	}

	/**
	 * Resolves the pathname for one full logical key.
	 *
	 * @param indexName index collection
	 * @param logicalKey full logical key
	 * @return pinned index pathname
	 */
	private Path lineageIndexPath(String indexName, String logicalKey)
	{
		return directory.resolve(indexName).resolve(sha256(logicalKey.getBytes(
			StandardCharsets.UTF_8)) + ".json");
	}

	/**
	 * Reads and strictly validates one lineage index entry.
	 *
	 * @param file pinned index pathname
	 * @return validated index entry
	 * @throws IOException if the entry is missing or malformed
	 */
	private LineageIndexEntry readLineageIndex(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Create-once index is missing: " + file);
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			LineageIndexEntry entry = JSON.readValue(bytes, LineageIndexEntry.class);
			if (entry == null || entry.schemaVersion() != 1 || !Arrays.equals(bytes, JSON.writeValueAsBytes(entry)))
				throw new IOException("Create-once index is malformed: " + file);
			return entry;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Create-once index is malformed: " + file, e);
		}
	}

	/**
	 * Verifies that the campaign owns the lineage and contract.
	 *
	 * @param campaign current campaign
	 * @param expected expected lineage and contract
	 * @throws IOException if the lineage is foreign
	 */
	private static void verifyLineage(Campaign campaign, LineageKey expected) throws IOException
	{
		if (campaign.lineages().stream().noneMatch(lineage -> lineage.lineageId().equals(expected.lineageId()) &&
			lineage.confirmedContractId().equals(expected.contractId())))
			throw new IOException("Index lineage does not belong to campaign: " + expected.lineageId());
	}

	/**
	 * Enforces attempt-index namespace ownership.
	 *
	 * @param indexName index collection
	 * @param evidence target evidence
	 */
	private static void validateIndexCollection(String indexName, EvidenceRef evidence)
	{
		String required = switch (indexName)
		{
			case "initial-category-decision-index" -> "category-decisions";
			case "development-category-decision-index" -> "development-category-decisions";
			case "development-round-start-index" -> "development-round-starts";
			case "generation-attempt-intent-index" -> "generation-attempt-intents";
			case "generation-attempt-readiness-index" -> "generation-attempt-readiness";
			case "candidate-generation-request-index" -> "candidate-generation-requests";
			case "trial-assignment-index" -> "trial-assignments";
			case "measurement-before-call-index", "measurement-after-call-index" ->
				"dynamic-baseline-roots";
			case "generator-validation-attempts" -> "generator-validation-attempts";
			case "candidate-transport-attempts" -> "candidate-native-attempts";
			case "trial-execution-attempts" -> "trial-transport-attempts";
			case "evaluator-execution-attempts" -> "evaluator-transport-attempts";
			case "trial-outcome-index" -> "trial-outcomes";
			case "advisor-dispatch-attempts" -> "advisor-dispatch-attempts";
			default -> null;
		};
		if (required != null && !required.equals(evidence.collection()))
			throw new IllegalArgumentException("Index requires evidence in " + required);
	}

	/**
	 * Rehashes a JSON or binary evidence target.
	 *
	 * @param evidence target evidence
	 * @throws IOException if the target is missing or corrupt
	 */
	private void verifyEvidence(EvidenceRef evidence) throws IOException
	{
		readImmutableEvidence(evidence, "Indexed evidence is missing: ");
	}

	private record LineageIndexEntry(int schemaVersion, String indexName, String logicalKey, String campaignId,
		LineageKey lineage, EvidenceRef evidence, String contentSha256)
	{
	}

	/**
	 * Publishes an immutable campaign-bound index before any lineage exists.
	 *
	 * @param binding exact opened campaign
	 * @param kind closed preconfirmation namespace
	 * @param logicalKey full logical key
	 * @param evidence persisted evidence
	 * @return verified indexed evidence
	 * @throws IOException if the entry conflicts or evidence is missing or corrupt
	 */
	public EvidenceRef appendPreconfirmationIndex(CampaignBinding binding, PreconfirmationIndexKind kind,
		String logicalKey, EvidenceRef evidence) throws IOException
	{
		validatePreconfirmationInput(binding, kind, logicalKey);
		if (evidence == null)
			throw new NullPointerException("evidence");
		validatePreconfirmationCollection(kind, evidence);
		return withLock(() ->
		{
			verifyCollectionDirectory("preconfirmation-index", true);
			Campaign campaign = verifyBinding(binding);
			if (campaign.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION ||
				campaign.activeLineageId() != null)
				throw new IOException("Preconfirmation index requires an awaiting, unconfirmed campaign");
			verifyEvidence(evidence);
			Path file = preconfirmationIndexPath(binding, kind, logicalKey);
			PreconfirmationEntry entry = new PreconfirmationEntry(1, binding.campaignId(), kind, logicalKey,
				evidence, evidence.evidenceId());
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			{
				PreconfirmationEntry existing = readPreconfirmationEntry(file);
				if (!entry.equals(existing))
					throw new IOException("Preconfirmation index collision at " + file);
				return evidence;
			}
			writeCreateOnce(file, JSON.writeValueAsBytes(entry));
			return evidence;
		});
	}

	/**
	 * Reads a verified campaign-bound index, including its immutable evidence target.
	 *
	 * @param binding exact opened campaign
	 * @param kind closed preconfirmation namespace
	 * @param logicalKey full logical key
	 * @return empty if absent, otherwise verified evidence
	 * @throws IOException if the entry or evidence is foreign or corrupt
	 */
	public Optional<EvidenceRef> readPreconfirmationIndex(CampaignBinding binding, PreconfirmationIndexKind kind,
		String logicalKey) throws IOException
	{
		validatePreconfirmationInput(binding, kind, logicalKey);
		return withLock(() -> readPreconfirmationIndexLocked(binding, kind, logicalKey));
	}

	/**
	 * Reads an index while the campaign lock is already held by publication or verification.
	 *
	 * @param binding exact opened campaign
	 * @param kind closed preconfirmation namespace
	 * @param logicalKey full logical key
	 * @return empty if absent, otherwise verified evidence
	 * @throws IOException if the entry or evidence is foreign or corrupt
	 */
	private Optional<EvidenceRef> readPreconfirmationIndexLocked(CampaignBinding binding,
		PreconfirmationIndexKind kind, String logicalKey) throws IOException
	{
		validatePreconfirmationInput(binding, kind, logicalKey);
		verifyBinding(binding);
		if (Files.exists(directory.resolve("preconfirmation-index"), LinkOption.NOFOLLOW_LINKS))
			verifyCollectionDirectory("preconfirmation-index", false);
		Path file = preconfirmationIndexPath(binding, kind, logicalKey);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		PreconfirmationEntry entry = readPreconfirmationEntry(file);
		if (!entry.campaignId().equals(binding.campaignId()) || entry.indexKind() != kind ||
			!entry.logicalKey().equals(logicalKey) || !entry.contentSha256().equals(entry.evidenceRef().evidenceId()))
			throw new IOException("Preconfirmation index key, campaign, kind, or hash is foreign: " + file);
		validatePreconfirmationCollection(kind, entry.evidenceRef());
		verifyEvidence(entry.evidenceRef());
		return Optional.of(entry.evidenceRef());
	}

	/**
	 * Requires the exact before-call observation for an unconfirmed inspection claim.
	 *
	 * @param binding owning campaign
	 * @param campaign current campaign state
	 * @param ownerRef indexed inspection owner
	 * @param cycleRef indexed preparation cycle
	 * @param logicalCallId claimed logical call
	 * @param requestRef immutable native request
	 * @throws IOException if the indexed observation is absent, foreign, or inconsistent with the request
	 */
	void verifyPreparationBeforeCallObservation(CampaignBinding binding, Campaign campaign,
		EvidenceRef ownerRef, EvidenceRef cycleRef, String logicalCallId, EvidenceRef requestRef)
		throws IOException
	{
		if (campaign.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION ||
			campaign.activeLineageId() != null)
			throw new IOException("Preconfirmation measurement requires an unconfirmed campaign");
		String key = PreparationMeasurementObservation.indexKey(cycleRef, logicalCallId);
		EvidenceRef observationRef = readPreconfirmationIndexLocked(binding,
			PreconfirmationIndexKind.CONTRACT_INSPECTION_BEFORE_OBSERVATION, key).
			orElseThrow(() -> new IOException("Inspection measurement observation is missing: " + logicalCallId));
		try
		{
			byte[] bytes = readImmutableJson(observationRef);
			PreparationMeasurementObservation observation = JSON.readValue(bytes,
				PreparationMeasurementObservation.class);
			if (observation == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(observation)) ||
				!observation.campaignId().equals(campaign.campaignId()) ||
				!observation.ownerRef().equals(ownerRef) || !observation.cycleRef().equals(cycleRef) ||
				!observation.logicalCallId().equals(logicalCallId) ||
				observation.stage() != MeasurementObservation.Stage.BEFORE_CALL)
				throw new IOException("Inspection measurement observation has foreign authority");
			InspectionRunRequest request = JSON.readValue(readImmutableJson(requestRef),
				InspectionRunRequest.class);
			if (request == null || !request.campaignId().equals(campaign.campaignId()) ||
				!request.logicalCallId().equals(logicalCallId))
				throw new IOException("Inspection measurement request has foreign authority");
			EvidenceRef modelRef = new EvidenceRef("measurement-values",
				sha256(JSON.writeValueAsBytes(request.modelProfile())));
			Map<MeasurementConditions.Kind, MeasurementConditions.Value> values = observation.conditions().values();
			if (!new MeasurementConditions.Value.Known(modelRef).equals(
				values.get(MeasurementConditions.Kind.MODEL_PROFILE)) ||
				!new MeasurementConditions.Value.Known(request.context().snapshot().snapshotManifestRef()).equals(
					values.get(MeasurementConditions.Kind.PROMPT_SOURCES)))
				throw new IOException("Inspection measurement conflicts with selected model or starting snapshot");
			for (MeasurementConditions.Value value : values.values())
				if (value instanceof MeasurementConditions.Value.Known known)
					verifyEvidence(known.evidence());
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Inspection measurement observation is malformed", exception);
		}
	}

	/**
	 * Checks the closed API values before resolving an index path.
	 *
	 * @param binding campaign binding
	 * @param kind preconfirmation namespace
	 * @param logicalKey full logical key
	 */
	private static void validatePreconfirmationInput(CampaignBinding binding, PreconfirmationIndexKind kind,
		String logicalKey)
	{
		if (binding == null || kind == null || logicalKey == null || logicalKey.isBlank())
			throw new IllegalArgumentException("Preconfirmation binding, kind, or key is invalid");
	}

	/**
	 * Checks the fixed inspection and source-change evidence namespaces.
	 *
	 * @param kind preconfirmation namespace
	 * @param evidence evidence target
	 */
	private static void validatePreconfirmationCollection(PreconfirmationIndexKind kind, EvidenceRef evidence)
	{
		String required = switch (kind)
		{
			case CONTRACT_PREPARATION -> "contract-preparation-cycles";
			case CONTRACT_SOURCE_CAPTURE -> "contract-source-captures";
			case CONTRACT_INSPECTION_OWNER -> "contract-preparation-owners";
			case CONTRACT_INSPECTION_ATTEMPT -> "contract-inspection-attempts";
			case CONTRACT_INSPECTION_BEFORE_OBSERVATION -> "preparation-measurement-observations";
			case CONTRACT_SOURCE_CHANGE -> "contract-preparation-source-changes";
		};
		if (required != null && !evidence.collection().equals(required))
			throw new IllegalArgumentException("Preconfirmation index requires " + required + " evidence");
	}

	/**
	 * Resolves the domain-separated filename for a preconfirmation index.
	 *
	 * @param binding campaign binding
	 * @param kind preconfirmation namespace
	 * @param logicalKey full logical key
	 * @return pinned index pathname
	 * @throws IOException if the key cannot be serialized
	 */
	private Path preconfirmationIndexPath(CampaignBinding binding, PreconfirmationIndexKind kind,
		String logicalKey) throws IOException
	{
		byte[] key = JSON.writeValueAsBytes(new PreconfirmationKey(binding.campaignId(), kind, logicalKey));
		return directory.resolve("preconfirmation-index").resolve(sha256(key) + ".json");
	}

	/**
	 * Verifies that the binding still names this store's campaign.
	 *
	 * @param binding requested campaign binding
	 * @return campaign snapshot validated for the binding
	 * @throws IOException if the binding is foreign or the campaign cannot be validated
	 */
	Campaign verifyBinding(CampaignBinding binding) throws IOException
	{
		if (binding == null || !directory.equals(binding.realDirectory()))
			throw new IOException("Preconfirmation binding belongs to another campaign");
		Campaign campaign = load();
		verifyBinding(binding, campaign);
		return campaign;
	}

	/**
	 * Checks a binding against a campaign snapshot already verified by this store.
	 *
	 * @param binding requested campaign binding
	 * @param campaign verified campaign snapshot
	 * @throws IOException if the binding identifies another campaign
	 */
	private void verifyBinding(CampaignBinding binding, Campaign campaign) throws IOException
	{
		if (binding == null || !directory.equals(binding.realDirectory()) ||
			!campaign.campaignId().equals(binding.campaignId()))
			throw new IOException("Preconfirmation binding belongs to another campaign");
	}

	/**
	 * Reads a bound campaign within its state lock.
	 *
	 * @param binding exact campaign binding
	 * @return locked campaign snapshot
	 * @throws IOException if the binding or campaign is invalid
	 */
	Campaign lockedSnapshot(CampaignBinding binding) throws IOException
	{
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			reconcileTransitionJournal(campaign);
			return campaign;
		});
	}

	/**
	 * Commits one host-stop interruption under the same lock as its campaign predecessor.
	 *
	 * @param request exact interruption request and immutable producer identity
	 * @return the original committed result, including on equal retry
	 * @throws IOException if the producer, stop, counter, or campaign phase conflicts
	 */
	TransitionResult commitInterruption(TransitionRequest request) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(request.binding());
			if (request.kind() != TransitionKind.INTERRUPTION ||
				!request.producerRecordRef().collection().equals("campaign-interruption-records"))
				throw new IOException("Interruption request has the wrong kind or producer collection");
			InterruptionRecord producer = InterruptionRecord.decode(readImmutableJson(
				request.producerRecordRef()));
			if (!producer.binding().equals(request.binding()) ||
				producer.expectedCounter() != request.expectedCounter() ||
				producer.reason() != InterruptionRecord.Reason.HOST_OR_TOKEN_STOP)
				throw new IOException("Interruption producer conflicts with its request");
			if (current.transitionCounter() > request.expectedCounter())
			{
				for (TransitionReceipt receipt : current.transitionReceipts())
					if (receipt.expectedCounter() == request.expectedCounter())
					{
						if (receipt.kind() != request.kind() ||
							!receipt.producerRecordRef().equals(request.producerRecordRef()) ||
							!receipt.binding().equals(request.binding()))
							throw new IOException("Conflicting campaign transition for committed counter: " +
								request.expectedCounter());
						verifyRetainedInterruptionStop(current, request.binding(), producer);
						reconcileTransitionJournal(current);
						return receipt.result();
					}
			}
			if (current.transitionCounter() != request.expectedCounter())
				throw new IOException("Stale campaign transition counter: " + request.expectedCounter());
			if (current.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION &&
				current.status() != CampaignStatus.DEVELOPING &&
				current.status() != CampaignStatus.FORMAL_TESTING)
				throw new IOException("Campaign phase cannot be interrupted: " + current.status());
			verifyInterruptionStop(current, request.binding(), producer);
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, CampaignStatus.INTERRUPTED,
				current.activeLineageId(), current.currentCandidateId(),
				"interruption-" + request.producerRecordRef().evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(request.binding(), TransitionKind.INTERRUPTION,
				request.expectedCounter(), request.producerRecordRef(), nextCounter, result);
			Campaign next = current.withInterruption(receipt);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return result;
		});
	}

	/**
	 * Commits a caller-selected cancellation while the confirmed campaign is paused for a known change.
	 *
	 * @param request exact cancellation decision and paused predecessor revision
	 * @return original cancellation result, including on an equal retry
	 * @throws IOException if the producer, pause, native slots, or predecessor conflict
	 */
	TransitionResult commitMeasurementPauseCancellation(TransitionRequest request) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(request.binding());
			if (request.kind() != TransitionKind.CANCELLATION ||
				!request.producerRecordRef().collection().equals("measurement-pause-cancellations"))
				throw new IOException("Paused cancellation requires its measurement-pause producer");
			MeasurementPauseCancellation producer = readMeasurementPauseCancellation(
				request.producerRecordRef());
			if (!producer.binding().equals(request.binding()) ||
				producer.expectedCounter() != request.expectedCounter())
				throw new IOException("Paused cancellation producer conflicts with its request");
			if (current.transitionCounter() > request.expectedCounter())
			{
				for (TransitionReceipt receipt : current.transitionReceipts())
					if (receipt.expectedCounter() == request.expectedCounter())
					{
						if (receipt.kind() != request.kind() ||
							!receipt.producerRecordRef().equals(request.producerRecordRef()) ||
							!receipt.binding().equals(request.binding()))
							throw new IOException("Conflicting cancellation at committed counter: " +
								request.expectedCounter());
						reconcileTransitionJournal(current);
						return receipt.result();
					}
			}
			if (current.transitionCounter() != request.expectedCounter())
				throw new IOException("Stale cancellation counter: " + request.expectedCounter());
			MeasurementState measurement = current.measurementState();
			if (current.status() != producer.sourcePhase() ||
				!Objects.equals(current.activeLineageId(), producer.lineageId()) ||
				measurement == null || !measurement.epochRef().equals(producer.epochRef()) ||
				measurement.pause().isEmpty() ||
				measurement.pause().orElseThrow().origin() != MeasurementPause.Origin.ADMISSION ||
				!measurement.pause().orElseThrow().changeRef().equals(producer.changeRef()))
				throw new IOException("Paused cancellation differs from the current measurement change");
			if (current.scheduler().hasUnadoptedCalls())
				throw new IOException("Paused cancellation requires settled prior calls");
			MeasurementChange change = readMeasurementChange(producer.changeRef());
			if (!change.epochRef().equals(producer.epochRef()) ||
				!change.lineageId().equals(producer.lineageId()))
				throw new IOException("Paused cancellation change evidence is foreign");
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, CampaignStatus.CANCELLED,
				current.activeLineageId(), current.currentCandidateId(),
				"cancellation-" + request.producerRecordRef().evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(request.binding(), TransitionKind.CANCELLATION,
				request.expectedCounter(), request.producerRecordRef(), nextCounter, result);
			Campaign next = current.withMeasurementPauseCancellation(receipt);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return result;
		});
	}

	/**
	 * Commits the first source-backed confirmed contract and measurement epoch under the state lock.
	 *
	 * @param request exact initial confirmation request
	 * @return original result on first call and equal retry
	 * @throws IOException if evidence, predecessor, or scheduling state is invalid
	 */
	TransitionResult commitConfirmation(TransitionRequest request) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(request.binding());
			if (request.kind() != TransitionKind.CONFIRMED_CONTRACT ||
				!request.producerRecordRef().collection().equals("contracts"))
				throw new IOException("Confirmation request has the wrong kind or producer collection");
			ConfirmedContract contract = new ContractService(this).loadConfirmedContract(
				request.producerRecordRef().evidenceId());
			if (current.transitionCounter() > request.expectedCounter())
			{
				for (TransitionReceipt receipt : current.transitionReceipts())
					if (receipt.expectedCounter() == request.expectedCounter())
					{
						if (!receipt.binding().equals(request.binding()) ||
							receipt.kind() != request.kind() ||
							!receipt.producerRecordRef().equals(request.producerRecordRef()))
							throw new IOException("Conflicting campaign transition for committed counter: " +
								request.expectedCounter());
						reconcileTransitionJournal(current);
						return receipt.result();
					}
			}
			if (current.transitionCounter() != request.expectedCounter() ||
				current.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION ||
				current.activeLineageId() != null)
				throw new IOException("Confirmation requires the exact unconfirmed campaign predecessor");
			if (current.scheduler().hasUnadoptedCalls() ||
				current.scheduler().invocations().stream().anyMatch(registration ->
					registration.status() != InvocationStopRegistration.Status.CLOSED) ||
				!current.scheduler().stops().isEmpty() || current.scheduler().barrier().isPresent())
				throw new IOException("Confirmation requires settled preparation calls and no pending stop");
			String lineageId = sha256(JSON.writeValueAsBytes(request));
			StoredProposal source = new ContractService(this).loadProposal(contract.sourceProposalId());
			MeasurementEpoch epoch = new MeasurementEpoch(MeasurementEpoch.SCHEMA_VERSION,
				current.campaignId(), lineageId, request.producerRecordRef(),
				new EvidenceRef("prompt-loading", source.loadingEvidenceId()), Optional.empty(),
				Optional.empty());
			EvidenceRef epochRef = appendImmutableJsonDirect("measurement-epochs", JSON.writeValueAsBytes(epoch));
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, CampaignStatus.DEVELOPING, lineageId,
				null, "confirmation-" + request.producerRecordRef().evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(request.binding(), request.kind(),
				request.expectedCounter(), request.producerRecordRef(), nextCounter, result);
			Campaign next = current.withConfirmedContract(receipt, contract.contractId(), epochRef);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return result;
		});
	}

	/**
	 * Selects a retained first candidate under the campaign state lock.
	 *
	 * @param binding exact pinned campaign
	 * @param candidateRef immutable first-candidate record
	 * @return committed selection, including on equal retry
	 * @throws IOException if the candidate does not belong to the active confirmed lineage
	 */
	TransitionResult commitInitialCandidate(CampaignBinding binding, EvidenceRef candidateRef) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			InitialCandidate candidate = readInitialCandidate(candidateRef);
			if (!candidate.campaignId().equals(binding.campaignId()) ||
				!candidate.lineageId().equals(current.activeLineageId()) ||
				current.lineages().stream().noneMatch(lineage ->
					lineage.lineageId().equals(candidate.lineageId()) &&
					lineage.confirmedContractId().equals(candidate.contractId())))
				throw new IOException("Initial candidate conflicts with the active confirmed claim");
			new PromptSnapshotRepository(directory).load(candidate.candidateSnapshotId());
			for (TransitionReceipt receipt : current.transitionReceipts())
			{
				if (receipt.kind() == TransitionKind.INITIAL_CANDIDATE &&
					receipt.producerRecordRef().equals(candidateRef))
				{
					if (!candidate.candidateSnapshotId().equals(current.currentCandidateId()))
						throw new IOException("Initial candidate is no longer the current candidate");
					reconcileTransitionJournal(current);
					return receipt.result();
				}
			}
			if (current.status() != CampaignStatus.DEVELOPING || current.currentCandidateId() != null ||
				current.appliedCandidateId() != null || !current.trials().isEmpty())
				throw new IOException("Initial candidate requires an unselected developing campaign");
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, CampaignStatus.DEVELOPING,
				candidate.lineageId(), candidate.candidateSnapshotId(),
				"initial-candidate-" + candidateRef.evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.INITIAL_CANDIDATE,
				current.transitionCounter(), candidateRef, nextCounter, result);
			Campaign next = current.withInitialCandidate(receipt, candidate);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return result;
		});
	}

	/**
	 * Commits one indexed development draw before a native call can be claimed.
	 *
	 * @param binding exact pinned campaign
	 * @param assignmentRef immutable indexed assignment
	 * @return original result on equal retry
	 * @throws IOException if the draw, candidate, or active claim conflicts
	 */
	TransitionResult commitTrialAssignment(CampaignBinding binding, EvidenceRef assignmentRef) throws IOException
	{
		return withLock(() ->
		{
			TrialEvidenceRead read = readTrialEvidence();
			Campaign current = read.campaign();
			verifyBinding(binding, current);
			TrialAssignment assignment = readTrialAssignment(assignmentRef);
			if (current.status() != CampaignStatus.DEVELOPING ||
				current.measurementState() == null || current.measurementState().pause().isPresent() ||
				!assignment.campaignId().equals(binding.campaignId()) ||
				!assignment.lineage().lineageId().equals(current.activeLineageId()) ||
				!assignment.candidateId().equals(current.currentCandidateId()))
				throw new IOException("Trial assignment conflicts with the active confirmed candidate");
			if (!assignment.measurementEpochRef().equals(current.measurementState().epochRef()))
				throw new IOException("Trial assignment conflicts with the active measurement epoch");
			if (current.transitionReceipts().stream().noneMatch(receipt ->
				receipt.kind() == TransitionKind.INITIAL_CANDIDATE &&
				Objects.equals(receipt.result().lineageId(), assignment.lineage().lineageId()) &&
				Objects.equals(receipt.result().candidateId(), assignment.candidateId())))
				throw new IOException("Trial assignment requires the adopted initial candidate");
			ConfirmedContract contract = new ContractService(this).loadConfirmedContract(
				assignment.lineage().contractId());
			if (assignment.categoryDecisionRef().collection().equals("category-decisions"))
				requireIndexedCategoryDecision(assignment, current);
			verifyCategoryDecision(assignment, contract, current, new HashMap<>(), new HashMap<>(),
				new DevelopmentCategoryDecisionService.ValidationSession(current), new HashSet<>());
			try
			{
				TrialSampler.verifySelection(contract, assignment.instance());
			}
			catch (IllegalArgumentException exception)
			{
				throw new IOException("Trial assignment has a foreign selection", exception);
			}
			String key;
			if (assignment.categoryDecisionRef().collection().equals("development-category-decisions"))
				key = TrialAssignmentService.postCoverageDevelopmentKey(assignment.lineage(),
					assignment.measurementEpochRef(), assignment.categoryDecisionRef(), assignment.ordinal());
			else
				key = TrialAssignmentService.initialDevelopmentKey(assignment.lineage(),
					assignment.measurementEpochRef(), assignment.instance().definition().categoryId(),
					assignment.ordinal());
			if (!readCreateOnceIndexForCampaign("trial-assignment-index", key, assignment.lineage(), current).evidence().
				equals(assignmentRef))
				throw new IOException("Trial assignment differs from its create-once index");
			for (TransitionReceipt receipt : current.transitionReceipts())
			{
				if (receipt.kind() == TransitionKind.TRIAL_ASSIGNMENT &&
					receipt.producerRecordRef().equals(assignmentRef))
				{
					reconcileTransitionJournal(current);
					return receipt.result();
				}
			}
			if (current.trials().stream().anyMatch(trial ->
				trial.logicalTrialId().equals(assignment.logicalTrialId())))
				throw new IOException("Trial assignment logical identity is already committed");
			DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(this).
				groupTrialEvidence(read);
			DevelopmentWeaknessGuard.requireNoClearlyWeakCategory(evidence, contract);
			new DevelopmentReviewService(this).requireEligible(evidence, contract);
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, CampaignStatus.DEVELOPING,
				assignment.lineage().lineageId(), assignment.candidateId(),
				"trial-assignment-" + assignmentRef.evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.TRIAL_ASSIGNMENT,
				current.transitionCounter(), assignmentRef, nextCounter, result);
			Campaign next = current.withTrialAssignment(receipt, assignment);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return result;
		});
	}

	/**
	 * Applies one verified semantic outcome to its assigned development trial under the state lock.
	 *
	 * @param request outcome transition with its observed campaign counter
	 * @return committed result
	 * @throws IOException if the assignment, outcome index, or campaign state changed
	 */
	TransitionResult commitSemanticOutcome(TransitionRequest request) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(request.binding());
			TrialOutcomeRecord outcome = readTrialOutcome(request.producerRecordRef());
			TrialAssignment assignment = readTrialAssignment(outcome.assignmentRef());
			if (request.kind() != TransitionKind.SEMANTIC_OUTCOME ||
				current.transitionCounter() != request.expectedCounter() ||
				current.status() != CampaignStatus.DEVELOPING || current.measurementState() == null ||
				current.measurementState().pause().isPresent() ||
				!outcome.campaignId().equals(request.binding().campaignId()) ||
				!outcome.lineage().equals(assignment.lineage()) ||
				!assignment.campaignId().equals(request.binding().campaignId()) ||
				!assignment.lineage().lineageId().equals(current.activeLineageId()) ||
				!assignment.candidateId().equals(current.currentCandidateId()) ||
				!assignment.measurementEpochRef().equals(current.measurementState().epochRef()) ||
				!readCreateOnceIndex("trial-outcome-index", outcome.assignmentRef().evidenceId(),
					outcome.lineage()).evidence().equals(request.producerRecordRef()))
				throw new IOException("Semantic outcome conflicts with the active development trial");
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, CampaignStatus.DEVELOPING,
				assignment.lineage().lineageId(), assignment.candidateId(),
				"semantic-outcome-" + request.producerRecordRef().evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(request.binding(), TransitionKind.SEMANTIC_OUTCOME,
				current.transitionCounter(), request.producerRecordRef(), nextCounter, result);
			Campaign next = current.withSemanticOutcome(receipt, assignment, outcome);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return result;
		});
	}

	/**
	 * Compares one retained before-call observation and commits its baseline or material-change pause.
	 *
	 * @param binding exact confirmed campaign
	 * @param observationRef immutable complete observation
	 * @return accepted observation or retained change pause, including on equal retry
	 * @throws IOException if the observation, predecessor, or conditions are invalid
	 */
	MeasurementObservationDecision commitBeforeCallObservation(CampaignBinding binding,
		EvidenceRef observationRef) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			MeasurementObservation observed = readMeasurementObservation(observationRef);
			MeasurementState measurement = current.measurementState();
			if (measurement == null || current.status() != CampaignStatus.DEVELOPING &&
				current.status() != CampaignStatus.FORMAL_TESTING ||
				!observed.campaignId().equals(current.campaignId()) ||
				!observed.lineageId().equals(current.activeLineageId()) ||
				!observed.epochRef().equals(measurement.epochRef()) ||
				observed.stage() != MeasurementObservation.Stage.BEFORE_CALL)
				throw new IOException("Before-call observation has a foreign claim, epoch, or phase");
			if (measurement.pause().isPresent())
			{
				EvidenceRef changeRef = measurement.pause().orElseThrow().changeRef();
				MeasurementChange retained = JSON.readValue(readImmutableJson(changeRef), MeasurementChange.class);
				if (retained == null || !retained.changedRef().equals(observationRef))
					throw new IOException("Campaign is paused for another measurement change");
				return new MeasurementObservationDecision(MeasurementObservationDecision.Status.PAUSED,
					observationRef, Optional.of(changeRef));
			}
			if (measurement.baselineRootRef().equals(Optional.of(observationRef)))
				return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
					observationRef, Optional.empty());
			if (current.scheduler().hasUnadoptedCalls())
				throw new IOException("Measurement observation requires settled prior calls");
			if (measurement.baselineRootRef().isPresent())
			{
				EvidenceRef baselineRef = measurement.baselineRootRef().orElseThrow();
				MeasurementObservation baseline = readMeasurementObservation(baselineRef);
				if (baseline.logicalCallId().equals(observed.logicalCallId()))
					throw new IOException("A logical call already has a different before-call observation");
				if (baseline.stage() == MeasurementObservation.Stage.BEFORE_CALL &&
					current.scheduler().slots().stream().anyMatch(slot ->
						slot.logicalCallId().equals(baseline.logicalCallId()) &&
							slot.state() == CallSlot.State.ADOPTED))
					throw new IOException("Adopted call requires an after-call observation before another call");
				if (!baseline.campaignId().equals(current.campaignId()) ||
					!baseline.lineageId().equals(current.activeLineageId()) ||
					!baseline.epochRef().equals(measurement.epochRef()))
					throw new IOException("Dynamic baseline belongs to another claim or epoch");
				if (baseline.stage() == MeasurementObservation.Stage.AFTER_CALL)
				{
					MeasurementEpoch epoch = JSON.readValue(readImmutableJson(measurement.epochRef()),
						MeasurementEpoch.class);
					LineageKey lineage = new LineageKey(current.activeLineageId(),
						epoch.contractRef().evidenceId());
					String key = MeasurementObservation.callIndexKey(measurement.epochRef(),
						baseline.logicalCallId());
					EvidenceRef indexed;
					try
					{
						indexed = readCreateOnceIndex("measurement-after-call-index", key, lineage).evidence();
					}
					catch (IOException exception)
					{
						throw new IOException("Current after-call observation index is missing or invalid",
							exception);
					}
					if (!indexed.equals(baselineRef))
						throw new IOException("Current after-call observation differs from its index");
				}
				MeasurementConditions.Comparison comparison = baseline.conditions().compare(observed.conditions());
				if (comparison.hasKnownChange())
					return commitMeasurementChange(binding, current, baselineRef, observationRef, comparison);
			}
			writeCampaign(current.withMeasurementBaseline(observationRef));
			return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
				observationRef, Optional.empty());
		});
	}

	/**
	 * Compares an adopted call's after-call conditions and retains a pause before another call.
	 *
	 * @param binding exact confirmed campaign
	 * @param observationRef immutable complete after-call observation
	 * @return accepted observation or retained change pause, including on equal retry
	 * @throws IOException if the call has not been adopted or evidence conflicts
	 */
	MeasurementObservationDecision commitAfterCallObservation(CampaignBinding binding,
		EvidenceRef observationRef) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			MeasurementObservation observed = readMeasurementObservation(observationRef);
			MeasurementState measurement = current.measurementState();
			if (measurement == null || current.status() != CampaignStatus.DEVELOPING &&
				current.status() != CampaignStatus.FORMAL_TESTING ||
				!observed.campaignId().equals(current.campaignId()) ||
				!observed.lineageId().equals(current.activeLineageId()) ||
				!observed.epochRef().equals(measurement.epochRef()) ||
				observed.stage() != MeasurementObservation.Stage.AFTER_CALL)
				throw new IOException("After-call observation has a foreign claim, epoch, or phase");
			CallSlot slot = current.scheduler().slots().stream().filter(candidate ->
				candidate.logicalCallId().equals(observed.logicalCallId())).findFirst().orElseThrow(() ->
				new IOException("After-call observation has no retained call"));
			if (slot.state() != CallSlot.State.ADOPTED)
				throw new IOException("After-call observation requires an adopted call");
			verifySlotClaim(binding, current, slot);
			if (measurement.pause().isPresent())
			{
				EvidenceRef changeRef = measurement.pause().orElseThrow().changeRef();
				MeasurementChange retained = readMeasurementChange(changeRef);
				if (!retained.changedRef().equals(observationRef))
					throw new IOException("Campaign is paused for another measurement change");
				return new MeasurementObservationDecision(MeasurementObservationDecision.Status.PAUSED,
					observationRef, Optional.of(changeRef));
			}
			if (measurement.baselineRootRef().equals(Optional.of(observationRef)))
				return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
					observationRef, Optional.empty());
			EvidenceRef baselineRef = measurement.baselineRootRef().orElseThrow(() ->
				new IOException("After-call observation requires a before-call baseline"));
			verifyBeforeCallObservation(binding, current, observed.logicalCallId());
			MeasurementObservation baseline = readMeasurementObservation(baselineRef);
			MeasurementConditions.Comparison comparison = baseline.conditions().compare(observed.conditions());
			if (comparison.hasKnownChange())
				return commitMeasurementChange(binding, current, baselineRef, observationRef, comparison);
			writeCampaign(current.withMeasurementBaseline(observationRef));
			return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
				observationRef, Optional.empty());
		});
	}

	/**
	 * Commits a restored before-call baseline under the original confirmed measurement epoch.
	 *
	 * @param binding exact confirmed campaign
	 * @param observationRef complete restored before-call conditions
	 * @return accepted observation, including on an equal publication retry
	 * @throws IOException if the known change remains or another call is unresolved
	 */
	MeasurementObservationDecision commitRestoredObservation(CampaignBinding binding,
		EvidenceRef observationRef) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			MeasurementObservation observed = readMeasurementObservation(observationRef);
			MeasurementState measurement = current.measurementState();
			if (measurement == null || current.status() != CampaignStatus.DEVELOPING &&
				current.status() != CampaignStatus.FORMAL_TESTING ||
				!observed.campaignId().equals(current.campaignId()) ||
				!observed.lineageId().equals(current.activeLineageId()) ||
				!observed.epochRef().equals(measurement.epochRef()) ||
				observed.stage() != MeasurementObservation.Stage.BEFORE_CALL)
				throw new IOException("Restoration observation has a foreign claim, epoch, or phase");
			if (measurement.pause().isEmpty())
			{
				if (!measurement.baselineRootRef().equals(Optional.of(observationRef)) ||
					current.transitionReceipts().isEmpty())
					throw new IOException("Campaign has no matching measurement pause to restore");
				TransitionReceipt latest = current.transitionReceipts().getLast();
				if (latest.kind() != TransitionKind.MEASUREMENT_RESTORED ||
					!readMeasurementRestoration(latest.producerRecordRef()).restoredRef().equals(observationRef))
					throw new IOException("Campaign has no matching measurement restoration retry");
				return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
					observationRef, Optional.empty());
			}
			if (current.scheduler().hasUnadoptedCalls())
				throw new IOException("Restoration requires settled prior calls");
			MeasurementPause pause = measurement.pause().orElseThrow();
			if (pause.origin() != MeasurementPause.Origin.ADMISSION)
				throw new IOException("Only an admission measurement pause can restore before a call");
			MeasurementChange change = readMeasurementChange(pause.changeRef());
			EvidenceRef baselineRef = measurement.baselineRootRef().orElseThrow();
			if (!change.baselineRef().equals(baselineRef) ||
				!change.epochRef().equals(measurement.epochRef()) ||
				!change.lineageId().equals(current.activeLineageId()))
				throw new IOException("Measurement pause differs from its active baseline");
			MeasurementObservation baseline = readMeasurementObservation(baselineRef);
			MeasurementObservation changed = readMeasurementObservation(change.changedRef());
			MeasurementConditions.Comparison comparison = baseline.conditions().compare(observed.conditions());
			requireRestoredConditions(change, changed, observed, comparison);
			MeasurementRestoration restoration = new MeasurementRestoration(
				MeasurementRestoration.SCHEMA_VERSION, current.campaignId(), current.activeLineageId(),
				measurement.epochRef(), pause.changeRef(), observationRef, comparison);
			EvidenceRef restorationRef = appendImmutableJsonDirect("measurement-restorations",
				JSON.writeValueAsBytes(restoration));
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, current.status(),
				current.activeLineageId(), current.currentCandidateId(),
				"measurement-restored-" + restorationRef.evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.MEASUREMENT_RESTORED,
				current.transitionCounter(), restorationRef, nextCounter, result);
			Campaign next = current.withMeasurementRestoration(receipt, restorationRef, observationRef);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
				observationRef, Optional.empty());
		});
	}

	/**
	 * Resolves a known admission pause by retaining a distinct epoch and first before-call observation.
	 *
	 * @param binding exact confirmed campaign
	 * @param logicalCallId pending call to admit under the new identity
	 * @param conditions complete current conditions for that call
	 * @return original accepted observation on an equal retry
	 * @throws IOException if the pause, call identity, or retained evidence conflicts
	 */
	MeasurementObservationDecision commitNewMeasurement(CampaignBinding binding, String logicalCallId,
		MeasurementConditions conditions) throws IOException
	{
		Objects.requireNonNull(logicalCallId, "logicalCallId");
		Objects.requireNonNull(conditions, "conditions");
		if (logicalCallId.isBlank())
			throw new IllegalArgumentException("logicalCallId must not be blank");
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			for (TransitionReceipt previous : current.transitionReceipts())
			{
				if (previous.kind() != TransitionKind.NEW_MEASUREMENT)
					continue;
				MeasurementObservation retained = readMeasurementObservation(previous.producerRecordRef());
				if (!retained.logicalCallId().equals(logicalCallId))
					continue;
				if (!retained.conditions().equals(conditions))
					throw new IOException("New measurement retry changes the retained conditions");
				return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
					previous.producerRecordRef(), Optional.empty());
			}
			MeasurementState measurement = current.measurementState();
			if (measurement == null || current.status() != CampaignStatus.DEVELOPING &&
				current.status() != CampaignStatus.FORMAL_TESTING || measurement.pause().isEmpty())
				throw new IOException("New measurement requires an active confirmed measurement pause");
			if (current.scheduler().hasUnadoptedCalls())
				throw new IOException("New measurement requires settled prior calls");
			MeasurementPause pause = measurement.pause().orElseThrow();
			if (pause.origin() != MeasurementPause.Origin.ADMISSION)
				throw new IOException("Only an admission measurement pause can start a new identity");
			MeasurementChange change = readMeasurementChange(pause.changeRef());
			if (!change.campaignId().equals(current.campaignId()) ||
				!change.lineageId().equals(current.activeLineageId()) ||
				!change.epochRef().equals(measurement.epochRef()) ||
				!measurement.baselineRootRef().equals(Optional.of(change.baselineRef())))
				throw new IOException("New measurement pause differs from its active baseline");
			MeasurementObservation changed = readMeasurementObservation(change.changedRef());
			requirePendingMeasurementCall(changed, logicalCallId);
			MeasurementEpoch previousEpoch = JSON.readValue(readImmutableJson(measurement.epochRef()),
				MeasurementEpoch.class);
			MeasurementEpoch epoch = new MeasurementEpoch(MeasurementEpoch.SCHEMA_VERSION,
				current.campaignId(), current.activeLineageId(), previousEpoch.contractRef(),
				previousEpoch.loadingEvidenceRef(), Optional.of(measurement.epochRef()),
				Optional.of(pause.changeRef()));
			EvidenceRef epochRef = appendImmutableJsonDirect("measurement-epochs",
				JSON.writeValueAsBytes(epoch));
			MeasurementObservation observed = MeasurementObservation.from(current.campaignId(),
				current.activeLineageId(), epochRef, logicalCallId,
				MeasurementObservation.Stage.BEFORE_CALL, conditions);
			EvidenceRef observationRef = appendImmutableJsonDirect("dynamic-baseline-roots",
				JSON.writeValueAsBytes(observed));
			long nextCounter = current.transitionCounter() + 1;
			TransitionResult result = new TransitionResult(nextCounter, current.status(),
				current.activeLineageId(), current.currentCandidateId(),
				"new-measurement-" + epochRef.evidenceId());
			TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.NEW_MEASUREMENT,
				current.transitionCounter(), observationRef, nextCounter, result);
			Campaign next = current.withNewMeasurement(receipt, epochRef, observationRef);
			writeCampaign(next);
			reconcileTransitionJournal(next);
			return new MeasurementObservationDecision(MeasurementObservationDecision.Status.ACCEPTED,
				observationRef, Optional.empty());
		});
	}

	/**
	 * Requires a pending before-call identity distinct from an adopted call that exposed a change.
	 *
	 * @param changed observation that caused the pause
	 * @param logicalCallId pending call under the resumed measurement
	 * @throws IOException if the call does not follow the change boundary
	 */
	private static void requirePendingMeasurementCall(MeasurementObservation changed, String logicalCallId)
		throws IOException
	{
		if (changed.stage() == MeasurementObservation.Stage.BEFORE_CALL &&
			!changed.logicalCallId().equals(logicalCallId) ||
			changed.stage() == MeasurementObservation.Stage.AFTER_CALL &&
				changed.logicalCallId().equals(logicalCallId))
			throw new IOException("New measurement identifies another pending logical call");
	}

	/**
	 * Commits a known difference from either boundary while the campaign state lock is held.
	 *
	 * @param binding exact campaign identity
	 * @param current locked active campaign
	 * @param baselineRef last accepted observation
	 * @param observationRef changed observation
	 * @param comparison complete known-change comparison
	 * @return the durable pause decision
	 * @throws IOException if change publication fails
	 */
	private MeasurementObservationDecision commitMeasurementChange(CampaignBinding binding, Campaign current,
		EvidenceRef baselineRef, EvidenceRef observationRef, MeasurementConditions.Comparison comparison)
		throws IOException
	{
		MeasurementState measurement = current.measurementState();
		MeasurementChange change = new MeasurementChange(MeasurementChange.SCHEMA_VERSION,
			current.campaignId(), current.activeLineageId(), measurement.epochRef(), baselineRef,
			observationRef, comparison);
		EvidenceRef changeRef = appendImmutableJsonDirect("measurement-changes",
			JSON.writeValueAsBytes(change));
		long nextCounter = current.transitionCounter() + 1;
		TransitionResult result = new TransitionResult(nextCounter, current.status(),
			current.activeLineageId(), current.currentCandidateId(),
			"measurement-" + changeRef.evidenceId());
		TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.MEASUREMENT_CHANGED,
			current.transitionCounter(), changeRef, nextCounter, result);
		Campaign next = current.withMeasurementChange(receipt, changeRef);
		writeCampaign(next);
		reconcileTransitionJournal(next);
		return new MeasurementObservationDecision(MeasurementObservationDecision.Status.PAUSED,
			observationRef, Optional.of(changeRef));
	}

	/**
	 * Reconstructs a canonical complete observation from its immutable reference.
	 *
	 * @param reference dynamic baseline observation
	 * @return validated observation
	 * @throws IOException if the record is absent, malformed, or noncanonical
	 */
	MeasurementObservation readMeasurementObservation(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("dynamic-baseline-roots"))
			throw new IOException("Measurement observation has the wrong collection");
		byte[] bytes = readImmutableJson(reference);
		try
		{
			MeasurementObservation observed = JSON.readValue(bytes, MeasurementObservation.class);
			if (observed == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(observed)))
				throw new IOException("Measurement observation is noncanonical or empty");
			return observed;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Measurement observation is malformed", exception);
		}
	}

	/**
	 * Verifies that the exact call owns the current accepted before-call observation.
	 *
	 * @param binding exact campaign
	 * @param campaign current locked campaign state
	 * @param logicalCallId call seeking admission
	 * @throws IOException if the indexed observation is absent, stale, or foreign
	 */
	void verifyBeforeCallObservation(CampaignBinding binding, Campaign campaign, String logicalCallId)
		throws IOException
	{
		MeasurementState measurement = campaign.measurementState();
		if (measurement == null || measurement.baselineRootRef().isEmpty() || measurement.pause().isPresent())
			throw new IOException("Confirmed call requires a current unpaused measurement observation");
		MeasurementEpoch epoch = JSON.readValue(readImmutableJson(measurement.epochRef()),
			MeasurementEpoch.class);
		LineageKey lineage = new LineageKey(campaign.activeLineageId(), epoch.contractRef().evidenceId());
		String key = MeasurementObservation.callIndexKey(measurement.epochRef(), logicalCallId);
		EvidenceRef indexed = readCreateOnceIndex("measurement-before-call-index", key, lineage).evidence();
		if (!indexed.equals(measurement.baselineRootRef().orElseThrow()))
			throw new IOException("Before-call observation is not the current measurement baseline");
		MeasurementObservation observed = readMeasurementObservation(indexed);
		if (!observed.campaignId().equals(binding.campaignId()) ||
			!observed.lineageId().equals(campaign.activeLineageId()) ||
			!observed.epochRef().equals(measurement.epochRef()) ||
			!observed.logicalCallId().equals(logicalCallId) ||
			observed.stage() != MeasurementObservation.Stage.BEFORE_CALL)
			throw new IOException("Before-call observation identifies another call or epoch");
	}

	/**
	 * Selects the current indexed before-call evidence for pre-start publication.
	 *
	 * @param binding exact campaign
	 * @param logicalCallId call seeking publication
	 * @return the confirmed observation, or empty for a prospective inspection
	 * @throws IOException if a confirmed call lacks current observation authority
	 */
	Optional<EvidenceRef> currentBeforeCallObservationRef(CampaignBinding binding, String logicalCallId)
		throws IOException
	{
		return withLock(() ->
		{
			Campaign campaign = verifyBinding(binding);
			if (campaign.measurementState() == null)
				return Optional.empty();
			verifyBeforeCallObservation(binding, campaign, logicalCallId);
			return campaign.measurementState().baselineRootRef();
		});
	}

	/**
	 * Checks an adopted stop and any in-flight slot before changing the campaign phase.
	 *
	 * @param campaign locked active predecessor
	 * @param binding exact campaign identity
	 * @param producer canonical interruption producer
	 * @throws IOException if the stop or native cancellation authority is missing
	 */
	private void verifyInterruptionStop(Campaign campaign, CampaignBinding binding,
		InterruptionRecord producer) throws IOException
	{
		StopRequest stop = verifyRetainedInterruptionStop(campaign, binding, producer);
		if (stop.status() != StopRequest.Status.PENDING ||
			producer.adoptingFence() != campaign.scheduler().fence())
			throw new IOException("Interruption requires an adopted pending stop on this owner fence");
		if (producer.logicalCallId().isEmpty())
		{
			if (campaign.scheduler().slots().stream().anyMatch(slot ->
				slot.fence() == producer.adoptingFence()))
				throw new IOException("No-call interruption conflicts with an admitted call");
			return;
		}
		String callId = producer.logicalCallId().orElseThrow();
		CallSlot slot = campaign.scheduler().slots().stream().filter(candidate ->
			candidate.logicalCallId().equals(callId)).findFirst().orElseThrow(() ->
			new IOException("Interruption call slot is missing"));
		verifySlotClaim(binding, campaign, slot);
		if (slot.fence() > producer.adoptingFence() ||
			(slot.state() == CallSlot.State.STARTING || slot.state() == CallSlot.State.ACTIVE) &&
				!slot.cancellationRequested())
			throw new IOException("Interruption call has no retained cancellation request");
	}

	/**
	 * Rehashes the original stop on both first commit and equal retry.
	 *
	 * @param campaign current campaign state
	 * @param binding exact campaign identity
	 * @param producer canonical interruption producer
	 * @return retained stop with its original adopting fence
	 * @throws IOException if the stop was removed, duplicated, or changed
	 */
	private StopRequest verifyRetainedInterruptionStop(Campaign campaign, CampaignBinding binding,
		InterruptionRecord producer) throws IOException
	{
		EvidenceRef stopRef = producer.stopRef().orElseThrow();
		StopRequest stop = null;
		for (StopRequest candidate : campaign.scheduler().stops())
			if (candidate.stopRef().equals(stopRef))
			{
				if (stop != null)
					throw new IOException("Interruption stop is duplicated");
				stop = candidate;
			}
		if (stop == null || !stop.adoptingFence().equals(Optional.of(producer.adoptingFence())))
			throw new IOException("Interruption requires its original adopted stop");
		CampaignStopInbox.verifyStopEvidence(this, binding, stop);
		return stop;
	}

	/**
	 * Commits one scheduler-only state update under the campaign state lock.
	 *
	 * @param binding exact campaign binding
	 * @param mutation scheduler mutation
	 * @return committed scheduler state
	 * @throws IOException if the binding, mutation, or commit fails
	 */
	SchedulerState updateScheduler(CampaignBinding binding, SchedulerMutation mutation) throws IOException
	{
		return updateSchedulerWithEvidence(binding, (campaign, _) -> mutation.apply(campaign));
	}

	/**
	 * Commits one stop acknowledgment and its active pointer in the same campaign write.
	 *
	 * @param binding exact campaign binding
	 * @param stopRef stop to acknowledge
	 * @param mutation ACK-only scheduler mutation
	 * @return committed scheduler state
	 * @throws IOException if the mutation changes another scheduler field or committed evidence is invalid
	 */
	SchedulerState updateAcknowledgedInterruptionStop(CampaignBinding binding, EvidenceRef stopRef,
		SchedulerMutation mutation) throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			SchedulerState previous = current.scheduler();
			SchedulerState next = mutation.apply(current);
			if (previous.fence() != next.fence() || !previous.invocations().equals(next.invocations()) ||
				!previous.slots().equals(next.slots()) || !previous.barrier().equals(next.barrier()) ||
				previous.stops().size() != next.stops().size())
			{
				throw new IOException("Stop acknowledgment changed unrelated scheduler state");
			}
			StopRequest oldStop = null;
			StopRequest acknowledged = null;
			for (int index = 0; index < previous.stops().size(); ++index)
			{
				StopRequest before = previous.stops().get(index);
				StopRequest after = next.stops().get(index);
				if (!before.stopRef().equals(stopRef))
				{
					if (!before.equals(after))
						throw new IOException("Stop acknowledgment changed another stop");
					continue;
				}
				if (oldStop != null)
					throw new IOException("Stop acknowledgment reference is duplicated");
				oldStop = before;
				acknowledged = after;
			}
			if (oldStop == null || acknowledged.status() != StopRequest.Status.ACKNOWLEDGED ||
				!acknowledged.stopRef().equals(stopRef) ||
				!oldStop.sourceInvocationId().equals(acknowledged.sourceInvocationId()) ||
				!oldStop.sourceFence().equals(acknowledged.sourceFence()) ||
				!oldStop.reason().equals(acknowledged.reason()) ||
				!oldStop.adoptingFence().equals(acknowledged.adoptingFence()))
			{
				throw new IOException("Stop acknowledgment changed the original stop identity");
			}
			CampaignStopInbox.verifyCommittedInterruption(this, binding, current, acknowledged,
				acknowledged.interruptionReceiptId().orElseThrow(), acknowledged.logicalCallId());
			if (oldStop.status() == StopRequest.Status.ACKNOWLEDGED && oldStop.equals(acknowledged) &&
				stopRef.equals(current.acknowledgedInterruptionStopRef()))
				return next;
			if (oldStop.status() != StopRequest.Status.PENDING || current.acknowledgedInterruptionStopRef() != null)
				throw new IOException("Stop acknowledgment is not the first ACK or an equal replay");
			writeCampaign(current.withAcknowledgedInterruptionStop(next, stopRef));
			return next;
		});
	}

	/**
	 * Commits scheduler state with an immutable append under the same state lock.
	 *
	 * @param binding exact campaign binding
	 * @param mutation locked scheduler mutation
	 * @return committed scheduler state
	 * @throws IOException if evidence or state persistence fails
	 */
	SchedulerState updateSchedulerWithEvidence(CampaignBinding binding, SchedulerEvidenceMutation mutation)
		throws IOException
	{
		return withLock(() ->
		{
			Campaign current = verifyBinding(binding);
			SchedulerState next = mutation.apply(current, this::appendImmutableJsonDirect);
			if (!next.equals(current.scheduler()))
				writeCampaign(current.withScheduler(next));
			return next;
		});
	}

	/** Appends immutable JSON only while the caller already holds the campaign state lock. */
	@FunctionalInterface
	interface LockedEvidenceAppender
	{
		/**
		 * Appends canonical immutable JSON before the campaign state commit.
		 *
		 * @param collection evidence collection
		 * @param content canonical JSON bytes
		 * @return content-addressed evidence reference
		 * @throws IOException if append fails
		 */
		EvidenceRef appendImmutableJson(String collection, byte[] content) throws IOException;
	}

	/** Computes a scheduler mutation that can append immutable evidence under its state lock. */
	@FunctionalInterface
	interface SchedulerEvidenceMutation
	{
		/**
		 * Computes one state change, optionally appending immutable evidence first.
		 *
		 * @param campaign authoritative campaign snapshot
		 * @param evidence append capability valid only during this call
		 * @return replacement scheduler state
		 * @throws IOException if validation or append fails
		 */
		SchedulerState apply(Campaign campaign, LockedEvidenceAppender evidence) throws IOException;
	}

	/**
	 * Computes one validated scheduler-only state change.
	 */
	@FunctionalInterface
	interface SchedulerMutation
	{
		/**
		 * Computes the next scheduler state from one locked campaign snapshot.
		 *
		 * @param campaign current campaign
		 * @return next scheduler state
		 * @throws IOException if the mutation is invalid
		 */
		SchedulerState apply(Campaign campaign) throws IOException;
	}

	/**
	 * Repairs a missing journal suffix from embedded commit receipts and rejects conflicting content.
	 *
	 * @param campaign authoritative campaign record
	 * @throws IOException if the mirror is malformed or conflicts with embedded receipts
	 */
	private void reconcileTransitionJournal(Campaign campaign) throws IOException
	{
		Path file = directory.resolve("transition-journal.json");
		List<TransitionReceipt> authoritative = campaign.transitionReceipts();
		if (Files.notExists(file))
		{
			if (!authoritative.isEmpty())
				writeAtomically(file, JSON.writeValueAsBytes(new TransitionJournal(1, authoritative)));
			return;
		}
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Transition journal is redirected: " + file);
		TransitionJournal journal;
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			journal = JSON.readValue(bytes, TransitionJournal.class);
			if (journal == null || journal.schemaVersion() != 1 || journal.receipts() == null ||
				!Arrays.equals(bytes, JSON.writeValueAsBytes(journal)))
				throw new IOException("Transition journal is malformed: " + file);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Transition journal is malformed: " + file, e);
		}
		if (journal.receipts().size() > authoritative.size() ||
			!authoritative.subList(0, journal.receipts().size()).equals(journal.receipts()))
			throw new IOException("Transition journal conflicts with embedded campaign receipts: " + file);
		if (journal.receipts().size() < authoritative.size())
			writeAtomically(file, JSON.writeValueAsBytes(new TransitionJournal(1, authoritative)));
	}

	private record TransitionJournal(int schemaVersion, List<TransitionReceipt> receipts)
	{
	}

	/**
	 * Strictly reads one canonical preconfirmation entry.
	 *
	 * @param file index pathname
	 * @return validated entry
	 * @throws IOException if bytes are malformed or noncanonical
	 */
	private PreconfirmationEntry readPreconfirmationEntry(Path file) throws IOException
	{
		try
		{
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Preconfirmation index is missing or redirected: " + file);
			byte[] bytes = Files.readAllBytes(file);
			PreconfirmationEntry entry = JSON.readValue(bytes, PreconfirmationEntry.class);
			if (entry == null || entry.schemaVersion() != 1 || !Arrays.equals(bytes, JSON.writeValueAsBytes(entry)))
				throw new IOException("Preconfirmation index is malformed: " + file);
			return entry;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Preconfirmation index is malformed: " + file, e);
		}
	}

	private record PreconfirmationKey(String campaignId, PreconfirmationIndexKind indexKind, String logicalKey)
	{
	}

	private record PreconfirmationEntry(int schemaVersion, String campaignId, PreconfirmationIndexKind indexKind,
		String logicalKey, EvidenceRef evidenceRef, String contentSha256)
	{
	}

	/**
	 * Appends immutable content with one evidence-file suffix.
	 *
	 * @param collection the evidence collection name
	 * @param content the immutable evidence bytes
	 * @param suffix the evidence-file suffix
	 * @return evidence file addressed by content hash
	 * @throws IOException if persistence fails or existing bytes differ
	 */
	private EvidenceRef appendImmutableRef(String collection, byte[] content, String suffix) throws IOException
	{
		if (!validCollection(collection) || content == null)
			throw new IllegalArgumentException("Evidence collection or content is invalid");
		return withLock(() -> appendImmutableRefDirect(collection, content, suffix));
	}

	/**
	 * Appends canonical JSON when a locked scheduler mutation already owns the state lock.
	 *
	 * @param collection evidence collection
	 * @param content canonical JSON bytes
	 * @return content-addressed evidence reference
	 * @throws IOException if append fails
	 */
	private EvidenceRef appendImmutableJsonDirect(String collection, byte[] content) throws IOException
	{
		return appendImmutableRefDirect(collection, content, ".json");
	}

	/**
	 * Appends content without reacquiring the campaign state lock.
	 *
	 * @param collection evidence collection
	 * @param content immutable content
	 * @param suffix evidence-file suffix
	 * @return content-addressed evidence reference
	 * @throws IOException if append fails
	 */
	private EvidenceRef appendImmutableRefDirect(String collection, byte[] content, String suffix)
		throws IOException
	{
		if (!validCollection(collection) || content == null)
			throw new IllegalArgumentException("Evidence collection or content is invalid");
		EvidenceRef reference = new EvidenceRef(collection, sha256(content));
		Path evidence = directory.resolve(collection).resolve(reference.evidenceId() + suffix);
		verifyCollectionDirectory(collection, true);
		if (Files.exists(evidence, LinkOption.NOFOLLOW_LINKS))
		{
			if (!Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Immutable evidence is redirected: " + evidence);
			if (!Arrays.equals(content, Files.readAllBytes(evidence)))
				throw new IOException("Immutable evidence collision at " + evidence);
			return reference;
		}
		writeAtomically(evidence, content);
		return reference;
	}

	/**
	 * Reads immutable content with one evidence-file suffix.
	 *
	 * @param collection the evidence collection name
	 * @param evidenceId the content hash
	 * @param suffix the evidence-file suffix
	 * @return verified evidence bytes
	 * @throws IOException if evidence is missing or does not match its content address
	 */
	private byte[] readImmutable(String collection, String evidenceId, String suffix) throws IOException
	{
		if (!validCollection(collection) || !validEvidenceId(evidenceId))
			throw new IllegalArgumentException("Evidence collection or id is invalid");
		verifyCollectionDirectory(collection, false);
		Path evidence = directory.resolve(collection).resolve(evidenceId + suffix);
		if (!Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Immutable evidence is missing: " + evidence);
		byte[] content = Files.readAllBytes(evidence);
		if (!sha256(content).equals(evidenceId))
			throw new IOException("Immutable evidence does not match its content address: " + evidence);
		return content;
	}

	/**
	 * Writes one campaign summary.
	 *
	 * @param campaign campaign
	 * @throws IOException if persistence fails
	 */
	private void writeCampaign(Campaign campaign) throws IOException
	{
		writeAtomically(campaignFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(campaign));
	}

	/**
	 * Runs an operation holding the campaign lock.
	 *
	 * @param <T> result type
	 * @param operation operation
	 * @return operation result
	 * @throws IOException if locking fails
	 */
	private <T> T withLock(IoOperation<T> operation) throws IOException
	{
		synchronized (CampaignFileLocks.class)
		{
			try
			{
				Files.createDirectory(directory);
			}
			catch (FileAlreadyExistsException _)
			{
				// The directory may already exist; the no-follow identity check below decides whether it is safe.
			}
			verifyPinnedDirectory();
			return CampaignFileLocks.withLock(directory.resolve("campaign.lock"), () ->
			{
				verifyPinnedDirectory();
				return operation.run();
			});
		}
	}

	/**
	 * Rejects a campaign path that no longer names the pinned real directory.
	 *
	 * @throws IOException if the directory is missing, replaced, or not a directory
	 */
	private void verifyPinnedDirectory() throws IOException
	{
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
			throw new IOException("Pinned campaign directory was replaced: " + directory);
		Object currentKey = Files.readAttributes(directory, BasicFileAttributes.class,
			LinkOption.NOFOLLOW_LINKS).fileKey();
		if (currentKey == null)
			throw new IOException("Cannot establish pinned campaign directory identity: " + directory);
		Object originalKey = pinnedDirectoryKey;
		if (originalKey == null)
			pinnedDirectoryKey = currentKey;
		else if (!originalKey.equals(currentKey))
			throw new IOException("Pinned campaign directory was replaced: " + directory);
	}

	/**
	 * Pins an evidence or index collection below the campaign and rejects redirected directories.
	 *
	 * @param collection collection name
	 * @param create whether a missing collection should be created
	 * @throws IOException if the collection is missing, redirected, or not a real directory
	 */
	private void verifyCollectionDirectory(String collection, boolean create) throws IOException
	{
		verifyPinnedDirectory();
		Path path = directory.resolve(collection);
		if (create)
		{
			try
			{
				Files.createDirectory(path);
			}
			catch (FileAlreadyExistsException _)
			{
				// The identity check below rejects a preexisting redirect.
			}
		}
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
			throw new IOException("Campaign collection directory is missing or redirected: " + path);
	}

	/**
	 * Returns whether an evidence collection name is safe below the campaign directory.
	 *
	 * @param collection evidence collection name
	 * @return whether the collection name is valid
	 */
	private static boolean validCollection(String collection)
	{
		return collection != null && COLLECTION_NAME.matcher(collection).matches();
	}

	/**
	 * Returns whether an evidence id is a lowercase SHA-256 hex address.
	 *
	 * @param evidenceId evidence id
	 * @return whether the evidence id is valid
	 */
	private static boolean validEvidenceId(String evidenceId)
	{
		return evidenceId != null && EVIDENCE_ID.matcher(evidenceId).matches();
	}

	/**
	 * Computes a SHA-256 content address.
	 *
	 * @param content content
	 * @return address
	 */
	private static String sha256(byte[] content)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Flushes a sibling temporary file and atomically replaces its target.
	 *
	 * @param target target
	 * @param content content
	 * @throws IOException if replacement fails
	 */
	private static void writeAtomically(Path target, byte[] content) throws IOException
	{
		Path parent = target.getParent();
		assert parent != null;
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			try
			{
				Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				throw new IOException("Atomic campaign replacement is not supported for " + target, e);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Publishes immutable bytes without replacing an existing target.
	 *
	 * @param target target pathname
	 * @param content complete immutable bytes
	 * @throws IOException if publication fails
	 */
	private static void writeCreateOnce(Path target, byte[] content) throws IOException
	{
		Path parent = target.getParent();
		assert parent != null;
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			Files.move(temporary, target, ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	@FunctionalInterface
	private interface IoOperation<T>
	{
		/**
		 * Runs the operation.
		 *
		 * @return operation result
		 * @throws IOException if it fails
		 */
		T run() throws IOException;
	}
}
