package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.HostToolRequirement;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Checks the retained request for an assigned initial development trial. */
public final class InitialTrialRequestServiceTest
{
	/** Creates the request fixture. */
	public InitialTrialRequestServiceTest()
	{
	}

	/**
	 * An assignment yields an exact isolated request that is recovered without another call or draw.
	 *
	 * @throws IOException if fixture or campaign evidence cannot be read
	 */
	@Test
	public void preparesAssignedInitialTrial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("initial-trial-request-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first");
			TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
			InitialTrialRequestService service = new InitialTrialRequestService(locator, store);

			EvidenceRef requestRef = service.prepare(assignmentRef);

			InitialTrialRunRequest request = JsonMapper.builder().build().readValue(
				store.readImmutableJson(requestRef), InitialTrialRunRequest.class);
			requireThat(requestRef.collection(), "requestCollection").isEqualTo("run-requests");
			requireThat(request.assignmentRef(), "assignedTrial").isEqualTo(assignmentRef);
			requireThat(request.logicalCallId(), "logicalTrialCall").isEqualTo(assignment.logicalTrialId());
			requireThat(request.turns(), "concreteTurns").isEqualTo(assignment.instance().turns());
			requireThat(request.modelProfile(), "confirmedTrialModel").isEqualTo(store.load().optimizationProfile());
			requireThat(request.requiredHostTools(), "confirmedHostTools").isEqualTo(
				List.of(new HostToolRequirement("tool", "tool")));
			requireThat(request.asNested().purpose(), "trialPurpose").isEqualTo(EvidencePurpose.DEVELOPMENT);
			Path copy = request.context().snapshot().projectRoot();
			requireThat(copy.equals(root.resolve("project")), "isolatedProject").isFalse();
			requireThat(Files.readString(copy.resolve("prompt.md")), "candidateTarget").
				isEqualTo("target prompt");
			requireThat(Files.readString(copy.resolve("rules.md")), "loadedSource").
				isEqualTo("loaded rule");
			requireThat(store.load().scheduler().slots().size(), "noNativeCallBeforeAdmission").isEqualTo(0);

			CampaignStore reopened = CampaignStore.open(store.directory());
			EvidenceRef retried = new InitialTrialRequestService(locator, reopened).prepare(assignmentRef);
			requireThat(retried, "equalRequestAfterRestart").isEqualTo(requestRef);
			requireThat(reopened.load().trials().size(), "oneAssignedDraw").isEqualTo(1);
		}
	}

	/**
	 * An unowned selected edit stops assignment and request preparation until the original bytes return.
	 *
	 * @throws IOException if fixture persistence or expected admission refusal fails
	 */
	@Test
	public void rejectsSelectedDriftAcrossInitialHandoff() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("selected-initial-trial-drift-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			Path selectedFile = locator.optimizationRoot(store.binding()).resolve("prompt.md");
			byte[] original = Files.readAllBytes(selectedFile);
			Files.writeString(selectedFile, "unowned selected edit\n");
			IOException changedAssignment = Assert.expectThrows(IOException.class, () ->
				new TrialAssignmentService(locator, store).assignInitialDevelopment("first"));
			requireThat(changedAssignment.getMessage(), "changedBeforeAssignment").contains("clean fork");
			requireThat(store.load().trials().isEmpty(), "noTrialAssignedAfterDrift").isTrue();

			Files.write(selectedFile, original);
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first");
			Files.writeString(selectedFile, "later unowned selected edit\n");
			IOException changedRequest = Assert.expectThrows(IOException.class, () ->
				new InitialTrialRequestService(locator, store).prepare(assignmentRef));
			requireThat(changedRequest.getMessage(), "changedBeforeRequest").contains("clean fork");

			Files.write(selectedFile, original);
			EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
			requireThat(requestRef.collection(), "recoveredInitialRequest").isEqualTo("run-requests");
		}
	}

	/**
	 * A committed trial cannot prepare native execution after its category authority index disappears.
	 *
	 * @throws IOException when the published decision index is missing
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*category decision index.*")
	public void rejectsMissingDecisionIndexBeforeRequest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("request-missing-decision-index-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first");
			try (Stream<Path> entries = Files.list(store.directory().resolve("initial-category-decision-index")))
			{
				Files.delete(entries.findFirst().orElseThrow());
			}

			new InitialTrialRequestService(locator, CampaignStore.open(store.directory())).prepare(assignmentRef);
		}
	}

	/**
	 * A different assignment cannot be turned into a native request.
	 *
	 * @throws IOException if fixture or campaign evidence cannot be read
	 */
	@Test
	public void rejectsForeignAssignment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-trial-request-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			try
			{
				new InitialTrialRequestService(locator, store).prepare(
					new EvidenceRef("trial-assignments", "a".repeat(64)));
				throw new AssertionError("Foreign trial evidence must not publish a native request");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignAssignment").contains("Trial assignment");
			}
			requireThat(store.load().scheduler().slots().size(), "noNativeCalls").isEqualTo(0);
		}
	}

	/**
	 * A retained assignment cannot prepare a call after the campaign selects another candidate.
	 *
	 * @throws IOException if fixture or campaign evidence cannot be read
	 */
	@Test
	public void rejectsChangedCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("changed-trial-request-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			new InitialCandidateService(locator, store).capture();
			new InitialCategoryDecisionService(locator, store).publish();
			EvidenceRef assignmentRef = new TrialAssignmentService(locator, store).
				assignInitialDevelopment("first");
			ProspectiveCampaignState.withCandidateState(store, "f".repeat(64), null, List.of());
			try
			{
				new InitialTrialRequestService(locator, store).prepare(assignmentRef);
				throw new AssertionError("An earlier candidate assignment must not prepare a new call");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "changedCandidate").contains("not current");
			}
			requireThat(store.load().scheduler().slots().size(), "noNativeCalls").isEqualTo(0);
		}
	}
}
