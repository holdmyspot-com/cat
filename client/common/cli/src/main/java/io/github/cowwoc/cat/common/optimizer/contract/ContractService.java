package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.loading.LoadedPromptContext;
import io.github.cowwoc.cat.common.optimizer.loading.InspectedSourceEvidence;
import io.github.cowwoc.cat.common.optimizer.loading.InspectedSourceEvidenceService;
import io.github.cowwoc.cat.common.optimizer.loading.PromptContentEvidenceReader;
import io.github.cowwoc.cat.common.optimizer.loading.PromptLoadingEvidence;
import io.github.cowwoc.cat.common.optimizer.loading.PromptLoadingEvidenceValidator;
import io.github.cowwoc.cat.common.optimizer.loading.PromptLoadingRecord;
import io.github.cowwoc.cat.common.optimizer.loading.PromptSourceBinding;
import io.github.cowwoc.cat.common.optimizer.loading.ValidatedPromptLoading;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceAuthorityService;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.TransitionRequest;
import io.github.cowwoc.cat.common.optimizer.state.TransitionResult;
import io.github.cowwoc.cat.common.optimizer.state.TransitionKind;
import io.github.cowwoc.cat.common.optimizer.state.UnavailableTransitionException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records immutable proposals; retired lifecycle signatures reject requests.
 */
public final class ContractService
{
	private final CampaignStore store;
	private final ContractProposalParser parser = new ContractProposalParser();
	private final JsonMapper json = ContractJson.mapper();

	/**
	 * Creates a service using the C1 campaign-store boundary.
	 *
	 * @param store durable C1 campaign store
	 */
	public ContractService(CampaignStore store)
	{
		if (store == null)
			throw new NullPointerException("store");
		this.store = store;
	}

	/**
	 * Parses and persists a proposal bound to already validated prompt loading.
	 *
	 * @param proposalJson reviewed proposal JSON
	 * @param loading validated production loading
	 * @return persisted proposal provenance
	 * @throws IOException if validation or persistence fails
	 */
	public StoredProposal recordProposal(byte[] proposalJson, ValidatedPromptLoading loading) throws IOException
	{
		ContractProposal proposal = parser.parse(proposalJson);
		if (!proposal.loadingEvidenceId().equals(loading.loadingEvidenceId()) ||
			!proposal.promptSystem().targetPath().equals(loading.targetPath()) ||
			!proposal.promptSystem().sources().equals(loading.sources()))
			throw new IOException("Proposal prompt sources do not exactly match loading evidence");
		if (!proposal.proposalId().equals(proposalIdentity(proposal)))
			throw new IOException("Proposal id does not match canonical JSON SHA-256");
		PromptLoadingRecord loadingRecord = loadingRecord(loading);
		if (!loading.loadingEvidenceId().equals(loadingIdentity(loadingRecord)))
			throw new IOException("Loading evidence id does not match canonical JSON SHA-256");
		validateLoading(loadingRecord);
		store.appendImmutableJson("prompt-loading", canonical(unsigned(loadingRecord)));
		store.appendImmutableJson("proposals", canonical(unsigned(proposal)));
		return new StoredProposal(proposal, proposal.proposalId(), loading.loadingEvidenceId());
	}

	/**
	 * Records a proposal bound to the selected source of an adopted inspection.
	 *
	 * @param proposalJson complete reviewed proposal
	 * @param sourceEvidenceRef retained inspected-source evidence
	 * @return recorded proposal and source provenance
	 * @throws IOException if the proposal or adopted source evidence disagrees
	 */
	public StoredProposal recordInspectedProposal(byte[] proposalJson, EvidenceRef sourceEvidenceRef)
		throws IOException
	{
		ContractProposal proposal = parser.parse(proposalJson);
		InspectedSourceEvidence evidence = inspectedSources().load(store.binding(), sourceEvidenceRef);
		validateInspectedSources(proposal, evidence, sourceEvidenceRef.evidenceId());
		if (!proposal.proposalId().equals(proposalIdentity(proposal)))
			throw new IOException("Proposal id does not match canonical JSON SHA-256");
		store.appendImmutableJson("proposals", canonical(unsigned(proposal)));
		return loadProposal(proposal.proposalId());
	}

	/**
	 * Loads and revalidates all immutable proposal provenance.
	 *
	 * @param proposalId requested proposal identity
	 * @return reconstructed proposal provenance
	 * @throws IOException if immutable evidence is invalid
	 */
	public StoredProposal loadProposal(String proposalId) throws IOException
	{
		ContractValues.nonBlank(proposalId, "proposal id");
		ContractProposal proposal = proposal(store.readImmutableJson("proposals", proposalId), proposalId);
		if (!proposalId.equals(proposalIdentity(proposal)))
			throw new IOException("Persisted proposal id does not match canonical JSON SHA-256");
		byte[] evidenceBytes = store.readImmutableJson("prompt-loading", proposal.loadingEvidenceId());
		JsonNode evidenceJson = json.readTree(evidenceBytes);
		int evidenceSchema = evidenceJson.path("schemaVersion").intValue();
		if (evidenceSchema == PromptLoadingRecord.SCHEMA_VERSION)
		{
			PromptLoadingRecord record = loading(evidenceBytes, proposal.loadingEvidenceId());
			ValidatedPromptLoading loading = validateLoading(record);
			if (!proposal.loadingEvidenceId().equals(loading.loadingEvidenceId()))
				throw new IOException("Persisted loading evidence is not in canonical receipt order");
			if (!proposal.promptSystem().targetPath().equals(loading.targetPath()) ||
				!proposal.promptSystem().sources().equals(loading.sources()))
				throw new IOException("Persisted proposal prompt sources do not match loading evidence");
		}
		else if (evidenceSchema == InspectedSourceEvidence.SCHEMA_VERSION)
		{
			EvidenceRef reference = new EvidenceRef("prompt-loading", proposal.loadingEvidenceId());
			InspectedSourceEvidence evidence = inspectedSources().loadRetained(reference);
			validateInspectedSources(proposal, evidence, reference.evidenceId());
		}
		else
			throw new IOException("Persisted prompt loading schema is unsupported");
		return new StoredProposal(proposal, proposalId, proposal.loadingEvidenceId());
	}

	/**
	 * Checks that review neither changes the retained selected source nor omits an observed additional path.
	 *
	 * @param proposal reviewed proposal
	 * @param evidence adopted inspection evidence
	 * @param evidenceId immutable evidence identity named by the proposal
	 * @throws IOException if source identities or observed paths disagree
	 */
	private void validateInspectedSources(ContractProposal proposal, InspectedSourceEvidence evidence,
		String evidenceId) throws IOException
	{
		PromptSource selected = evidence.selectedSource();
		if (!proposal.loadingEvidenceId().equals(evidenceId) ||
			!proposal.promptSystem().targetPath().equals(selected.path()) ||
			!proposal.promptSystem().sources().equals(evidence.sources()))
			throw new IOException("Proposal prompt sources do not match adopted inspection evidence");
	}

	/**
	 * Opens the adopted-inspection source boundary for this campaign.
	 *
	 * @return source evidence service
	 */
	private InspectedSourceEvidenceService inspectedSources()
	{
		return new InspectedSourceEvidenceService(new CampaignLocator(store.directory().getParent()), store);
	}

	/**
	 * Persists the exact reviewed proposal as immutable confirmation input without activating a lineage.
	 *
	 * @param proposalId recorded proposal revision
	 * @param reviewedJson complete revision shown for confirmation
	 * @return capability bound to the persisted contract and campaign directory
	 * @throws IOException if the review differs from the recorded proposal or source evidence is invalid
	 */
	public ConfirmedContractActivation prepareConfirmation(String proposalId, byte[] reviewedJson)
		throws IOException
	{
		StoredProposal stored = loadProposal(proposalId);
		verifyAdoptedInspectedSources(stored.proposal());
		ContractProposal reviewed = parser.parse(reviewedJson);
		if (!stored.proposal().equals(reviewed))
			throw new IOException("Confirmation reviewed revision differs from the recorded proposal");
		ConfirmedContract pending = confirmed(stored.proposal(), "pending");
		String contractId = sha256(canonical(pending));
		EvidenceRef reference = store.appendImmutableJson("contracts", canonical(pending));
		if (!reference.evidenceId().equals(contractId))
			throw new IOException("Persisted confirmed contract has an unexpected identity");
		return new ConfirmedContractActivation(store.directory(), contractId);
	}

	/**
	 * Rechecks adoption before advancing a reviewed inspection-derived contract.
	 *
	 * @param proposal recorded proposal
	 * @throws IOException if its inspected source is no longer the adopted result
	 */
	private void verifyAdoptedInspectedSources(ContractProposal proposal) throws IOException
	{
		EvidenceRef reference = new EvidenceRef("prompt-loading", proposal.loadingEvidenceId());
		JsonNode evidenceJson = json.readTree(store.readImmutableJson(reference));
		if (evidenceJson.path("schemaVersion").intValue() != InspectedSourceEvidence.SCHEMA_VERSION)
			return;
		InspectedSourceEvidence retained = inspectedSources().loadRetained(reference);
		InspectedSourceEvidence adopted = inspectedSources().load(new CampaignBinding(retained.campaignId(),
			store.directory()), reference);
		validateInspectedSources(proposal, adopted, reference.evidenceId());
	}

	/**
	 * Reloads a prepared contract and its complete source proposal after restart.
	 *
	 * @param contractId immutable confirmed-contract identity
	 * @return revalidated confirmed contract
	 * @throws IOException if the contract, proposal, or retained source bytes disagree
	 */
	public ConfirmedContract loadConfirmedContract(String contractId) throws IOException
	{
		ContractValues.nonBlank(contractId, "contract id");
		ConfirmedContract persisted;
		try
		{
			persisted = json.readValue(store.readImmutableJson("contracts", contractId), ConfirmedContract.class);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Persisted confirmed contract is malformed", e);
		}
		if (!persisted.contractId().equals("pending") ||
			!contractId.equals(sha256(canonical(persisted))))
			throw new IOException("Persisted confirmed contract has an invalid canonical identity");
		ConfirmedContract identified = new ConfirmedContract(persisted.schemaVersion(), contractId,
			persisted.sourceProposalId(), persisted.promptSystem(), persisted.requirements(), persisted.categories(),
			persisted.tests(), persisted.developmentAllocationPolicy(), persisted.developmentReadiness(),
			persisted.developmentRoundPolicy(),
			persisted.formalProcedure(), persisted.evaluator(),
			persisted.limitations());
		StoredProposal source = loadProposal(identified.sourceProposalId());
		if (!identified.equals(confirmed(source.proposal(), contractId)))
			throw new IOException("Confirmed contract differs from its recorded source proposal");
		ContractValidator.validateConfirmed(identified);
		return identified;
	}

	/**
	 * Copies a complete proposal into the confirmation schema.
	 *
	 * @param proposal reviewed proposal
	 * @param contractId canonical or pending identity
	 * @return complete contract graph
	 */
	private ConfirmedContract confirmed(ContractProposal proposal, String contractId)
	{
		return new ConfirmedContract(ConfirmedContract.SCHEMA_VERSION, contractId, proposal.proposalId(),
			proposal.promptSystem(), proposal.requirements(), proposal.categories(), proposal.tests(),
			proposal.developmentAllocationPolicy(), proposal.developmentReadiness(),
			proposal.developmentRoundPolicy(), proposal.formalProcedure(),
			proposal.evaluator(),
			proposal.limitations());
	}

	/**
	 * Confirms the exact reviewed proposal through its registered campaign and retained claim index.
	 *
	 * @param expectedCounter expected campaign counter
	 * @param proposalId persisted proposal identity
	 * @param reviewedJson reviewed proposal JSON
	 * @return committed lineage and frozen contract, including on equal retry
	 * @throws IOException if the review, campaign predecessor, or claim publication is invalid
	 */
	public LineageActivation confirm(long expectedCounter, String proposalId, byte[] reviewedJson) throws IOException
	{
		ConfirmedContractActivation prepared = prepareConfirmation(proposalId, reviewedJson);
		TransitionRequest request = new TransitionRequest(store.binding(), expectedCounter,
			TransitionKind.CONFIRMED_CONTRACT, new EvidenceRef("contracts", prepared.contractId()));
		CampaignLocator locator = new CampaignLocator(store.directory().getParent());
		ConfirmedSourceAuthorityService sourceAuthorityService = new ConfirmedSourceAuthorityService(locator, store);
		if (store.load().transitionCounter() == expectedCounter)
			sourceAuthorityService.verifyBeforeConfirmation(prepared.contractId());
		TransitionResult result = locator.confirm(request);
		EvidenceRef sourceAuthority = sourceAuthorityService.publish(prepared.contractId());
		return new LineageActivation(loadConfirmedContract(prepared.contractId()), result.lineageId(),
			result.resultingCounter(), sourceAuthority);
	}

	/**
	 * Rejects the retired revision route before inspecting its arguments.
	 *
	 * @param expectedCounter expected campaign counter
	 * @param proposalId persisted proposal identity
	 * @param reviewedJson reviewed proposal JSON
	 * @return never returns
	 * @throws UnavailableTransitionException because C4 producer integration is unavailable
	 */
	public LineageActivation revise(long expectedCounter, String proposalId, byte[] reviewedJson) throws IOException
	{
		throw new UnavailableTransitionException(TransitionKind.REVISED_CONTRACT, "C4");
	}

	/**
	 * Rebuilds the durable binding record from the validated ordered graph.
	 *
	 * @param loading validated graph
	 * @return durable loading record
	 */
	private PromptLoadingRecord loadingRecord(ValidatedPromptLoading loading)
	{
		List<PromptSourceBinding> bindings = new ArrayList<>();
		for (int index = 0; index < loading.receipts().size(); index += 1)
		{
			bindings.add(new PromptSourceBinding(loading.receipts().get(index).sourceId(),
				loading.sources().get(index).path()));
		}
		return new PromptLoadingRecord(PromptLoadingRecord.SCHEMA_VERSION, loading.loadingEvidenceId(),
			loading.targetPath(), bindings, loading.receipts());
	}

	/**
	 * Revalidates durable loading and all retained source blobs.
	 *
	 * @param record durable record
	 * @return revalidated graph
	 * @throws IOException if a blob is invalid
	 */
	private ValidatedPromptLoading validateLoading(PromptLoadingRecord record) throws IOException
	{
		PromptContentEvidenceReader reader = id -> store.readImmutableBytes("prompt-source-content", id);
		return PromptLoadingEvidenceValidator.validate(new PromptLoadingEvidence(record.targetPath(),
			new LoadedPromptContext("", record.receipts()), record.bindings()), reader);
	}

	/**
	 * Restores a proposal's filename identity into its unsigned durable payload.
	 *
	 * @param bytes unsigned durable bytes
	 * @param id filename identity
	 * @return restored proposal
	 * @throws IOException if parsing fails
	 */
	private ContractProposal proposal(byte[] bytes, String id) throws IOException
	{
		ContractProposal raw = parser.parseStored(bytes);
		return new ContractProposal(raw.schemaVersion(), id, raw.loadingEvidenceId(), raw.promptSystem(),
			raw.requirements(), raw.categories(), raw.tests(), raw.developmentAllocationPolicy(),
			raw.developmentReadiness(), raw.developmentRoundPolicy(), raw.formalProcedure(),
			raw.evaluator(), raw.limitations());
	}

	/**
	 * Restores and verifies a durable loading record.
	 *
	 * @param bytes unsigned durable bytes
	 * @param id filename identity
	 * @return restored loading record
	 * @throws IOException if parsing or identity validation fails
	 */
	private PromptLoadingRecord loading(byte[] bytes, String id) throws IOException
	{
		try
		{
			PromptLoadingRecord raw = json.readValue(bytes, PromptLoadingRecord.class);
			PromptLoadingRecord record = new PromptLoadingRecord(raw.schemaVersion(), id, raw.targetPath(),
				raw.bindings(), raw.receipts());
			if (!id.equals(loadingIdentity(record)))
				throw new IOException("Persisted loading evidence id does not match canonical JSON SHA-256");
			return record;
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Invalid prompt loading record", e);
		}
	}

	/**
	 * Replaces a proposal identity with its canonical unsigned placeholder.
	 *
	 * @param value identified proposal
	 * @return unsigned proposal
	 */
	private ContractProposal unsigned(ContractProposal value)
	{
		return new ContractProposal(value.schemaVersion(), "pending", value.loadingEvidenceId(), value.promptSystem(),
			value.requirements(), value.categories(), value.tests(), value.developmentAllocationPolicy(),
			value.developmentReadiness(), value.developmentRoundPolicy(), value.formalProcedure(),
			value.evaluator(), value.limitations());
	}

	/**
	 * Replaces a loading identity with its canonical unsigned placeholder.
	 *
	 * @param value identified loading record
	 * @return unsigned loading record
	 */
	private PromptLoadingRecord unsigned(PromptLoadingRecord value)
	{
		return new PromptLoadingRecord(value.schemaVersion(), "pending", value.targetPath(), value.bindings(),
			value.receipts());
	}

	/**
	 * Computes a proposal canonical identity.
	 *
	 * @param value proposal to identify
	 * @return proposal identity
	 * @throws IOException if serialization fails
	 */
	private String proposalIdentity(ContractProposal value) throws IOException
	{
		return sha256(canonical(unsigned(value)));
	}

	/**
	 * Computes a loading canonical identity.
	 *
	 * @param value loading record to identify
	 * @return loading identity
	 * @throws IOException if serialization fails
	 */
	private String loadingIdentity(PromptLoadingRecord value) throws IOException
	{
		return sha256(canonical(unsigned(value)));
	}

	/**
	 * Serializes canonical JSON bytes.
	 *
	 * @param value value to serialize
	 * @return canonical bytes
	 * @throws IOException if serialization fails
	 */
	private byte[] canonical(Object value) throws IOException
	{
		return json.writeValueAsBytes(value);
	}

	/**
	 * Computes a SHA-256 content identity.
	 *
	 * @param content content bytes
	 * @return lowercase SHA-256 identity
	 * @throws IOException if SHA-256 is unavailable
	 */
	private String sha256(byte[] content) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}
}
