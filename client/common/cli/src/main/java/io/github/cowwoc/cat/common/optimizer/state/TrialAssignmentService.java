package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.trial.TrialSampler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes development assignments under confirmed category choices.
 */
public final class TrialAssignmentService
{
	private final CampaignLocator locator;
	private final CampaignStore store;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Binds assignment to one registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign evidence store
	 */
	public TrialAssignmentService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or recovers one initial development assignment for a confirmed category.
	 *
	 * @param categoryId category chosen for the first development measurement
	 * @return immutable assignment reference committed into campaign trial state
	 * @throws IOException if campaign or assignment evidence is invalid
	 */
	public EvidenceRef assignInitialDevelopment(String categoryId) throws IOException
	{
		return assignInitialDevelopment(categoryId, 0);
	}

	/**
	 * Assigns a draw from an already published post-coverage category choice.
	 *
	 * @param decisionRef immutable category choice selected before this draw
	 * @param ordinal zero-based draw within the selected batch
	 * @return indexed assignment committed into the campaign
	 * @throws IOException if the choice or current campaign does not authorize the draw
	 */
	public EvidenceRef assignPostCoverageDevelopment(EvidenceRef decisionRef, int ordinal) throws IOException
	{
		Objects.requireNonNull(decisionRef, "decisionRef");
		if (!decisionRef.collection().equals("development-category-decisions") || ordinal < 0)
			throw new IllegalArgumentException("Post-coverage choice and nonnegative ordinal are required");
		CampaignStore.TrialEvidenceRead read = store.readTrialEvidence();
		Campaign current = read.campaign();
		CampaignBinding binding = new CampaignBinding(current.campaignId(), store.directory());
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		DevelopmentCategoryDecision decision = new DevelopmentCategoryDecisionService(locator, store).
			read(decisionRef, current);
		if (current.status() != CampaignStatus.DEVELOPING || current.measurementState() == null ||
			current.measurementState().pause().isPresent() ||
			!claim.campaignId().equals(current.campaignId()) ||
			!claim.lineageId().equals(current.activeLineageId()) ||
			!decision.lineage().equals(new LineageKey(claim.lineageId(), claim.contractId())) ||
			!decision.candidateId().equals(current.currentCandidateId()) ||
			!decision.measurementEpochRef().equals(current.measurementState().epochRef()) ||
			ordinal >= decision.allocationPolicy().batchSize())
			throw new IOException("Post-coverage draw conflicts with the current campaign or selected batch");
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
		String key = postCoverageDevelopmentKey(decision, decisionRef, ordinal);
		String logicalTrialId = "trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
		Optional<LineageEvidenceRef> indexed = store.findCreateOnceIndex("trial-assignment-index", key,
			decision.lineage(), current);
		if (indexed.isPresent())
			return commit(binding, indexed.orElseThrow().evidence());
		for (TrialState trial : current.trials())
		{
			if (!trial.logicalTrialId().equals(logicalTrialId))
				continue;
			TrialAssignment retained = store.readTrialAssignment(trial.selectionEvidence());
			if (!retained.categoryDecisionRef().equals(decisionRef) || retained.ordinal() != ordinal ||
				!retained.lineage().equals(decision.lineage()) ||
				!retained.candidateId().equals(decision.candidateId()) ||
				!retained.measurementEpochRef().equals(decision.measurementEpochRef()))
				throw new IOException("Retained post-coverage draw conflicts with its assignment index");
			store.appendCreateOnceIndex("trial-assignment-index", key,
				new LineageEvidenceRef(decision.lineage(), trial.selectionEvidence()));
			return commit(binding, trial.selectionEvidence());
		}
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).groupTrialEvidence(read);
		DevelopmentWeaknessGuard.requireNoClearlyWeakCategory(evidence, contract);
		new DevelopmentReviewService(store).retainDueAndRequireEligible(evidence, contract);
		TrialAssignment assignment = createPostCoverageAssignment(decision, decisionRef, ordinal, logicalTrialId,
			new TrialSampler(contract), Clock.systemUTC());
		EvidenceRef reference = retainAssignment(assignment);
		try
		{
			store.appendCreateOnceIndex("trial-assignment-index", key,
				new LineageEvidenceRef(decision.lineage(), reference));
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex("trial-assignment-index", key,
				decision.lineage(), current);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		return commit(binding, reference);
	}

	/**
	 * Samples one instance of the chosen category and binds it to the already authorized choice.
	 *
	 * @param decision validated post-coverage choice
	 * @param decisionRef indexed choice identity
	 * @param ordinal already validated draw ordinal within the batch
	 * @param logicalTrialId stable trial identity computed from the assignment index key
	 * @param sampler confirmed contract sampler
	 * @param clock current time in production or controlled time in tests
	 * @return assignment ready for canonical persistence and commit
	 */
	static TrialAssignment createPostCoverageAssignment(DevelopmentCategoryDecision decision, EvidenceRef decisionRef,
		int ordinal, String logicalTrialId, TrialSampler sampler, Clock clock)
	{
		return new TrialAssignment(TrialAssignment.SCHEMA_VERSION, decision.campaignId(), decision.lineage(),
			decision.measurementEpochRef(), decisionRef, logicalTrialId, ordinal, decision.candidateId(),
			sampler.sample(EvidencePurpose.DEVELOPMENT, decision.categoryId()), clock.instant());
	}

	/**
	 * Writes one assignment record before its owning caller publishes the index and commits the draw.
	 *
	 * @param assignment constructed draw whose authority the owning caller validates
	 * @return immutable canonical assignment identity
	 * @throws IOException if the assignment cannot be retained
	 */
	EvidenceRef retainAssignment(TrialAssignment assignment) throws IOException
	{
		return store.appendImmutableJson("trial-assignments", JSON.writeValueAsBytes(assignment));
	}

	/**
	 * Names one post-coverage draw under its exact published choice.
	 *
	 * @param decision published category choice
	 * @param decisionRef immutable choice reference
	 * @param ordinal zero-based trial within that batch
	 * @return stable create-once assignment key
	 */
	static String postCoverageDevelopmentKey(DevelopmentCategoryDecision decision, EvidenceRef decisionRef, int ordinal)
	{
		Objects.requireNonNull(decision, "decision");
		if (ordinal >= decision.allocationPolicy().batchSize())
			throw new IllegalArgumentException("Post-coverage assignment key has an invalid choice or ordinal");
		return postCoverageDevelopmentKey(decision.lineage(), decision.measurementEpochRef(), decisionRef, ordinal);
	}

	/**
	 * Reconstructs an assignment key from its retained choice identity.
	 *
	 * @param lineage confirmed lineage
	 * @param epochRef measurement identity
	 * @param decisionRef published development choice
	 * @param ordinal zero-based trial within that batch
	 * @return stable create-once assignment key
	 */
	static String postCoverageDevelopmentKey(LineageKey lineage, EvidenceRef epochRef, EvidenceRef decisionRef,
		int ordinal)
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(epochRef, "epochRef");
		Objects.requireNonNull(decisionRef, "decisionRef");
		if (!epochRef.collection().equals("measurement-epochs") ||
			!decisionRef.collection().equals("development-category-decisions") || ordinal < 0)
			throw new IllegalArgumentException("Post-coverage assignment key has an invalid choice or ordinal");
		return "post-coverage-development-v1:" + lineage.lineageId() + ':' + epochRef.evidenceId() + ':' +
			decisionRef.evidenceId() + ':' + ordinal;
	}

	/**
	 * Publishes or recovers one numbered development assignment for the initial candidate.
	 *
	 * @param categoryId confirmed category
	 * @param ordinal zero-based trial number within the category
	 * @return retained assignment reference
	 * @throws IOException if the assignment cannot be retained
	 */
	public EvidenceRef assignInitialDevelopment(String categoryId, int ordinal) throws IOException
	{
		if (categoryId == null || categoryId.isBlank())
			throw new IllegalArgumentException("Category id must be nonblank");
		if (ordinal < 0)
			throw new IllegalArgumentException("Trial ordinal must be nonnegative");
		CampaignStore.TrialEvidenceRead read = store.readTrialEvidence();
		Campaign current = read.campaign();
		CampaignBinding binding = new CampaignBinding(current.campaignId(), store.directory());
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		if (!claim.campaignId().equals(binding.campaignId()) ||
			!claim.lineageId().equals(current.activeLineageId()) ||
			current.status() != CampaignStatus.DEVELOPING ||
			current.measurementState() == null || current.measurementState().pause().isPresent())
			throw new IOException("Development assignment requires the current unpaused confirmed campaign");
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
		if (contract.tests().stream().noneMatch(test -> test.categoryId().equals(categoryId)))
			throw new IllegalArgumentException("Unknown confirmed category: " + categoryId);
		locator.verifyInitialFork(binding);
		LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
		EvidenceRef measurementEpochRef = current.measurementState().epochRef();
		String key = initialDevelopmentKey(lineage, measurementEpochRef, categoryId, ordinal);
		String logicalTrialId = "trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
		Optional<LineageEvidenceRef> indexed = store.findCreateOnceIndex("trial-assignment-index", key,
			lineage);
		if (indexed.isPresent())
		{
			verifyCurrentInitialCandidate(current);
			return commit(binding, indexed.orElseThrow().evidence());
		}

		for (TrialState trial : current.trials())
		{
			if (!trial.logicalTrialId().equals(logicalTrialId))
				continue;
			TrialAssignment retained = store.readTrialAssignment(trial.selectionEvidence());
			if (!retained.campaignId().equals(binding.campaignId()) ||
				!retained.lineage().equals(lineage) ||
				!retained.measurementEpochRef().equals(measurementEpochRef) ||
				!retained.candidateId().equals(current.currentCandidateId()) ||
				!retained.logicalTrialId().equals(logicalTrialId) ||
				retained.ordinal() != ordinal ||
				!retained.instance().definition().categoryId().equals(categoryId))
				throw new IOException("Retained initial trial conflicts with its assignment index");
			verifyCurrentInitialCandidate(current);
			store.appendCreateOnceIndex("trial-assignment-index", key,
				new LineageEvidenceRef(lineage, trial.selectionEvidence()));
			return commit(binding, trial.selectionEvidence());
		}

		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).groupTrialEvidence(read);
		DevelopmentWeaknessGuard.requireNoClearlyWeakCategory(evidence, contract);
		new DevelopmentReviewService(store).retainDueAndRequireEligible(evidence, contract);
		EvidenceRef decisionRef = new InitialCategoryDecisionService(locator, store).requirePublished();
		InitialCategoryDecision decision = store.readInitialCategoryDecision(decisionRef);
		if (!decision.candidateId().equals(current.currentCandidateId()))
			throw new IOException("Development assignment requires the current initial candidate");
		if (!decision.categoryId().equals(categoryId))
			throw new IOException("Requested category differs from the published category decision");
		if (ordinal >= decision.allocationPolicy().batchSize())
			throw new IOException("Trial ordinal exceeds the published category decision batch");
		TrialAssignment assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
			binding.campaignId(), lineage, measurementEpochRef, decisionRef, logicalTrialId, ordinal,
			decision.candidateId(),
			new TrialSampler(contract).sample(EvidencePurpose.DEVELOPMENT, categoryId), Instant.now());
		EvidenceRef reference = retainAssignment(assignment);
		try
		{
			store.appendCreateOnceIndex("trial-assignment-index", key,
				new LineageEvidenceRef(lineage, reference));
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex("trial-assignment-index", key,
				lineage);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		return commit(binding, reference);
	}

	/**
	 * Revalidates source authority before reusing a retained initial-candidate draw.
	 *
	 * @param campaign campaign that contains the retained draw
	 * @throws IOException if the current initial candidate or its source capture is invalid
	 */
	private void verifyCurrentInitialCandidate(Campaign campaign) throws IOException
	{
		String candidateId = new InitialCandidateService(locator, store).load().candidateSnapshotId();
		if (!candidateId.equals(campaign.currentCandidateId()))
			throw new IOException("Development assignment requires the current initial candidate");
	}

	/**
	 * Names one development assignment for a lineage, measurement epoch, category, and ordinal.
	 *
	 * @param lineage confirmed lineage
	 * @param measurementEpochRef active measurement identity
	 * @param categoryId confirmed category
	 * @param ordinal zero-based trial number within the category
	 * @return stable create-once key
	 */
	static String initialDevelopmentKey(LineageKey lineage, EvidenceRef measurementEpochRef,
		String categoryId, int ordinal)
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(measurementEpochRef, "measurementEpochRef");
		if (!measurementEpochRef.collection().equals("measurement-epochs") ||
			categoryId == null || categoryId.isBlank() || ordinal < 0)
			throw new IllegalArgumentException("Assignment key requires an epoch, category, and ordinal");
		return "initial-development-v3:" + lineage.lineageId() + ":" +
			measurementEpochRef.evidenceId() + ":" + categoryId + ":" + ordinal;
	}

	/**
	 * Commits a retained indexed draw to the campaign before any native call exists.
	 *
	 * @param binding exact pinned campaign
	 * @param reference indexed immutable assignment
	 * @return retained assignment reference
	 * @throws IOException if the assignment conflicts with the current campaign
	 */
	private EvidenceRef commit(CampaignBinding binding, EvidenceRef reference) throws IOException
	{
		new CampaignTransitions(store).assignDevelopmentTrial(binding, reference);
		return reference;
	}
}
