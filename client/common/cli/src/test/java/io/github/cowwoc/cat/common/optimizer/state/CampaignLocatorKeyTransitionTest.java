package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests recovery of per-key campaign transitions before another campaign observes the key.
 */
public final class CampaignLocatorKeyTransitionTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public CampaignLocatorKeyTransitionTest()
	{
	}

	/**
	 * A restarted lookup finishes a committed cancellation before it opens the stale key entry.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void findRecoversCommittedCancellation() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-cancel-recovery-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-cancel-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			locator.createOrFind(key, new CampaignCreationRequest("campaign-a", profile));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign-a"));
			Campaign current = store.load();
			CampaignBinding binding = store.binding();
			EvidenceRef producer = store.appendImmutableJson("cancellation-records", "{}".getBytes(UTF_8));
			TransitionResult result = new TransitionResult(1, CampaignStatus.CANCELLED, null, null,
				"cancellation-receipt");
			TransitionReceipt receipt = new TransitionReceipt(binding, TransitionKind.CANCELLATION, 0, producer, 1,
				result);
			ProspectiveCampaignState.write(store, new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.CANCELLED, null, null, null, null, 1,
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, List.of(), List.of(), List.of(), profile,
				List.of(receipt), current.scheduler(), current.preparationScope(),
				current.activeFormalFailureTriggerRef(), current.measurementState(), current.terminalResults(),
				current.acknowledgedInterruptionStopRef()));

			BindingDocument bindingDocument = new BindingDocument("campaign-a", binding.realDirectory().toString());
			String requestDigest = digest(json.writeValueAsBytes(new RequestDocument(bindingDocument, 0,
				TransitionKind.CANCELLATION, producer)));
			Map<String, Object> intent = new LinkedHashMap<>();
			intent.put("schemaVersion", 1);
			intent.put("operation", "CANCEL");
			intent.put("key", new KeyDocument(project.toString(), "prompt.md"));
			intent.put("binding", bindingDocument);
			intent.put("expectedCounter", 0);
			intent.put("producerRecordRef", producer);
			intent.put("requestDigest", requestDigest);
			intent.put("expectedOwner", "campaign-a");
			intent.put("expectedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			intent.put("expectedClosedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			Files.write(intentPath, json.writeValueAsBytes(intent));

			CampaignLocator restarted = new CampaignLocator(workflowRoot);
			List<CampaignHandle> found = restarted.find(key);
			requireThat(found.size(), "registeredCampaignCount").isEqualTo(1);
			requireThat(found.getFirst().status(), "recoveredPhase").isEqualTo(CampaignStatus.CANCELLED);
			requireThat(found.getFirst().transitionCounter(), "recoveredCounter").isEqualTo(1L);
			requireThat(new CampaignLocator(workflowRoot).find(key).getFirst().transitionCounter(),
				"equalRestartCounter").isEqualTo(1L);
			requireThat(restarted.createOrFind(key, new CampaignCreationRequest("campaign-b", profile)).status(),
				"releasedKey").isEqualTo(CampaignRegistration.Status.CREATED);
			requireThat(store.load().transitionCounter(), "oneCancellation").isEqualTo(1L);
			requireThat(Files.notExists(intentPath), "completedIntent").isTrue();
		}
	}

	/**
	 * A restarted lookup reacquires a key from a committed resumption without a second transition.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void findRecoversCommittedResumption() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-resume-recovery-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-resume-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignHandle initial = locator.createOrFind(key,
				new CampaignCreationRequest("campaign-a", profile)).handle();
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign-a"));
			CampaignBinding binding = store.binding();
			EvidenceRef cancellation = store.appendImmutableJson("cancellation-records", "{}".getBytes(UTF_8));
			TransitionReceipt cancellationReceipt = new TransitionReceipt(binding, TransitionKind.CANCELLATION, 0,
				cancellation, 1, new TransitionResult(1, CampaignStatus.CANCELLED, null, null,
					"cancellation-receipt"));
			Campaign current = store.load();
			ProspectiveCampaignState.write(store, new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.CANCELLED, null, null, null, null, 1,
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, List.of(), List.of(), List.of(), profile,
				List.of(cancellationReceipt), current.scheduler(), current.preparationScope(),
				current.activeFormalFailureTriggerRef(), current.measurementState(), current.terminalResults(),
				current.acknowledgedInterruptionStopRef()));
			locator.refresh(initial);

			EvidenceRef resumption = store.appendImmutableJson("resumption-records", "{}".getBytes(UTF_8));
			TransitionReceipt resumptionReceipt = new TransitionReceipt(binding, TransitionKind.RESUMPTION, 1,
				resumption, 2, new TransitionResult(2, CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, null, null,
					"resumption-receipt"));
			ProspectiveCampaignState.write(store, new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, null, null, null, null, 2, null,
				List.of(), List.of(), List.of(), profile, List.of(cancellationReceipt, resumptionReceipt),
				current.scheduler(), current.preparationScope(), current.activeFormalFailureTriggerRef(),
				current.measurementState(), current.terminalResults(), current.acknowledgedInterruptionStopRef()));

			BindingDocument bindingDocument = new BindingDocument("campaign-a", binding.realDirectory().toString());
			String requestDigest = digest(json.writeValueAsBytes(new RequestDocument(bindingDocument, 1,
				TransitionKind.RESUMPTION, resumption)));
			Map<String, Object> intent = new LinkedHashMap<>();
			intent.put("schemaVersion", 1);
			intent.put("operation", "RESUME");
			intent.put("key", new KeyDocument(project.toString(), "prompt.md"));
			intent.put("binding", bindingDocument);
			intent.put("expectedCounter", 1);
			intent.put("producerRecordRef", resumption);
			intent.put("requestDigest", requestDigest);
			intent.put("expectedPhase", CampaignStatus.CANCELLED);
			intent.put("expectedClosedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			byte[] canonicalIntent = json.writeValueAsBytes(intent);
			Files.write(intentPath, canonicalIntent);
			byte[] priorIndex = Files.readAllBytes(workflowRoot.resolve("campaign-index.json"));
			intent.put("expectedClosedPhase", CampaignStatus.DEVELOPING);
			Files.write(intentPath, json.writeValueAsBytes(intent));
			Assert.expectThrows(IOException.class, () -> new CampaignLocator(workflowRoot).find(key));
			requireThat(Files.readAllBytes(workflowRoot.resolve("campaign-index.json")), "unchangedIndex").
				isEqualTo(priorIndex);
			requireThat(Files.exists(intentPath), "retainedWrongRestoreIntent").isTrue();
			Files.write(intentPath, canonicalIntent);

			CampaignLocator restarted = new CampaignLocator(workflowRoot);
			List<CampaignHandle> found = restarted.find(key);
			requireThat(found.size(), "registeredCampaignCount").isEqualTo(1);
			requireThat(found.getFirst().status(), "restoredPhase").isEqualTo(
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			requireThat(found.getFirst().transitionCounter(), "recoveredCounter").isEqualTo(2L);
			requireThat(restarted.createOrFind(key, new CampaignCreationRequest("campaign-b", profile)).status(),
				"reacquiredKey").isEqualTo(CampaignRegistration.Status.EXISTING_CAMPAIGN_ACTION_REQUIRED);
			requireThat(new CampaignLocator(workflowRoot).find(key).getFirst().transitionCounter(),
				"equalRestartCounter").isEqualTo(2L);
			requireThat(store.load().transitionCounter(), "oneResumption").isEqualTo(2L);
			requireThat(Files.notExists(intentPath), "completedIntent").isTrue();
		}
	}

	/**
	 * Cancellation after interruption releases the key while retaining its original resumable phase.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void recoversInterruptedCancellation() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-interrupted-cancel-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-interrupted-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignHandle initial = locator.createOrFind(key,
				new CampaignCreationRequest("campaign-a", profile)).handle();
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign-a"));
			CampaignBinding binding = store.binding();
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			inbox.beginInvocation(binding, "key-transition-invocation");
			inbox.submit(binding, "key-transition-invocation", "token");
			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding,
				"key-transition-invocation"))
			{
				StopRequest stop = inbox.adoptPending(lease).orElseThrow();
				new CampaignInterruptionService(locator, store).commitHostStop(lease, stop, Optional.empty());
			}
			Campaign current = store.load();
			TransitionReceipt interruptionReceipt = current.transitionReceipts().getLast();
			locator.refresh(initial);

			EvidenceRef cancellation = store.appendImmutableJson("cancellation-records", "{}".getBytes(UTF_8));
			TransitionReceipt cancellationReceipt = new TransitionReceipt(binding, TransitionKind.CANCELLATION, 1,
				cancellation, 2, new TransitionResult(2, CampaignStatus.CANCELLED, null, null,
					"cancellation-receipt"));
			ProspectiveCampaignState.write(store, new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.CANCELLED, null, null, null, null, 2,
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, List.of(), List.of(), List.of(), profile,
				List.of(interruptionReceipt, cancellationReceipt), current.scheduler(), current.preparationScope(),
				current.activeFormalFailureTriggerRef(), current.measurementState(), current.terminalResults(),
				null));

			BindingDocument bindingDocument = new BindingDocument("campaign-a", binding.realDirectory().toString());
			String requestDigest = digest(json.writeValueAsBytes(new RequestDocument(bindingDocument, 1,
				TransitionKind.CANCELLATION, cancellation)));
			Map<String, Object> intent = new LinkedHashMap<>();
			intent.put("schemaVersion", 1);
			intent.put("operation", "CANCEL");
			intent.put("key", new KeyDocument(project.toString(), "prompt.md"));
			intent.put("binding", bindingDocument);
			intent.put("expectedCounter", 1);
			intent.put("producerRecordRef", cancellation);
			intent.put("requestDigest", requestDigest);
			intent.put("expectedOwner", "campaign-a");
			intent.put("expectedPhase", CampaignStatus.INTERRUPTED);
			intent.put("expectedClosedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			byte[] canonicalIntent = json.writeValueAsBytes(intent);
			Files.write(intentPath, canonicalIntent);
			byte[] priorIndex = Files.readAllBytes(workflowRoot.resolve("campaign-index.json"));
			intent.put("expectedClosedPhase", CampaignStatus.DEVELOPING);
			Files.write(intentPath, json.writeValueAsBytes(intent));
			Assert.expectThrows(IOException.class, () -> new CampaignLocator(workflowRoot).find(key));
			requireThat(Files.readAllBytes(workflowRoot.resolve("campaign-index.json")), "unchangedIndex").
				isEqualTo(priorIndex);
			requireThat(Files.exists(intentPath), "retainedWrongSavedPhaseIntent").isTrue();
			Files.write(intentPath, canonicalIntent);

			CampaignLocator restarted = new CampaignLocator(workflowRoot);
			CampaignHandle found = restarted.find(key).getFirst();
			requireThat(found.status(), "cancelledPhase").isEqualTo(CampaignStatus.CANCELLED);
			requireThat(found.transitionCounter(), "cancelledCounter").isEqualTo(2L);
			requireThat(store.load().closedPhase(), "retainedResumablePhase").isEqualTo(
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			requireThat(restarted.createOrFind(key, new CampaignCreationRequest("campaign-b", profile)).status(),
				"releasedKey").isEqualTo(CampaignRegistration.Status.CREATED);
			requireThat(Files.notExists(intentPath), "completedIntent").isTrue();
		}
	}

	/**
	 * An intent without a campaign commit is discarded without changing the registered campaign.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void findDiscardsUncommittedCancellation() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-cancel-uncommitted-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-cancel-uncommitted-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			locator.createOrFind(key, new CampaignCreationRequest("campaign-a", profile));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign-a"));
			CampaignBinding binding = store.binding();
			EvidenceRef producer = store.appendImmutableJson("cancellation-records", "{}".getBytes(UTF_8));
			BindingDocument bindingDocument = new BindingDocument("campaign-a", binding.realDirectory().toString());
			String requestDigest = digest(json.writeValueAsBytes(new RequestDocument(bindingDocument, 0,
				TransitionKind.CANCELLATION, producer)));
			Map<String, Object> intent = new LinkedHashMap<>();
			intent.put("schemaVersion", 1);
			intent.put("operation", "CANCEL");
			intent.put("key", new KeyDocument(project.toString(), "prompt.md"));
			intent.put("binding", bindingDocument);
			intent.put("expectedCounter", 0);
			intent.put("producerRecordRef", producer);
			intent.put("requestDigest", requestDigest);
			intent.put("expectedOwner", "campaign-a");
			intent.put("expectedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			intent.put("expectedClosedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			Files.write(intentPath, json.writeValueAsBytes(intent));

			List<CampaignHandle> found = new CampaignLocator(workflowRoot).find(key);
			requireThat(found.getFirst().status(), "unchangedPhase").isEqualTo(
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			requireThat(found.getFirst().transitionCounter(), "unchangedCounter").isEqualTo(0L);
			requireThat(store.load().transitionReceipts().size(), "uncommittedReceipts").isEqualTo(0);
			requireThat(Files.notExists(intentPath), "discardedIntent").isTrue();
		}
	}

	/**
	 * An uncommitted cancellation cannot discard its predecessor when another campaign owns the key.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void rejectsConflictingPredecessorOwner() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-conflicting-owner-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-conflicting-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			locator.createOrFind(key, new CampaignCreationRequest("campaign-a", profile));
			locator.createOrFind(new CampaignLookupKey(project, Path.of("other.md")),
				new CampaignCreationRequest("campaign-b", profile));
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign-a"));
			CampaignBinding binding = store.binding();
			EvidenceRef producer = store.appendImmutableJson("cancellation-records", "{}".getBytes(UTF_8));
			BindingDocument bindingDocument = new BindingDocument("campaign-a", binding.realDirectory().toString());
			String requestDigest = digest(json.writeValueAsBytes(new RequestDocument(bindingDocument, 0,
				TransitionKind.CANCELLATION, producer)));
			Map<String, Object> intent = new LinkedHashMap<>();
			intent.put("schemaVersion", 1);
			intent.put("operation", "CANCEL");
			intent.put("key", new KeyDocument(project.toString(), "prompt.md"));
			intent.put("binding", bindingDocument);
			intent.put("expectedCounter", 0);
			intent.put("producerRecordRef", producer);
			intent.put("requestDigest", requestDigest);
			intent.put("expectedOwner", "campaign-a");
			intent.put("expectedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			intent.put("expectedClosedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			Files.write(intentPath, json.writeValueAsBytes(intent));

			Path indexFile = workflowRoot.resolve("campaign-index.json");
			JsonNode index = json.readTree(Files.readAllBytes(indexFile));
			((ObjectNode) index.get("campaigns").get(1)).put("targetPath", "prompt.md");
			byte[] conflictingIndex = json.writeValueAsBytes(index);
			Files.write(indexFile, conflictingIndex);
			Assert.expectThrows(IOException.class, () -> new CampaignLocator(workflowRoot).find(key));
			requireThat(Files.readAllBytes(indexFile), "unchangedConflictingIndex").isEqualTo(conflictingIndex);
			requireThat(Files.exists(intentPath), "retainedConflictingIntent").isTrue();
			requireThat(store.load().transitionCounter(), "unchangedCampaignCounter").isEqualTo(0L);
		}
	}

	/**
	 * An uncommitted resumption cannot erase its intent while a newer campaign occupies the key.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void rejectsOccupiedResumePredecessor() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-occupied-resume-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-occupied-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			CampaignHandle initial = locator.createOrFind(key,
				new CampaignCreationRequest("campaign-a", profile)).handle();
			CampaignStore store = new CampaignStore(workflowRoot.resolve("campaign-campaign-a"));
			CampaignBinding binding = store.binding();
			Campaign current = store.load();
			EvidenceRef cancellation = store.appendImmutableJson("cancellation-records", "{}".getBytes(UTF_8));
			TransitionReceipt cancellationReceipt = new TransitionReceipt(binding, TransitionKind.CANCELLATION, 0,
				cancellation, 1, new TransitionResult(1, CampaignStatus.CANCELLED, null, null,
					"cancellation-receipt"));
			ProspectiveCampaignState.write(store, new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.CANCELLED, null, null, null, null, 1,
				CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, List.of(), List.of(), List.of(), profile,
				List.of(cancellationReceipt), current.scheduler(), current.preparationScope(),
				current.activeFormalFailureTriggerRef(), current.measurementState(), current.terminalResults(),
				current.acknowledgedInterruptionStopRef()));
			locator.refresh(initial);
			locator.createOrFind(key, new CampaignCreationRequest("campaign-b", profile));

			EvidenceRef resumption = store.appendImmutableJson("resumption-records", "{}".getBytes(UTF_8));
			BindingDocument bindingDocument = new BindingDocument("campaign-a", binding.realDirectory().toString());
			String requestDigest = digest(json.writeValueAsBytes(new RequestDocument(bindingDocument, 1,
				TransitionKind.RESUMPTION, resumption)));
			Map<String, Object> intent = new LinkedHashMap<>();
			intent.put("schemaVersion", 1);
			intent.put("operation", "RESUME");
			intent.put("key", new KeyDocument(project.toString(), "prompt.md"));
			intent.put("binding", bindingDocument);
			intent.put("expectedCounter", 1);
			intent.put("producerRecordRef", resumption);
			intent.put("requestDigest", requestDigest);
			intent.put("expectedPhase", CampaignStatus.CANCELLED);
			intent.put("expectedClosedPhase", CampaignStatus.AWAITING_CONTRACT_CONFIRMATION);
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			Files.write(intentPath, json.writeValueAsBytes(intent));
			byte[] priorIndex = Files.readAllBytes(workflowRoot.resolve("campaign-index.json"));

			Assert.expectThrows(IOException.class, () -> new CampaignLocator(workflowRoot).find(key));
			requireThat(Files.readAllBytes(workflowRoot.resolve("campaign-index.json")), "unchangedIndex").
				isEqualTo(priorIndex);
			requireThat(Files.exists(intentPath), "retainedOccupiedIntent").isTrue();
			requireThat(store.load().transitionCounter(), "unchangedCancelledCounter").isEqualTo(1L);
		}
	}

	/**
	 * Duplicate fields cannot create a valid recovery predecessor or mutate its index.
	 *
	 * @throws IOException if fixture or campaign persistence fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	@Test
	public void findRejectsDuplicateIntentFields() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workflowRoot = cleanup.add(Files.createTempDirectory("key-duplicate-intent-").toRealPath());
			Path project = cleanup.add(Files.createTempDirectory("key-duplicate-project-").toRealPath());
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("prompt.md"));
			ModelProfile profile = new ModelProfile("model-1", Optional.empty(), Map.of());
			CampaignLocator locator = new CampaignLocator(workflowRoot);
			locator.createOrFind(key, new CampaignCreationRequest("campaign-a", profile));
			byte[] priorIndex = Files.readAllBytes(workflowRoot.resolve("campaign-index.json"));
			Path intentDirectory = Files.createDirectory(workflowRoot.resolve("campaign-key-transition-intents"));
			String keyDigest = digest(json.writeValueAsBytes(new ClaimKeyDocument(project.toString(), "prompt.md")));
			Path intentPath = intentDirectory.resolve(keyDigest + ".json");
			Files.writeString(intentPath, "{\"schemaVersion\":1,\"schemaVersion\":1}");

			Assert.expectThrows(IOException.class, () -> new CampaignLocator(workflowRoot).find(key));
			requireThat(Files.readAllBytes(workflowRoot.resolve("campaign-index.json")), "unchangedIndex").
				isEqualTo(priorIndex);
			requireThat(Files.exists(intentPath), "retainedInvalidIntent").isTrue();
		}
	}

	/**
	 * Computes the content address of fixture bytes.
	 *
	 * @param bytes canonical fixture bytes
	 * @return lowercase SHA-256 digest
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static String digest(byte[] bytes) throws NoSuchAlgorithmException
	{
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private record KeyDocument(String canonicalProjectRoot, String normalizedTargetRelativePath)
	{
	}

	private record BindingDocument(String campaignId, String realDirectory)
	{
	}

	private record RequestDocument(BindingDocument binding, long expectedCounter, TransitionKind kind,
		EvidenceRef producerRecordRef)
	{
	}

	private record ClaimKeyDocument(String projectRoot, String targetPath)
	{
	}
}
