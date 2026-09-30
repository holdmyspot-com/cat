package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidate;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractProposal;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResultSchema;
import io.github.cowwoc.cat.common.optimizer.runner.Adapter;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCall;
import io.github.cowwoc.cat.common.optimizer.runner.AdapterCallReconciliation;
import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunException;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.trial.SampledTrialInstance;
import io.github.cowwoc.cat.common.optimizer.trial.TrialSampler;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the initial candidate's development assignments at the registered campaign boundary.
 */
public final class TrialAssignmentServiceTest
{
	/** Creates the test fixture. */
	public TrialAssignmentServiceTest()
	{
	}

	/**
	 * A confirmed candidate cannot be assigned before the campaign publishes its first category decision.
	 *
	 * @throws IOException if fixture persistence fails or the category decision is absent
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision.*")
	public void rejectsAssignmentWithoutCategoryDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("missing-category-decision-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();

			new TrialAssignmentService(locator, store).assignInitialDevelopment("first");
		}
	}

	/**
	 * A direct campaign transition also refuses an indexed draw without a published category choice.
	 *
	 * @throws IOException if fixture persistence fails or the category decision is absent
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision.*")
	public void transitionRejectsMissingCategoryDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("transition-missing-category-decision-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(store.binding());
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			String key = TrialAssignmentService.initialDevelopmentKey(lineage, epochRef, "first", 0);
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			TrialAssignment assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				store.binding().campaignId(), lineage, epochRef,
				new EvidenceRef("category-decisions", "f".repeat(64)),
				"trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), 0,
				initial.candidateSnapshotId(), new TrialSampler(contract).sample(EvidencePurpose.DEVELOPMENT, "first"),
				Instant.now());
			EvidenceRef reference = store.appendImmutableJson("trial-assignments",
				JsonMapper.builder().build().writeValueAsBytes(assignment));
			store.appendCreateOnceIndex("trial-assignment-index", key, new LineageEvidenceRef(lineage, reference));

			new CampaignTransitions(store).assignDevelopmentTrial(store.binding(), reference);
		}
	}

	/**
	 * The first confirmed category choice survives a cold store reopening before any draw is made.
	 *
	 * @throws IOException if confirmed evidence cannot be retained or reopened
	 */
	@Test
	public void recoversPublishedFirstCategoryDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("first-category-decision-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();

			EvidenceRef firstRef = new InitialCategoryDecisionService(locator, store).publish();
			InitialCategoryDecision first = store.readInitialCategoryDecision(firstRef);
			requireThat(first.categoryId(), "firstConfirmedCategory").isEqualTo("first");
			requireThat(first.candidateId(), "selectedCandidate").isEqualTo(initial.candidateSnapshotId());
			requireThat(first.measurementEpochRef(), "selectedEpoch").isEqualTo(
				store.load().measurementState().epochRef());
			requireThat(first.sourceEvidenceRef().evidenceId(), "confirmedSourceEvidence").isEqualTo(
				fixture.proposal().loadingEvidenceId());
			requireThat(first.eligibleCategories(), "initialEligibleCategories").isEqualTo(List.of("first", "second"));
			requireThat(first.stoppedCategories(), "initialStoppedCategories").isEmpty();
			requireThat(store.load().trials().size(), "drawsBeforeDecision").isEqualTo(0);

			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef recovered = new InitialCategoryDecisionService(locator, reopened).publish();
			requireThat(recovered, "samePublishedDecision").isEqualTo(firstRef);
			requireThat(new InitialCategoryDecisionService(locator, reopened).requirePublished(),
				"sameRequiredDecision").isEqualTo(firstRef);
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, reopened).
				assignInitialDevelopment("first");
			requireThat(reopened.readTrialAssignment(assignmentRef).categoryDecisionRef(),
				"choiceUsedAfterReopening").isEqualTo(firstRef);
		}
	}

	/**
	 * A cold campaign read rejects a representative assignment whose embedded trial claims validation purpose.
	 *
	 * @throws IOException if fixture persistence fails or the changed purpose is rejected
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*purpose.*")
	public void rejectsChangedTrialPurposeOnColdRead() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("changed-trial-purpose-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			new TrialAssignmentService(locator, store).assignInitialDevelopment("first", 0);
			Path campaignFile = store.directory().resolve("campaign.json");
			JsonMapper json = JsonMapper.builder().build();
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			((ObjectNode) document.get("trials").get(0)).put("purpose", "VALIDATION");
			Files.write(campaignFile, json.writeValueAsBytes(document));

			CampaignStore.open(store.directory()).load();
		}
	}

	/**
	 * A counted revision preserves its exact outcome prefix across retry and a cold store read.
	 *
	 * @throws IOException if campaign or revision evidence cannot be retained or read
	 */
	@Test
	public void preservesCountedOutcomeRevisionPrefix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("counted-revision-prefix-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			CampaignBinding binding = store.binding();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			EvidenceRef decisionRef = new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef firstAssignment = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 0);
			Campaign counted = countBatchOutcomes(store, store.load(), binding, lineage, epochRef,
				decisionRef, firstAssignment, 2, 0);
			JsonMapper json = JsonMapper.builder().build();
			Files.write(store.directory().resolve("campaign.json"),
				json.writerWithDefaultPrettyPrinter().writeValueAsBytes(counted));

			CampaignStore reopened = CampaignStore.open(store.directory());
			CampaignStore.TrialEvidenceRead read = reopened.readTrialEvidence();
			requireThat(read.assignments().size(), "validatedAssignmentRecords").isEqualTo(2);
			requireThat(read.outcomes().size(), "validatedOutcomeRecords").isEqualTo(2);
			DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(reopened).
				groupTrialEvidence(read);
			CampaignStore other = new CampaignStore(Files.createDirectory(root.resolve("other-campaign")));
			IOException foreignRead = Assert.expectThrows(IOException.class, () ->
				new DevelopmentEvidenceService(other).groupTrialEvidence(read));
			requireThat(foreignRead.getMessage(), "foreignReadOwner").contains("different campaign store");
			DevelopmentEvidenceRevisionService revisions = new DevelopmentEvidenceRevisionService(reopened);
			EvidenceRef revisionRef = revisions.publish(evidence);
			DevelopmentEvidenceRevision revision = revisions.read(revisionRef, evidence.campaign());
			requireThat(revision.outcomeRefs().size(), "countedRevisionOutcomes").isEqualTo(2);
			requireThat(revision.sourceCounter(), "revisionCounter").isEqualTo(counted.transitionCounter());
			requireThat(revisions.publish(evidence), "equalRevisionRetry").isEqualTo(revisionRef);
			DevelopmentEvidenceRevision earlier = new DevelopmentEvidenceRevision(
				DevelopmentEvidenceRevision.SCHEMA_VERSION, revision.campaignId(), revision.lineage(),
				revision.candidateId(), revision.measurementEpochRef(), revision.sourceCounter() - 1,
				revision.outcomeRefs().subList(0, 1));
			EvidenceRef earlierRef = reopened.appendImmutableJson("development-evidence-revisions",
				json.writeValueAsBytes(earlier));
			requireThat(revisions.read(earlierRef, evidence.campaign()), "historicalCountedPrefix").isEqualTo(earlier);
			DevelopmentEvidenceRevision omitted = new DevelopmentEvidenceRevision(
				DevelopmentEvidenceRevision.SCHEMA_VERSION, revision.campaignId(), revision.lineage(),
				revision.candidateId(), revision.measurementEpochRef(), revision.sourceCounter(),
				revision.outcomeRefs().subList(1, 2));
			EvidenceRef omittedRef = reopened.appendImmutableJson("development-evidence-revisions",
				json.writeValueAsBytes(omitted));
			Assert.expectThrows(IOException.class, () -> revisions.read(omittedRef, evidence.campaign()));
			requireThat(new DevelopmentEvidenceRevisionService(CampaignStore.open(store.directory())).read(revisionRef),
				"coldRevisionRead").isEqualTo(revision);
			EvidenceRef outcomeRef = revision.outcomeRefs().getFirst();
			Path outcomeFile = reopened.directory().resolve(outcomeRef.collection()).
				resolve(outcomeRef.evidenceId() + ".json");
			byte[] retainedOutcome = Files.readAllBytes(outcomeFile);
			Files.writeString(outcomeFile, "corrupt outcome");
			try
			{
				Assert.expectThrows(IOException.class, () -> new DevelopmentEvidenceService(reopened).readCurrent());
				Assert.expectThrows(IOException.class, () -> new DevelopmentEvidenceService(reopened).
					readCurrent(evidence.campaign()));
			}
			finally
			{
				Files.write(outcomeFile, retainedOutcome);
			}
			requireThat(new DevelopmentEvidenceService(reopened).readCurrent().categorySeries(50).
				get("first").total(), "restoredOutcomeRead").isEqualTo(new CategoryCounts(2, 2));
		}
	}


	/**
	 * An adaptive batch returns to coverage; an answered review then permits more sampling until the next checkpoint.
	 *
	 * @throws IOException if the review campaign or its outcome evidence cannot be retained
	 */
	@Test
	public void extendsReviewAndBlocksClaimedCall() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("short-review-checkpoint-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			ContractProposal proposal = ContractTestFixtures.withReviewCheckpoint(fixture.proposal(), 1);
			fixture.service().recordProposal(ContractTestFixtures.bytes(proposal), fixture.loading());
			fixture.service().confirm(0, proposal.proposalId(), ContractTestFixtures.bytes(proposal));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			CampaignBinding binding = store.binding();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = store.directory().resolve("campaign.json");

			EvidenceRef firstDecision = new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef firstAssignment = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 0);
			Campaign oneOutcome = countBatchOutcomes(store, store.load(), binding, lineage, epochRef,
				firstDecision, firstAssignment, 1, 0);
			Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(oneOutcome));
			CampaignStore afterFirst = CampaignStore.open(store.directory());
			IOException firstStop = Assert.expectThrows(IOException.class, () ->
				new TrialAssignmentService(locator, afterFirst).assignInitialDevelopment("first", 1));
			requireThat(firstStop.getMessage(), "firstReviewStop").contains("review");
			DevelopmentReviewService reviews = new DevelopmentReviewService(afterFirst);
			EvidenceRef firstRequestRef = reviews.pending("first").orElseThrow();
			DevelopmentReviewRequest firstRequest = reviews.read(firstRequestRef);
			requireThat(firstRequest.totalOutcomes(), "firstReviewTotal").isEqualTo(1);
			requireThat(firstRequest.reviewPoint(), "firstReviewPoint").isEqualTo(1);
			requireThat(firstRequest.compliantOutcomes(), "firstReviewCompliantOutcomes").isEqualTo(1);
			requireThat(firstRequest.choices(), "firstReviewChoices").isEqualTo(List.of(
				DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE,
				DevelopmentReviewRequest.Choice.TARGETED_OPTIMIZATION,
				DevelopmentReviewRequest.Choice.STOP));
			requireThat(new DevelopmentReviewService(CampaignStore.open(store.directory())).pending(
				"first").orElseThrow(), "coldFirstReviewRetry").isEqualTo(firstRequestRef);
			try (Stream<Path> entries = Files.list(store.directory().resolve("development-review-index")))
			{
				requireThat(entries.count(), "oneRetainedReviewRequest").isEqualTo(1L);
			}
			EvidenceRef firstResponseRef = reviews.recordChoice(firstRequestRef,
				DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE, 2);
			DevelopmentReviewResponse firstResponse = new DevelopmentReviewService(CampaignStore.open(
				store.directory())).readResponse(firstResponseRef);
			requireThat(firstResponse.requestRef(), "firstResponseRequest").isEqualTo(firstRequestRef);
			requireThat(firstResponse.choice(), "firstResponseChoice").isEqualTo(
				DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE);
			requireThat(firstResponse.nextReviewPoint(), "firstResponsePoint").isEqualTo(2);
			requireThat(new DevelopmentReviewService(CampaignStore.open(store.directory())).pending("first"),
				"answeredReviewIsNotPending").isEqualTo(Optional.empty());
			requireThat(new DevelopmentReviewService(CampaignStore.open(store.directory())).recordChoice(
				firstRequestRef, DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE, 2),
				"equalReviewResponseRetry").isEqualTo(firstResponseRef);
			Assert.expectThrows(IOException.class, () -> reviews.recordChoice(firstRequestRef,
				DevelopmentReviewRequest.Choice.STOP, 0));
			Assert.expectThrows(IllegalArgumentException.class, () -> reviews.recordChoice(firstRequestRef,
				DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE, 1));
			EvidenceRef secondAssignment = new TrialAssignmentService(locator, afterFirst).
				assignInitialDevelopment("first", 1);
			Campaign twoOutcomes = countAssignedOutcome(afterFirst, afterFirst.load(), binding,
				secondAssignment, afterFirst.readTrialAssignment(secondAssignment), EvaluatorLabel.COMPLIANT, json);
			Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(twoOutcomes));

			CampaignStore afterSecond = CampaignStore.open(store.directory());
			IOException secondStop = Assert.expectThrows(IOException.class, () ->
				new TrialAssignmentService(locator, afterSecond).assignInitialDevelopment("first", 2));
			requireThat(secondStop.getMessage(), "secondReviewStop").contains("review");
			DevelopmentReviewService laterReviews = new DevelopmentReviewService(afterSecond);
			EvidenceRef secondRequestRef = laterReviews.pending("first").orElseThrow();
			DevelopmentReviewRequest secondRequest = laterReviews.read(secondRequestRef);
			requireThat(secondRequest.totalOutcomes(), "secondReviewTotal").isEqualTo(2);
			requireThat(secondRequest.reviewPoint(), "secondReviewPoint").isEqualTo(2);
			requireThat(secondRequest.precedingResponseRef(), "secondReviewPredecessor").
				isEqualTo(Optional.of(firstResponseRef));
			requireThat(new DevelopmentReviewService(CampaignStore.open(store.directory())).pending(
				"first").orElseThrow(), "coldExtendedReviewRetry").isEqualTo(secondRequestRef);
			EvidenceRef secondResponseRef = laterReviews.recordChoice(secondRequestRef,
				DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE, 3);
			requireThat(new DevelopmentReviewService(CampaignStore.open(store.directory())).readResponse(
				secondResponseRef).nextReviewPoint(), "secondResponsePoint").isEqualTo(3);
			TrialAssignmentService assignments = new TrialAssignmentService(locator, afterSecond);
			EvidenceRef thirdAssignment = assignments.assignInitialDevelopment("first", 2);
			EvidenceRef retainedAfterPoint = assignments.assignInitialDevelopment("first", 3);
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "short-review-admission");
			ObjectNode host = json.createObjectNode();
			host.put("schemaVersion", 1);
			host.put("tokenDigest", "7".repeat(64));
			host.put("hostSessionId", "short-review-session");
			host.put("hostTurnId", "short-review-turn");
			inbox.publishHostObservation(binding, "short-review-admission", registration,
				json.writeValueAsBytes(host));
			Path artifact = Files.createDirectory(root.resolve("short-review-artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("short-review-runs"), Clock.systemUTC());
			RecordingAdapter adapter = new RecordingAdapter();
			NestedAgent agent = new NestedAgent(adapter, afterSecond::readImmutableJson);
			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding,
				"short-review-admission"))
			{
				InitialTrialAdmission beforeReview = new InitialTrialAdmissionService(locator, afterSecond).
					admit(lease, retainedAfterPoint, artifact);
				requireThat(beforeReview instanceof InitialTrialAdmission.Claimed,
					"trialClaimedBeforeReview").isTrue();
				Campaign threeOutcomes = countAssignedOutcome(afterSecond, afterSecond.load(), binding,
					thirdAssignment, afterSecond.readTrialAssignment(thirdAssignment), EvaluatorLabel.COMPLIANT,
					json);
				Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(threeOutcomes));
				CampaignStore afterThird = CampaignStore.open(store.directory());
				IOException startFailure = Assert.expectThrows(IOException.class, () ->
					new InitialTrialCallCoordinator(locator, afterThird).
						startClaimed(lease, retainedAfterPoint, agent, manager, artifact));
				requireThat(startFailure.getMessage(), "reviewBlocksNativeStart").contains("review");
				requireThat(adapter.starts, "noNativeStartAfterReview").isEqualTo(0);
			}
			CampaignStore atReview = CampaignStore.open(store.directory());
			Assert.expectThrows(IOException.class, () -> new TrialAssignmentService(locator, atReview).
				assignInitialDevelopment("first", 4));
			requireThat(new TrialAssignmentService(locator, atReview).
				assignInitialDevelopment("first", 3), "retainedReviewDrawRetry").
				isEqualTo(retainedAfterPoint);
			DevelopmentReviewService finalReviews = new DevelopmentReviewService(atReview);
			EvidenceRef finalRequestRef = finalReviews.pending("first").orElseThrow();
			DevelopmentReviewRequest finalRequest = finalReviews.read(finalRequestRef);
			requireThat(finalRequest.reviewPoint(), "finalReviewPoint").isEqualTo(3);
			requireThat(finalRequest.precedingResponseRef(), "finalReviewPredecessor").
				isEqualTo(Optional.of(secondResponseRef));
			String bypassKey = TrialAssignmentService.initialDevelopmentKey(lineage, epochRef, "first", 4);
			TrialAssignment retainedDraw = atReview.readTrialAssignment(retainedAfterPoint);
			TrialAssignment bypass = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				binding.campaignId(), lineage, epochRef, firstDecision,
				"trial-" + UUID.nameUUIDFromBytes(bypassKey.getBytes(StandardCharsets.UTF_8)), 4,
				retainedDraw.candidateId(), retainedDraw.instance(), Instant.now());
			EvidenceRef bypassRef = atReview.appendImmutableJson("trial-assignments",
				json.writeValueAsBytes(bypass));
			atReview.appendCreateOnceIndex("trial-assignment-index", bypassKey,
				new LineageEvidenceRef(lineage, bypassRef));
			Assert.expectThrows(IOException.class, () -> new CampaignTransitions(atReview).
				assignDevelopmentTrial(binding, bypassRef));
			EvidenceRef afterPointRegistration = inbox.beginInvocation(binding, "short-review-after-point");
			host.put("tokenDigest", "8".repeat(64));
			host.put("hostSessionId", "short-review-after-session");
			host.put("hostTurnId", "short-review-after-turn");
			inbox.publishHostObservation(binding, "short-review-after-point", afterPointRegistration,
				json.writeValueAsBytes(host));
			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding,
				"short-review-after-point"))
			{
				IOException admissionFailure = Assert.expectThrows(IOException.class, () ->
					new InitialTrialAdmissionService(locator, atReview).
						admit(lease, retainedAfterPoint, artifact));
				requireThat(admissionFailure.getMessage(), "reviewBlocksAdmission").contains("review");
				requireThat(atReview.load().scheduler().slots().size(), "oneClaimBeforeReview").isEqualTo(1);
			EvidenceRef requestRef = new InitialTrialRequestService(locator, atReview).
					prepare(retainedAfterPoint);
				String logicalTrialId = atReview.readTrialAssignment(retainedAfterPoint).logicalTrialId();
				IOException claimFailure = Assert.expectThrows(IOException.class, () ->
					locator.openSchedulerLease(binding).claimOrResumeCall(lease, logicalTrialId,
						requestRef, Optional.empty(), Optional.empty()));
				requireThat(claimFailure.getMessage(), "reviewBlocksLockedClaim").contains("review");
				requireThat(atReview.load().scheduler().slots().size(), "noClaimAfterReview").isEqualTo(1);
			}
			try (Stream<Path> entries = Files.list(store.directory().resolve(
				"development-review-response-index")))
			{
				for (Path entry : entries.toList())
					Files.delete(entry);
			}
			Assert.expectThrows(IOException.class, () -> new DevelopmentReviewService(CampaignStore.open(
				store.directory())).read(finalRequestRef));
		}
	}

	/**
	 * Published coverage and adaptive choices retain their cursors and assignments across store reopenings.
	 *
	 * @throws IOException if campaign or representative evidence cannot be retained or read
	 */
	@Test
	public void retainsAdaptiveChoiceAfterReopen() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-coverage-resumption-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			CampaignBinding binding = store.binding();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = store.directory().resolve("campaign.json");

			EvidenceRef first = new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef firstDraw = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 0);
			Campaign counted = countRemainingBatch(store, store.load(), binding, lineage, epochRef,
				first, contract, firstDraw, 5);
			Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(counted));
			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef second = new InitialCategoryDecisionService(locator, reopened).publish();
			EvidenceRef secondDraw = new TrialAssignmentService(locator, reopened).
				assignInitialDevelopment("second", 0);
			counted = countRemainingBatch(reopened, reopened.load(), binding, lineage, epochRef,
				second, contract, secondDraw, 0);
			Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(counted));

			String[] expectedCategories = {"first", "first"};
			for (int batch = 0; batch < expectedCategories.length; ++batch)
			{
				CampaignStore current = CampaignStore.open(store.directory());
				DevelopmentCategoryDecisionService decisions = new DevelopmentCategoryDecisionService(locator,
					current);
				EvidenceRef decisionRef;
				if (batch == 0)
					decisionRef = decisions.publishFirst();
				else
					decisionRef = decisions.publishNext();
				DevelopmentCategoryDecision decision = decisions.read(decisionRef);
				requireThat(decision.categoryId(), "checkpointBatchCategory" + batch).
					isEqualTo(expectedCategories[batch]);
				if (batch == 1)
					requireThat(new DevelopmentCategoryDecisionService(locator,
						CampaignStore.open(store.directory())).publishNext(), "coldAdaptiveChoiceRetry").
						isEqualTo(decisionRef);
				if (batch == 1)
				{
					requireThat(decision.batchNumber(), "adaptiveBatchNumber").isEqualTo(3);
					requireThat(decision.position(), "adaptivePosition").isEqualTo(
						new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 1));
					requireThat(decision.nextPosition(), "retainedCoverageCursor").isEqualTo(
						new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.COVERAGE, 1));
				}
				EvidenceRef firstAssignment = new TrialAssignmentService(locator, current).
					assignPostCoverageDevelopment(decisionRef, 0);
				requireThat(new DevelopmentCategoryDecisionService(locator,
					CampaignStore.open(store.directory())).publishNext(), "coldChoiceRetryAfterDraw" + batch).
					isEqualTo(decisionRef);
				TrialAssignment retainedDraw = current.readTrialAssignment(firstAssignment);
				requireThat(retainedDraw.categoryDecisionRef(), "retainedDrawChoice" + batch).isEqualTo(decisionRef);
				requireThat(retainedDraw.instance().definition().categoryId(), "retainedDrawCategory" + batch).
					isEqualTo(decision.categoryId());
				if (batch == 1)
					break;
				counted = countRemainingBatch(current, current.load(), binding, lineage, epochRef,
					decisionRef, contract, firstAssignment, 1);
				Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(counted));
			}
		}
	}

	/**
	 * A retained review response authorizes a post-coverage choice and draw until its index is removed.
	 *
	 * @throws IOException if campaign or representative evidence cannot be retained or read
	 */
	@Test
	public void authorizesPostCoverageDrawAfterReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("post-coverage-review-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			ContractProposal proposal = ContractTestFixtures.withReviewCheckpoint(
				ContractTestFixtures.withFirstCategoryOnly(fixture.proposal()), 100);
			fixture.service().recordProposal(ContractTestFixtures.bytes(proposal), fixture.loading());
			fixture.service().confirm(0, proposal.proposalId(), ContractTestFixtures.bytes(proposal));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			CampaignBinding binding = store.binding();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef decisionRef = new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 0);
			Campaign counted = countRemainingBatch(store, store.load(), binding, lineage, epochRef,
				decisionRef, contract, assignmentRef, 1);
			Files.write(store.directory().resolve("campaign.json"),
				json.writerWithDefaultPrettyPrinter().writeValueAsBytes(counted));

			CampaignStore afterCoverage = CampaignStore.open(store.directory());
			EvidenceRef followupDecision = new DevelopmentCategoryDecisionService(locator, afterCoverage).
				publishFirst();
			EvidenceRef followupAssignment = new TrialAssignmentService(locator, afterCoverage).
				assignPostCoverageDevelopment(followupDecision, 0);
			counted = countRemainingBatch(afterCoverage, afterCoverage.load(), binding, lineage, epochRef,
				followupDecision, contract, followupAssignment, 1);
			Files.write(store.directory().resolve("campaign.json"),
				json.writerWithDefaultPrettyPrinter().writeValueAsBytes(counted));

			CampaignStore checkpoint = CampaignStore.open(store.directory());
			IOException stop = Assert.expectThrows(IOException.class,
				() -> new DevelopmentCategoryDecisionService(locator, checkpoint).publishNext());
			requireThat(stop.getMessage(), "checkpointStop").contains("review");
			DevelopmentReviewService reviews = new DevelopmentReviewService(CampaignStore.open(store.directory()));
			EvidenceRef pendingRef = reviews.pending("first").orElseThrow();
			DevelopmentReviewRequest pending = reviews.read(pendingRef);
			requireThat(pending.compliantOutcomes(), "reviewCompliantOutcomes").isEqualTo(98);
			requireThat(pending.totalOutcomes(), "reviewTotalOutcomes").isEqualTo(100);
			requireThat(pending.reviewPoint(), "confirmedReviewPoint").isEqualTo(100);
			EvidenceRef responseRef = reviews.recordChoice(pendingRef,
				DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE, 101);
			CampaignStore extendedStore = CampaignStore.open(store.directory());
			DevelopmentCategoryDecisionService extendedChoices = new DevelopmentCategoryDecisionService(locator,
				extendedStore);
			EvidenceRef extendedRef = extendedChoices.publishNext();
			requireThat(extendedChoices.read(extendedRef).categoryId(), "extendedCoverageCategory").
				isEqualTo("first");
			requireThat(json.readTree(extendedStore.readImmutableJson(extendedRef)).
				get("reviewAuthorizationRefs").toString(), "retainedReviewAuthority").
				contains(responseRef.evidenceId());
			EvidenceRef extendedDraw = new TrialAssignmentService(locator, extendedStore).
				assignPostCoverageDevelopment(extendedRef, 0);
			requireThat(extendedStore.readTrialAssignment(extendedDraw).categoryDecisionRef(),
				"extendedDrawAuthority").isEqualTo(extendedRef);
			try (Stream<Path> entries = Files.list(store.directory().resolve(
				"development-review-response-index")))
			{
				for (Path entry : entries.toList())
					Files.delete(entry);
			}
			Assert.expectThrows(IOException.class, () -> new DevelopmentCategoryDecisionService(locator,
				CampaignStore.open(store.directory())).read(extendedRef));
		}
	}

	/**
	 * A clearly weak first category stops draws, starts a retained round, and rejects stale publication.
	 * Its native authoring request carries the confirmed requirements, exact editable paths, edit restrictions,
	 * and counted development evidence.
	 *
	 * @throws IOException if counted campaign evidence cannot be retained or read
	 * @throws NestedRunException if the controlled generation call cannot start or close
	 */
	@Test
	public void startsRoundAfterWeakInitialCategory() throws IOException, NestedRunException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("weak-initial-category-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root, project ->
			{
				Files.writeString(project.resolve("auxiliary.md"), "private link target\n");
				Files.createSymbolicLink(project.resolve("linked.md"), Path.of("auxiliary.md"));
			});
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			EvidenceRef firstRef = new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef firstAssignment = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 0);
			IOException prematureRound = Assert.expectThrows(IOException.class,
				() -> new DevelopmentRoundService(locator, store).publishInitial());
			requireThat(prematureRound.getMessage(), "noWeakCategoryYet").contains("clearly weak");
			CampaignBinding binding = store.binding();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			Campaign beforeWeakOutcome = store.load();
			Campaign completed = countBatchOutcomes(store, store.load(), binding, lineage, epochRef,
				firstRef, firstAssignment, 1, 1);
			Files.write(store.directory().resolve("campaign.json"),
				JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(completed));
			CampaignStore reopened = CampaignStore.open(store.directory());

			IOException failure = Assert.expectThrows(IOException.class,
				() -> new InitialCategoryDecisionService(locator, reopened).publish());
			requireThat(failure.getMessage(), "weakCategoryStop").contains("weak");
			Assert.expectThrows(IOException.class, () -> new TrialAssignmentService(locator, reopened).
				assignInitialDevelopment("second", 0));
			requireThat(new TrialAssignmentService(locator, reopened).assignInitialDevelopment("first", 0),
				"retainedWeakAssignmentRetry").isEqualTo(firstAssignment);
			requireThat(new DevelopmentEvidenceService(reopened).readCurrent().categorySeries(50).
				get("first").total(), "retainedWeakOutcomes").isEqualTo(new CategoryCounts(0, 1));
			DevelopmentRoundService rounds = new DevelopmentRoundService(locator, reopened);
			EvidenceRef roundRef = rounds.publishInitial();
			IOException stalePublication = Assert.expectThrows(IOException.class, () ->
				reopened.appendCreateOnceIndexIfCurrent("development-round-start-index", "stale-round-probe",
					new LineageEvidenceRef(lineage, roundRef), beforeWeakOutcome));
			requireThat(stalePublication.getMessage(), "staleRoundPublication").contains("changed");
			DevelopmentRoundStart round = new DevelopmentRoundService(locator,
				CampaignStore.open(store.directory())).read(roundRef);
			requireThat(round.failingCategories(), "roundFailingCategories").isEqualTo(List.of("first"));
			requireThat(round.roundPolicy(), "roundLimits").isEqualTo(contract.developmentRoundPolicy());
			requireThat(round.comparisonPolicy(), "roundComparison").isEqualTo(
				contract.developmentReadiness().comparisonPolicy());
			requireThat(new DevelopmentRoundService(locator, CampaignStore.open(store.directory())).
				publishInitial(), "equalInitialRoundRetry").isEqualTo(roundRef);
			GenerationAttemptIntentService intents = new GenerationAttemptIntentService(locator, reopened);
			Path selected = locator.optimizationRoot(store.binding());
			Path unowned = selected.resolve("unowned.md");
			Files.writeString(unowned, "unowned change\n");
			IOException unownedIntent = Assert.expectThrows(IOException.class, () ->
				intents.publish(roundRef, "first", "generation-unowned"));
			requireThat(unownedIntent.getMessage(), "unownedGenerationIntent").contains("clean fork");
			Files.delete(unowned);
			Path baseProject = locator.readRegisteredKey(store.binding()).canonicalProjectRoot();
			Files.writeString(baseProject.resolve(".git/info/exclude"), "unowned.md\n");
			Files.writeString(unowned, "ignored unowned change\n");
			IOException ignoredIntent = Assert.expectThrows(IOException.class, () ->
				intents.publish(roundRef, "first", "generation-ignored"));
			requireThat(ignoredIntent.getMessage(), "ignoredGenerationIntent").contains("unowned paths");
			Files.delete(unowned);
			EvidenceRef intentRef = intents.publish(roundRef, "first", "generation-1");
			GenerationAttemptIntent intent = new GenerationAttemptIntentService(locator,
				CampaignStore.open(store.directory())).read(intentRef);
			requireThat(intent.roundRef(), "generationRound").isEqualTo(roundRef);
			requireThat(intent.categoryId(), "generationCategory").isEqualTo("first");
			requireThat(intent.incumbentCandidateId(), "generationIncumbent").isEqualTo(
				store.load().currentCandidateId());
			requireThat(intent.privateProject().equals(intent.selectedProject()), "privateAttemptIsSelected").
				isFalse();
			requireThat(Files.notExists(intent.privateProject()), "intentBeforeWorktree").isTrue();
			requireThat(intents.publish(roundRef, "first", "generation-1"), "equalGenerationIntentRetry").isEqualTo(
				intentRef);
			IOException excludedCategory = Assert.expectThrows(IOException.class, () ->
				intents.publish(roundRef, "second", "generation-1"));
			requireThat(excludedCategory.getMessage(), "excludedGenerationCategory").contains(
				"does not require an attempt");
			requireThat(intents.requireCurrent(intentRef), "currentGenerationIntent").isEqualTo(intent);
			cleanup.add(intent.privateProject());
			GenerationAttemptWorktreeService worktrees = new GenerationAttemptWorktreeService(locator, reopened);
			EvidenceRef readyRef = worktrees.prepare(intentRef);
			GenerationAttemptReadiness ready = new GenerationAttemptWorktreeService(locator,
				CampaignStore.open(store.directory())).read(readyRef);
			requireThat(ready.intentRef(), "generationReadyIntent").isEqualTo(intentRef);
			requireThat(Files.isDirectory(intent.privateProject()), "generationPrivateWorktree").isTrue();
			requireThat(intent.privateProject().resolve("linked.md").toRealPath(), "privateLinkedTarget").
				isEqualTo(intent.privateProject().resolve("auxiliary.md"));
			requireThat(worktrees.prepare(intentRef), "equalGenerationWorktreeRetry").isEqualTo(readyRef);
			requireThat(intents.requireCurrent(intentRef), "selectedUnchangedByPrivatePreparation").isEqualTo(
				intent);
			EvidenceRef requestRef = new CandidateGenerationRequestService(locator, reopened).publish(readyRef);
			requireThat(requestRef.collection(), "generationRequestCollection").isEqualTo(
				"candidate-generation-requests");
			requireThat(new CandidateGenerationRequestService(locator,
				CampaignStore.open(store.directory())).publish(readyRef), "generationRequestRetry").isEqualTo(
				requestRef);
			CandidateGenerationRequest request = new CandidateGenerationRequestService(locator,
				CampaignStore.open(store.directory())).read(requestRef);
			requireThat(request.logicalCallId(), "generationRequestCall").isEqualTo(intent.logicalCallId());
			requireThat(request.categoryId(), "generationRequestCategory").isEqualTo("first");
			requireThat(request.incumbentCandidateId(), "generationRequestIncumbent").isEqualTo(
				intent.incumbentCandidateId());
			requireThat(request.sourceRevisionRef(), "generationRequestSource").isEqualTo(
				round.sourceRevisionRef());
			requireThat(request.confirmedRootsRef(), "generationRequestAuthority").isEqualTo(
				intent.confirmedRootsRef());
			requireThat(request.readinessRef(), "generationRequestReadiness").isEqualTo(readyRef);
			requireThat(request.privateProject(), "generationRequestPrivateProject").isEqualTo(
				intent.privateProject());
			PrivateGenerationContext context = new CandidateGenerationRequestService(locator, reopened).
				context(requestRef);
			requireThat(context.selectedProject(), "generationContextSelectedProject").isEqualTo(
				intent.selectedProject());
			requireThat(context.privateProject(), "generationContextPrivateProject").isEqualTo(
				intent.privateProject());
			requireThat(context.generationRequestRef(), "generationContextRequest").isEqualTo(requestRef);
			requireThat(context.readinessRef(), "generationContextReadiness").isEqualTo(readyRef);
			requireThat(context.confirmedRootsRef(), "generationContextAuthority").isEqualTo(
				intent.confirmedRootsRef());
			CandidateGenerationRunRequestService nativeRequests = new CandidateGenerationRunRequestService(locator,
				reopened);
			EvidenceRef nativeRequestRef = nativeRequests.prepare(requestRef);
			requireThat(nativeRequestRef.collection(), "generationNativeRequestCollection").isEqualTo(
				"run-requests");
			requireThat(new CandidateGenerationRunRequestService(locator,
				CampaignStore.open(store.directory())).prepare(requestRef), "generationNativeRequestRetry").isEqualTo(
				nativeRequestRef);
			CandidateGenerationRunRequest nativeRequest = new CandidateGenerationRunRequestService(locator,
				CampaignStore.open(store.directory())).read(nativeRequestRef);
			requireThat(nativeRequest.semanticRequestRef(), "generationNativeSemanticRequest").isEqualTo(
				requestRef);
			requireThat(nativeRequest.logicalCallId(), "generationNativeCall").isEqualTo(
				intent.logicalCallId());
			requireThat(nativeRequest.context(), "generationNativeContext").isEqualTo(context);
			requireThat(nativeRequest.modelProfile(), "generationNativeModel").isEqualTo(
				store.load().optimizationProfile());
			requireThat(nativeRequest.turn(), "generationNativeRequirement").contains("First obligation");
			requireThat(nativeRequest.turn(), "generationNativeOutcome").contains(
				"Confirmed development result");
			requireThat(nativeRequest.turn(), "generationSymlinkRestriction").contains(
				"Do not create, delete, or retarget symbolic links");
			requireThat(nativeRequest.asNested().context(), "generationNestedContext").isEqualTo(context);
			Assert.expectThrows(IOException.class, () -> new CandidateGenerationRequestService(locator,
				reopened).publish(firstAssignment));
			EvidenceRef interruptedIntentRef = intents.publish(roundRef, "first", "generation-creation-fault");
			GenerationAttemptIntent interrupted = intents.read(interruptedIntentRef);
			cleanup.add(interrupted.privateProject());
			VerifiedCleanFork.Identity fork = VerifiedCleanFork.load(store.directory(),
				locator.readRegisteredKey(store.binding()).canonicalProjectRoot());
			VerifiedCleanFork.ensureAt(fork, interrupted.privateProject());
			Files.delete(interrupted.privateProject().resolve("prompt.md"));
			EvidenceRef recoveredReadyRef = new GenerationAttemptWorktreeService(locator,
				CampaignStore.open(store.directory())).prepare(interruptedIntentRef);
			requireThat(worktrees.read(recoveredReadyRef).intentRef(), "recoveredGenerationIntent").isEqualTo(
				interruptedIntentRef);
			reopened.appendCreateOnceIndex("candidate-generation-request-index",
				recoveredReadyRef.evidenceId(), new LineageEvidenceRef(lineage, requestRef));
			IOException foreignGenerationRequest = Assert.expectThrows(IOException.class, () ->
				new CandidateGenerationRequestService(locator, reopened).publish(recoveredReadyRef));
			requireThat(foreignGenerationRequest.getMessage(), "foreignGenerationRequest").contains(
				"another readiness");
			requireThat(worktrees.requirePristine(readyRef), "pristinePrivateBeforeStart").isEqualTo(ready);
			Path selectedGit = intent.selectedProject().resolve(".git");
			String originalSelectedGitBinding = Files.readString(selectedGit);
			Files.writeString(selectedGit, "gitdir: " + baseProject.resolve(".git") + "\n");
			try
			{
				IOException redirectedSelected = Assert.expectThrows(IOException.class, () ->
					worktrees.requirePristine(readyRef));
				requireThat(redirectedSelected.getMessage(), "redirectedSelectedRepository").contains(
					"not registered at its reserved path");
			}
			finally
			{
				Files.writeString(selectedGit, originalSelectedGitBinding);
			}
			Path privateGit = intent.privateProject().resolve(".git");
			String originalGitBinding = Files.readString(privateGit);
			Files.writeString(privateGit, "gitdir: " + baseProject.resolve(".git") + "\n");
			try
			{
				IOException redirectedRepository = Assert.expectThrows(IOException.class, () ->
					worktrees.requirePristine(readyRef));
				requireThat(redirectedRepository.getMessage(), "redirectedPrivateRepository").contains(
					"not registered at its reserved path");
			}
			finally
			{
				Files.writeString(privateGit, originalGitBinding);
			}
			Files.writeString(intent.privateProject().resolve("prompt.md"), "private pre-start edit\n");
			IOException changedPrivate = Assert.expectThrows(IOException.class, () ->
				worktrees.requirePristine(readyRef));
			requireThat(changedPrivate.getMessage(), "changedPrivateBeforeStart").contains(
				"changed before native authoring");
			IOException changedPrivateRequest = Assert.expectThrows(IOException.class, () ->
				new CandidateGenerationRequestService(locator, reopened).publish(readyRef));
			requireThat(changedPrivateRequest.getMessage(), "changedPrivateRequest").contains(
				"changed before native authoring");
			IOException changedPrivateContext = Assert.expectThrows(IOException.class, () ->
				new CandidateGenerationRequestService(locator, reopened).context(requestRef));
			requireThat(changedPrivateContext.getMessage(), "changedPrivateContext").contains(
				"changed before native authoring");
			IOException changedNativeRequest = Assert.expectThrows(IOException.class, () ->
				nativeRequests.prepare(requestRef));
			requireThat(changedNativeRequest.getMessage(), "changedGenerationNativeRequest").contains(
				"changed before native authoring");
			requireThat(nativeRequests.read(nativeRequestRef), "generationNativeRequestAfterAuthoringEdit").
				isEqualTo(nativeRequest);
			requireThat(Files.readString(intent.selectedProject().resolve("prompt.md")),
				"selectedUntouchedByPrivateEdit").isEqualTo("target prompt");
			Files.writeString(intent.privateProject().resolve("prompt.md"), "target prompt");
			requireThat(worktrees.requirePristine(readyRef), "restoredGenerationPrivateWorktree").
				isEqualTo(ready);
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "generation-claim");
			ObjectNode host = JsonMapper.builder().build().createObjectNode();
			host.put("schemaVersion", 1);
			host.put("tokenDigest", "a".repeat(64));
			host.put("hostSessionId", "generation-session");
			host.put("hostTurnId", "generation-turn");
			inbox.publishHostObservation(binding, "generation-claim", registration,
				JsonMapper.builder().build().writeValueAsBytes(host));
			CampaignSchedulerLease scheduler = locator.openSchedulerLease(binding);
			RecordingAdapter generationAdapter = new RecordingAdapter();
			generationAdapter.dispatchStore = reopened;
			try (SchedulerLease lease = scheduler.acquire(binding, "generation-claim"))
			{
				Map<MeasurementConditions.Kind, MeasurementConditions.Value> conditions =
					new EnumMap<>(MeasurementConditions.Kind.class);
				for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
					conditions.put(kind, new MeasurementConditions.Value.Unknown(
						"Candidate claim fixture does not observe native host conditions"));
				EvidenceRef stableModel = reopened.appendImmutableJson("measurement-values",
					JsonMapper.builder().build().writeValueAsBytes(reopened.load().optimizationProfile()));
				conditions.put(MeasurementConditions.Kind.MODEL_PROFILE,
					new MeasurementConditions.Value.Known(stableModel));
				MeasurementConditions observedConditions = new MeasurementConditions(conditions);
				locator.openMeasurementObserver(binding).beforeCall(lease, "generation-baseline", observedConditions);
				EvidenceRef changedModel = reopened.appendImmutableJson("measurement-values",
					JsonMapper.builder().build().writeValueAsBytes("changed generation model"));
				conditions.put(MeasurementConditions.Kind.MODEL_PROFILE,
					new MeasurementConditions.Value.Known(changedModel));
				MeasurementConditions changedConditions = new MeasurementConditions(conditions);
				locator.openMeasurementObserver(binding).beforeCall(lease, "other-paused-call", changedConditions);
				IOException otherPause = Assert.expectThrows(IOException.class, () ->
					new CandidateGenerationAdmissionService(locator, reopened).
						admit(lease, requestRef, changedConditions));
				requireThat(otherPause.getMessage(), "foreignGenerationPause").contains("another generation call");
				locator.openMeasurementObserver(binding).restoreBeforeCall(lease, "other-paused-call",
					observedConditions);
				CandidateGenerationAdmission paused = new CandidateGenerationAdmissionService(locator, reopened).
					admit(lease, requestRef, changedConditions);
				requireThat(paused, "pausedGenerationAdmission").isInstanceOf(CandidateGenerationAdmission.Paused.class);
				requireThat(((CandidateGenerationAdmission.Paused) paused).requestRef(), "pausedGenerationRequest").
					isEqualTo(nativeRequestRef);
				requireThat(reopened.load().scheduler().slots().size(), "noPausedGenerationSlot").isEqualTo(0);
				requireThat(new CandidateGenerationAdmissionService(locator, CampaignStore.open(store.directory())).
						admit(lease, requestRef, changedConditions), "generationPauseRetry").isEqualTo(paused);
					Assert.expectThrows(IOException.class, () ->
						new CandidateGenerationAdmissionService(locator, reopened).
							admit(lease, requestRef, observedConditions));
					Assert.expectThrows(IOException.class, () -> worktrees.requirePristine(readyRef));
					locator.openMeasurementObserver(binding).restoreBeforeCall(lease, intent.logicalCallId(),
						observedConditions);
				MeasurementObservationDecision beforeGeneration = locator.openMeasurementObserver(binding).
					beforeCall(lease, intent.logicalCallId(), observedConditions);
				EvidenceRef foreignNativeRequest = reopened.appendImmutableJson("run-requests",
					JsonMapper.builder().build().writeValueAsBytes(Map.of(
						"logicalCallId", intent.logicalCallId())));
				Path intentIndexPath = fixtureIndexPath(store, "generation-attempt-intent-index",
					intent.logicalCallId());
				byte[] intentIndexBytes = Files.readAllBytes(intentIndexPath);
				Files.delete(intentIndexPath);
				try
				{
					IOException missingIntentIndex = Assert.expectThrows(IOException.class, () ->
						scheduler.claimOrResumeCall(lease, intent.logicalCallId(), foreignNativeRequest,
							Optional.empty(), Optional.empty()));
					requireThat(missingIntentIndex.getMessage(), "missingGenerationIntentIndex").contains(
						"Candidate generation intent index is missing");
				}
				finally
				{
					Files.write(intentIndexPath, intentIndexBytes);
				}
				IOException foreignClaim = Assert.expectThrows(IOException.class, () ->
						scheduler.claimOrResumeCall(lease, intent.logicalCallId(), foreignNativeRequest,
							Optional.empty(), Optional.empty()));
					requireThat(foreignClaim.getMessage(), "foreignGenerationClaim").contains(
						"Candidate generation");
					requireThat(reopened.load().scheduler().slots().size(), "noForeignGenerationSlot").isEqualTo(0);
				CandidateGenerationAdmission admitted = new CandidateGenerationAdmissionService(locator, reopened).
					admit(lease, requestRef, observedConditions);
				requireThat(admitted, "generationAdmission").isInstanceOf(CandidateGenerationAdmission.Claimed.class);
				CandidateGenerationAdmission.Claimed accepted = (CandidateGenerationAdmission.Claimed) admitted;
				requireThat(accepted.requestRef(), "admittedGenerationRequest").isEqualTo(nativeRequestRef);
				requireThat(accepted.observationRef(), "admittedGenerationObservation").isEqualTo(
					beforeGeneration.observationRef());
				requireThat(new CandidateGenerationAdmissionService(locator, CampaignStore.open(store.directory())).
					admit(lease, requestRef, observedConditions), "generationAdmissionRetry").isEqualTo(admitted);
				ClaimedSlot generationClaim = accepted.claim();
				IOException unstartedRecovery = Assert.expectThrows(IOException.class, () ->
					new CandidateGenerationCallCoordinator(locator, reopened).reconcile(lease, requestRef,
						new NestedAgent(generationAdapter, reopened::readImmutableJson)));
				requireThat(unstartedRecovery.getMessage(), "generationRecoveryBeforeStart").contains(
					"pre-start is missing");
				requireThat(generationAdapter.reconciliations, "noUnstartedNativeRecovery").isEqualTo(0);
				requireThat(generationClaim.slot().requestRef(), "generationClaimNativeRequest").isEqualTo(
					nativeRequestRef);
				CandidateTransportAttemptService attempts = new CandidateTransportAttemptService(locator, reopened);
				EvidenceRef attemptRef = attempts.publishAttempt(lease, requestRef);
				requireThat(attemptRef.collection(), "generationAttemptCollection").isEqualTo(
					"candidate-native-attempts");
				requireThat(attempts.publishAttempt(lease, requestRef), "generationAttemptRetry").isEqualTo(
					attemptRef);
				CandidateTransportAttempt attempt = attempts.readAttempt(attemptRef);
				requireThat(attempt.requestRef(), "generationAttemptSemanticRequest").isEqualTo(requestRef);
				requireThat(attempt.header().slotClaimRef(), "generationAttemptClaim").isEqualTo(
					generationClaim.slotClaimRef());
				IllegalArgumentException wrongAttemptKind = Assert.expectThrows(
					IllegalArgumentException.class, () -> reopened.appendCreateOnceIndex(
						"candidate-transport-attempts", "wrong-candidate-kind",
						new LineageEvidenceRef(lineage, firstAssignment)));
				requireThat(wrongAttemptKind.getMessage(), "generationAttemptIndexKind").contains(
					"candidate-native-attempts");
				Path artifact = Files.createDirectory(root.resolve("generation-artifact"));
				RunnerRuntimeManifest.write(artifact);
				HarnessRunManager manager = new HarnessRunManager(root.resolve("generation-runs"),
					Clock.systemUTC());
				Path privatePrompt = intent.privateProject().resolve("prompt.md");
				String pristinePrompt = Files.readString(privatePrompt);
				Files.writeString(privatePrompt, "edit before independent pre-start publication\n");
				try
				{
					IOException independentPreStart = Assert.expectThrows(IOException.class, () ->
						attempts.publishPreStart(lease, attemptRef, manager, artifact));
					requireThat(independentPreStart.getMessage(), "independentPreStartFreshness").contains(
						"changed before native authoring");
				}
				finally
				{
					Files.writeString(privatePrompt, pristinePrompt);
				}
				EvidenceRef preStartRef = attempts.publishPreStart(lease, attemptRef, manager, artifact);
				requireThat(preStartRef.collection(), "generationPreStartCollection").isEqualTo(
					"call-pre-starts");
				NestedAgent generationAgent = new NestedAgent(generationAdapter, reopened::readImmutableJson);
				CandidateGenerationCallCoordinator coordinator = new CandidateGenerationCallCoordinator(locator,
					reopened);
				Files.writeString(intent.privateProject().resolve("prompt.md"), "changed after claim\n");
				IOException changedBeforeStart = Assert.expectThrows(IOException.class, () ->
					lease.prepareStart(intent.logicalCallId(), preStartRef));
				requireThat(changedBeforeStart.getMessage(), "generationChangedBeforeStart").contains(
					"changed before native authoring");
				Assert.expectThrows(IOException.class, () ->
					coordinator.startClaimed(lease, requestRef, generationAgent, manager, artifact));
				requireThat(generationAdapter.starts, "noGenerationStartWithChangedWorkspace").isEqualTo(0);
				Files.writeString(intent.privateProject().resolve("prompt.md"), "target prompt");
			try (ActiveCall nativeCall = coordinator.startClaimed(lease, requestRef, generationAgent, manager,
					artifact))
				{
					CallPreStartRecord preStart = reopened.readCallPreStartRecord(binding, intent.logicalCallId()).
						orElseThrow();
					requireThat(nativeCall.runId(), "generationRetainedRun").isEqualTo(preStart.runId());
					requireThat(generationAdapter.lastRequest, "generationDispatchedRequest").isEqualTo(
						nativeRequest.asNested());
					requireThat(reopened.readCallSlotClaim(binding, intent.logicalCallId()).slot().state(),
						"generationActiveSlot").isEqualTo(CallSlot.State.ACTIVE);
					Assert.expectThrows(IOException.class, () ->
						coordinator.startClaimed(lease, requestRef, generationAgent, manager, artifact));
					requireThat(generationAdapter.starts, "noGenerationRedispatch").isEqualTo(1);
					Files.writeString(intent.privateProject().resolve("prompt.md"), "authored private prompt\n");
					generationAdapter.reconcileAsActive = true;
					CandidateGenerationCallCoordinator coldCoordinator = new CandidateGenerationCallCoordinator(
						locator, CampaignStore.open(store.directory()));
					CandidateGenerationCallReconciliation recovered = coldCoordinator.reconcile(lease, requestRef,
						generationAgent);
						requireThat(recovered, "coldGenerationRecovery").
						isInstanceOf(CandidateGenerationCallReconciliation.Active.class);
					try (ActiveCall recoveredCall = ((CandidateGenerationCallReconciliation.Active) recovered).call())
					{
						requireThat(recoveredCall.runId(), "coldGenerationRun").isEqualTo(nativeCall.runId());
					}
					requireThat(generationAdapter.lastReconciledRequest, "generationRecoveredRequest").isEqualTo(
						nativeRequest.asNested());
					requireThat(generationAdapter.lastReconciledRun, "generationRecoveredRun").isEqualTo(
						Optional.of(preStart.runId()));
					requireThat(generationAdapter.starts, "noGenerationRecoveryDispatch").isEqualTo(1);
				}
			}
			EvidenceRef recoveryRegistration = inbox.beginInvocation(binding, "generation-recovery");
			host.put("tokenDigest", "b".repeat(64));
			host.put("hostTurnId", "generation-recovery-turn");
			inbox.publishHostObservation(binding, "generation-recovery", recoveryRegistration,
				JsonMapper.builder().build().writeValueAsBytes(host));
			CampaignStore recoveryStore = CampaignStore.open(store.directory());
			CandidateGenerationCallCoordinator recoveryCoordinator = new CandidateGenerationCallCoordinator(locator,
				recoveryStore);
			NestedAgent recoveryAgent = new NestedAgent(generationAdapter, recoveryStore::readImmutableJson);
			try (SchedulerLease successor = scheduler.acquire(binding, "generation-recovery"))
			{
				CallSlot originalSlot = recoveryStore.readCallSlotClaim(binding, intent.logicalCallId()).slot();
				requireThat(successor.fence(), "successorGenerationFence").isGreaterThan(originalSlot.fence());
				CandidateGenerationCallReconciliation recovered = recoveryCoordinator.reconcile(successor, requestRef,
					recoveryAgent);
				requireThat(recovered, "successorGenerationRecovery").
					isInstanceOf(CandidateGenerationCallReconciliation.Active.class);
				try (ActiveCall recoveredCall = ((CandidateGenerationCallReconciliation.Active) recovered).call())
				{
					requireThat(recoveredCall.runId(), "successorGenerationRun").isEqualTo(
						originalSlot.runId().orElseThrow());
				}
				requireThat(recoveryStore.readCallSlotClaim(binding, intent.logicalCallId()).slot(),
					"preservedGenerationSlot").isEqualTo(originalSlot);
				requireThat(generationAdapter.starts, "noSuccessorGenerationDispatch").isEqualTo(1);
				requireThat(Files.readString(intent.privateProject().resolve("prompt.md")),
					"preservedAuthoredGenerationFile").isEqualTo("authored private prompt\n");
				generationAdapter.unknownFailure = new NestedRunFailure(NestedRunFailureKind.NATIVE_STATE_UNKNOWN,
					intent.logicalCallId(), originalSlot.runId(), "Native process state is unavailable", List.of(),
					Optional.empty(), Optional.empty());
				CandidateGenerationCallReconciliation unknown = recoveryCoordinator.reconcile(successor, requestRef,
					recoveryAgent);
				requireThat(unknown, "unknownGenerationRecovery").
					isInstanceOf(CandidateGenerationCallReconciliation.Unknown.class);
				requireThat(((CandidateGenerationCallReconciliation.Unknown) unknown).failure().reason(),
					"unknownGenerationReason").isEqualTo("Native process state is unavailable");
				requireThat(recoveryStore.readCallSlotClaim(binding, intent.logicalCallId()).slot(),
					"preservedUnknownGenerationSlot").isEqualTo(originalSlot);
				generationAdapter.unknownFailure = null;
				successor.requestCancellation(intent.logicalCallId());
				int interruptsBeforeRecovery = generationAdapter.interrupts;
				CandidateGenerationCallReconciliation cancelled = recoveryCoordinator.reconcile(successor, requestRef,
					recoveryAgent);
				requireThat(cancelled, "cancelledGenerationRecovery").
					isInstanceOf(CandidateGenerationCallReconciliation.Active.class);
				try (ActiveCall recoveredCall = ((CandidateGenerationCallReconciliation.Active) cancelled).call())
				{
					requireThat(recoveredCall.runId(), "cancelledGenerationRun").isEqualTo(
						originalSlot.runId().orElseThrow());
					requireThat(generationAdapter.interrupts, "generationRecoveryHonorsCancellation").isEqualTo(
						interruptsBeforeRecovery + 1);
				}
				requireThat(generationAdapter.starts, "noUnknownOrCancelledGenerationDispatch").isEqualTo(1);
				Campaign beforeStartingRecovery = recoveryStore.load();
				CallSlot retainedStartingSlot = generationAdapter.slotAtDispatch;
				requireThat(retainedStartingSlot.state(), "generationSlotAtNativeDispatch").
					isEqualTo(CallSlot.State.STARTING);
				requireThat(retainedStartingSlot.runId(), "activationNotRecordedAtDispatch").isEmpty();
				Files.write(recoveryStore.directory().resolve("campaign.json"), JsonMapper.builder().build().
					writerWithDefaultPrettyPrinter().writeValueAsBytes(
						campaignWithRetainedSlot(beforeStartingRecovery, retainedStartingSlot)));
				CampaignStore startingStore = CampaignStore.open(store.directory());
				CandidateGenerationCallCoordinator startingCoordinator = new CandidateGenerationCallCoordinator(locator,
					startingStore);
				NestedAgent startingAgent = new NestedAgent(generationAdapter, startingStore::readImmutableJson);
				ClaimedSlot startingClaim = startingStore.readCallSlotClaim(binding, intent.logicalCallId());
				CandidateGenerationCallReconciliation startingRecovery = startingCoordinator.reconcile(successor,
					requestRef, startingAgent);
				requireThat(startingRecovery, "generationRecoveryBeforeActivation").
					isInstanceOf(CandidateGenerationCallReconciliation.Active.class);
				try (ActiveCall recoveredCall = ((CandidateGenerationCallReconciliation.Active) startingRecovery).call())
				{
					requireThat(recoveredCall.runId(), "startingGenerationRetainedRun").isEqualTo(
						originalSlot.runId().orElseThrow());
					requireThat(startingStore.readCallSlotClaim(binding, intent.logicalCallId()), "unchangedStartingClaim").
						isEqualTo(startingClaim);
				}
				requireThat(generationAdapter.starts, "noStartingRecoveryDispatch").isEqualTo(1);
				Files.write(recoveryStore.directory().resolve("campaign.json"), JsonMapper.builder().build().
					writerWithDefaultPrettyPrinter().writeValueAsBytes(beforeStartingRecovery));
				CallPreStartRecord preStart = recoveryStore.readCallPreStartRecord(binding, intent.logicalCallId()).
					orElseThrow();
				EvidenceRef eventRef = recoveryStore.appendImmutableBytes("native-events",
					"generation terminal response".getBytes(StandardCharsets.UTF_8));
				NestedRunResult nativeResult = createGenerationResult(nativeRequest, preStart, intent.privateProject(),
					eventRef);
				CandidateGenerationCompletionService completions = new CandidateGenerationCompletionService(locator,
					recoveryStore);
				ClaimedSlot retainedClaim = recoveryStore.readCallSlotClaim(binding, intent.logicalCallId());
				NativeCompletionPublisher publisher = new NativeCompletionPublisher(recoveryStore);
				Assert.expectThrows(IOException.class, () -> publisher.publish(binding, retainedClaim, preStart,
					nativeRequest.asNested(), new NestedRunCompletion.Succeeded(createGenerationResult(nativeRequest,
						preStart, intent.selectedProject(), eventRef))));
				Assert.expectThrows(IOException.class, () -> publisher.publish(binding, retainedClaim, preStart,
					nativeRequest.asNested(), new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.INTERRUPTED, intent.logicalCallId(), Optional.of("another-native-run"),
						"Interrupted another run", List.of(), Optional.empty(), Optional.empty()))));
				requireThat(recoveryStore.readNativeCompletionIndex(binding, intent.logicalCallId()),
					"noConflictingGenerationCompletion").isEmpty();
				EvidenceRef completionRef = completions.publishCompletion(successor, requestRef,
					new NestedRunCompletion.Succeeded(nativeResult));
				requireThat(publisher.publish(binding, retainedClaim, preStart, nativeRequest.asNested(),
					new NestedRunCompletion.Succeeded(nativeResult)), "generationCompletionPublicationRetry").
					isEqualTo(completionRef);
				Assert.expectThrows(IOException.class, () -> publisher.publish(binding, retainedClaim, preStart,
					nativeRequest.asNested(), new NestedRunCompletion.Failed(new NestedRunFailure(
						NestedRunFailureKind.INTERRUPTED, intent.logicalCallId(), Optional.of(preStart.runId()),
						"Conflicting settlement", List.of(), Optional.empty(), Optional.empty()))));
				requireThat(recoveryStore.readNativeCompletionIndex(binding, intent.logicalCallId()).orElseThrow(),
					"preservedGenerationCompletionIndex").isEqualTo(completionRef);
				Campaign beforeNativeAdoption = recoveryStore.load();
				requireThat(beforeNativeAdoption.scheduler().slots().getFirst().state(),
					"generationStateBeforeNativeAdoption").isEqualTo(CallSlot.State.ACTIVE);
				requireThat(recoveryStore.readCallSlotClaim(binding, intent.logicalCallId()).slot().state(),
					"generationCompletionBeforeAdoption").isEqualTo(CallSlot.State.ACTIVE);
				int reconciliationsBeforeAdoption = generationAdapter.reconciliations;
				CandidateGenerationCallCoordinator completionCoordinator = new CandidateGenerationCallCoordinator(locator,
					CampaignStore.open(store.directory()));
				CandidateGenerationCallReconciliation settlement = completionCoordinator.reconcile(successor, requestRef,
					recoveryAgent);
				requireThat(generationAdapter.reconciliations, "indexedGenerationSkipsNativeRecovery").isEqualTo(
					reconciliationsBeforeAdoption);
				requireThat(settlement, "adoptedGenerationSettlement").isInstanceOf(
					CandidateGenerationCallReconciliation.Settled.class);
				EvidenceRef executionRef = ((CandidateGenerationCallReconciliation.Settled) settlement).executionRef();
				CandidateGenerationExecutionRecord execution = JsonMapper.builder().build().readValue(
					recoveryStore.readImmutableJson(executionRef), CandidateGenerationExecutionRecord.class);
				requireThat(execution.requestRef(), "generationExecutionRequest").isEqualTo(requestRef);
				requireThat(execution.attemptRef(), "generationExecutionAttempt").isEqualTo(preStart.producerAttemptRef());
				requireThat(execution.completionRef(), "generationExecutionCompletion").isEqualTo(completionRef);
				requireThat(recoveryStore.readCallSlotClaim(binding, intent.logicalCallId()).slot().state(),
					"generationAdoptedSlot").isEqualTo(CallSlot.State.ADOPTED);
				requireThat(((CandidateGenerationCallReconciliation.Settled) completionCoordinator.reconcile(successor,
					requestRef, recoveryAgent)).executionRef(), "generationAdoptionRetry").isEqualTo(executionRef);
				requireThat(completions.adoptIndexed(successor, requestRef).executionRef(),
					"generationPublicAdoptionRetry").isEqualTo(executionRef);
				requireThat(generationAdapter.starts, "noGenerationCompletionRedispatch").isEqualTo(1);
				requireThat(generationAdapter.reconciliations, "generationAdoptionRetrySkipsNativeRecovery").isEqualTo(
					reconciliationsBeforeAdoption);
				NativeRunCompletionEvidence evidence = NativeRunCompletionCodec.decode(completionRef,
					recoveryStore::readImmutableJson);
				requireThat(evidence.result().orElseThrow().executionProject(), "retainedPrivateGenerationProject").
					isEqualTo(intent.privateProject());
				MeasurementObservation after = recoveryStore.readMeasurementObservation(recoveryStore.readCreateOnceIndex(
					"measurement-after-call-index", MeasurementObservation.callIndexKey(epochRef, intent.logicalCallId()),
					lineage).evidence());
				requireThat(after.stage(), "generationAfterCallStage").isEqualTo(MeasurementObservation.Stage.AFTER_CALL);
				requireThat(after.conditions().values().get(MeasurementConditions.Kind.MODEL_PROFILE),
					"generationObservedModel").isEqualTo(new MeasurementConditions.Value.Known(
						recoveryStore.appendImmutableJson("measurement-values",
							JsonMapper.builder().build().writeValueAsBytes(nativeResult.observedModelProfile()))));
				NestedRunFailure interruptedGeneration = new NestedRunFailure(NestedRunFailureKind.INTERRUPTED,
					intent.logicalCallId(), Optional.of(preStart.runId()), "No completed native observations", List.of(),
					Optional.empty(), Optional.empty());
				NativeRunCompletionEvidence failedEvidence = new NativeRunCompletionEvidence(binding.campaignId(),
					intent.logicalCallId(), retainedClaim.slot().requestRef(), retainedClaim.slotClaimRef(),
					Optional.of(preStart.runId()), NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(),
					Optional.of(interruptedGeneration), List.of(), Optional.empty(), Optional.empty());
				MeasurementConditions limited = new NativeCompletionMeasurements(recoveryStore).conditions(binding,
					retainedClaim, preStart, epochRef, failedEvidence);
				requireThat(limited.values().get(MeasurementConditions.Kind.MODEL_PROFILE),
					"noInventedGenerationModel").isInstanceOf(MeasurementConditions.Value.Unknown.class);
				requireThat(limited.values().get(MeasurementConditions.Kind.HARNESS_RUNNER),
					"noInventedGenerationRunner").isInstanceOf(MeasurementConditions.Value.Unknown.class);

				Files.write(recoveryStore.directory().resolve("campaign.json"), JsonMapper.builder().build().
					writerWithDefaultPrettyPrinter().writeValueAsBytes(beforeNativeAdoption));
				Files.delete(findNativeCompletionIndex(recoveryStore, intent.logicalCallId()));
				generationAdapter.pending.complete(new NestedRunCompletion.Succeeded(nativeResult));
				generationAdapter.reconcileAsSettled = true;
				CandidateGenerationCallCoordinator unindexedCoordinator = new CandidateGenerationCallCoordinator(locator,
					CampaignStore.open(store.directory()));
				CandidateGenerationCallReconciliation.Settled unindexedSettlement =
					(CandidateGenerationCallReconciliation.Settled) unindexedCoordinator.reconcile(successor, requestRef,
						recoveryAgent);
				requireThat(unindexedSettlement.executionRef(), "generationRecoversUnindexedNativeCompletion").
					isEqualTo(executionRef);
				requireThat(generationAdapter.reconciliations, "generationQueriesUnindexedNativeCompletionOnce").isEqualTo(
					reconciliationsBeforeAdoption + 1);
				requireThat(generationAdapter.starts, "unindexedGenerationHasNoRedispatch").isEqualTo(1);
				Files.write(recoveryStore.directory().resolve("campaign.json"), JsonMapper.builder().build().
					writerWithDefaultPrettyPrinter().writeValueAsBytes(
						campaignWithRetainedSlot(beforeNativeAdoption, retainedStartingSlot)));
				Files.delete(findNativeCompletionIndex(recoveryStore, intent.logicalCallId()));
				CandidateGenerationCallReconciliation.Settled startingSettlement =
					(CandidateGenerationCallReconciliation.Settled) startingCoordinator.reconcile(successor, requestRef,
						startingAgent);
				requireThat(startingSettlement.executionRef(), "startingGenerationAdoptsOriginalExecution").
					isEqualTo(executionRef);
				requireThat(startingStore.readCallSlotClaim(binding, intent.logicalCallId()).slot().state(),
					"startingGenerationAdoptedSlot").isEqualTo(CallSlot.State.ADOPTED);
				requireThat(generationAdapter.starts, "noStartingSettlementDispatch").isEqualTo(1);
			}
			Files.writeString(intent.selectedProject().resolve("prompt.md"), "unowned change\n");
			IOException staleIntent = Assert.expectThrows(IOException.class, () -> intents.requireCurrent(intentRef));
			requireThat(staleIntent.getMessage(), "staleGenerationIntent").contains("selected worktree changed");
			IOException staleReadiness = Assert.expectThrows(IOException.class, () ->
				worktrees.requirePristine(readyRef));
			requireThat(staleReadiness.getMessage(), "staleGenerationReadiness").contains(
				"selected worktree changed");
			Path sourceFile = store.directory().resolve(round.sourceRevisionRef().collection()).resolve(
				round.sourceRevisionRef().evidenceId() + ".json");
			Files.delete(sourceFile);
			Assert.expectThrows(IOException.class, () -> new DevelopmentRoundService(locator,
				CampaignStore.open(store.directory())).read(roundRef));
			Assert.expectThrows(IOException.class, () -> new CandidateGenerationRequestService(locator,
				CampaignStore.open(store.directory())).read(requestRef));
		}
	}

	/**
	 * A first follow-up draw survives index repair; a clearly weak partial batch then prevents another draw.
	 *
	 * @throws IOException if counted campaign evidence cannot be retained or read
	 */
	@Test
	public void stopsNewDrawsInWeakFollowupBatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("weak-followup-category-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			ContractProposal proposal = ContractTestFixtures.withWeakThreshold(
				ContractTestFixtures.withFirstCategoryOnly(fixture.proposal()), new BigDecimal("0.99"));
			fixture.service().recordProposal(ContractTestFixtures.bytes(proposal), fixture.loading());
			fixture.service().confirm(0, proposal.proposalId(), ContractTestFixtures.bytes(proposal));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			CampaignBinding binding = store.binding();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = store.directory().resolve("campaign.json");

			EvidenceRef firstRef = new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef firstAssignment = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first", 0);
			Campaign firstComplete = countRemainingBatch(store, store.load(), binding, lineage, epochRef,
				firstRef, contract, firstAssignment, 1);
			Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(firstComplete));
			CampaignStore followupStore = CampaignStore.open(store.directory());
			EvidenceRef followupRef = new DevelopmentCategoryDecisionService(locator, followupStore).
				publishFirst();
			requireThat(new DevelopmentCategoryDecisionService(locator,
				CampaignStore.open(store.directory())).publishFirst(), "coldFollowupChoiceRetry").
				isEqualTo(followupRef);
			EvidenceRef followupAssignment = new TrialAssignmentService(locator, followupStore).
				assignPostCoverageDevelopment(followupRef, 0);
			requireThat(followupStore.readTrialAssignment(followupAssignment).categoryDecisionRef(),
				"firstFollowupDrawChoice").isEqualTo(followupRef);
			requireThat(new TrialAssignmentService(locator, CampaignStore.open(store.directory())).
				assignPostCoverageDevelopment(followupRef, 0), "coldFollowupDrawRetry").
				isEqualTo(followupAssignment);
			Path followupAssignmentIndex = fixtureIndexPath(followupStore, "trial-assignment-index",
				TrialAssignmentService.postCoverageDevelopmentKey(lineage, epochRef, followupRef, 0));
			Files.delete(followupAssignmentIndex);
			requireThat(new TrialAssignmentService(locator, CampaignStore.open(store.directory())).
				assignPostCoverageDevelopment(followupRef, 0), "repairedFollowupDrawIndex").
				isEqualTo(followupAssignment);
			int weakFollowupOutcomes = 1;
			EvidenceRef pendingAssignment = new TrialAssignmentService(locator, followupStore).
				assignPostCoverageDevelopment(followupRef, weakFollowupOutcomes);
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef startRegistration = inbox.beginInvocation(binding, "weak-prestart-trial");
			ObjectNode host = json.createObjectNode();
			host.put("schemaVersion", 1);
			host.put("tokenDigest", "2".repeat(64));
			host.put("hostSessionId", "weak-prestart-session");
			host.put("hostTurnId", "weak-prestart-turn");
			inbox.publishHostObservation(binding, "weak-prestart-trial", startRegistration,
				json.writeValueAsBytes(host));
			Path artifact = Files.createDirectory(root.resolve("weak-pending-artifact"));
			RunnerRuntimeManifest.write(artifact);
			HarnessRunManager manager = new HarnessRunManager(root.resolve("weak-pending-runs"),
				Clock.systemUTC());
			RecordingAdapter adapter = new RecordingAdapter();
			NestedAgent agent = new NestedAgent(adapter, followupStore::readImmutableJson);
			try (SchedulerLease lease = locator.openSchedulerLease(binding).
				acquire(binding, "weak-prestart-trial"))
			{
				InitialTrialAdmission admission = new InitialTrialAdmissionService(locator, followupStore).
					admit(lease, pendingAssignment, artifact);
				requireThat(admission instanceof InitialTrialAdmission.Claimed,
					"pendingTrialClaimedBeforeWeakness").isTrue();
				EvidenceRef admittedRequestRef = ((InitialTrialAdmission.Claimed) admission).claim().slot().
					requestRef();
				InitialTrialRunRequest admittedRequest = json.readValue(
					followupStore.readImmutableJson(admittedRequestRef), InitialTrialRunRequest.class);
				requireThat(admittedRequest.assignmentRef(), "admittedFollowupAssignment").
					isEqualTo(pendingAssignment);
				Campaign partial = countBatchOutcomes(followupStore, followupStore.load(), binding,
					lineage, epochRef, followupRef, followupAssignment, weakFollowupOutcomes,
					weakFollowupOutcomes);
				Files.write(campaignFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(partial));
				IOException startFailure = Assert.expectThrows(IOException.class, () ->
					new InitialTrialCallCoordinator(locator, followupStore).
						startClaimed(lease, pendingAssignment, agent, manager, artifact));
				requireThat(startFailure.getMessage(), "weakPendingStart").contains("weak");
				requireThat(adapter.starts, "noWeakNativeStart").isEqualTo(0);
			}
			CampaignStore reopened = CampaignStore.open(store.directory());

			IOException failure = Assert.expectThrows(IOException.class,
				() -> new TrialAssignmentService(locator, reopened).
					assignPostCoverageDevelopment(followupRef, weakFollowupOutcomes + 1));
			requireThat(failure.getMessage(), "weakFollowupStop").contains("weak");
			String key = TrialAssignmentService.postCoverageDevelopmentKey(lineage, epochRef,
				followupRef, weakFollowupOutcomes + 1);
			TrialAssignment retained = reopened.readTrialAssignment(followupAssignment);
			TrialAssignment bypass = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				binding.campaignId(), lineage, epochRef, followupRef,
				"trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
				weakFollowupOutcomes + 1,
				retained.candidateId(), retained.instance(), Instant.now());
			EvidenceRef bypassRef = reopened.appendImmutableJson("trial-assignments",
				json.writeValueAsBytes(bypass));
			reopened.appendCreateOnceIndex("trial-assignment-index", key,
				new LineageEvidenceRef(lineage, bypassRef));
			IOException commitFailure = Assert.expectThrows(IOException.class,
				() -> new CampaignTransitions(reopened).assignDevelopmentTrial(binding, bypassRef));
			requireThat(commitFailure.getMessage(), "weakCommitStop").contains("weak");
			requireThat(new TrialAssignmentService(locator, reopened).
				assignPostCoverageDevelopment(followupRef, 0), "retainedFollowupDrawRetry").
				isEqualTo(followupAssignment);
			EvidenceRef registration = inbox.beginInvocation(binding, "weak-pending-trial");
			host.put("tokenDigest", "3".repeat(64));
			host.put("hostSessionId", "weak-pending-session");
			host.put("hostTurnId", "weak-pending-turn");
			inbox.publishHostObservation(binding, "weak-pending-trial", registration,
				json.writeValueAsBytes(host));
			try (SchedulerLease lease = locator.openSchedulerLease(binding).
				acquire(binding, "weak-pending-trial"))
			{
				IOException admissionFailure = Assert.expectThrows(IOException.class,
					() -> new InitialTrialAdmissionService(locator, reopened).
						admit(lease, pendingAssignment, artifact));
				requireThat(admissionFailure.getMessage(), "weakPendingAdmission").contains("weak");
				EvidenceRef requestRef = new InitialTrialRequestService(locator, reopened).
					prepare(pendingAssignment);
				InitialTrialRunRequest request = json.readValue(reopened.readImmutableJson(requestRef),
					InitialTrialRunRequest.class);
				TrialAssignment pending = reopened.readTrialAssignment(pendingAssignment);
				MeasurementConditions conditions = new InitialTrialAdmissionService(locator, reopened).
					conditions(pending, request, artifact);
				MeasurementObservationDecision observation = locator.openMeasurementObserver(binding).
					beforeCall(lease, pending.logicalTrialId(), conditions);
				requireThat(observation.status(), "weakPendingObservation").
					isEqualTo(MeasurementObservationDecision.Status.ACCEPTED);
				IOException claimFailure = Assert.expectThrows(IOException.class, () ->
					locator.openSchedulerLease(binding).claimOrResumeCall(lease, pending.logicalTrialId(),
						requestRef, Optional.empty(), Optional.empty()));
				requireThat(claimFailure.getMessage(), "weakLockedClaim").contains("weak");
			}
			requireThat(new DevelopmentEvidenceService(reopened).readCurrent().batches().getLast().counts(),
				"retainedWeakFollowupOutcomes").isEqualTo(new CategoryCounts(0, weakFollowupOutcomes));
		}
	}

	/**
	 * Counts an assigned first draw and the remaining draws of its category batch.
	 *
	 * @param store campaign evidence store
	 * @param campaign current campaign with the category's first assignment
	 * @param binding pinned campaign identity
	 * @param lineage confirmed lineage
	 * @param epochRef active measurement epoch
	 * @param decisionRef published category decision
	 * @param contract confirmed batch size
	 * @param firstAssignment already committed first assignment
	 * @param noncompliantTrials number of trailing outcomes labeled noncompliant
	 * @return campaign with a complete batch
	 * @throws IOException if assignment or outcome evidence cannot be stored
	 */
	private static Campaign countRemainingBatch(CampaignStore store, Campaign campaign,
		CampaignBinding binding, LineageKey lineage, EvidenceRef epochRef, EvidenceRef decisionRef,
		ConfirmedContract contract, EvidenceRef firstAssignment, int noncompliantTrials) throws IOException
	{
		return countBatchOutcomes(store, campaign, binding, lineage, epochRef, decisionRef,
			firstAssignment, contract.developmentAllocationPolicy().batchSize(), noncompliantTrials);
	}

	/**
	 * Counts the first draws of a batch while leaving later ordinals available for a stop decision.
	 *
	 * @param store campaign evidence store
	 * @param campaign current campaign with the category's first assignment
	 * @param binding pinned campaign identity
	 * @param lineage confirmed lineage
	 * @param epochRef active measurement epoch
	 * @param decisionRef published category decision
	 * @param firstAssignment already committed first assignment
	 * @param outcomeCount number of draws to count
	 * @param noncompliantTrials number of trailing outcomes labeled noncompliant
	 * @return campaign with the counted batch prefix
	 * @throws IOException if assignment or outcome evidence cannot be stored
	 */
	private static Campaign countBatchOutcomes(CampaignStore store, Campaign campaign,
		CampaignBinding binding, LineageKey lineage, EvidenceRef epochRef, EvidenceRef decisionRef,
		EvidenceRef firstAssignment, int outcomeCount,
		int noncompliantTrials) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		SampledTrialInstance instance = store.readTrialAssignment(firstAssignment).instance();
		String categoryId = instance.definition().categoryId();
		for (int ordinal = 0; ordinal < outcomeCount; ++ordinal)
		{
			EvidenceRef assignmentRef = firstAssignment;
			TrialAssignment assignment;
			if (ordinal == 0)
				assignment = store.readTrialAssignment(assignmentRef);
			else
			{
				String key;
				if (decisionRef.collection().equals("development-category-decisions"))
					key = TrialAssignmentService.postCoverageDevelopmentKey(lineage, epochRef, decisionRef, ordinal);
				else
					key = TrialAssignmentService.initialDevelopmentKey(lineage, epochRef, categoryId, ordinal);
				String logicalTrialId = "trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
				assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION, binding.campaignId(), lineage,
					epochRef, decisionRef, logicalTrialId, ordinal, campaign.currentCandidateId(), instance,
					Instant.now());
				assignmentRef = store.appendImmutableJson("trial-assignments", json.writeValueAsBytes(assignment));
				writeFixtureIndex(store, json, "trial-assignment-index", key, binding,
					new LineageEvidenceRef(lineage, assignmentRef));
				long assignedCounter = campaign.transitionCounter() + 1;
				TransitionResult assigned = new TransitionResult(assignedCounter, CampaignStatus.DEVELOPING,
					lineage.lineageId(), campaign.currentCandidateId(), "assignment-" + assignmentRef.evidenceId());
				campaign = campaign.withTrialAssignment(new TransitionReceipt(binding,
					TransitionKind.TRIAL_ASSIGNMENT, campaign.transitionCounter(), assignmentRef, assignedCounter,
					assigned), assignment);
			}
			EvaluatorLabel label = EvaluatorLabel.COMPLIANT;
			if (ordinal >= outcomeCount - noncompliantTrials)
				label = EvaluatorLabel.NONCOMPLIANT;
			campaign = countAssignedOutcome(store, campaign, binding, assignmentRef, assignment, label, json);
		}
		return campaign;
	}

	/**
	 * Counts one assigned representative outcome in a synthetic campaign snapshot.
	 *
	 * @param store campaign evidence store
	 * @param campaign snapshot containing the assigned trial
	 * @param binding pinned campaign identity
	 * @param assignmentRef retained assignment reference
	 * @param assignment retained assignment
	 * @param label semantic outcome label
	 * @param json mapper for canonical evidence
	 * @return campaign with one additional counted outcome
	 * @throws IOException if the outcome or index cannot be retained
	 */
	private static Campaign countAssignedOutcome(CampaignStore store, Campaign campaign,
		CampaignBinding binding, EvidenceRef assignmentRef, TrialAssignment assignment, EvaluatorLabel label,
		JsonMapper json) throws IOException
	{
		TrialOutcomeRecord outcome = new TrialOutcomeRecord(TrialOutcomeRecord.SCHEMA_VERSION,
			binding.campaignId(), assignment.lineage(), assignmentRef,
			new EvidenceRef("trial-execution-records", "a".repeat(64)),
			new EvidenceRef("evaluator-execution-records", "b".repeat(64)),
			EvaluatorResultSchema.LABEL_AND_REASON_JSON_V1,
			new EvaluatorResponse(label, "Confirmed development result"));
		EvidenceRef outcomeRef = store.appendImmutableJson("trial-outcomes", json.writeValueAsBytes(outcome));
		writeFixtureIndex(store, json, "trial-outcome-index", assignmentRef.evidenceId(), binding,
			new LineageEvidenceRef(assignment.lineage(), outcomeRef));
		long outcomeCounter = campaign.transitionCounter() + 1;
		TransitionResult counted = new TransitionResult(outcomeCounter, CampaignStatus.DEVELOPING,
			assignment.lineage().lineageId(), campaign.currentCandidateId(), "outcome-" + outcomeRef.evidenceId());
		return campaign.withSemanticOutcome(new TransitionReceipt(binding,
			TransitionKind.SEMANTIC_OUTCOME, campaign.transitionCounter(), outcomeRef, outcomeCounter, counted),
			assignment, outcome);
	}

	/**
	 * Retains an index for a synthetic outcome without revalidating the unchanged campaign for every fixture record.
	 *
	 * @param store campaign containing the synthetic evidence
	 * @param json mapper that writes the canonical index record
	 * @param indexName collection that readers use to find the evidence
	 * @param logicalKey logical assignment or outcome identity
	 * @param binding campaign identity written in the index
	 * @param target lineage and evidence selected by the index
	 * @throws IOException if the fixture index cannot be written
	 */
	private static void writeFixtureIndex(CampaignStore store, JsonMapper json, String indexName,
		String logicalKey, CampaignBinding binding, LineageEvidenceRef target) throws IOException
	{
		Path entry = fixtureIndexPath(store, indexName, logicalKey);
		Files.createDirectories(entry.getParent());
		Files.write(entry, json.writeValueAsBytes(new FixtureIndexEntry(1,
			indexName, logicalKey, binding.campaignId(), target.lineage(), target.evidence(),
			target.evidence().evidenceId())));
	}

	/**
	 * Locates one fixture index entry by the same content-addressed key used by the store.
	 *
	 * @param store campaign containing the fixture index
	 * @param indexName index collection
	 * @param logicalKey logical assignment or outcome identity
	 * @return path to the indexed JSON entry
	 */
	private static Path fixtureIndexPath(CampaignStore store, String indexName, String logicalKey)
	{
		try
		{
			String keyDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				logicalKey.getBytes(StandardCharsets.UTF_8)));
			return store.directory().resolve(indexName).resolve(keyDigest + ".json");
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new AssertionError("SHA-256 is unavailable", exception);
		}
	}

	/**
	 * Canonical index entry read by the campaign store for each synthetic assignment or outcome.
	 *
	 * @param schemaVersion index format version
	 * @param indexName index collection
	 * @param logicalKey assignment or outcome identity
	 * @param campaignId campaign containing the synthetic evidence
	 * @param lineage confirmed lineage selected by the index
	 * @param evidence indexed assignment or outcome
	 * @param contentSha256 evidence content address
	 */
	private record FixtureIndexEntry(int schemaVersion, String indexName, String logicalKey, String campaignId,
		LineageKey lineage, EvidenceRef evidence, String contentSha256)
	{
	}

	/**
	 * An indexed assignment cannot be reused after its initial source capture loses integrity.
	 *
	 * @throws IOException if the retained source capture cannot be verified
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsIndexedDrawWithCorruptCapture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("indexed-trial-source-capture-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			TrialAssignmentService assignments = new TrialAssignmentService(locator, store);
			assignments.assignInitialDevelopment("first");
			EvidenceRef captureRef = initial.sourceCaptureRef();
			Path captureFile = store.directory().resolve(captureRef.collection()).resolve(
				captureRef.evidenceId() + ".json");
			Files.writeString(captureFile, "{}");

			assignments.assignInitialDevelopment("first");
		}
	}

	/**
	 * A missing published-choice index prevents a draw until its authority is reconciled.
	 *
	 * @throws IOException if retained evidence or the index cannot be inspected
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision.*published.*")
	public void rejectsMissingCategoryDecisionIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("missing-category-index-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			try (Stream<Path> entries = Files.list(store.directory().resolve("initial-category-decision-index")))
			{
				Files.delete(entries.findFirst().orElseThrow());
			}

			new TrialAssignmentService(locator, CampaignStore.open(store.directory())).
				assignInitialDevelopment("first");
		}
	}

	/**
	 * An indexed choice for another source revision cannot authorize a draw under the confirmed proposal.
	 *
	 * @throws IOException if the retained choice conflicts with the confirmed source
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision has stale confirmed loading evidence.*")
	public void rejectsDifferentSourceEvidenceDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-source-decision-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(store.binding());
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			InitialCategoryDecision foreign = new InitialCategoryDecision(InitialCategoryDecision.SCHEMA_VERSION,
				store.binding().campaignId(), lineage, store.load().currentCandidateId(), epochRef,
				new EvidenceRef("prompt-loading", "f".repeat(64)),
				fixture.proposal().developmentAllocationPolicy(), List.of("first", "second"), List.of(),
				"first", EvidencePurpose.DEVELOPMENT, 0, 0);
			EvidenceRef reference = store.appendImmutableJson("category-decisions",
				JsonMapper.builder().build().writeValueAsBytes(foreign));
			store.appendCreateOnceIndex("initial-category-decision-index",
				InitialCategoryDecisionService.key(lineage, epochRef), new LineageEvidenceRef(lineage, reference));

			new TrialAssignmentService(locator, store).assignInitialDevelopment("first");
		}
	}

	/**
	 * A caller cannot replace the published first category with another confirmed category.
	 *
	 * @throws IOException when the requested category differs from the retained decision
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category.*published category decision.*")
	public void rejectsDifferentRequestedCategory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("different-requested-category-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();

			new TrialAssignmentService(locator, store).assignInitialDevelopment("second");
		}
	}

	/**
	 * A direct transition cannot attach a draw for another category to the first published choice.
	 *
	 * @throws IOException when the indexed assignment conflicts with the selected category
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision.*")
	public void transitionRejectsDifferentCategory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("transition-different-category-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();
			EvidenceRef decisionRef = new InitialCategoryDecisionService(locator, store).publish();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(store.binding());
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			String key = TrialAssignmentService.initialDevelopmentKey(lineage, epochRef, "second", 0);
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			TrialAssignment assignment = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				store.binding().campaignId(), lineage, epochRef, decisionRef,
				"trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), 0,
				initial.candidateSnapshotId(), new TrialSampler(contract).sample(EvidencePurpose.DEVELOPMENT, "second"),
				Instant.now());
			EvidenceRef reference = store.appendImmutableJson("trial-assignments",
				JsonMapper.builder().build().writeValueAsBytes(assignment));
			store.appendCreateOnceIndex("trial-assignment-index", key, new LineageEvidenceRef(lineage, reference));

			new CampaignTransitions(store).assignDevelopmentTrial(store.binding(), reference);
		}
	}

	/**
	 * A confirmed initial candidate gets one retained assignment before any native call slot exists.
	 *
	 * @throws IOException if fixture or assignment evidence cannot be retained
	 */
	@Test
	public void persistsInitialAssignmentBeforeStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("initial-trial-assignment-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();
			EvidenceRef categoryRef = new InitialCategoryDecisionService(locator, store).publish();
			TrialAssignmentService service = new TrialAssignmentService(locator, store);

			EvidenceRef firstRef = service.assignInitialDevelopment("first");

			TrialAssignment first = JsonMapper.builder().build().readValue(store.readImmutableJson(firstRef),
				TrialAssignment.class);
			Campaign assigned = store.load();
			requireThat(first.candidateId(), "assignedInitialCandidate").isEqualTo(initial.candidateSnapshotId());
			requireThat(first.measurementEpochRef(), "assignedMeasurementEpoch").isEqualTo(
				assigned.measurementState().epochRef());
			requireThat(first.categoryDecisionRef(), "assignedCategoryDecision").isEqualTo(categoryRef);
			requireThat(first.instance().definition().categoryId(), "assignedCategory").isEqualTo("first");
			requireThat(first.instance().purpose(), "samplePurpose").isEqualTo(EvidencePurpose.DEVELOPMENT);
			requireThat(assigned.trials().size(), "assignedTrialCount").isEqualTo(1);
			requireThat(assigned.trials().getFirst().selectionEvidence(), "retainedAssignment").isEqualTo(firstRef);
			requireThat(assigned.trials().getFirst().status(), "trialStatus").isEqualTo(TrialStatus.ASSIGNED);
			requireThat(assigned.scheduler().slots().size(), "nativeCallsBeforeAssignment").isEqualTo(0);

			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef recovered = new TrialAssignmentService(locator, reopened).assignInitialDevelopment("first");
			requireThat(recovered, "recoveredAssignment").isEqualTo(firstRef);
			requireThat(reopened.load().transitionCounter(), "equalRetryCounter").isEqualTo(
				assigned.transitionCounter());
			requireThat(reopened.load().trials().size(), "recoveredTrialCount").isEqualTo(1);
		}
	}

	/**
	 * Different ordinal slots keep separate draws, while each equal retry retains its own selection.
	 *
	 * @throws IOException if fixture or assignment evidence cannot be retained
	 */
	@Test
	public void keepsDistinctNumberedAssignments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("numbered-trial-assignment-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			TrialAssignmentService service = new TrialAssignmentService(locator, store);

			EvidenceRef firstRef = service.assignInitialDevelopment("first", 0);
			EvidenceRef secondRef = service.assignInitialDevelopment("first", 1);

			TrialAssignment first = JsonMapper.builder().build().readValue(store.readImmutableJson(firstRef),
				TrialAssignment.class);
			TrialAssignment second = JsonMapper.builder().build().readValue(store.readImmutableJson(secondRef),
				TrialAssignment.class);
			requireThat(firstRef.equals(secondRef), "separateNumberedDraws").isFalse();
			requireThat(first.logicalTrialId().equals(second.logicalTrialId()), "separateLogicalTrials").isFalse();
			requireThat(first.instance().drawId().equals(second.instance().drawId()), "separateDrawIds").isFalse();
			requireThat(store.load().trials().size(), "twoAssignedTrials").isEqualTo(2);
			requireThat(store.load().scheduler().slots().size(), "noNativeTrialCalls").isEqualTo(0);

			CampaignStore reopened = CampaignStore.open(store.directory());
			TrialAssignmentService resumed = new TrialAssignmentService(locator, reopened);
			requireThat(resumed.assignInitialDevelopment("first", 0), "firstRetry").isEqualTo(firstRef);
			requireThat(resumed.assignInitialDevelopment("first", 1), "secondRetry").isEqualTo(secondRef);
			requireThat(resumed.assignInitialDevelopment("first"), "firstOverload").isEqualTo(firstRef);
			requireThat(reopened.load().trials().size(), "noExtraTrials").isEqualTo(2);
		}
	}

	/**
	 * The same category draw number in a new measurement epoch names another logical assignment.
	 */
	@Test
	public void separatesAssignmentKeysByEpoch()
	{
		LineageKey lineage = new LineageKey("lineage", "contract");
		EvidenceRef firstEpoch = new EvidenceRef("measurement-epochs", "a".repeat(64));
		EvidenceRef nextEpoch = new EvidenceRef("measurement-epochs", "b".repeat(64));
		String original = TrialAssignmentService.initialDevelopmentKey(lineage, firstEpoch, "first", 0);
		String distinct = TrialAssignmentService.initialDevelopmentKey(lineage, nextEpoch, "first", 0);
		requireThat(distinct, "nextEpochAssignmentKey").isNotEqualTo(original);
		requireThat(TrialAssignmentService.initialDevelopmentKey(lineage, firstEpoch, "first", 0),
			"equalEpochAssignmentKey").isEqualTo(original);
	}

	/**
	 * Negative ordinal values cannot reserve a trial or consume a draw.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void rejectsNegativeAssignmentOrdinal() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("negative-trial-ordinal-")).toRealPath();
			CampaignStore store = new CampaignStore(root);
			CampaignLocator locator = new CampaignLocator(root);
			try
			{
				new TrialAssignmentService(locator, store).assignInitialDevelopment("first", -1);
				throw new AssertionError("A negative ordinal cannot assign a development trial");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "negativeTrialOrdinal").contains("ordinal");
			}
			try (Stream<Path> entries = Files.list(root))
			{
				requireThat(entries.count(), "noFilesAfterNegativeOrdinal").isEqualTo(0L);
			}
		}
	}

	/**
	 * An initial-category reference cannot reserve a post-coverage draw or create campaign files.
	 *
	 * @throws IOException if the empty directory cannot be inspected
	 */
	@Test
	public void rejectsInitialDecisionForPostCoverage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("post-coverage-reference-")).toRealPath();
			TrialAssignmentService service = new TrialAssignmentService(new CampaignLocator(root),
				new CampaignStore(root));
			EvidenceRef initialDecision = new EvidenceRef("category-decisions", "a".repeat(64));
			IllegalArgumentException failure = Assert.expectThrows(IllegalArgumentException.class,
				() -> service.assignPostCoverageDevelopment(initialDecision, 0));
			requireThat(failure.getMessage(), "postCoverageReferenceCollection").contains("Post-coverage choice");
			try (Stream<Path> entries = Files.list(root))
			{
				requireThat(entries.count(), "noFilesAfterInitialDecisionReference").isEqualTo(0L);
			}
		}
	}

	/**
	 * The first published batch cannot assign an ordinal reserved for a later category decision.
	 *
	 * @throws IOException when the ordinal exceeds the published batch
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision.*batch.*")
	public void rejectsOrdinalBeyondFirstBatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("beyond-first-batch-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();

			new TrialAssignmentService(locator, store).assignInitialDevelopment("first", 50);
		}
	}

	/**
	 * An unconfirmed registered campaign cannot acquire a development assignment.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void rejectsUnconfirmedAssignment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unconfirmed-trial-assignment-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			try
			{
				new TrialAssignmentService(locator, store).assignInitialDevelopment("first");
				throw new AssertionError("A trial cannot be assigned before contract confirmation");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "confirmationRequired").contains("confirmed");
			}
			requireThat(store.load().trials().size(), "unconfirmedTrialCount").isEqualTo(0);
		}
	}

	/**
	 * A different current candidate cannot receive an assignment through the initial-candidate route.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void rejectsChangedInitialCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("changed-initial-assignment-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			ProspectiveCampaignState.withCandidateState(store, "f".repeat(64), null, List.of());
			try
			{
				new TrialAssignmentService(locator, store).assignInitialDevelopment("first");
				throw new AssertionError("A changed candidate cannot use the first-candidate assignment route");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedInitialCandidate").contains("initial candidate");
			}
			requireThat(store.load().trials().size(), "changedCandidateTrialCount").isEqualTo(0);
		}
	}

	/**
	 * An index published before campaign mutation supplies the same draw after restart.
	 *
	 * @throws IOException if the fixture or retained evidence cannot be opened
	 */
	@Test
	public void recoversPublishedAssignment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("published-trial-assignment-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();
			EvidenceRef categoryRef = new InitialCategoryDecisionService(locator, store).publish();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(store.binding());
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			String key = TrialAssignmentService.initialDevelopmentKey(lineage, epochRef, "first", 0);
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			TrialAssignment unpublished = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				store.binding().campaignId(), lineage, epochRef, categoryRef,
				"trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), 0,
				initial.candidateSnapshotId(), new TrialSampler(contract).sample(EvidencePurpose.DEVELOPMENT, "first"),
				Instant.now());
			EvidenceRef reference = store.appendImmutableJson("trial-assignments",
				JsonMapper.builder().build().writeValueAsBytes(unpublished));
			store.appendCreateOnceIndex("trial-assignment-index", key, new LineageEvidenceRef(lineage,
				reference));
			requireThat(store.load().trials().size(), "trialBeforeRecovery").isEqualTo(0);

			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef recovered = new TrialAssignmentService(locator, reopened).assignInitialDevelopment("first");

			requireThat(recovered, "publishedDraw").isEqualTo(reference);
			requireThat(reopened.load().trials().size(), "committedAfterRecovery").isEqualTo(1);
			requireThat(reopened.load().trials().getFirst().selectionEvidence(), "recoveredSelection").
				isEqualTo(reference);
		}
	}

	/**
	 * A committed assignment repairs a missing index without replacing its original draw.
	 *
	 * @throws IOException if the fixture or retained evidence cannot be opened
	 */
	@Test
	public void repairsMissingAssignmentIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("missing-trial-index-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef original = new TrialAssignmentService(locator, store).assignInitialDevelopment("first");
			long counter = store.load().transitionCounter();
			try (Stream<Path> entries = Files.list(store.directory().resolve("trial-assignment-index")))
			{
				Files.delete(entries.findFirst().orElseThrow());
			}

			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef recovered = new TrialAssignmentService(locator, reopened).assignInitialDevelopment("first");

			requireThat(recovered, "repairedSelection").isEqualTo(original);
			requireThat(reopened.load().transitionCounter(), "repairedCounter").isEqualTo(counter);
			requireThat(reopened.load().trials().size(), "repairedTrialCount").isEqualTo(1);
		}
	}

	/**
	 * A direct transition cannot attach a draw after the selected first candidate changes.
	 *
	 * @throws IOException if the fixture or retained evidence cannot be opened
	 */
	@Test
	public void transitionRejectsChangedInitialCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-initial-trial-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			EvidenceRef categoryRef = new InitialCategoryDecisionService(locator, store).publish();
			String changedCandidate = "f".repeat(64);
			ProspectiveCampaignState.withCandidateState(store, changedCandidate, null, List.of());
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(store.binding());
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef epochRef = store.load().measurementState().epochRef();
			String key = TrialAssignmentService.initialDevelopmentKey(lineage, epochRef, "first", 0);
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			TrialAssignment forged = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				store.binding().campaignId(), lineage, epochRef, categoryRef,
				"trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), 0, changedCandidate,
				new TrialSampler(contract).sample(EvidencePurpose.DEVELOPMENT, "first"), Instant.now());
			EvidenceRef reference = store.appendImmutableJson("trial-assignments",
				JsonMapper.builder().build().writeValueAsBytes(forged));
			store.appendCreateOnceIndex("trial-assignment-index", key, new LineageEvidenceRef(lineage,
				reference));
			try
			{
				new CampaignTransitions(store).assignDevelopmentTrial(store.binding(), reference);
				throw new AssertionError("A direct transition cannot assign a changed initial candidate");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "initialSelectionRequired").contains("initial candidate");
			}
			requireThat(store.load().trials().size(), "foreignTrialCount").isEqualTo(0);
		}
	}

	/**
	 * A direct transition cannot attach an indexed draw from another measurement epoch.
	 *
	 * @throws IOException if fixture or assignment evidence cannot be retained
	 */
	@Test
	public void transitionRejectsForeignMeasurementEpoch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-epoch-trial-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidate initial = new InitialCandidateService(locator, store).capture();
			EvidenceRef categoryRef = new InitialCategoryDecisionService(locator, store).publish();
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(store.binding());
			LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
			EvidenceRef foreignEpochRef = new EvidenceRef("measurement-epochs", "f".repeat(64));
			String key = TrialAssignmentService.initialDevelopmentKey(lineage, foreignEpochRef, "first", 0);
			ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
			TrialAssignment foreign = new TrialAssignment(TrialAssignment.SCHEMA_VERSION,
				store.binding().campaignId(), lineage, foreignEpochRef, categoryRef,
				"trial-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), 0,
				initial.candidateSnapshotId(),
				new TrialSampler(contract).sample(EvidencePurpose.DEVELOPMENT, "first"), Instant.now());
			EvidenceRef reference = store.appendImmutableJson("trial-assignments",
				JsonMapper.builder().build().writeValueAsBytes(foreign));
			store.appendCreateOnceIndex("trial-assignment-index", key,
				new LineageEvidenceRef(lineage, reference));
			try
			{
				new CampaignTransitions(store).assignDevelopmentTrial(store.binding(), reference);
				throw new AssertionError("A foreign measurement epoch cannot assign a trial");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignMeasurementEpoch").contains("measurement epoch");
			}
			requireThat(store.load().trials().size(), "foreignEpochTrialCount").isEqualTo(0);
		}
	}

	/**
	 * Creates one controlled authoring result with a retained terminal event and explicit observation limits.
	 *
	 * @param request original native generation request
	 * @param preStart indexed run and runner identity
	 * @param executionProject reported native workspace
	 * @param eventRef immutable terminal event
	 * @return one-turn native result
	 */
	private static NestedRunResult createGenerationResult(CandidateGenerationRunRequest request,
		CallPreStartRecord preStart, Path executionProject, EvidenceRef eventRef)
	{
		NativeEventEvidence event = new NativeEventEvidence("generation-conversation", 1, eventRef);
		List<ObservationCoverage> coverage = Stream.of(ObservationDomain.values()).map(domain ->
			new ObservationCoverage(domain, ObservationCoverageStatus.LIMITED,
				Optional.of("Controlled generation fixture does not observe native instruction or tool paths"), List.of())).
			toList();
		return new NestedRunResult("test", preStart.runId(), executionProject,
			List.of(new TurnResult(1, "generation-conversation", "authored prompt", event)), List.of(event),
			new RunnerIdentity("test", "1", preStart.runtimeManifestSha256(), Optional.of("test-provider"),
				Map.of(), List.of()), request.modelProfile(), List.of(), List.of(), List.of(), coverage);
	}

	/**
	 * Finds one retained call's completion index for an induced publication fault.
	 *
	 * @param store owning campaign
	 * @param logicalCallId original call identity
	 * @return exact completion index file
	 * @throws IOException if the call's index cannot be found or read
	 */
	private static Path findNativeCompletionIndex(CampaignStore store, String logicalCallId) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		try (Stream<Path> entries = Files.list(store.directory().resolve("native-completion-index")))
		{
			for (Path entry : entries.toList())
				if (json.readValue(Files.readAllBytes(entry), NativeCompletionIndexEntry.class).logicalCallId().
					equals(logicalCallId))
					return entry;
		}
		throw new IOException("Native completion index is missing for induced fault: " + logicalCallId);
	}

	/**
	 * Restores one previously observed call slot while preserving the current scheduler owner and registrations.
	 *
	 * @param campaign campaign at the induced fault boundary
	 * @param retainedSlot slot observed before activation was recorded
	 * @return campaign with that call's earlier durable boundary
	 */
	private static Campaign campaignWithRetainedSlot(Campaign campaign, CallSlot retainedSlot)
	{
		SchedulerState scheduler = campaign.scheduler();
		List<CallSlot> slots = scheduler.slots().stream().map(slot ->
		{
			if (slot.logicalCallId().equals(retainedSlot.logicalCallId()))
				return retainedSlot;
			return slot;
		}).toList();
		return campaign.withScheduler(new SchedulerState(scheduler.fence(), scheduler.invocations(), scheduler.stops(),
			slots, scheduler.barrier()));
	}

	/**
	 * Counts native starts and holds their controlled completion.
	 */
	private static final class RecordingAdapter implements Adapter
	{
		private final CompletableFuture<NestedRunCompletion> pending = new CompletableFuture<>();
		private int starts;
		private int reconciliations;
		private int interrupts;
		private NestedRunRequest lastRequest;
		private boolean reconcileAsSettled;
		private boolean reconcileAsActive;
		private AdapterCall lastCall;
		private NestedRunRequest lastReconciledRequest;
		private Optional<String> lastReconciledRun;
		private NestedRunFailure unknownFailure;
		private CampaignStore dispatchStore;
		private CallSlot slotAtDispatch;

		@Override
		public AdapterCall start(NestedRunRequest request, String runId) throws IOException
		{
			++starts;
			lastRequest = request;
			if (dispatchStore != null)
				slotAtDispatch = dispatchStore.readCallSlotClaim(dispatchStore.binding(), request.logicalCallId()).slot();

			lastCall = new AdapterCall()
			{
				@Override
				public String runId()
				{
					return runId;
				}

				@Override
				public CompletableFuture<NestedRunCompletion> completion()
				{
					return pending;
				}

				@Override
				public void interrupt()
				{
					++interrupts;
				}

				@Override
				public void close()
				{
				}
			};
			return lastCall;
		}

		@Override
		public AdapterCallReconciliation reconcile(NestedRunRequest request, Optional<String> knownRunId)
		{
			++reconciliations;
			lastReconciledRequest = request;
			lastReconciledRun = knownRunId;
			if (unknownFailure != null)
				return new AdapterCallReconciliation.Unknown(unknownFailure);
			if (reconcileAsSettled)
				return new AdapterCallReconciliation.Settled(pending.join());
			if (reconcileAsActive)
				return new AdapterCallReconciliation.Active(lastCall);
			throw new AssertionError("Unexpected native reconciliation");
		}
	}
}
