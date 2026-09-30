package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Locked durable index that discovers campaigns from a project-root and target-path identity.
 */
public final class CampaignLocator
{
	private final Path workflowRoot;
	private final Path indexFile;
	private final Path creationIntentFile;
	private final Path forkIntentFile;
	private final Path confirmationIntentFile;
	private final Path keyTransitionIntentDirectory;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates a locator whose index is owned below one durable workflow root.
	 *
	 * @param workflowRoot durable optimizer workflow root
	 */
	public CampaignLocator(Path workflowRoot)
	{
		if (workflowRoot == null)
			throw new NullPointerException("workflowRoot");
		this.workflowRoot = workflowRoot.toAbsolutePath().normalize();
		indexFile = this.workflowRoot.resolve("campaign-index.json");
		creationIntentFile = this.workflowRoot.resolve("campaign-create-intent.json");
		forkIntentFile = this.workflowRoot.resolve("campaign-fork-intent.json");
		confirmationIntentFile = this.workflowRoot.resolve("campaign-confirm-intent.json");
		keyTransitionIntentDirectory = this.workflowRoot.resolve("campaign-key-transition-intents");
	}

	/**
	 * Opens stop handling only for an exact campaign registered under this workflow root.
	 *
	 * @param binding pinned campaign binding
	 * @return locator-owned stop inbox
	 * @throws IOException if the campaign is foreign, unregistered, or redirected
	 */
	public CampaignStopInbox openStopInbox(CampaignBinding binding) throws IOException
	{
		return new CampaignStopInbox(this, requireRegisteredBinding(binding));
	}

	/**
	 * Finds the open invocation registered for one observed host turn.
	 *
	 * @param hostSessionId non-blank host session identity
	 * @param hostTurnId non-blank host turn identity
	 * @return exact open invocation, or empty when this turn has no open invocation
	 * @throws IOException if retained host or campaign evidence is inconsistent
	 */
	public Optional<InvocationStopRegistration> findOpenInvocationByHostTurn(String hostSessionId,
		String hostTurnId) throws IOException
	{
		Optional<InvocationStopRegistration> found = new HostObservationRegistry(workflowRoot).
			findOpenByHostTurn(hostSessionId, hostTurnId);
		if (found.isPresent())
			requireRegisteredBinding(found.orElseThrow().binding());
		return found;
	}

	/**
	 * Opens lease acquisition only for an exact campaign registered under this workflow root.
	 *
	 * @param binding pinned campaign binding
	 * @return locator-owned lease acquisition service
	 * @throws IOException if the campaign is foreign, unregistered, or redirected
	 */
	public CampaignSchedulerLease openSchedulerLease(CampaignBinding binding) throws IOException
	{
		return new CampaignSchedulerLease(this, requireRegisteredBinding(binding));
	}

	/**
	 * Opens the before-call condition observer for one registered campaign.
	 *
	 * @param binding exact campaign binding
	 * @return locator-bound measurement observer
	 * @throws IOException if the campaign is foreign or unregistered
	 */
	public MeasurementObserver openMeasurementObserver(CampaignBinding binding) throws IOException
	{
		return new MeasurementObserver(this, requireRegisteredBinding(binding));
	}

	/**
	 * Cancels a confirmed campaign paused by a known measurement change.
	 *
	 * @param binding registered campaign whose pause the caller chose to cancel
	 * @param expectedCounter campaign revision shown when the caller chose cancellation
	 * @return retained cancellation result, including on an equal retry
	 * @throws IOException if the pause, evidence, or key ownership conflicts
	 */
	public TransitionResult cancelPaused(CampaignBinding binding, long expectedCounter) throws IOException
	{
		if (expectedCounter < 0)
			throw new IllegalArgumentException("Expected cancellation counter must be nonnegative");
		CampaignStore store = requireRegisteredBinding(binding);
		Campaign current = store.lockedSnapshot(binding);
		if (current.transitionCounter() > expectedCounter)
		{
			for (TransitionReceipt receipt : current.transitionReceipts())
				if (receipt.expectedCounter() == expectedCounter)
				{
					if (receipt.kind() != TransitionKind.CANCELLATION ||
						!receipt.producerRecordRef().collection().equals("measurement-pause-cancellations"))
						throw new IOException("Cancellation conflicts with the committed campaign revision");
					return cancel(new TransitionRequest(binding, expectedCounter,
						TransitionKind.CANCELLATION, receipt.producerRecordRef()));
				}
		}
		if (current.transitionCounter() != expectedCounter)
			throw new IOException("Cancellation has a stale campaign revision");
		MeasurementState measurement = current.measurementState();
		if (current.status() != CampaignStatus.DEVELOPING &&
			current.status() != CampaignStatus.FORMAL_TESTING || measurement == null ||
			measurement.pause().isEmpty() ||
			measurement.pause().orElseThrow().origin() != MeasurementPause.Origin.ADMISSION)
			throw new IOException("Cancellation requires a confirmed measurement-change pause");
		MeasurementPauseCancellation producer = new MeasurementPauseCancellation(
			MeasurementPauseCancellation.SCHEMA_VERSION, binding, expectedCounter, current.status(),
			current.activeLineageId(), measurement.epochRef(),
			measurement.pause().orElseThrow().changeRef());
		EvidenceRef producerRef = store.appendImmutableJson("measurement-pause-cancellations",
			JSON.writeValueAsBytes(producer));
		return cancel(new TransitionRequest(binding, expectedCounter, TransitionKind.CANCELLATION,
			producerRef));
	}

	/**
	 * Refuses cancellation before the C7 producer and recoverable key transaction integrate.
	 *
	 * @param request proposed cancellation transition
	 * @return committed cancellation once its producer integrates
	 * @throws IOException while cancellation remains unavailable
	 */
	public TransitionResult cancel(TransitionRequest request) throws IOException
	{
		return withLock(() ->
		{
			CampaignTransitions.requireIntegratedProducer(TransitionKind.CANCELLATION);
			if (request == null || request.kind() != TransitionKind.CANCELLATION)
				throw new IllegalArgumentException("Cancellation requires a CANCELLATION request");
			recoverCreationIntent();
			recoverConfirmationIntent();
			CampaignLookupKey key = keyForBinding(request.binding());
			recoverKeyTransitionIntent(key);
			CampaignStore store = new CampaignStore(request.binding().realDirectory());
			Campaign campaign = store.load();
			CampaignTransitions transitions = new CampaignTransitions(store);
			if (campaign.transitionCounter() > request.expectedCounter())
				return transitions.apply(request);
			if (campaign.transitionCounter() != request.expectedCounter() ||
				!ownsActiveKey(campaign.status()))
				throw new IOException("Cancellation has a stale or non-resumable predecessor: " + key);
			IndexDocument index = readIndex();
			int activeOwners = 0;
			boolean requestOwnsKey = false;
			for (IndexedCampaign entry : index.campaigns())
			{
				if (matchesKey(entry, key) && ownsActiveKey(loadCurrent(entry).status()))
				{
					++activeOwners;
					requestOwnsKey = entry.campaignId().equals(request.binding().campaignId()) &&
						entry.campaignDirectory().equals(request.binding().realDirectory().toString());
				}
			}
			if (activeOwners != 1 || !requestOwnsKey)
				throw new IOException("Cancellation requires the unique active key owner: " + key);
			CampaignStatus savedPhase = campaign.closedPhase();
			if (savedPhase == null)
				savedPhase = campaign.status();
			KeyTransitionIntent intent = new KeyTransitionIntent(KeyTransitionIntent.Operation.CANCEL, key,
				request.binding(), request.expectedCounter(), request.producerRecordRef(),
				KeyTransitionIntent.requestDigest(request), Optional.of(campaign.campaignId()),
				campaign.status(), savedPhase, Optional.ofNullable(readClaim(key)));
			writeKeyTransitionIntent(intent);
			TransitionResult result = transitions.apply(request);
			recoverKeyTransitionIntent(key);
			return result;
		});
	}

	/**
	 * Refuses resumption before the C7 producer and recoverable key transaction integrate.
	 *
	 * @param request proposed resumption transition
	 * @return committed resumption or typed occupied-key result once integrated
	 * @throws IOException while resumption remains unavailable
	 */
	public CampaignKeyTransitionResult resumeCancelled(TransitionRequest request) throws IOException
	{
		return withLock(() ->
		{
			CampaignTransitions.requireIntegratedProducer(TransitionKind.RESUMPTION);
			if (request == null || request.kind() != TransitionKind.RESUMPTION)
				throw new IllegalArgumentException("Resumption requires a RESUMPTION request");
			recoverCreationIntent();
			CampaignLookupKey key = keyForBinding(request.binding());
			recoverKeyTransitionIntent(key);
			CampaignStore store = new CampaignStore(request.binding().realDirectory());
			Campaign campaign = store.load();
			CampaignTransitions transitions = new CampaignTransitions(store);
			if (campaign.transitionCounter() > request.expectedCounter())
				return new CampaignKeyTransitionResult.Committed(transitions.apply(request));
			if (campaign.transitionCounter() != request.expectedCounter() ||
				campaign.status() != CampaignStatus.CANCELLED)
				throw new IOException("Resumption requires the matching cancelled predecessor: " + key);
			for (IndexedCampaign entry : readIndex().campaigns())
				if (matchesKey(entry, key) && ownsActiveKey(loadCurrent(entry).status()))
					return new CampaignKeyTransitionResult.KeyOccupied(key, entry.campaignId());
			recoverConfirmationIntent();
			LatestConfirmedClaim claim = readClaim(key);
			if (campaign.activeLineageId() == null && claim != null &&
				claim.campaignId().equals(campaign.campaignId()) ||
				campaign.activeLineageId() != null && (claim == null ||
					!claim.campaignId().equals(campaign.campaignId()) ||
					!claim.lineageId().equals(campaign.activeLineageId())))
				throw new IOException("Cancelled campaign no longer has its exact confirmed claim: " + key);
			KeyTransitionIntent intent = new KeyTransitionIntent(KeyTransitionIntent.Operation.RESUME, key,
				request.binding(), request.expectedCounter(), request.producerRecordRef(),
				KeyTransitionIntent.requestDigest(request), Optional.empty(), campaign.status(),
				campaign.closedPhase(), Optional.ofNullable(claim));
			writeKeyTransitionIntent(intent);
			TransitionResult result = transitions.apply(request);
			recoverKeyTransitionIntent(key);
			return new CampaignKeyTransitionResult.Committed(result);
		});
	}

	/**
	 * Publishes one validated key-transition predecessor before its campaign transition.
	 *
	 * @param intent request-bound key transition predecessor
	 * @throws IOException if an existing intent conflicts or atomic publication fails
	 */
	private void writeKeyTransitionIntent(KeyTransitionIntent intent) throws IOException
	{
		Path file = keyTransitionIntentDirectory.resolve(keyDigest(intent.key()) + ".json");
		Files.createDirectories(keyTransitionIntentDirectory);
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Campaign key transition intent already exists: " + file);
		writeAtomically(file, intent.encode());
	}

	/**
	 * Revalidates the exact registered store under the workflow index lock.
	 *
	 * @param binding pinned campaign binding
	 * @return the verified pinned store
	 * @throws IOException if registration or campaign identity is absent or foreign
	 */
	CampaignStore requireRegisteredBinding(CampaignBinding binding) throws IOException
	{
		if (binding == null)
			throw new IOException("Campaign binding is missing");
		return withLock(() ->
		{
			recoverCreationIntent();
			verifyCampaignDirectory(binding.realDirectory(), binding.campaignId());
			CampaignStore store = new CampaignStore(binding.realDirectory());
			store.verifyBinding(binding);
			if (!store.directory().equals(binding.realDirectory()))
				throw new IOException("Campaign binding does not identify the pinned store");
			int matches = 0;
			CampaignLookupKey key = null;
			for (IndexedCampaign entry : readIndex().campaigns())
				if (entry.campaignId().equals(binding.campaignId()) &&
					entry.campaignDirectory().equals(binding.realDirectory().toString()))
				{
					++matches;
					key = new CampaignLookupKey(Path.of(entry.projectRoot()), Path.of(entry.targetPath()));
				}
			if (matches != 1)
				throw new IOException("Campaign binding requires one exact registration: " + binding.campaignId());
			recoverKeyTransitionIntent(key);
			return store;
		});
	}

	/**
	 * Returns this locator's trusted canonical workflow root to its owned services.
	 *
	 * @return workflow root
	 */
	Path workflowRoot()
	{
		return workflowRoot;
	}

	/**
	 * Commits one validated initial confirmation with a recoverable claim and campaign index update.
	 *
	 * @param request exact initial confirmation transition
	 * @return committed result, including on equal retry
	 * @throws IOException if registration, evidence, predecessor, or index publication fails
	 */
	public TransitionResult confirm(TransitionRequest request) throws IOException
	{
		if (request == null)
			throw new NullPointerException("request");
		if (request.kind() != TransitionKind.CONFIRMED_CONTRACT)
			throw new IllegalArgumentException("Initial confirmation requires CONFIRMED_CONTRACT");
		return withLock(() ->
		{
			recoverCreationIntent();
			recoverConfirmationIntent();
			CampaignLookupKey key = keyForBinding(request.binding());
			CampaignStore store = new CampaignStore(request.binding().realDirectory());
			Campaign current = store.load();
			if (!request.producerRecordRef().collection().equals("contracts"))
				throw new IOException("Confirmation requires a contracts producer record");
			if (!new ContractService(store).loadConfirmedContract(request.producerRecordRef().evidenceId()).
				promptSystem().targetPath().equals(key.normalizedTargetRelativePath()))
				throw new IOException("Confirmed contract target differs from the registered campaign target");
			if (current.transitionCounter() > request.expectedCounter())
				return store.commitConfirmation(request);
			if (current.transitionCounter() != request.expectedCounter() ||
				current.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION)
				throw new IOException("Confirmation requires the current unconfirmed campaign: " + key);
			ConfirmationIntent intent = new ConfirmationIntent(1, key.canonicalProjectRoot().toString(),
				key.normalizedTargetRelativePath().toString(), request.binding().campaignId(),
				request.binding().realDirectory().toString(), request.expectedCounter(), request.kind(),
				request.producerRecordRef());
			writeAtomically(confirmationIntentFile, JSON.writeValueAsBytes(intent));
			TransitionResult result = store.commitConfirmation(request);
			recoverConfirmationIntent();
			return result;
		});
	}

	/**
	 * Reads the current per-key confirmed claim after rolling forward a committed confirmation.
	 *
	 * @param binding campaign used only to derive the canonical key
	 * @return latest confirmed claim and its sequence version
	 * @throws IOException if the binding, intent, or claim is absent, foreign, or corrupt
	 */
	public LatestConfirmedClaim readLatestConfirmedClaim(CampaignBinding binding) throws IOException
	{
		return withLock(() ->
		{
			recoverConfirmationIntent();
			CampaignLookupKey key = keyForBinding(binding);
			LatestConfirmedClaim claim = readClaim(key);
			if (claim == null)
				throw new IOException("Latest confirmed claim is missing for " + key);
			return claim;
		});
	}

	/**
	 * Reads the canonical key of one exact registered campaign.
	 *
	 * @param binding campaign binding
	 * @return pinned project and target key
	 * @throws IOException if the campaign is unregistered or inconsistent
	 */
	public CampaignLookupKey readRegisteredKey(CampaignBinding binding) throws IOException
	{
		return withLock(() -> keyForBinding(binding));
	}

	/**
	 * Publishes an assessment only while the exact expected claim is latest for this key.
	 *
	 * @param binding campaign from which to derive the canonical key
	 * @param expectedClaimVersion expected per-key claim sequence
	 * @param expectedCampaignId expected latest campaign identity
	 * @param expectedLineageId expected latest lineage identity
	 * @param expectedContractId expected latest contract identity
	 * @param writeAssessment create-once assessment writer invoked under the key lock
	 * @return publication receipt or a changed-claim result
	 * @throws IOException if claim state, assessment evidence, or publication fails
	 */
	public ClaimPublication publishIfLatestClaim(CampaignBinding binding, long expectedClaimVersion,
		String expectedCampaignId, String expectedLineageId, String expectedContractId,
		CheckedSupplier<EvidenceRef> writeAssessment) throws IOException
	{
		if (expectedClaimVersion < 1 || expectedCampaignId == null || expectedLineageId == null ||
			expectedContractId == null || writeAssessment == null)
			throw new IllegalArgumentException("Expected claim or assessment writer is invalid");
		return withLock(() ->
		{
			recoverConfirmationIntent();
			CampaignLookupKey key = keyForBinding(binding);
			LatestConfirmedClaim claim = readClaim(key);
			if (claim == null)
				throw new IOException("Latest confirmed claim is missing for " + key);
			if (claim.confirmationSequence() != expectedClaimVersion ||
				!claim.campaignId().equals(expectedCampaignId) ||
				!claim.lineageId().equals(expectedLineageId) ||
				!claim.contractId().equals(expectedContractId))
				return new ClaimPublication(ClaimPublication.Status.CLAIM_CHANGED, key,
					claim.confirmationSequence(), null, null);
			EvidenceRef assessment = writeAssessment.get();
			if (assessment == null)
				throw new IOException("Assessment writer returned no evidence reference");
			CampaignStore.open(binding.realDirectory()).readImmutableJson(assessment);
			byte[] identity = JSON.writeValueAsBytes(new PublicationKey(key.canonicalProjectRoot().toString(),
				key.normalizedTargetRelativePath().toString(), expectedClaimVersion, assessment));
			String publicationId = sha256(identity);
			ClaimPublication publication = new ClaimPublication(ClaimPublication.Status.PUBLISHED, key,
				expectedClaimVersion, assessment, publicationId);
			PublicationDocument document = PublicationDocument.from(publication);
			Path file = workflowRoot.resolve("claim-publications").resolve(publicationId + ".json");
			Files.createDirectories(file.getParent());
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			{
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
					!Arrays.equals(Files.readAllBytes(file), JSON.writeValueAsBytes(document)))
					throw new IOException("Claim publication conflicts with existing receipt: " + file);
				return publication;
			}
			writeAtomically(file, JSON.writeValueAsBytes(document));
			return publication;
		});
	}

	private record PublicationKey(String projectRoot, String targetPath, long claimVersion,
		EvidenceRef assessmentRef)
	{
	}

	private record PublicationDocument(int schemaVersion, String projectRoot, String targetPath,
		long claimVersion, EvidenceRef assessmentRef, String publicationId)
	{
		/**
		 * Converts an accepted publication to its flat durable receipt.
		 *
		 * @param publication accepted publication
		 * @return canonical receipt document
		 */
		private static PublicationDocument from(ClaimPublication publication)
		{
			return new PublicationDocument(1, publication.key().canonicalProjectRoot().toString(),
				publication.key().normalizedTargetRelativePath().toString(), publication.claimVersion(),
				publication.assessmentRef(), publication.publicationId());
		}
	}

	/**
	 * Rolls a committed confirmation receipt into the per-key claim index.
	 *
	 * @throws IOException if the intent or committed state is corrupt or conflicting
	 */
	private void recoverConfirmationIntent() throws IOException
	{
		if (Files.notExists(confirmationIntentFile))
			return;
		ConfirmationIntent intent;
		try
		{
			byte[] bytes = Files.readAllBytes(confirmationIntentFile);
			intent = JSON.readValue(bytes, ConfirmationIntent.class);
			if (intent == null || intent.schemaVersion() != 1 ||
				!Arrays.equals(bytes, JSON.writeValueAsBytes(intent)))
				throw new IOException("Confirmation intent is malformed: " + confirmationIntentFile);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Confirmation intent is malformed: " + confirmationIntentFile, e);
		}
		CampaignLookupKey key = new CampaignLookupKey(Path.of(intent.projectRoot()), Path.of(intent.targetPath()));
		CampaignBinding binding = new CampaignBinding(intent.campaignId(), Path.of(intent.campaignDirectory()));
		TransitionRequest request = new TransitionRequest(binding, intent.expectedCounter(), intent.kind(),
			intent.producerRecordRef());
		if (request.kind() != TransitionKind.CONFIRMED_CONTRACT &&
			request.kind() != TransitionKind.REVISED_CONTRACT)
			throw new IOException("Confirmation intent has a non-confirmation transition kind");
		if (!keyForBinding(binding).equals(key))
			throw new IOException("Confirmation intent has a foreign campaign key");
		Campaign campaign = new CampaignStore(request.binding().realDirectory()).load();
		if (campaign.transitionCounter() == request.expectedCounter())
		{
			Files.delete(confirmationIntentFile);
			return;
		}
		if (!new ContractService(CampaignStore.open(binding.realDirectory())).loadConfirmedContract(
			request.producerRecordRef().evidenceId()).promptSystem().targetPath().equals(
			key.normalizedTargetRelativePath()))
			throw new IOException("Confirmation intent has a foreign target contract");
		TransitionReceipt receipt = campaign.transitionReceipts().stream().filter(candidate ->
			candidate.expectedCounter() == request.expectedCounter()).findFirst().orElseThrow(() ->
			new IOException("Confirmation intent has no committed transition receipt"));
		if (!receipt.binding().equals(request.binding()) || receipt.kind() != request.kind() ||
			!receipt.producerRecordRef().equals(request.producerRecordRef()))
			throw new IOException("Confirmation intent conflicts with committed transition receipt");
		String lineageId = receipt.result().lineageId();
		LineageSummary lineage = campaign.lineages().stream().filter(value ->
			value.lineageId().equals(lineageId)).findFirst().orElseThrow(() ->
			new IOException("Confirmed lineage is missing from campaign"));
		LatestConfirmedClaim previous = readClaim(key);
		if (previous != null && previous.campaignId().equals(campaign.campaignId()) &&
			previous.lineageId().equals(lineageId) &&
			previous.confirmationCounter() == receipt.resultingCounter())
		{
			reconcileConfirmationIndex(key, binding, campaign, receipt);
			Files.delete(confirmationIntentFile);
			return;
		}
		long sequence = 1;
		if (previous != null)
			sequence = Math.addExact(previous.confirmationSequence(), 1);
		LatestConfirmedClaim claim = new LatestConfirmedClaim(key, campaign.campaignId(), lineageId,
			lineage.confirmedContractId(), receipt.resultingCounter(), sequence);
		Path file = claimFile(key);
		Files.createDirectories(file.getParent());
		writeAtomically(file, JSON.writeValueAsBytes(ClaimDocument.from(claim)));
		reconcileConfirmationIndex(key, binding, campaign, receipt);
		Files.delete(confirmationIntentFile);
	}

	/**
	 * Brings the campaign discovery index to the committed confirmation counter before dropping intent.
	 *
	 * @param key registered target key
	 * @param binding confirmed campaign
	 * @param campaign committed campaign state
	 * @param receipt committed confirmation receipt
	 * @throws IOException if the discovery index is foreign or stale
	 */
	private void reconcileConfirmationIndex(CampaignLookupKey key, CampaignBinding binding, Campaign campaign,
		TransitionReceipt receipt) throws IOException
	{
		IndexDocument index = readIndex();
		List<IndexedCampaign> entries = new ArrayList<>();
		int matches = 0;
		boolean changed = false;
		for (IndexedCampaign entry : index.campaigns())
		{
			if (!entry.campaignId().equals(binding.campaignId()) ||
				!entry.campaignDirectory().equals(binding.realDirectory().toString()))
			{
				entries.add(entry);
				continue;
			}
			matches += 1;
			if (!matchesKey(entry, key) || entry.transitionCounter() != receipt.expectedCounter() &&
				entry.transitionCounter() != receipt.resultingCounter())
				throw new IOException("Confirmation discovery index conflicts with committed receipt");
			if (entry.transitionCounter() == receipt.expectedCounter())
			{
				entries.add(indexed(key, handle(binding.realDirectory(), campaign)));
				changed = true;
			}
			else
				entries.add(entry);
		}
		if (matches != 1)
			throw new IOException("Confirmation requires one registered campaign index entry");
		if (changed)
			writeIndex(new IndexDocument(entries));
	}

	/**
	 * Finds the unique registered key of one exact campaign binding.
	 *
	 * @param binding exact campaign binding
	 * @return canonical lookup key
	 * @throws IOException if registration is missing or ambiguous
	 */
	CampaignLookupKey keyForBinding(CampaignBinding binding) throws IOException
	{
		if (binding == null)
			throw new NullPointerException("binding");
		verifyCampaignDirectory(binding.realDirectory(), binding.campaignId());
		new CampaignStore(binding.realDirectory()).verifyBinding(binding);
		CampaignLookupKey found = null;
		for (IndexedCampaign entry : readIndex().campaigns())
		{
			if (entry.campaignId().equals(binding.campaignId()) &&
				entry.campaignDirectory().equals(binding.realDirectory().toString()))
			{
				if (found != null)
					throw new IOException("Campaign binding has ambiguous registered keys: " + binding);
				found = new CampaignLookupKey(Path.of(entry.projectRoot()), Path.of(entry.targetPath()));
			}
		}
		if (found == null)
			throw new IOException("Campaign binding has no registered key: " + binding);
		return found;
	}

	/**
	 * Reads one canonical schema-1 claim index.
	 *
	 * @param key canonical lookup key
	 * @return claim or null when absent
	 * @throws IOException if the claim is malformed or foreign
	 */
	private LatestConfirmedClaim readClaim(CampaignLookupKey key) throws IOException
	{
		Path file = claimFile(key);
		if (Files.notExists(file))
			return null;
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Latest confirmed claim is redirected: " + file);
		try
		{
			byte[] bytes = Files.readAllBytes(file);
			ClaimDocument document = JSON.readValue(bytes, ClaimDocument.class);
			if (document == null || document.schemaVersion() != 1 ||
				!Arrays.equals(bytes, JSON.writeValueAsBytes(document)))
				throw new IOException("Latest confirmed claim is malformed or foreign: " + file);
			LatestConfirmedClaim claim = document.claim();
			if (!claim.key().equals(key))
				throw new IOException("Latest confirmed claim is foreign: " + file);
			verifyClaimOwner(claim);
			return claim;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Latest confirmed claim is malformed: " + file, e);
		}
	}

	/**
	 * Validates that a claim still names a registered campaign's durable lineage.
	 *
	 * @param claim indexed claim
	 * @throws IOException if its campaign or lineage is missing or foreign
	 */
	private void verifyClaimOwner(LatestConfirmedClaim claim) throws IOException
	{
		for (IndexedCampaign entry : readIndex().campaigns())
		{
			if (entry.campaignId().equals(claim.campaignId()) && matchesKey(entry, claim.key()))
			{
				CampaignStore store = new CampaignStore(Path.of(entry.campaignDirectory()));
				Campaign campaign = store.load();
				if (campaign.transitionCounter() < claim.confirmationCounter() ||
					campaign.lineages().stream().noneMatch(lineage ->
						lineage.lineageId().equals(claim.lineageId()) &&
							lineage.confirmedContractId().equals(claim.contractId())))
					throw new IOException("Latest confirmed claim disagrees with campaign state: " + claim.campaignId());
				if (!new ContractService(store).
					loadConfirmedContract(claim.contractId()).promptSystem().targetPath().equals(
					claim.key().normalizedTargetRelativePath()))
					throw new IOException("Latest confirmed claim has a foreign target contract");
				return;
			}
		}
		throw new IOException("Latest confirmed claim has no registered campaign: " + claim.campaignId());
	}

	/**
	 * Derives a domain-separated claim pathname from the full canonical key.
	 *
	 * @param key canonical lookup key
	 * @return claim pathname
	 * @throws IOException if key serialization fails
	 */
	private Path claimFile(CampaignLookupKey key) throws IOException
	{
		return workflowRoot.resolve("latest-confirmed-claims").resolve(keyDigest(key) + ".json");
	}

	/**
	 * Computes the shared per-key digest used by claim and key-transition intent paths.
	 *
	 * @param key canonical project and target identity
	 * @return lowercase SHA-256 digest
	 */
	private String keyDigest(CampaignLookupKey key)
	{
		byte[] bytes = JSON.writeValueAsBytes(new ClaimKey(key.canonicalProjectRoot().toString(),
			key.normalizedTargetRelativePath().toString()));
		return sha256(bytes);
	}

	/**
	 * Completes an already committed key transition before another caller observes that key.
	 *
	 * @param key canonical project and target identity
	 * @throws IOException if the intent, campaign receipt, retained claim, or index conflicts
	 */
	private void recoverKeyTransitionIntent(CampaignLookupKey key) throws IOException
	{
		Path file = keyTransitionIntentDirectory.resolve(keyDigest(key) + ".json");
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return;
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Campaign key transition intent is redirected: " + file);
		KeyTransitionIntent intent = KeyTransitionIntent.decode(Files.readAllBytes(file));
		if (!intent.key().equals(key) || !keyForBinding(intent.binding()).equals(key))
			throw new IOException("Campaign key transition intent has a foreign registration: " + file);
		CampaignStore store = new CampaignStore(intent.binding().realDirectory());
		store.verifyBinding(intent.binding());
		store.readImmutableJson(intent.producerRecordRef());
		LatestConfirmedClaim retainedClaim = readClaim(key);
		if (!intent.expectedRetainedClaim().equals(Optional.ofNullable(retainedClaim)))
			throw new IOException("Campaign key transition intent has a changed confirmed claim: " + file);
		Campaign campaign = store.load();
		if (!campaign.campaignId().equals(intent.binding().campaignId()))
			throw new IOException("Campaign key transition intent has a foreign campaign: " + file);
		IndexDocument index = readIndex();
		IndexedCampaign entry = index.campaigns().stream().filter(value ->
			matchesKey(value, key) && value.campaignId().equals(intent.binding().campaignId()) &&
				value.campaignDirectory().equals(intent.binding().realDirectory().toString())).findFirst().orElseThrow(() ->
				new IOException("Campaign key transition intent has no exact index entry: " + file));
		if (index.campaigns().stream().filter(value -> matchesKey(value, key) &&
			value.campaignId().equals(intent.binding().campaignId())).count() != 1)
			throw new IOException("Campaign key transition intent has ambiguous index entries: " + file);
		TransitionReceipt receipt = campaign.transitionReceipts().stream().filter(value ->
			value.expectedCounter() == intent.expectedCounter()).findFirst().orElse(null);
		if (receipt == null)
		{
			CampaignStatus predecessorClosedPhase = null;
			if (intent.expectedPhase() == CampaignStatus.INTERRUPTED ||
				intent.expectedPhase() == CampaignStatus.CANCELLED)
				predecessorClosedPhase = intent.expectedClosedPhase();
			if (campaign.transitionCounter() != intent.expectedCounter() ||
				!indexReachesPredecessor(campaign, entry.transitionCounter(), intent.expectedCounter()) ||
				campaign.status() != intent.expectedPhase() ||
				campaign.closedPhase() != predecessorClosedPhase ||
				intent.operation() == KeyTransitionIntent.Operation.CANCEL &&
					!ownsActiveKey(campaign.status()) ||
				intent.operation() == KeyTransitionIntent.Operation.RESUME &&
					campaign.status() != CampaignStatus.CANCELLED)
				throw new IOException("Uncommitted campaign key transition intent has changed predecessor state: " + file);
			int activeOwners = 0;
			boolean boundCampaignOwnsKey = false;
			for (IndexedCampaign candidate : index.campaigns())
			{
				if (matchesKey(candidate, key) && ownsActiveKey(loadCurrent(candidate).status()))
				{
					++activeOwners;
					boundCampaignOwnsKey = sameIdentity(candidate, entry);
				}
			}
			if (intent.operation() == KeyTransitionIntent.Operation.CANCEL &&
				(activeOwners != 1 || !boundCampaignOwnsKey) ||
				intent.operation() == KeyTransitionIntent.Operation.RESUME && activeOwners != 0)
				throw new IOException("Uncommitted campaign key transition intent has conflicting key ownership: " + file);
			Files.delete(file);
			return;
		}
		if (campaign.transitionCounter() != intent.expectedCounter() + 1 ||
			!receipt.binding().equals(intent.binding()) || receipt.kind() != intent.request().kind() ||
			!receipt.producerRecordRef().equals(intent.producerRecordRef()) ||
			receipt.resultingCounter() != campaign.transitionCounter() ||
			!KeyTransitionIntent.requestDigest(new TransitionRequest(receipt.binding(), receipt.expectedCounter(),
				receipt.kind(), receipt.producerRecordRef())).equals(intent.requestDigest()) ||
			receipt.result().phase() != campaign.status())
			throw new IOException("Campaign key transition intent conflicts with committed receipt: " + file);
		if (intent.operation() == KeyTransitionIntent.Operation.CANCEL)
		{
			if (campaign.status() != CampaignStatus.CANCELLED ||
				campaign.closedPhase() != intent.expectedClosedPhase())
				throw new IOException("Committed cancellation has the wrong closed phase: " + file);
		}
		else if (intent.expectedPhase() != CampaignStatus.CANCELLED ||
			campaign.closedPhase() != null || campaign.status() != intent.expectedClosedPhase())
			throw new IOException("Committed resumption has the wrong restored phase: " + file);
		if (entry.transitionCounter() != receipt.resultingCounter() &&
			!indexReachesPredecessor(campaign, entry.transitionCounter(), intent.expectedCounter()))
			throw new IOException("Campaign key transition index has a conflicting counter: " + file);
		for (IndexedCampaign other : index.campaigns())
			if (matchesKey(other, key) && !sameIdentity(other, entry) &&
				ownsActiveKey(loadCurrent(other).status()))
				throw new IOException("Campaign key transition has another active owner: " + file);
		if (entry.transitionCounter() != receipt.resultingCounter())
		{
			List<IndexedCampaign> entries = new ArrayList<>();
			for (IndexedCampaign value : index.campaigns())
			{
				if (sameIdentity(value, entry))
					entries.add(indexed(key, handle(intent.binding().realDirectory(), campaign)));
				else
					entries.add(value);
			}
			writeIndex(new IndexDocument(entries));
		}
		Files.delete(file);
	}

	/**
	 * Accepts an older locator counter only when retained store-only transitions explain every revision.
	 *
	 * @param campaign current campaign with its transition receipts
	 * @param indexedCounter locator's last published campaign revision
	 * @param predecessorCounter revision selected for the key transition
	 * @return whether the intervening receipts preserve locator key ownership
	 */
	private static boolean indexReachesPredecessor(Campaign campaign, long indexedCounter,
		long predecessorCounter)
	{
		if (indexedCounter > predecessorCounter)
			return false;
		long cursor = indexedCounter;
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (cursor == predecessorCounter)
				return true;
			if (receipt.expectedCounter() < cursor)
				continue;
			if (receipt.expectedCounter() != cursor || receipt.resultingCounter() != cursor + 1)
				return false;
			boolean storeOnly = switch (receipt.kind())
			{
				case CONFIRMED_CONTRACT, REVISED_CONTRACT, CANCELLATION, RESUMPTION -> false;
				default -> true;
			};
			if (!storeOnly)
				return false;
			cursor = receipt.resultingCounter();
		}
		return cursor == predecessorCounter;
	}

	/**
	 * Computes a lowercase SHA-256 identity for canonical bytes.
	 *
	 * @param bytes canonical bytes
	 * @return lowercase hexadecimal identity
	 */
	private static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is unavailable", e);
		}
	}

	private record ConfirmationIntent(int schemaVersion, String projectRoot, String targetPath,
		String campaignId, String campaignDirectory, long expectedCounter, TransitionKind kind,
		EvidenceRef producerRecordRef)
	{
	}

	private record ClaimKey(String projectRoot, String targetPath)
	{
	}

	private record ClaimDocument(int schemaVersion, String projectRoot, String targetPath, String campaignId,
		String lineageId, String contractId, long confirmationCounter, long confirmationSequence)
	{
		/**
		 * Converts one domain claim to its flat durable representation.
		 *
		 * @param claim confirmed claim
		 * @return canonical claim document
		 */
		private static ClaimDocument from(LatestConfirmedClaim claim)
		{
			return new ClaimDocument(1, claim.key().canonicalProjectRoot().toString(),
				claim.key().normalizedTargetRelativePath().toString(), claim.campaignId(), claim.lineageId(),
				claim.contractId(), claim.confirmationCounter(), claim.confirmationSequence());
		}

		/**
		 * Validates and restores the domain claim from its flat document.
		 *
		 * @return confirmed claim
		 */
		private LatestConfirmedClaim claim()
		{
			return new LatestConfirmedClaim(new CampaignLookupKey(Path.of(projectRoot), Path.of(targetPath)),
				campaignId, lineageId, contractId, confirmationCounter, confirmationSequence);
		}
	}

	/**
	 * Creates one campaign under a canonical key or returns its existing active owner.
	 *
	 * @param key canonical project and target identity
	 * @param request initial campaign identity and profile
	 * @return created or existing campaign registration
	 * @throws IOException if key state, campaign state, or publication is invalid
	 */
	public CampaignRegistration createOrFind(CampaignLookupKey key, CampaignCreationRequest request)
		throws IOException
	{
		if (key == null || request == null)
			throw new NullPointerException("Campaign lookup key and creation request must not be null");
		return withLock(() ->
		{
			recoverCreationIntent();
			recoverKeyTransitionIntent(key);
			IndexDocument index = readIndex();
			Optional<CampaignHandle> occupied = activeHandle(index, key);
			if (occupied.isPresent())
				return new CampaignRegistration(CampaignRegistration.Status.EXISTING_CAMPAIGN_ACTION_REQUIRED,
					occupied.orElseThrow());
			Path directory = workflowRoot.resolve("campaign-" + request.campaignId());
			if (Files.exists(directory))
				throw new IOException("Campaign directory already exists without a registration: " + directory);
			if (Files.exists(forkIntentFile, LinkOption.NOFOLLOW_LINKS))
				Files.delete(forkIntentFile);
			VerifiedCleanFork.Identity fork = null;
			if (Files.exists(key.canonicalProjectRoot().resolve(".git"), LinkOption.NOFOLLOW_LINKS))
			{
				fork = VerifiedCleanFork.preflight(key, directory);
				writeAtomically(forkIntentFile, VerifiedCleanFork.encode(fork));
			}
			CreationIntent intent = new CreationIntent(1, key.canonicalProjectRoot().toString(),
				key.normalizedTargetRelativePath().toString(), request.campaignId(), directory.toString(),
				request.optimizationProfile());
			writeAtomically(creationIntentFile, JSON.writeValueAsBytes(intent));
			CampaignStore store = CampaignStore.initialize(directory, request.campaignId(), request.optimizationProfile());
			if (fork != null)
			{
				VerifiedCleanFork.publish(fork, directory);
				VerifiedCleanFork.ensure(fork);
			}
			CampaignHandle handle = handle(directory, store.load());
			List<IndexedCampaign> entries = new ArrayList<>(index.campaigns());
			entries.add(indexed(key, handle));
			writeIndex(new IndexDocument(entries));
			Files.delete(creationIntentFile);
			if (fork != null)
				Files.delete(forkIntentFile);
			return new CampaignRegistration(CampaignRegistration.Status.CREATED, handle);
		});
	}

	/**
	 * Returns the pinned optimization worktree for a registered campaign.
	 *
	 * @param binding registered campaign
	 * @return retained optimization root, or the synthetic source root for a non-Git fixture
	 * @throws IOException if the fork identity is absent or foreign
	 */
	public Path optimizationRoot(CampaignBinding binding) throws IOException
	{
		CampaignStore store = requireRegisteredBinding(binding);
		CampaignLookupKey key = keyForBinding(binding);
		if (!Files.exists(key.canonicalProjectRoot().resolve(".git"), LinkOption.NOFOLLOW_LINKS))
			return key.canonicalProjectRoot();
		return VerifiedCleanFork.load(store.directory(), key.canonicalProjectRoot()).optimizationRoot();
	}

	/**
	 * Verifies the untouched fork before initial source capture or proposal work.
	 *
	 * @param binding registered campaign
	 * @return verified original optimization root
	 * @throws IOException if the source fork differs from the clean base commit
	 */
	public Path verifyInitialFork(CampaignBinding binding) throws IOException
	{
		Path root = optimizationRoot(binding);
		CampaignLookupKey key = keyForBinding(binding);
		if (!root.equals(key.canonicalProjectRoot()))
			VerifiedCleanFork.ensure(VerifiedCleanFork.load(binding.realDirectory(), key.canonicalProjectRoot()));
		return root;
	}

	/**
	 * Finds the sole campaign that currently owns a canonical target, without creating another campaign.
	 *
	 * @param key canonical project and target identity
	 * @return the active owner, or empty when no campaign currently owns the key
	 * @throws IOException if key recovery, a registered campaign, or ownership is inconsistent
	 */
	public Optional<CampaignHandle> findActive(CampaignLookupKey key) throws IOException
	{
		if (key == null)
			throw new NullPointerException("key");
		return withLock(() ->
		{
			recoverCreationIntent();
			recoverKeyTransitionIntent(key);
			return activeHandle(readIndex(), key);
		});
	}

	/**
	 * Selects the unique active owner from an index already protected by the locator lock.
	 *
	 * @param index current durable campaign index
	 * @param key canonical target identity
	 * @return the active owner, if any
	 * @throws IOException if multiple campaigns own the key
	 */
	private Optional<CampaignHandle> activeHandle(IndexDocument index, CampaignLookupKey key) throws IOException
	{
		CampaignHandle occupied = null;
		for (IndexedCampaign entry : index.campaigns())
		{
			if (!matchesKey(entry, key))
				continue;
			CampaignHandle existing = loadCurrent(entry);
			if (!ownsActiveKey(existing.status()))
				continue;
			if (occupied != null)
				throw new IOException("Campaign key has multiple active owners: " + key);
			occupied = existing;
		}
		return Optional.ofNullable(occupied);
	}

	/**
	 * Completes a campaign registration when state committed before its key index.
	 *
	 * @throws IOException if the intent or committed campaign conflicts with the index
	 */
	private void recoverCreationIntent() throws IOException
	{
		if (Files.notExists(creationIntentFile))
			return;
		CreationIntent intent;
		try
		{
			byte[] bytes = Files.readAllBytes(creationIntentFile);
			intent = JSON.readValue(bytes, CreationIntent.class);
			if (intent == null || intent.schemaVersion() != 1 ||
				!Arrays.equals(bytes, JSON.writeValueAsBytes(intent)))
				throw new IOException("Campaign creation intent is malformed: " + creationIntentFile);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Campaign creation intent is malformed: " + creationIntentFile, e);
		}
		Path directory = Path.of(intent.campaignDirectory());
		verifyCampaignDirectory(directory, intent.campaignId());
		CampaignLookupKey key = new CampaignLookupKey(Path.of(intent.projectRoot()), Path.of(intent.targetPath()));
		VerifiedCleanFork.Identity fork = null;
		if (Files.exists(forkIntentFile, LinkOption.NOFOLLOW_LINKS))
		{
			fork = VerifiedCleanFork.readIntent(forkIntentFile);
			if (!fork.baseRoot().equals(key.canonicalProjectRoot()) ||
				!fork.optimizationRoot().equals(VerifiedCleanFork.forkPath(key.canonicalProjectRoot(), directory)))
				throw new IOException("Campaign clean-fork intent is foreign: " + forkIntentFile);
		}
		else if (Files.exists(key.canonicalProjectRoot().resolve(".git"), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Campaign creation is missing its clean-fork intent: " + directory);
		if (Files.notExists(directory.resolve("campaign.json")))
		{
			Files.delete(creationIntentFile);
			if (fork != null)
				Files.delete(forkIntentFile);
			return;
		}
		Campaign campaign = new CampaignStore(directory).load();
		if (!campaign.campaignId().equals(intent.campaignId()) ||
			!intent.optimizationProfile().equals(campaign.optimizationProfile()))
			throw new IOException("Campaign creation intent disagrees with committed campaign: " + directory);
		if (fork != null)
		{
			VerifiedCleanFork.publish(fork, directory);
			VerifiedCleanFork.ensure(fork);
		}
		IndexDocument index = readIndex();
		IndexedCampaign expected = indexed(key, handle(directory, campaign));
		for (IndexedCampaign entry : index.campaigns())
		{
			if (matchesKey(entry, key) && ownsActiveKey(loadCurrent(entry).status()) &&
				!sameIdentity(entry, expected))
				throw new IOException("Campaign creation intent conflicts with occupied key: " + directory);
			if (sameIdentity(entry, expected))
			{
				Files.delete(creationIntentFile);
				if (fork != null)
					Files.delete(forkIntentFile);
				return;
			}
		}
		List<IndexedCampaign> entries = new ArrayList<>(index.campaigns());
		entries.add(expected);
		writeIndex(new IndexDocument(entries));
		Files.delete(creationIntentFile);
		if (fork != null)
			Files.delete(forkIntentFile);
	}

	/**
	 * Checks one indexed project's canonical key.
	 *
	 * @param entry indexed campaign
	 * @param key expected lookup key
	 * @return whether this entry belongs to the key
	 */
	private static boolean matchesKey(IndexedCampaign entry, CampaignLookupKey key)
	{
		return entry.projectRoot().equals(key.canonicalProjectRoot().toString()) &&
			entry.targetPath().equals(key.normalizedTargetRelativePath().toString());
	}

	/**
	 * Identifies phases that retain the key for scheduling or explicit continuation.
	 *
	 * @param status durable campaign phase
	 * @return whether the campaign occupies its lookup key
	 */
	private static boolean ownsActiveKey(CampaignStatus status)
	{
		return status == CampaignStatus.AWAITING_CONTRACT_CONFIRMATION || status == CampaignStatus.DEVELOPING ||
			status == CampaignStatus.FORMAL_TESTING || status == CampaignStatus.INTERRUPTED;
	}

	private record CreationIntent(int schemaVersion, String projectRoot, String targetPath, String campaignId,
		String campaignDirectory, ModelProfile optimizationProfile)
	{
	}

	/**
	 * Finds validated campaigns for one canonical lookup identity.
	 *
	 * @param key project and target identity
	 * @return validated durable campaign handles
	 * @throws IOException if the index or a referenced campaign is corrupt or stale
	 */
	public List<CampaignHandle> find(CampaignLookupKey key) throws IOException
	{
		if (key == null)
			throw new NullPointerException("key");
		return withLock(() ->
		{
			recoverCreationIntent();
			recoverKeyTransitionIntent(key);
			List<CampaignHandle> result = new ArrayList<>();
			for (IndexedCampaign entry : readIndex().campaigns())
				if (entry.projectRoot().equals(key.canonicalProjectRoot().toString()) &&
					entry.targetPath().equals(key.normalizedTargetRelativePath().toString()))
				{
					result.add(open(entry));
				}
			return List.copyOf(result);
		});
	}

	/**
	 * Registers a validated campaign under one canonical lookup identity.
	 *
	 * @param key project and target identity
	 * @param handle campaign summary to validate and register
	 * @throws IOException if state cannot be validated or the index cannot be replaced atomically
	 */
	public void register(CampaignLookupKey key, CampaignHandle handle) throws IOException
	{
		if (key == null || handle == null)
			throw new NullPointerException("Campaign lookup key and handle must not be null");
		withLock(() ->
		{
			recoverKeyTransitionIntent(key);
			CampaignHandle current = open(indexed(key, handle));
			if (!sameHandle(current, handle))
				throw new IOException("Campaign handle is stale: " + handle.campaignId());
			IndexDocument index = readIndex();
			List<IndexedCampaign> entries = new ArrayList<>(index.campaigns());
			IndexedCampaign replacement = indexed(key, current);
			if (entries.stream().anyMatch(entry -> entry.campaignId().equals(replacement.campaignId()) ||
				entry.campaignDirectory().equals(replacement.campaignDirectory())))
				throw new IOException("Campaign is already registered: " + current.campaignId());
			if (ownsActiveKey(current.status()))
			{
				for (IndexedCampaign entry : entries)
				{
					if (matchesKey(entry, key) && ownsActiveKey(loadCurrent(entry).status()))
						throw new IOException("Campaign key is already occupied: " + key);
				}
			}
			entries.add(replacement);
			writeIndex(new IndexDocument(entries));
			return null;
		});
	}

	/**
	 * Reopens a handle from durable campaign state and refreshes its index summary.
	 *
	 * @param handle handle to reopen
	 * @return the validated current campaign handle
	 * @throws IOException if the handle is absent, corrupt, or stale
	 */
	public CampaignHandle refresh(CampaignHandle handle) throws IOException
	{
		if (handle == null)
			throw new NullPointerException("handle");
		return withLock(() ->
		{
			IndexDocument index = readIndex();
			IndexedCampaign entry = index.campaigns().stream().filter(value ->
				value.campaignId().equals(handle.campaignId()) &&
					value.campaignDirectory().equals(handle.campaignDirectory().toString())).findFirst().orElseThrow(() ->
					new IOException("Campaign handle is not registered: " + handle.campaignId()));
			if (entry.transitionCounter() != handle.transitionCounter())
				throw new IOException("Campaign refresh handle is stale: " + handle.campaignId());
			CampaignHandle current = loadCurrent(entry);
			if (current.transitionCounter() < entry.transitionCounter() ||
				current.transitionCounter() == entry.transitionCounter() && !sameHandle(current, handle))
			{
				throw new IOException("Campaign refresh handle does not match durable state: " + handle.campaignId());
			}
			List<IndexedCampaign> entries = new ArrayList<>();
			for (IndexedCampaign value : index.campaigns())
			{
				if (sameIdentity(value, entry))
				{
					CampaignLookupKey key = new CampaignLookupKey(Path.of(value.projectRoot()),
						Path.of(value.targetPath()));
					entries.add(indexed(key, current));
				}
				else
					entries.add(value);
			}
			writeIndex(new IndexDocument(entries));
			return current;
		});
	}

	/**
	 * Opens and validates a campaign represented by an index entry.
	 *
	 * @param entry durable index entry
	 * @return current durable campaign handle
	 * @throws IOException if the entry cannot be reopened
	 */
	private CampaignHandle open(IndexedCampaign entry) throws IOException
	{
		Path directory = Path.of(entry.campaignDirectory()).toAbsolutePath().normalize();
		verifyCampaignDirectory(directory, entry.campaignId());
		CampaignStore store = new CampaignStore(directory);
		Campaign campaign = store.lockedSnapshot(new CampaignBinding(entry.campaignId(), directory));
		if (!campaign.campaignId().equals(entry.campaignId()) ||
			!indexReachesPredecessor(campaign, entry.transitionCounter(), campaign.transitionCounter()))
			throw new IOException("Campaign index entry is stale: " + entry.campaignId());
		return handle(directory, campaign);
	}

	/**
	 * Loads current campaign state without trusting the index transition counter.
	 *
	 * @param entry durable index entry
	 * @return current durable campaign handle
	 * @throws IOException if the campaign cannot be reopened
	 */
	private CampaignHandle loadCurrent(IndexedCampaign entry) throws IOException
	{
		Path campaignDirectory = Path.of(entry.campaignDirectory()).toAbsolutePath().normalize();
		verifyCampaignDirectory(campaignDirectory, entry.campaignId());
		Campaign campaign = new CampaignStore(campaignDirectory).load();
		if (!campaign.campaignId().equals(entry.campaignId()))
			throw new IOException("Campaign index identity does not match durable state: " + entry.campaignId());
		return handle(campaignDirectory, campaign);
	}

	/**
	 * Ensures a registered campaign is the canonical direct child assigned to its identity.
	 *
	 * @param directory campaign directory
	 * @param campaignId durable campaign identity
	 * @throws IOException if the directory is nested, redirected, or misnamed
	 */
	private void verifyCampaignDirectory(Path directory, String campaignId) throws IOException
	{
		Path normalized = directory.toAbsolutePath().normalize();
		if (!normalized.getParent().equals(workflowRoot) ||
			!normalized.getFileName().toString().equals("campaign-" + campaignId))
			throw new IOException("Campaign directory must be a named direct child of the workflow root: " + directory);
		if (!workflowRoot.toRealPath().equals(workflowRoot) ||
			Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !normalized.toRealPath().equals(normalized))
			throw new IOException("Campaign directory is redirected: " + directory);
	}

	/**
	 * Returns the current handle from durable campaign state.
	 *
	 * @param directory campaign directory
	 * @param campaign durable campaign
	 * @return current campaign handle
	 * @throws IOException if the campaign state metadata cannot be read
	 */
	private static CampaignHandle handle(Path directory, Campaign campaign) throws IOException
	{
		return new CampaignHandle(campaign.campaignId(), directory, campaign.status(), campaign.activeLineageId(),
			campaign.transitionCounter(), Files.getLastModifiedTime(directory.resolve("campaign.json")).toInstant(),
			campaign.closedPhase() != null);
	}

	/**
	 * Returns the portable index representation of one handle.
	 *
	 * @param key durable lookup key
	 * @param handle campaign handle
	 * @return portable index entry
	 */
	private static IndexedCampaign indexed(CampaignLookupKey key, CampaignHandle handle)
	{
		return new IndexedCampaign(key.canonicalProjectRoot().toString(), key.normalizedTargetRelativePath().toString(),
			handle.campaignId(), handle.campaignDirectory().toString(), handle.transitionCounter());
	}

	/**
	 * Returns whether two handle values have the same durable summary.
	 *
	 * @param first first handle
	 * @param second second handle
	 * @return whether the handles match
	 */
	private static boolean sameHandle(CampaignHandle first, CampaignHandle second)
	{
		return first.equals(second);
	}

	/**
	 * Returns whether two index entries identify the same campaign registration.
	 *
	 * @param first first index entry
	 * @param second second index entry
	 * @return whether the entries identify the same registration
	 */
	private static boolean sameIdentity(IndexedCampaign first, IndexedCampaign second)
	{
		return first.projectRoot().equals(second.projectRoot()) && first.targetPath().equals(second.targetPath()) &&
			first.campaignId().equals(second.campaignId()) && first.campaignDirectory().equals(second.campaignDirectory());
	}

	/**
	 * Reads the complete bounded index document.
	 *
	 * @return index document
	 * @throws IOException if the index is malformed
	 */
	private IndexDocument readIndex() throws IOException
	{
		if (Files.notExists(indexFile))
			return new IndexDocument(List.of());
		try
		{
			IndexDocument result = JSON.readValue(Files.readString(indexFile), IndexDocument.class);
			if (result == null || result.campaigns() == null)
				throw new IOException("Campaign index is malformed: " + indexFile);
			IndexDocument index = new IndexDocument(result.campaigns());
			validateIndex(index);
			return index;
		}
		catch (IOException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new IOException("Campaign index is malformed: " + indexFile, e);
		}
	}

	/**
	 * Validates every decoded entry and rejects ambiguous campaign registrations.
	 *
	 * @param index decoded index
	 * @throws IOException if an entry is noncanonical, unsafe, or duplicated
	 */
	private void validateIndex(IndexDocument index) throws IOException
	{
		Set<String> campaignIds = new HashSet<>();
		Set<String> campaignDirectories = new HashSet<>();
		for (IndexedCampaign entry : index.campaigns())
		{
			Path projectRoot = Path.of(entry.projectRoot());
			if (!projectRoot.isAbsolute() || !projectRoot.normalize().equals(projectRoot) ||
				!projectRoot.toRealPath().equals(projectRoot))
			{
				throw new IOException("Campaign index project root is not canonical: " + entry.projectRoot());
			}
			Path targetPath = Path.of(entry.targetPath());
			if (targetPath.isAbsolute() || targetPath.toString().isBlank() || !targetPath.normalize().equals(targetPath) ||
				targetPath.startsWith(".."))
			{
				throw new IOException("Campaign index target path is invalid: " + entry.targetPath());
			}
			Path campaignDirectory = Path.of(entry.campaignDirectory());
			if (!campaignDirectory.isAbsolute() || !campaignDirectory.normalize().equals(campaignDirectory))
			{
				throw new IOException("Campaign index directory is invalid: " + entry.campaignDirectory());
			}
			verifyCampaignDirectory(campaignDirectory, entry.campaignId());
			if (!campaignIds.add(entry.campaignId()) || !campaignDirectories.add(entry.campaignDirectory()))
				throw new IOException("Campaign index contains a duplicate registration: " + entry.campaignId());
		}
	}

	/**
	 * Atomically persists the complete index document.
	 *
	 * @param index replacement index
	 * @throws IOException if persistence fails
	 */
	private void writeIndex(IndexDocument index) throws IOException
	{
		writeAtomically(indexFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
	}

	/**
	 * Runs an operation holding the workflow index lock. A nested read on the same thread reuses that lock.
	 *
	 * @param <T> result type
	 * @param operation operation to run
	 * @return operation result
	 * @throws IOException if locking fails
	 */
	private <T> T withLock(IoOperation<T> operation) throws IOException
	{
		synchronized (CampaignFileLocks.class)
		{
			if (Files.exists(workflowRoot, LinkOption.NOFOLLOW_LINKS) &&
				(!Files.isDirectory(workflowRoot, LinkOption.NOFOLLOW_LINKS) ||
					!workflowRoot.toRealPath().equals(workflowRoot)))
				throw new IOException("Workflow root is not a canonical directory: " + workflowRoot);
			if (!Files.exists(workflowRoot, LinkOption.NOFOLLOW_LINKS) &&
				!workflowRoot.getParent().toRealPath().equals(workflowRoot.getParent()))
				throw new IOException("Workflow root parent is not canonical: " + workflowRoot);
			Files.createDirectories(workflowRoot);
			return CampaignFileLocks.withLock(workflowRoot.resolve("campaign-index.lock"), operation::run);
		}
	}

	/**
	 * Flushes a sibling temporary file and atomically replaces its target.
	 *
	 * @param target replacement target
	 * @param content complete replacement content
	 * @throws IOException if replacement fails
	 */
	static void writeAtomically(Path target, byte[] content) throws IOException
	{
		Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
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
				throw new IOException("Atomic campaign index replacement is not supported for " + target, e);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	private record IndexDocument(List<IndexedCampaign> campaigns)
	{
		/**
		 * Defensively copies index entries.
		 *
		 * @param campaigns index entries
		 */
		private IndexDocument
		{
			campaigns = List.copyOf(campaigns);
		}
	}

	private record IndexedCampaign(String projectRoot, String targetPath, String campaignId, String campaignDirectory,
		long transitionCounter)
	{
		/**
		 * Stores one portable index entry.
		 *
		 * @param projectRoot canonical project root
		 * @param targetPath normalized target path
		 * @param campaignId campaign id
		 * @param campaignDirectory campaign directory
		 * @param transitionCounter campaign transition counter
		 */
		private IndexedCampaign
		{
			if (projectRoot == null || projectRoot.isBlank() || targetPath == null || targetPath.isBlank() ||
				campaignId == null || campaignId.isBlank() || campaignDirectory == null || campaignDirectory.isBlank() ||
				transitionCounter < 0)
			{
				throw new IllegalArgumentException("Campaign index entry is invalid");
			}
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
