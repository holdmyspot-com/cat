package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the first contract inspection owner before the scheduler claims its call.
 */
public final class InspectionOwnerService
{
	private static final JsonMapper JSON = SnapshotJson.mapper();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds owner publication to a registered campaign.
	 *
	 * @param locator the campaign's durable locator
	 * @param store the pinned evidence store
	 */
	public InspectionOwnerService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or resumes the first inspection owner for the current preparation cycle.
	 *
	 * @param binding the registered campaign
	 * @return the indexed owner and native run request
	 * @throws IOException if current preparation or source evidence is invalid
	 */
	public PreparedInspectionCall prepare(CampaignBinding binding) throws IOException
	{
		requireRegisteredStore(binding);
		PreparationCycleService cycles = new PreparationCycleService(locator, store);
		EvidenceRef cycleRef = cycles.prepare(binding);
		ContractPreparationCycle cycle = cycles.load(binding, cycleRef);
		int ordinal = 0;
		String indexKey = indexKey(cycle.authority(), ordinal);
		Optional<EvidenceRef> existing = store.readPreconfirmationIndex(binding,
			PreconfirmationIndexKind.CONTRACT_INSPECTION_OWNER, indexKey);
		if (existing.isPresent())
			return load(binding, existing.orElseThrow());

		InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
		InitialTargetCapture source = sources.load(binding, cycle.sourceCaptureRef());
		EvidenceRef manifestRef = sources.publishSnapshotManifest(binding, cycle.sourceCaptureRef());
		Path projectCopy = sources.materializeProjectSnapshot(binding, cycle.sourceCaptureRef());
		String callId = logicalCallId(cycleRef, ordinal);
		ProjectRunContext context = new ProjectRunContext(new ProjectExecutionSnapshot(projectCopy,
			manifestRef), ProjectWorkspaceMode.ISOLATED_COPY, Optional.empty(), Optional.empty());
		InspectionRunRequest request = new InspectionRunRequest(1, binding.campaignId(), callId, context,
			List.of(inspectionInstruction(source.targetPath())),
			store.lockedSnapshot(binding).optimizationProfile());
		EvidenceRef requestRef = store.appendImmutableJson("run-requests", JSON.writeValueAsBytes(request));
		ContractInspectionOwner owner = new ContractInspectionOwner(1, binding.campaignId(), ordinal, callId,
			cycleRef, cycle.sourceCaptureRef(), requestRef);
		EvidenceRef ownerRef = store.appendImmutableJson("contract-preparation-owners",
			JSON.writeValueAsBytes(owner));
		store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_OWNER,
			indexKey, ownerRef);
		return load(binding, ownerRef);
	}

	/**
	 * Loads an indexed inspection owner and its exact native request.
	 *
	 * @param binding the registered campaign
	 * @param ownerRef the indexed owner evidence
	 * @return the validated owner and request
	 * @throws IOException if owner, cycle, source, or request evidence is invalid
	 */
	public PreparedInspectionCall load(CampaignBinding binding, EvidenceRef ownerRef) throws IOException
	{
		requireRegisteredStore(binding);
		Objects.requireNonNull(ownerRef, "ownerRef");
		if (!ownerRef.collection().equals("contract-preparation-owners"))
			throw new IOException("Inspection owner has the wrong evidence collection");
		try
		{
			byte[] ownerBytes = store.readImmutableJson(ownerRef);
			ContractInspectionOwner owner = JSON.readValue(ownerBytes, ContractInspectionOwner.class);
			if (owner == null || !Arrays.equals(ownerBytes, JSON.writeValueAsBytes(owner)) ||
				!owner.campaignId().equals(binding.campaignId()) ||
				!owner.logicalCallId().equals(logicalCallId(owner.preparationCycleRef(),
					owner.inspectionOrdinal())))
				throw new IOException("Inspection owner identity is foreign or noncanonical");
			ContractPreparationCycle cycle = new PreparationCycleService(locator, store).load(binding,
				owner.preparationCycleRef());
			Optional<EvidenceRef> indexed = store.readPreconfirmationIndex(binding,
				PreconfirmationIndexKind.CONTRACT_INSPECTION_OWNER,
				indexKey(cycle.authority(), owner.inspectionOrdinal()));
			if (!indexed.equals(Optional.of(ownerRef)) ||
				!owner.sourceCaptureRef().equals(cycle.sourceCaptureRef()))
				throw new IOException("Inspection owner conflicts with its preparation cycle or index");
			byte[] requestBytes = store.readImmutableJson(owner.runRequestRef());
			InspectionRunRequest request = JSON.readValue(requestBytes, InspectionRunRequest.class);
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
			EvidenceRef manifestRef = sources.publishSnapshotManifest(binding, owner.sourceCaptureRef());
			Path projectCopy = sources.materializeProjectSnapshot(binding, owner.sourceCaptureRef());
			if (request == null || !Arrays.equals(requestBytes, JSON.writeValueAsBytes(request)) ||
				!request.campaignId().equals(binding.campaignId()) ||
				!request.logicalCallId().equals(owner.logicalCallId()) ||
				!request.context().snapshot().projectRoot().equals(projectCopy) ||
				!request.context().snapshot().snapshotManifestRef().equals(manifestRef) ||
				!request.modelProfile().equals(store.lockedSnapshot(binding).optimizationProfile()))
				throw new IOException("Inspection run request conflicts with its owner or source capture");
			return new PreparedInspectionCall(ownerRef, owner, cycle, request);
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Inspection owner or run request is malformed", exception);
		}
	}

	/**
	 * Requires the locator and store to name the same registered campaign.
	 *
	 * @param binding the requested campaign
	 * @throws IOException if registration or pinned state differs
	 */
	private void requireRegisteredStore(CampaignBinding binding) throws IOException
	{
		CampaignStore registered = locator.requireRegisteredBinding(binding);
		if (!registered.directory().equals(store.directory()))
			throw new IOException("Inspection owner store is not the registered campaign");
		store.lockedSnapshot(binding);
	}

	/**
	 * Names one semantic inspection request within a preparation scope.
	 *
	 * @param authority the retained preparation scope
	 * @param ordinal the nonnegative inspection ordinal
	 * @return the campaign-scoped owner index key
	 */
	private static String indexKey(PreparationAuthority authority, int ordinal)
	{
		return PreparationIndexKey.derive(authority) + ":inspection:" + ordinal;
	}

	/**
	 * Derives the logical call identity from its immutable cycle and inspection ordinal.
	 *
	 * @param cycleRef the retained cycle
	 * @param ordinal the inspection ordinal
	 * @return a stable logical call identity
	 */
	private static String logicalCallId(EvidenceRef cycleRef, int ordinal)
	{
		return "inspection-" + cycleRef.evidenceId() + "-" + ordinal;
	}

	/**
	 * Requests source discovery through the selected native harness without editing project files.
	 *
	 * @param target the selected project-relative prompt file
	 * @return one native inspection instruction
	 */
	private static String inspectionInstruction(Path target)
	{
		return "Inspect the prompt system rooted at " + target + " using its normal loading behavior. " +
			"Read the target and report each readable prompt source, its loading relationship, and requirements " +
			"with source locations. Do not edit project files.";
	}
}
