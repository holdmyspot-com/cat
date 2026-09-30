package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import io.github.cowwoc.cat.common.optimizer.state.Campaign;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCapture;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCaptureService;
import io.github.cowwoc.cat.common.optimizer.state.LatestConfirmedClaim;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Checks the first candidate's exact retained prompt-system snapshot. */
public final class InitialCandidateServiceTest
{
	/** Creates the test fixture. */
	public InitialCandidateServiceTest()
	{
	}

	/**
	 * The initial candidate uses source-capture bytes even if the live worktree later changes.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void capturesInitialPromptSystem() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("initial-candidate-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			Files.writeString(root.resolve("project/rules.md"), "later base-only edit");
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			InitialCandidateService service = new InitialCandidateService(locator, store);
			InitialCandidate first = service.capture();
			Campaign selectedCampaign = store.load();
			requireThat(selectedCampaign.currentCandidateId(), "activeInitialCandidate").isEqualTo(
				first.candidateSnapshotId());
			PromptSnapshot selected = new PromptSnapshotRepository(store.directory()).load(first.candidateSnapshotId());
			requireThat(selected.entries().keySet(), "promptSystemPaths").isEqualTo(Set.of(
				Path.of("prompt.md"), Path.of("rules.md")));
			requireThat(new String(selected.entries().get(Path.of("prompt.md")).bytes(), StandardCharsets.UTF_8),
				"originalTarget").isEqualTo("target prompt");
			Files.writeString(root.resolve("project/prompt.md"), "later worktree edit");
			Files.delete(store.directory().resolve("initial-candidate-index").resolve(first.lineageId() + ".json"));
			InitialCandidate restarted = new InitialCandidateService(locator, CampaignStore.open(store.directory())).
				capture();
			requireThat(restarted, "recoveredInitialCandidate").isEqualTo(first);
			Campaign recovered = CampaignStore.open(store.directory()).load();
			requireThat(recovered.currentCandidateId(),
				"recoveredActiveCandidate").isEqualTo(first.candidateSnapshotId());
			requireThat(recovered.transitionCounter(), "equalRetryCounter").isEqualTo(
				selectedCampaign.transitionCounter());
			requireThat(service.load(), "loadedInitialCandidate").isEqualTo(first);
			requireThat(Files.readString(root.resolve("project/prompt.md")), "unmodifiedUserEdit").isEqualTo(
				"later worktree edit");
		}
	}

	/**
	 * A selected-worktree edit outside CAT's operations stops first-candidate admission.
	 *
	 * @throws IOException if fixture persistence or the expected admission refusal fails
	 */
	@Test
	public void rejectsUnownedEditBeforeInitialCapture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("changed-initial-fork-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			Path selected = locator.optimizationRoot(store.binding());
			Files.writeString(selected.resolve("prompt.md"), "unowned selected edit\n");
			IOException changed = Assert.expectThrows(IOException.class, () ->
				new InitialCandidateService(locator, store).capture());
			requireThat(changed.getMessage(), "changedOptimizationWorktree").contains("clean fork");
			requireThat(store.load().currentCandidateId(), "unselectedCandidate").isNull();
		}
	}

	/**
	 * Redirecting the selected checkout's Git pointer cannot authorize initial candidate admission.
	 *
	 * @throws IOException if fixture persistence or the expected admission refusal fails
	 */
	@Test
	public void rejectsRedirectedOptimizationGitPointer() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("redirected-initial-fork-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			Path selected = locator.optimizationRoot(store.binding());
			Path gitFile = selected.resolve(".git");
			String originalGitBinding = Files.readString(gitFile);
			Files.writeString(gitFile, "gitdir: " + root.resolve("project/.git") + "\n");
			try
			{
				IOException redirected = Assert.expectThrows(IOException.class, () ->
					new InitialCandidateService(locator, store).capture());
				requireThat(redirected.getMessage(), "redirectedOptimizationWorktree").contains(
					"not registered at its reserved path");
				requireThat(store.load().currentCandidateId(), "unselectedCandidate").isNull();
			}
			finally
			{
				Files.writeString(gitFile, originalGitBinding);
			}
		}
	}

	/**
	 * A campaign awaiting contract confirmation has no admitted candidate.
	 *
	 * @throws IOException if fixture persistence fails or admission is refused
	 */
	@Test
	public void rejectsCandidateBeforeConfirmation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("unconfirmed-candidate-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			InitialCandidateService service = new InitialCandidateService(new CampaignLocator(
				fixture.store().directory().getParent()), fixture.store());
			try
			{
				service.capture();
				throw new AssertionError("An unconfirmed campaign cannot admit a candidate");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unconfirmedClaim").contains("claim is missing");
			}
		}
	}

	/**
	 * A direct transition cannot substitute an arbitrary snapshot for the confirmed initial source selection.
	 *
	 * @throws IOException if fixture persistence or the expected admission refusal fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*initial source selection.*")
	public void rejectsForeignInitialSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("forged-initial-candidate-")).toRealPath();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.registered(root);
			fixture.service().confirm(0, fixture.proposal().proposalId(),
				ContractTestFixtures.bytes(fixture.proposal()));
			CampaignStore store = fixture.store();
			CampaignBinding binding = store.binding();
			CampaignLocator locator = new CampaignLocator(store.directory().getParent());
			LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
			EvidenceRef sourceRef = sources.initialCapture(binding);
			InitialTargetCapture capture = sources.load(binding, sourceRef);
			PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
			CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(store.directory(), binding);
			EvidenceRef rootsRef = new ConfirmedSourceAuthorityService(locator, store).load(claim.contractId());
			ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
				ConfirmedEditableRoots.class);
			ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(store.directory(), roots, snapshots);
			PromptSnapshot selected = snapshots.load(capture.snapshotId()).select(
				ConfirmedSourceVerifier.candidatePaths(roots, baseline));
			snapshots.save(selected);
			InitialCandidate genuine = new InitialCandidate(InitialCandidate.SCHEMA_VERSION,
				binding.campaignId(), claim.lineageId(), claim.contractId(), rootsRef, sourceRef, selected.id());
			EvidenceRef genuineRef = evidence.publish("initial-candidates", genuine);
			PromptSnapshotRepository.writeOnce(store.directory().resolve("initial-candidate-index").resolve(
				claim.lineageId() + ".json"), SnapshotJson.mapper().writeValueAsBytes(genuineRef));
			PromptSnapshot targetOnly = snapshots.load(capture.snapshotId()).select(Set.of(Path.of("prompt.md")));
			snapshots.save(targetOnly);
			InitialCandidate forged = new InitialCandidate(InitialCandidate.SCHEMA_VERSION,
				binding.campaignId(), claim.lineageId(), claim.contractId(), rootsRef, sourceRef, targetOnly.id());
			new CampaignTransitions(store).adoptInitialCandidate(binding,
				evidence.publish("initial-candidates", forged));
		}
	}
}
