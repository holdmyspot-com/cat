package io.github.cowwoc.cat.common.optimizer.loading;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshot;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.RetainedPromptSourceResolver;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RuntimePromptSource;
import io.github.cowwoc.cat.common.optimizer.state.ContractInspectionAttempt;
import io.github.cowwoc.cat.common.optimizer.state.ContractInspectionOwner;
import io.github.cowwoc.cat.common.optimizer.state.ContractInspectionResult;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCapture;
import io.github.cowwoc.cat.common.optimizer.state.InspectionCompletionService;
import io.github.cowwoc.cat.common.optimizer.state.SelectedSourceEditProposal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the source evidence selected by an adopted native inspection.
 */
public final class InspectedSourceEvidenceService
{
	private static final JsonMapper JSON = SnapshotJson.mapper();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Opens the evidence publisher for one campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	public InspectedSourceEvidenceService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the selected source and native observations for contract review.
	 *
	 * @param binding registered campaign
	 * @param inspectionResultRef adopted inspection result
	 * @return immutable prompt-loading evidence reference
	 * @throws IOException if the inspection or source evidence is invalid
	 */
	public EvidenceRef publish(CampaignBinding binding, EvidenceRef inspectionResultRef) throws IOException
	{
		InspectedSourceEvidence evidence = expected(binding, inspectionResultRef);
		EvidenceRef reference = store.appendImmutableJson("prompt-loading", JSON.writeValueAsBytes(evidence));
		load(binding, reference);
		return reference;
	}

	/**
	 * Reloads the evidence and verifies it against the adopted inspection after restart.
	 *
	 * @param binding registered campaign
	 * @param reference immutable prompt-loading evidence
	 * @return selected source and native observations
	 * @throws IOException if the evidence differs from its adopted producer
	 */
	public InspectedSourceEvidence load(CampaignBinding binding, EvidenceRef reference) throws IOException
	{
		InspectedSourceEvidence evidence = loadRetained(reference);
		if (!binding.campaignId().equals(evidence.campaignId()) ||
			!binding.realDirectory().equals(store.directory()))
			throw new IOException("Inspected source evidence belongs to another campaign");
		if (!evidence.equals(expected(binding, evidence.inspectionResultRef())))
			throw new IOException("Inspected source evidence differs from its adopted inspection");
		return evidence;
	}

	/**
	 * Validates immutable source, link, and native evidence without reopening campaign state.
	 * Campaign confirmation uses this while it verifies its own durable state; {@link #load} separately checks adoption.
	 *
	 * @param reference immutable inspected-source evidence
	 * @return revalidated source and observation record
	 * @throws IOException if any retained reference, link, byte hash, or native observation differs
	 */
	public InspectedSourceEvidence loadRetained(EvidenceRef reference) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals("prompt-loading"))
			throw new IOException("Inspected source evidence has the wrong collection");
		InspectedSourceEvidence evidence = readCanonical(reference, InspectedSourceEvidence.class);
		ContractInspectionResult result = readCanonical(evidence.inspectionResultRef(),
			ContractInspectionResult.class);
		if (result.selectedSourceProposalRef().isEmpty())
			throw new IOException("Inspected source has no selected-source proposal");
		SelectedSourceEditProposal selected = readCanonical(result.selectedSourceProposalRef().orElseThrow(),
			SelectedSourceEditProposal.class);
		InitialTargetCapture capture = readCanonical(result.sourceCaptureRef(), InitialTargetCapture.class);
		ContractInspectionOwner owner = readCanonical(result.ownerRef(), ContractInspectionOwner.class);
		ContractInspectionAttempt attempt = readCanonical(result.attemptRef(), ContractInspectionAttempt.class);
		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(result.completionRef(),
			store::readImmutableJson);
		if (completion.outcome() != NativeRunCompletionEvidence.Outcome.SUCCEEDED)
			throw new IOException("Inspected source requires a successful native completion");
		ObservationCoverage coverage = completion.result().orElseThrow().coverage().stream().filter(value ->
			value.domain() == ObservationDomain.PROMPT_SOURCE).findFirst().orElseThrow(() ->
			new IOException("Native prompt-source coverage is missing"));
		PromptSource source = selected.source();
		List<PromptSource> sources = retainedSources(capture, source, selected.promptPathObservations());
		if (!evidence.campaignId().equals(result.campaignId()) ||
			!evidence.campaignId().equals(selected.campaignId()) ||
			!evidence.campaignId().equals(capture.campaignId()) ||
			!evidence.campaignId().equals(owner.campaignId()) ||
			!evidence.campaignId().equals(completion.campaignId()) ||
			!result.sourceCaptureRef().equals(selected.sourceCaptureRef()) ||
			!result.sourceCaptureRef().equals(owner.sourceCaptureRef()) ||
			!result.sourceCaptureRef().equals(attempt.requestRef()) ||
			!result.ownerRef().equals(attempt.header().ownerRequestRef()) ||
			!result.completionRef().equals(selected.nativeCompletionRef()) ||
			!owner.logicalCallId().equals(completion.logicalCallId()) ||
			!owner.runRequestRef().equals(completion.runRequestRef()) ||
			!capture.source().equals(selected.source()) ||
			!selected.promptPathObservations().equals(completion.result().orElseThrow().promptSources()) ||
			!selected.promptSourceCoverage().equals(coverage) ||
			!evidence.selectedSource().equals(source) ||
			!evidence.sources().equals(sources) ||
			!evidence.pathObservations().equals(selected.promptPathObservations()) ||
			!evidence.coverage().equals(coverage) ||
			!evidence.proposedEditableRoot().equals(selected.editableRoot()))
			throw new IOException("Inspected source evidence differs from its retained producers");
		return evidence;
	}

	/**
	 * Reconstructs requested and observed sources from the initial Git inventory and retained file snapshot.
	 *
	 * @param capture retained source identity
	 * @param selected requested source with retained bytes
	 * @param observations native paths, which do not establish consumed bytes
	 * @return selected source followed by distinct observed in-project file sources
	 * @throws IOException if a source path, link identity, or retained file bytes differ
	 */
	private List<PromptSource> retainedSources(InitialTargetCapture capture, PromptSource selected,
		List<RuntimePromptSource> observations) throws IOException
	{
		LiveGitInventory inventory = readCanonical(capture.inventoryRef(), LiveGitInventory.class);
		PromptSnapshot snapshot = new PromptSnapshotRepository(store.directory()).load(capture.snapshotId());
		RetainedPromptSourceResolver resolver = new RetainedPromptSourceResolver(inventory, snapshot);
		if (!resolver.resolve(capture.targetPath()).equals(selected) || !capture.source().equals(selected))
			throw new IOException("Inspected source differs from the initial Git inventory or retained bytes");
		List<PromptSource> sources = new ArrayList<>();
		sources.add(selected);
		Set<Path> knownPaths = new HashSet<>();
		knownPaths.add(selected.path());
		knownPaths.add(selected.resolvedSourcePath());
		for (RuntimePromptSource observation : observations)
		{
			Path path = observation.normalizedRelativePath();
			if (!knownPaths.add(path))
				continue;
			sources.add(resolver.resolve(path));
		}
		return List.copyOf(sources);
	}

	/**
	 * Reads canonical immutable JSON without consulting mutable campaign state.
	 *
	 * @param <T> evidence type
	 * @param reference content-addressed evidence
	 * @param type expected evidence type
	 * @return canonical record
	 * @throws IOException if the record is absent, malformed, or noncanonical
	 */
	private <T> T readCanonical(EvidenceRef reference, Class<T> type) throws IOException
	{
		byte[] bytes = store.readImmutableJson(reference);
		try
		{
			T value = JSON.readValue(bytes, type);
			if (value == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(value)))
				throw new IOException("Inspected source evidence is noncanonical: " + reference);
			return value;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Inspected source evidence is malformed: " + reference, exception);
		}
	}

	/**
	 * Reconstructs review fields from the initial captured source and adopted native completion.
	 *
	 * @param binding registered campaign
	 * @param inspectionResultRef adopted inspection result
	 * @return selected source and separate observation evidence
	 * @throws IOException if the adopted inspection or its source evidence is invalid
	 */
	private InspectedSourceEvidence expected(CampaignBinding binding, EvidenceRef inspectionResultRef)
		throws IOException
	{
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(inspectionResultRef, "inspectionResultRef");
		if (!binding.realDirectory().equals(store.directory()))
			throw new IOException("Inspected source evidence belongs to another campaign store");
		SelectedSourceEditProposal proposal = new InspectionCompletionService(locator, store).
			loadSelectedSourceProposal(binding, inspectionResultRef);
		PromptSource source = proposal.source();
		InitialTargetCapture capture = readCanonical(proposal.sourceCaptureRef(), InitialTargetCapture.class);
		List<PromptSource> sources = retainedSources(capture, source, proposal.promptPathObservations());
		return new InspectedSourceEvidence(InspectedSourceEvidence.SCHEMA_VERSION, binding.campaignId(),
			inspectionResultRef, source, sources, proposal.promptPathObservations(), proposal.promptSourceCoverage(),
			proposal.editableRoot());
	}
}
