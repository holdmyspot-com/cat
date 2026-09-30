package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ContractTestFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests selected-and-applied candidate state and durable campaign discovery.
 */
public final class CandidateStateAndLocatorTest
{
	private static final String CANDIDATE_A = "a".repeat(64);
	private static final String CONTRACT = "d".repeat(64);
	private static final String INITIAL_LINEAGE_ID = "lineage-1-" + CONTRACT;

	/** Creates the test fixture. */
	public CandidateStateAndLocatorTest()
	{
	}

	/**
	 * Registry insertion refuses nested or differently named real campaign directories.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsNoncanonicalDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("nested-registry-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("nested-project-").toRealPath());
			Path nested = Files.createDirectory(workflowRoot.resolve("nested")).resolve("campaign-campaign");
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			for (Path directory : List.of(nested, workflowRoot.resolve("wrong-name")))
			{
				CampaignStore store = CampaignStore.initialize(directory, "campaign", profile);
				CampaignHandle handle = new CampaignHandle("campaign", store.directory(), store.load().status(), null, 0,
					Files.getLastModifiedTime(directory.resolve("campaign.json")).toInstant(), false);
				try
				{
					locator.register(new CampaignLookupKey(project, Path.of("prompt.md")), handle);
					throw new AssertionError("Noncanonical campaign must not be registered");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains("direct child");
				}
			}
			requireThat(Files.exists(workflowRoot.resolve("campaign-index.json")), "index").isFalse();
		}
	}

	/**
	 * A workflow-root alias cannot create a campaign beneath a second registry identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void createRejectsWorkflowRootAlias() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path holder = cleanup.add(Files.createTempDirectory("registry-root-alias-").toRealPath());
			Path workflowRoot = Files.createDirectory(holder.resolve("real"));
			Path alias = Files.createSymbolicLink(holder.resolve("alias"), workflowRoot);
			Path project = cleanup.add(Files.createTempDirectory("registry-alias-project-").toRealPath());
			CampaignLocator locator = new CampaignLocator(alias);
			try
			{
				locator.createOrFind(new CampaignLookupKey(project, Path.of("prompt.md")),
					new CampaignCreationRequest("campaign", new ModelProfile("model-1", Optional.empty(), Map.of())));
				throw new AssertionError("Aliased workflow root must not create a campaign");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "aliasFailure").contains("canonical");
			}
			requireThat(Files.exists(workflowRoot.resolve("campaign-campaign")), "noCampaign").isFalse();
			requireThat(Files.exists(workflowRoot.resolve("campaign-index.lock")), "noRegistryLock").isFalse();
		}
	}

	/**
	 * Verifies prospective selected, applied, and reserved candidate state survives a cold read.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void loadsProspectiveCandidateAfterRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-state-").toRealPath());
			CampaignStore store = prepared(directory);
			Campaign selected = ProspectiveCampaignState.withCandidateState(store, CANDIDATE_A, null, List.of());
			Campaign selectedAfterRestart = new CampaignStore(directory).load();
			requireThat(selectedAfterRestart, "selectedAfterRestart").isEqualTo(selected);
			requireThat(selectedAfterRestart.currentCandidateId(), "selectedCandidate").isEqualTo(CANDIDATE_A);
			requireThat(selectedAfterRestart.appliedCandidateId(), "unappliedCandidate").isNull();

			CandidateAllowance allowance = new CandidateAllowance(CANDIDATE_A, 1);
			Campaign applied = ProspectiveCampaignState.withCandidateState(store, CANDIDATE_A, CANDIDATE_A,
				List.of(allowance));
			Campaign appliedAfterRestart = new CampaignStore(directory).load();
			requireThat(appliedAfterRestart, "appliedAfterRestart").isEqualTo(applied);
			requireThat(appliedAfterRestart.appliedCandidateId(), "appliedCandidate").isEqualTo(CANDIDATE_A);
			requireThat(appliedAfterRestart.lineages().getFirst().candidates(), "allowances").isEqualTo(List.of(allowance));
			requireThat(appliedAfterRestart.lineages().getFirst().nextCandidateIndex(), "nextIndex").isEqualTo(2);
		}
	}

	/**
	 * Verifies malformed locator contents fail closed instead of returning unvalidated campaigns.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsCorruptLocatorIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-root-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-root-").toRealPath());
			Files.writeString(workflowRoot.resolve("campaign-index.json"), "{");
			try
			{
				new CampaignLocator(workflowRoot).find(new CampaignLookupKey(project, Path.of("src/Main.java")));
				throw new AssertionError("Malformed index must fail closed");
			}
			catch (IOException _)
			{
			}
		}
	}

	/**
	 * One canonical key admits one active campaign across repeated preparation requests.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void createOrFindKeepsOneActiveCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-create-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-create-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignRegistration first = locator.createOrFind(key, new CampaignCreationRequest("campaign-a", profile));
			CampaignRegistration second = new CampaignLocator(workflowRoot).createOrFind(key,
				new CampaignCreationRequest("campaign-b", profile));
			requireThat(first.status(), "firstStatus").isEqualTo(CampaignRegistration.Status.CREATED);
			requireThat(second.status(), "secondStatus").
				isEqualTo(CampaignRegistration.Status.EXISTING_CAMPAIGN_ACTION_REQUIRED);
			requireThat(second.handle().campaignId(), "existingId").isEqualTo("campaign-a");
			requireThat(locator.find(key).size(), "campaignCount").isEqualTo(1);
		}
	}

	/**
	 * A campaign committed before its key entry is discoverable after restart from its creation intent.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recoversCommittedCreationIntent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-create-recovery-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-create-recovery-").toRealPath());
			Path directory = workflowRoot.resolve("campaign-campaign");
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignStore.initialize(directory, "campaign", profile);
			CreationIntentFixture intent = new CreationIntentFixture(1, project.toString(), "src/Main.java",
				"campaign", directory.toString(), profile);
			Files.write(workflowRoot.resolve("campaign-create-intent.json"), JsonMapper.builder().build().
				writeValueAsBytes(intent));
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			List<CampaignHandle> found = new CampaignLocator(workflowRoot).find(key);
			requireThat(found.size(), "recoveredCount").isEqualTo(1);
			requireThat(found.getFirst().campaignId(), "recoveredId").isEqualTo("campaign");
			requireThat(Files.notExists(workflowRoot.resolve("campaign-create-intent.json")), "clearedIntent").isTrue();
		}
	}

	/**
	 * Competing creation requests for one key publish one campaign and return its identity to the loser.
	 *
	 * @throws Exception if fixture I/O or concurrent execution fails
	 */
	@Test
	public void concurrentCreateOrFindHasOneWinner() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-create-race-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-create-race-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService executor = Executors.newFixedThreadPool(2))
			{
				Future<CampaignRegistration> first = executor.submit(() -> createAfterStart(workflowRoot, key,
					new CampaignCreationRequest("campaign-a", profile), ready, start));
				Future<CampaignRegistration> second = executor.submit(() -> createAfterStart(workflowRoot, key,
					new CampaignCreationRequest("campaign-b", profile), ready, start));
				requireThat(ready.await(10, TimeUnit.SECONDS), "creationReady").isTrue();
				start.countDown();
				CampaignRegistration a = first.get();
				CampaignRegistration b = second.get();
				requireThat(a.handle().campaignId(), "sameCampaign").isEqualTo(b.handle().campaignId());
				requireThat(List.of(a.status(), b.status()).stream().filter(status ->
					status == CampaignRegistration.Status.CREATED).count(), "createdCount").isEqualTo(1L);
				requireThat(new CampaignLocator(workflowRoot).find(key).size(), "activeCount").isEqualTo(1);
			}
		}
	}

	/**
	 * The legacy registration entry point cannot attach a second active campaign to an occupied key.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*already occupied.*")
	public void registerRejectsSecondActiveOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-register-race-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-register-race-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			locator.createOrFind(key, new CampaignCreationRequest("campaign-a", profile));
			Path otherDirectory = workflowRoot.resolve("campaign-campaign-b");
			CampaignStore other = CampaignStore.initialize(otherDirectory, "campaign-b", profile);
			CampaignHandle otherHandle = new CampaignHandle("campaign-b", otherDirectory, other.load().status(), null,
				0, Files.getLastModifiedTime(otherDirectory.resolve("campaign.json")).toInstant(), false);
			locator.register(key, otherHandle);
		}
	}

	/**
	 * Registering one campaign under another target leaves its original registration usable after restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void registerRejectsSecondKeyForSameCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-register-second-key-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-register-second-key-").toRealPath());
			CampaignLookupKey firstKey = new CampaignLookupKey(project, Path.of("first.md"));
			CampaignLookupKey secondKey = new CampaignLookupKey(project, Path.of("second.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignHandle handle = locator.createOrFind(firstKey,
				new CampaignCreationRequest("campaign-a", profile)).handle();
			Path indexFile = workflowRoot.resolve("campaign-index.json");
			String originalIndex = Files.readString(indexFile);

			try
			{
				locator.register(secondKey, handle);
				throw new AssertionError("The same campaign must not be registered under another key");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("already registered");
			}

			requireThat(Files.readString(indexFile), "unchangedIndex").isEqualTo(originalIndex);
			CampaignLocator reopened = new CampaignLocator(workflowRoot);
			requireThat(reopened.find(firstKey).size(), "originalRegistrations").isEqualTo(1);
			requireThat(reopened.find(secondKey).size(), "otherRegistrations").isEqualTo(0);
			reopened.requireRegisteredBinding(CampaignStore.open(handle.campaignDirectory()).binding());
		}
	}

	/**
	 * Invalid producer evidence does not leave a confirmation intent behind.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void invalidConfirmationWritesNoIntent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("unavailable-confirm-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("unavailable-project-").toRealPath());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignHandle handle = locator.createOrFind(key,
				new CampaignCreationRequest("campaign", profile)).handle();
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			Campaign before = store.load();
			EvidenceRef record = store.appendImmutableJson("contract-confirmation-requests", "{}".getBytes(
				java.nio.charset.StandardCharsets.UTF_8));
			try
			{
				locator.confirm(new TransitionRequest(store.binding(), 0, TransitionKind.CONFIRMED_CONTRACT,
					record));
				throw new AssertionError("Foreign producer evidence must be rejected");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "invalidProducer").contains("contracts producer");
			}
			requireThat(store.load(), "unchangedCampaign").isEqualTo(before);
			requireThat(Files.exists(workflowRoot.resolve("campaign-confirm-intent.json")), "noIntent").isFalse();
		}
	}

	/**
	 * A source-backed contract for another target cannot activate the registered campaign.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsForeignConfirmationTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("foreign-confirm-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("foreign-project-").toRealPath());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("other.md"));
			CampaignHandle handle = locator.createOrFind(key, new CampaignCreationRequest("campaign",
				ProspectiveCampaignState.profile())).handle();
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recordIn(store);
			try
			{
				fixture.service().confirm(0, fixture.proposal().proposalId(),
					ContractTestFixtures.bytes(fixture.proposal()));
				throw new AssertionError("A foreign target must not be confirmed");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignTarget").contains("target differs");
			}
			requireThat(store.load().transitionCounter(), "unchangedCounter").isEqualTo(0L);
			requireThat(Files.exists(workflowRoot.resolve("campaign-confirm-intent.json")), "noIntent").isFalse();
		}
	}

	/**
	 * A confirmation committed in campaign state rolls its claim index forward after restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recoversCommittedConfirmationClaim() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-confirm-recovery-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-confirm-recovery-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignHandle handle = locator.createOrFind(key,
				new CampaignCreationRequest("campaign", profile)).handle();
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			CampaignBinding binding = store.binding();
			ContractTestFixtures.RecordedFixture fixture = ContractTestFixtures.recordIn(store);
			String proposalId = fixture.proposal().proposalId();
			EvidenceRef producer = new EvidenceRef("contracts", fixture.service().prepareConfirmation(
				proposalId, ContractTestFixtures.bytes(fixture.proposal())).contractId());
			TransitionRequest request = new TransitionRequest(binding, 0, TransitionKind.CONFIRMED_CONTRACT, producer);
			store.commitConfirmation(request);
			Campaign committed = store.load();
			String lineageId = committed.activeLineageId();
			String contractId = producer.evidenceId();
			JsonMapper json = JsonMapper.builder().build();
			Files.write(workflowRoot.resolve("campaign-confirm-intent.json"), json.writeValueAsBytes(
				new ConfirmationIntentFixture(1, key.canonicalProjectRoot().toString(),
					key.normalizedTargetRelativePath().toString(), binding.campaignId(),
					binding.realDirectory().toString(), 0, request.kind(), producer)));

			LatestConfirmedClaim claim = new CampaignLocator(workflowRoot).readLatestConfirmedClaim(binding);
			requireThat(claim.campaignId(), "claimedCampaign").isEqualTo("campaign");
			requireThat(claim.lineageId(), "claimedLineage").isEqualTo(lineageId);
			requireThat(claim.contractId(), "claimedContract").isEqualTo(contractId);
			requireThat(claim.confirmationSequence(), "claimVersion").isEqualTo(1L);
			requireThat(locator.find(key).getFirst().status(), "recoveredDiscoveryStatus").isEqualTo(
				CampaignStatus.DEVELOPING);
			requireThat(new CampaignLocator(workflowRoot).readLatestConfirmedClaim(binding), "equalRecovery").
				isEqualTo(claim);
			int[] rejectedWriterCalls = {0};
			ClaimPublication changed = locator.publishIfLatestClaim(binding, 2, "campaign", lineageId,
				contractId, () ->
				{
					++rejectedWriterCalls[0];
					return store.appendImmutableJson("assessments", "{}".getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
				});
			requireThat(changed.status(), "changedClaim").isEqualTo(ClaimPublication.Status.CLAIM_CHANGED);
			requireThat(rejectedWriterCalls[0], "writerNotInvoked").isEqualTo(0);
			try
			{
				locator.publishIfLatestClaim(binding, 1, "campaign", lineageId, contractId, () ->
				{
					store.appendImmutableJson("assessments", "{}".getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
					throw new IOException("writer failed after bytes");
				});
				throw new AssertionError("Writer failure must not publish a receipt");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "writerFailure").contains("writer failed");
			}
			requireThat(Files.notExists(workflowRoot.resolve("claim-publications")),
				"draftHasNoPublication").isTrue();
			ClaimPublication published = locator.publishIfLatestClaim(binding, 1, "campaign", lineageId,
				contractId, () -> store.appendImmutableJson("assessments", "{}".getBytes(
					java.nio.charset.StandardCharsets.UTF_8)));
			requireThat(published.status(), "publishedStatus").isEqualTo(ClaimPublication.Status.PUBLISHED);
			requireThat(locator.publishIfLatestClaim(binding, 1, "campaign", lineageId, contractId,
				() -> store.appendImmutableJson("assessments", "{}".getBytes(
						java.nio.charset.StandardCharsets.UTF_8))), "publicationReplay").isEqualTo(published);
			ProspectiveCampaignState.cancelled(store);
			requireThat(new CampaignLocator(workflowRoot).readLatestConfirmedClaim(binding), "cancelledClaim").
				isEqualTo(claim);
			CampaignHandle unconfirmed = locator.createOrFind(key,
				new CampaignCreationRequest("campaign-b", profile)).handle();
			requireThat(new CampaignLocator(workflowRoot).readLatestConfirmedClaim(
				new CampaignStore(unconfirmed.campaignDirectory()).binding()), "newUnconfirmedDoesNotSupersede").
				isEqualTo(claim);
			try (Stream<Path> files = Files.list(workflowRoot.resolve("latest-confirmed-claims")))
			{
				Files.writeString(files.findFirst().orElseThrow(), "{}");
			}
			try
			{
				new CampaignLocator(workflowRoot).readLatestConfirmedClaim(binding);
				throw new AssertionError("Corrupt claim must fail closed");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "corruptClaim").contains("malformed");
			}
		}
	}

	/**
	 * A crash after confirmation intent but before campaign commit leaves no claim.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void discardsUncommittedConfirmationIntent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-confirm-uncommitted-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-confirm-uncommitted-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignHandle handle = locator.createOrFind(key,
				new CampaignCreationRequest("campaign", profile)).handle();
			CampaignStore store = CampaignStore.open(handle.campaignDirectory());
			EvidenceRef producer = store.appendImmutableJson("confirmed-contract-records",
				"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			CampaignBinding binding = store.binding();
			JsonMapper json = JsonMapper.builder().build();
			Files.write(workflowRoot.resolve("campaign-confirm-intent.json"), json.writeValueAsBytes(
				new ConfirmationIntentFixture(1, key.canonicalProjectRoot().toString(),
					key.normalizedTargetRelativePath().toString(), binding.campaignId(),
					binding.realDirectory().toString(), 0, TransitionKind.CONFIRMED_CONTRACT, producer)));
			requireThat(store.load().transitionCounter(), "unchangedCounter").isEqualTo(0L);
			try
			{
				new CampaignLocator(workflowRoot).readLatestConfirmedClaim(store.binding());
				throw new AssertionError("Uncommitted intent must not publish a claim");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingClaim").contains("missing");
			}
			requireThat(Files.notExists(workflowRoot.resolve("campaign-confirm-intent.json")),
				"discardedIntent").isTrue();
		}
	}

	private record ConfirmationIntentFixture(int schemaVersion, String projectRoot, String targetPath,
		String campaignId, String campaignDirectory, long expectedCounter, TransitionKind kind,
		EvidenceRef producerRecordRef)
	{
	}

	/**
	 * Starts one competing campaign creation at a controlled gate.
	 *
	 * @param workflowRoot shared workflow root
	 * @param key shared project and target key
	 * @param request distinct creation request
	 * @param ready readiness latch
	 * @param start start latch
	 * @return creation or existing-campaign result
	 * @throws IOException if state publication fails
	 * @throws InterruptedException if the concurrent test is interrupted
	 */
	private static CampaignRegistration createAfterStart(Path workflowRoot, CampaignLookupKey key,
		CampaignCreationRequest request, CountDownLatch ready, CountDownLatch start)
		throws IOException, InterruptedException
	{
		ready.countDown();
		start.await();
		return new CampaignLocator(workflowRoot).createOrFind(key, request);
	}

	private record CreationIntentFixture(int schemaVersion, String projectRoot, String targetPath,
		String campaignId, String campaignDirectory, ModelProfile optimizationProfile)
	{
	}

	/**
	 * Rejects invalid entries anywhere in the index before returning a matching campaign.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void validatesEveryLocatorEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-root-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-root-").toRealPath());
			Path campaignDirectory = workflowRoot.resolve("campaign-campaign");
			prepared(campaignDirectory);
			CampaignHandle handle = new CampaignHandle("campaign", campaignDirectory, CampaignStatus.DEVELOPING,
				INITIAL_LINEAGE_ID, 1, Files.getLastModifiedTime(campaignDirectory.resolve("campaign.json")).toInstant(),
				false);
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			locator.register(key, handle);
			String index = Files.readString(workflowRoot.resolve("campaign-index.json"));
			String entry = index.substring(index.indexOf('[') + 1, index.lastIndexOf(']'));
			for (String malformed : List.of(index.replace("\"campaignId\" : \"campaign\"", "\"campaignId\" : \"\""),
				index.replace("\"targetPath\" : \"src/Main.java\"", "\"targetPath\" : \"src/../src/Main.java\""),
				index.replace(campaignDirectory.toString(), "/outside-workflow"),
				"{\"campaigns\": [" + entry + "," + entry + "]}",
				"{\"campaigns\": [" + entry + "," + entry.replace("\"targetPath\" : \"src/Main.java\"",
					"\"targetPath\" : \"other.md\"") + "]}"))
			{
				Files.writeString(workflowRoot.resolve("campaign-index.json"), malformed);
				try
				{
					locator.find(key);
					throw new AssertionError("Invalid locator entry must fail before lookup");
				}
				catch (IOException _)
				{
				}
			}
		}
	}

	/**
	 * Verifies concurrent stale-handle refreshes atomically replace the one indexed summary exactly once.
	 *
	 * @throws Exception if the fixture cannot be used
	 */
	@Test
	public void refreshesStaleHandleAtomically() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-root-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-root-").toRealPath());
			Path campaignDirectory = workflowRoot.resolve("campaign-campaign");
			CampaignStore store = prepared(campaignDirectory);
			CampaignHandle stale = new CampaignHandle("campaign", campaignDirectory, CampaignStatus.DEVELOPING,
				INITIAL_LINEAGE_ID, 1, Files.getLastModifiedTime(campaignDirectory.resolve("campaign.json")).toInstant(),
				false);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			new CampaignLocator(workflowRoot).register(key, stale);
			ProspectiveCampaignState.withStatus(store, CampaignStatus.FORMAL_TESTING);
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService executor = Executors.newFixedThreadPool(2))
			{
				Future<Boolean> first = executor.submit(() -> refresh(new CampaignLocator(workflowRoot), stale, ready, start));
				Future<Boolean> second = executor.submit(() -> refresh(new CampaignLocator(workflowRoot), stale, ready, start));
				requireThat(ready.await(10, TimeUnit.SECONDS), "refreshReady").isTrue();
				start.countDown();
				requireThat(refreshSuccesses(first.get(), second.get()), "refreshSuccesses").isEqualTo(1);
			}
			requireThat(new CampaignLocator(workflowRoot).find(key).getFirst().transitionCounter(), "indexedCounter").
			isEqualTo(2L);
		}
	}

	/**
	 * Verifies a child JVM reopens a campaign using only durable workflow-root, project-root, and target-path inputs.
	 *
	 * @throws Exception if the module-launched probe cannot run
	 */
	@Test
	public void discoversCampaignInFreshJvm() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-root-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-root-").toRealPath());
			Path campaignDirectory = workflowRoot.resolve("campaign-campaign");
			CampaignStore store = prepared(campaignDirectory);
			CampaignHandle stale = new CampaignHandle("campaign", campaignDirectory, CampaignStatus.DEVELOPING,
				INITIAL_LINEAGE_ID, 1, Files.getLastModifiedTime(campaignDirectory.resolve("campaign.json")).toInstant(),
				false);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/Main.java"));
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			locator.register(key, stale);
			ProspectiveCampaignState.withStatus(store, CampaignStatus.FORMAL_TESTING);
			locator.refresh(stale);

			try (Process process = new ProcessBuilder(javaExecutable().toString(),
				"--module-path", System.getProperty("jdk.module.path"),
				"--patch-module", "io.github.cowwoc.cat.common.cli=" + testClasses(), "--module",
				"io.github.cowwoc.cat.common.cli/io.github.cowwoc.cat.common.optimizer.state.CampaignLocatorDiscoveryProbe",
				workflowRoot.toString(), project.toString(), "src/Main.java").redirectErrorStream(true).start())
			{
				String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				if (process.waitFor() != 0)
					throw new AssertionError("Child JVM discovery failed: " + output);
				requireThat(output.lines().toList(), "childDiscovery").isEqualTo(List.of("campaign", "2", "FORMAL_TESTING"));
			}
		}
	}

	/**
	 * Verifies malformed persisted candidate identities are rejected on a cold campaign read.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsMalformedPersistedCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("candidate-state-").toRealPath());
			prepared(directory);
			Path campaignFile = directory.resolve("campaign.json");
			byte[] original = Files.readAllBytes(campaignFile);
			JsonMapper json = JsonMapper.builder().build();
			for (String candidate : List.of("upper".repeat(16), "a".repeat(63)))
			{
				ObjectNode document = (ObjectNode) json.readTree(original);
				document.put("currentCandidateId", candidate);
				byte[] malformed = json.writeValueAsBytes(document);
				Files.write(campaignFile, malformed);
				try
				{
					new CampaignStore(directory).load();
					throw new AssertionError("Malformed persisted candidate must fail");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "malformedFailure").contains("malformed");
					requireThat(Files.readAllBytes(campaignFile), "unchangedBytes").isEqualTo(malformed);
				}
			}
		}
	}

	/**
	 * Verifies a fresh locator resolves canonical root aliases, replaces an exact stale indexed handle, and rejects
	 * fabricated summaries.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void discoversAndValidatesCampaignHandles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("workflow-root-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("project-root-").toRealPath());
			Path campaignDirectory = workflowRoot.resolve("campaign-campaign");
			CampaignStore store = prepared(campaignDirectory);
			CampaignHandle handle = new CampaignHandle("campaign", campaignDirectory, CampaignStatus.DEVELOPING,
				INITIAL_LINEAGE_ID, 1, Files.getLastModifiedTime(campaignDirectory.resolve("campaign.json")).toInstant(),
				false);
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("src/../src/Main.java"));
			locator.register(key, handle);
			requireThat(locator.refresh(handle), "refreshedHandle").isEqualTo(handle);
			CampaignLookupKey alias = new CampaignLookupKey(project.resolve("."), Path.of("src/Main.java"));
			List<CampaignHandle> found = new CampaignLocator(workflowRoot).find(alias);
			requireThat(found, "freshDiscovery").isEqualTo(List.of(handle));
			ProspectiveCampaignState.withStatus(store, CampaignStatus.FORMAL_TESTING);
			CampaignHandle refreshed = locator.refresh(handle);
			requireThat(refreshed.transitionCounter(), "refreshedCounter").isEqualTo(2L);
			requireThat(refreshed.status(), "refreshedStatus").isEqualTo(CampaignStatus.FORMAL_TESTING);
			CampaignHandle fabricated = new CampaignHandle("campaign", campaignDirectory, CampaignStatus.DEVELOPING,
				INITIAL_LINEAGE_ID, refreshed.transitionCounter(), refreshed.updatedAt(), false);
			try
			{
				locator.refresh(fabricated);
				throw new AssertionError("Fabricated current-counter handle must fail");
			}
			catch (IOException _)
			{
			}
			ProspectiveCampaignState.withStatus(store, CampaignStatus.DEVELOPING);
			try
			{
				new CampaignLocator(workflowRoot).find(alias);
				throw new AssertionError("Stale indexed handle must fail closed");
			}
			catch (IOException _)
			{
			}
		}
	}

	/**
	 * Creates an active campaign with its initial lineage.
	 *
	 * @param directory campaign directory
	 * @return prepared campaign store
	 * @throws IOException if persistence fails
	 */
	private static CampaignStore prepared(Path directory) throws IOException
	{
		CampaignStore store = new CampaignStore(directory);
		ProspectiveCampaignState.initialize(store, "campaign");
		ProspectiveCampaignState.addLineage(store, CONTRACT);
		return store;
	}

	/**
	 * Refreshes one shared stale handle after both callers are ready.
	 *
	 * @param locator locator under test
	 * @param handle stale indexed handle
	 * @param ready caller readiness latch
	 * @param start common release latch
	 * @return whether refresh replaced the index
	 * @throws Exception if synchronization fails
	 */
	private static boolean refresh(CampaignLocator locator, CampaignHandle handle, CountDownLatch ready,
		CountDownLatch start) throws Exception
	{
		ready.countDown();
		start.await();
		try
		{
			locator.refresh(handle);
			return true;
		}
		catch (IOException _)
		{
			return false;
		}
	}

	/**
	 * Counts the successful refreshes.
	 *
	 * @param first first outcome
	 * @param second second outcome
	 * @return number of successful refreshes
	 */
	private static int refreshSuccesses(boolean first, boolean second)
	{
		int result = 0;
		if (first)
			result += 1;
		if (second)
			result += 1;
		return result;
	}

	/**
	 * Returns the project test-class directory patched into the named module for the probe launch.
	 *
	 * @return compiled test-class directory
	 */
	private static Path testClasses()
	{
		return Path.of("target/test-classes").toAbsolutePath().normalize();
	}

	/**
	 * Returns the Java launcher used by the project test JVM.
	 *
	 * @return Java executable path
	 */
	private static Path javaExecutable()
	{
		return Path.of(System.getProperty("java.home"), "bin", "java");
	}
}
