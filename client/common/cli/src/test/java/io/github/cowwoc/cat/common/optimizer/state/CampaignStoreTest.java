package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests durable campaign state through {@link CampaignStore}.
 */
public final class CampaignStoreTest
{
	private static final String CONTRACT = "d".repeat(64);
	private static final String INITIAL_LINEAGE_ID = "lineage-1-" + CONTRACT;
	private static final String CANDIDATE_A = "a".repeat(64);
	private static final String CANDIDATE_B = "b".repeat(64);

	/** Creates the test fixture. */
	public CampaignStoreTest()
	{
	}

	/**
	 * A round index rejects an assignment reference before reading evidence or creating files.
	 *
	 * @throws IOException if the empty directory cannot be inspected
	 */
	@Test
	public void rejectsRoundIndexTargetInWrongCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("round-index-collection-"));
			CampaignStore store = new CampaignStore(directory);
			LineageEvidenceRef target = new LineageEvidenceRef(new LineageKey("lineage", "contract"),
				new EvidenceRef("trial-assignments", "a".repeat(64)));
			try
			{
				store.appendCreateOnceIndex("development-round-start-index", "foreign-round-probe", target);
				throw new AssertionError("A round index cannot publish a trial assignment reference");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "roundIndexTarget").contains("development-round-starts");
			}
			try (Stream<Path> entries = Files.list(directory))
			{
				requireThat(entries.count(), "noFilesAfterWrongIndexCollection").isEqualTo(0L);
			}
		}
	}

	/**
	 * Verifies prospective allowance and trial state survive a cold campaign read.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void loadsProspectiveAllowanceAndTrialState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-store-"));
			CampaignStore store = prepared(directory);
			CandidateAllowance first = new CandidateAllowance(CANDIDATE_A, 1);
			CandidateAllowance second = new CandidateAllowance(CANDIDATE_B, 2);
			ProspectiveCampaignState.withCandidateState(store, CANDIDATE_B, CANDIDATE_B, List.of(first, second));
			ProspectiveCampaignState.withStatus(store, CampaignStatus.FORMAL_TESTING);
			TrialState trial = new TrialState("trial", INITIAL_LINEAGE_ID, CANDIDATE_B, EvidencePurpose.FORMAL,
				TrialStatus.RETRYABLE, FailureKind.INFRASTRUCTURE,
				new EvidenceRef("selections", "1".repeat(64)), null);
			ProspectiveCampaignState.withTrials(store, List.of(trial));
			Campaign loaded = new CampaignStore(directory).load();
			requireThat(first.candidateIndex(), "firstIndex").isEqualTo(1);
			requireThat(second.candidateIndex(), "secondIndex").isEqualTo(2);
			requireThat(loaded.lineages().getFirst().candidates(), "allowances").isEqualTo(List.of(first, second));
			requireThat(loaded.lineages().getFirst().nextCandidateIndex(), "nextIndex").isEqualTo(3);
			requireThat(loaded.trials().getFirst().selectionEvidence(), "selection").
			isEqualTo(new EvidenceRef("selections", "1".repeat(64)));
			requireThat(loaded.trials().getFirst().status(), "status").isEqualTo(TrialStatus.RETRYABLE);
			requireThat(new CampaignStore(directory).load(), "secondRead").isEqualTo(loaded);
		}
	}

	/**
	 * Verifies malformed campaign bytes fail a cold read.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsMalformedPersistedCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-store-"));
			Files.writeString(directory.resolve("campaign.json"), "{\"schemaVersion\":1}");
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Malformed state must fail");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("malformed");
			}
		}
	}

	/**
	 * Rejects persisted lifecycle combinations that cannot be created through the transition API.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsImpossiblePersistedStates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-lifecycle-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			String initial = Files.readString(directory.resolve("campaign.json"));
			for (String malformed : List.of(
				initial.replace("\"AWAITING_CONTRACT_CONFIRMATION\"", "\"CERTIFIED\""),
				initial.replace("\"closedPhase\":null", "\"closedPhase\":\"FORMAL_TESTING\"")))
			{
				Files.writeString(directory.resolve("campaign.json"), malformed);
				try
				{
					store.load();
					throw new AssertionError("Impossible persisted lifecycle state must fail");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "lifecycleMessage").contains("malformed");
				}
			}
		}
	}

	/**
	 * Rejects a persisted campaign that has no optimization profile after the JSON reader loads it.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsNullOptimizationProfileOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-profile-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.write(store, Campaign.create("campaign",
				new ModelProfile("model-1", Optional.empty(), Map.of())));
			JsonMapper json = JsonMapper.builder().build();
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(directory.resolve("campaign.json")));
			if (document.get("optimizationProfile") == null)
				throw new AssertionError("Fixture must contain an explicit optimization profile field");
			document.putNull("optimizationProfile");
			if (!document.get("optimizationProfile").isNull())
				throw new AssertionError("Fixture must contain an explicit null optimization profile");
			Files.write(directory.resolve("campaign.json"), json.writeValueAsBytes(document));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("A persisted campaign without an optimization profile must fail");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "profileFailure").contains("malformed");
			}
		}
	}

	/**
	 * Rejects a persisted campaign when a required schema-three authority field is absent.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsMissingBridgeFieldsInSchemaThree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-bridge-fields-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.write(store, Campaign.create("campaign",
				new ModelProfile("model-1", Optional.empty(), Map.of())));
			Path campaignFile = directory.resolve("campaign.json");
			byte[] original = Files.readAllBytes(campaignFile);
			JsonMapper json = JsonMapper.builder().build();
			for (String field : List.of("activeFormalFailureTriggerRef", "measurementState", "terminalResults",
				"acknowledgedInterruptionStopRef"))
			{
				ObjectNode document = (ObjectNode) json.readTree(original);
				if (document.get(field) == null)
					throw new AssertionError("Fixture must contain bridge field before removal: " + field);
				document.remove(field);
				if (document.get(field) != null)
					throw new AssertionError("Fixture must omit bridge field after removal: " + field);
				Files.write(campaignFile, json.writeValueAsBytes(document));
				try
				{
					new CampaignStore(directory).load();
					throw new AssertionError("Missing schema-three field must fail: " + field);
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "fieldFailure").contains("malformed");
				}
			}
			ObjectNode nullHistory = (ObjectNode) json.readTree(original);
			nullHistory.putNull("terminalResults");
			Files.write(campaignFile, json.writeValueAsBytes(nullHistory));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Null terminal history must fail a cold campaign read");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "nullHistoryFailure").contains("malformed");
			}
		}
	}

	/**
	 * Rejects a cold campaign read when its active formal-failure trigger has no immutable evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsMissingFormalTriggerOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-trigger-ref-"));
			CampaignStore store = prepared(directory);
			Campaign current = store.load();
			EvidenceRef missing = new EvidenceRef("formal-failure-round-triggers", "f".repeat(64));
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), missing, current.measurementState(),
				current.terminalResults(), current.acknowledgedInterruptionStopRef());
			ProspectiveCampaignState.write(store, prospective);
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Missing formal-failure evidence must fail a cold campaign read");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingTrigger").contains("malformed");
			}
		}
	}

	/**
	 * Verifies a restarted store retains and rehashes every nested result reference it owns.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void retainsResultRefsAcrossRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-result-refs-"));
			CampaignStore store = prepared(directory);
			ProspectiveCampaignState.withCandidateState(store, CANDIDATE_A, CANDIDATE_A,
				List.of(new CandidateAllowance(CANDIDATE_A, 1)));
			Campaign current = ProspectiveCampaignState.addLineage(store, "second-contract");
			EvidenceRef trigger = store.appendImmutableJson("formal-failure-round-triggers", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef epoch = store.appendImmutableJson("measurement-epochs", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef baseline = store.appendImmutableJson("dynamic-baseline-roots", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef change = store.appendImmutableJson("measurement-changes", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef terminal = store.appendImmutableJson("terminal-results", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef result = store.appendImmutableJson("measurement-results", "{}".getBytes(
				StandardCharsets.UTF_8));
			MeasurementState measurement = new MeasurementState(epoch, Optional.of(baseline), Optional.of(
				new MeasurementPause(change, MeasurementPause.Origin.TRIAL, CampaignStatus.DEVELOPING)));
			TerminalResultRef historical = new TerminalResultRef(TerminalResultRef.Kind.CERTIFIED,
				INITIAL_LINEAGE_ID, Optional.of(CANDIDATE_A), terminal, Optional.of(result),
				current.transitionCounter());
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), trigger, measurement, List.of(historical), null);
			ProspectiveCampaignState.write(store, prospective.withScheduler(current.scheduler()).withPreparationScope(
				current.preparationScope()));
			Campaign restarted = new CampaignStore(directory).load();
			requireThat(restarted.activeFormalFailureTriggerRef(), "trigger").isEqualTo(trigger);
			requireThat(restarted.measurementState(), "measurement").isEqualTo(measurement);
			requireThat(restarted.terminalResults(), "terminalHistory").isEqualTo(List.of(historical));
			for (EvidenceRef reference : List.of(trigger, epoch, baseline, change, terminal, result))
			{
				Path evidence = directory.resolve(reference.collection()).resolve(reference.evidenceId() + ".json");
				byte[] original = Files.readAllBytes(evidence);
				try
				{
					Files.delete(evidence);
					try
					{
						new CampaignStore(directory).load();
						throw new AssertionError("Missing result evidence must fail: " + reference);
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "missingEvidence").contains("malformed");
					}
					Files.writeString(evidence, "corrupt");
					try
					{
						new CampaignStore(directory).load();
						throw new AssertionError("Corrupt result evidence must fail: " + reference);
					}
					catch (IOException expected)
					{
						requireThat(expected.getMessage(), "corruptEvidence").contains("malformed");
					}
				}
				finally
				{
					Files.write(evidence, original);
				}
			}
		}
	}

	/**
	 * Rejects malformed durable result fields through a fresh campaign-store read.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsMalformedOwnedResultFieldsOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-result-shape-"));
			CampaignStore store = prepared(directory);
			Campaign current = ProspectiveCampaignState.addLineage(store, "second-contract");
			EvidenceRef trigger = store.appendImmutableJson("formal-failure-round-triggers", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef epoch = store.appendImmutableJson("measurement-epochs", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef change = store.appendImmutableJson("measurement-changes", "{}".getBytes(
				StandardCharsets.UTF_8));
			EvidenceRef root = store.appendImmutableJson("terminal-results", "{}".getBytes(
				StandardCharsets.UTF_8));
			MeasurementState measurement = new MeasurementState(epoch, Optional.empty(), Optional.of(
				new MeasurementPause(change, MeasurementPause.Origin.TRIAL, CampaignStatus.DEVELOPING)));
			TerminalResultRef terminal = new TerminalResultRef(TerminalResultRef.Kind.PLATEAU,
				INITIAL_LINEAGE_ID, Optional.empty(), root, Optional.empty(), current.transitionCounter());
			ProspectiveCampaignState.write(store, new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				current.status(), current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), trigger, measurement, List.of(terminal), null));
			Path campaignFile = directory.resolve("campaign.json");
			byte[] original = Files.readAllBytes(campaignFile);
			JsonMapper json = JsonMapper.builder().build();
			for (String corruption : List.of("triggerCollection", "epochCollection", "pauseOrigin", "pausePhase",
				"terminalCollection", "terminalKind"))
			{
				ObjectNode document = (ObjectNode) json.readTree(original);
				switch (corruption)
				{
					case "triggerCollection" -> ((ObjectNode) document.get("activeFormalFailureTriggerRef")).put(
						"collection", "wrong");
					case "epochCollection" -> ((ObjectNode) document.get("measurementState").get("epochRef")).put(
						"collection", "wrong");
					case "pauseOrigin" -> ((ObjectNode) document.get("measurementState").get("pause")).put(
						"origin", "FINALIZATION");
					case "pausePhase" -> ((ObjectNode) document.get("measurementState").get("pause")).put(
						"savedPhase", "CERTIFIED");
					case "terminalCollection" -> ((ObjectNode) document.get("terminalResults").get(0).get(
						"terminalRootRef")).put("collection", "wrong");
					case "terminalKind" -> ((ObjectNode) document.get("terminalResults").get(0)).put("kind",
						"CERTIFIED");
					default -> throw new AssertionError("Unexpected fixture corruption: " + corruption);
				}
				Files.write(campaignFile, json.writeValueAsBytes(document));
				try
				{
					new CampaignStore(directory).load();
					throw new AssertionError("Malformed result field must fail: " + corruption);
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "malformed" + corruption).contains("malformed");
				}
			}
		}
	}

	/**
	 * Rejects a retained terminal result whose lineage is absent from campaign history.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsForeignTerminalLineageOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-terminal-lineage-"));
			CampaignStore store = prepared(directory);
			Campaign current = store.load();
			EvidenceRef root = store.appendImmutableJson("terminal-results", "{}".getBytes(StandardCharsets.UTF_8));
			TerminalResultRef terminal = new TerminalResultRef(TerminalResultRef.Kind.PLATEAU,
				current.activeLineageId(),
				Optional.empty(), root, Optional.empty(), current.transitionCounter());
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), null, null, List.of(terminal), null);
			ProspectiveCampaignState.write(store, prospective);
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = directory.resolve("campaign.json");
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			((ObjectNode) document.get("terminalResults").get(0)).put("lineageId", "foreign-lineage");
			Files.write(campaignFile, json.writeValueAsBytes(document));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Foreign terminal lineage must fail a cold campaign read");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignTerminal").contains("malformed");
			}
		}
	}

	/**
	 * Rejects a terminal result whose claimed commit counter is later than the campaign commit.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsFutureTerminalCounterOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-terminal-counter-"));
			CampaignStore store = prepared(directory);
			Campaign current = ProspectiveCampaignState.addLineage(store, "second-contract");
			EvidenceRef root = store.appendImmutableJson("terminal-results", "{}".getBytes(StandardCharsets.UTF_8));
			TerminalResultRef terminal = new TerminalResultRef(TerminalResultRef.Kind.PLATEAU,
				INITIAL_LINEAGE_ID, Optional.empty(), root, Optional.empty(), current.transitionCounter());
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), null, null, List.of(terminal), null);
			ProspectiveCampaignState.write(store, prospective);
			requireThat(new CampaignStore(directory).load().terminalResults(), "validHistory").
				isEqualTo(List.of(terminal));
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = directory.resolve("campaign.json");
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			((ObjectNode) document.get("terminalResults").get(0)).put("resultingCounter",
				current.transitionCounter() + 1);
			Files.write(campaignFile, json.writeValueAsBytes(document));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Future terminal counter must fail a cold campaign read");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "futureTerminal").contains("malformed");
			}
		}
	}

	/**
	 * Rejects reused immutable roots and repeated counters in historical terminal entries.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsReusedTerminalIdentityOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-terminal-root-"));
			CampaignStore store = prepared(directory);
			ProspectiveCampaignState.addLineage(store, "second-contract");
			Campaign current = ProspectiveCampaignState.addLineage(store, "third-contract");
			EvidenceRef root = store.appendImmutableJson("terminal-results", "{}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef secondRoot = store.appendImmutableJson("terminal-results",
				"{\"second\":true}".getBytes(StandardCharsets.UTF_8));
			List<TerminalResultRef> history = List.of(
				new TerminalResultRef(TerminalResultRef.Kind.PLATEAU, INITIAL_LINEAGE_ID, Optional.empty(), root,
					Optional.empty(), 1),
				new TerminalResultRef(TerminalResultRef.Kind.PLATEAU, current.lineages().get(1).lineageId(),
					Optional.empty(), secondRoot, Optional.empty(), 2));
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), null, null, history, null);
			ProspectiveCampaignState.write(store, prospective);
			requireThat(new CampaignStore(directory).load().terminalResults(), "validHistory").isEqualTo(history);
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = directory.resolve("campaign.json");
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			ObjectNode second = (ObjectNode) document.get("terminalResults").get(1).get("terminalRootRef");
			second.put("evidenceId", root.evidenceId());
			Files.write(campaignFile, json.writeValueAsBytes(document));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Duplicate terminal root must fail a cold campaign read");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "duplicateTerminal").contains("malformed");
			}
			second.put("evidenceId", secondRoot.evidenceId());
			((ObjectNode) document.get("terminalResults").get(1)).put("resultingCounter", 1);
			Files.write(campaignFile, json.writeValueAsBytes(document));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Terminal counters must increase in history order");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "orderedTerminal").contains("malformed");
			}
		}
	}

	/**
	 * Rejects a certification that names a candidate never reserved in its retained lineage.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsUnreservedCertifiedCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-terminal-candidate-"));
			CampaignStore store = prepared(directory);
			ProspectiveCampaignState.withCandidateState(store, CANDIDATE_A, CANDIDATE_A,
				List.of(new CandidateAllowance(CANDIDATE_A, 1)));
			Campaign current = ProspectiveCampaignState.addLineage(store, "second-contract");
			EvidenceRef root = store.appendImmutableJson("terminal-results", "{}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef result = store.appendImmutableJson("measurement-results",
				"{}".getBytes(StandardCharsets.UTF_8));
			TerminalResultRef terminal = new TerminalResultRef(TerminalResultRef.Kind.CERTIFIED,
				INITIAL_LINEAGE_ID, Optional.of(CANDIDATE_A), root, Optional.of(result),
				current.transitionCounter());
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
				current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
				current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(), current.lineages(),
				current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), null, null, List.of(terminal), null);
			ProspectiveCampaignState.write(store, prospective);
			requireThat(new CampaignStore(directory).load().terminalResults(), "validCertification").
				isEqualTo(List.of(terminal));
			JsonMapper json = JsonMapper.builder().build();
			Path campaignFile = directory.resolve("campaign.json");
			ObjectNode document = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			((ObjectNode) document.get("terminalResults").get(0)).put("candidateId", CANDIDATE_B);
			Files.write(campaignFile, json.writeValueAsBytes(document));
			try
			{
				new CampaignStore(directory).load();
				throw new AssertionError("Unreserved certified candidate must fail a cold campaign read");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unreservedCandidate").contains("malformed");
			}
		}
	}

	/**
	 * Verifies collision and orphan temporary recovery do not alter durable state.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectsCollisionAndRecoversOrphanedFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-store-"));
			CampaignStore store = prepared(directory);
			Path evidence = store.appendImmutable("outcomes", "evidence".getBytes(StandardCharsets.UTF_8));
			Files.writeString(evidence, "different");
			try
			{
				store.appendImmutable("outcomes", "evidence".getBytes(StandardCharsets.UTF_8));
				throw new AssertionError("Collision must fail");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("collision");
			}
			Files.writeString(directory.resolve("campaign.json.orphan.tmp"), "bad");
			requireThat(new CampaignStore(directory).load().activeLineageId(), "lineage").isEqualTo(INITIAL_LINEAGE_ID);
		}
	}

	/**
	 * Verifies binary and JSON evidence round-trip through typed, content-addressed persistence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void verifiesTypedImmutableEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-store-"));
			CampaignStore store = prepared(directory);
			byte[] binary = {(byte) 0x80, 0, (byte) 0xff};
			EvidenceRef binaryEvidence = store.appendImmutableBytes("binaries", binary);
			requireThat(binaryEvidence.evidenceId(), "binaryName").isEqualTo(sha256(binary));
			String binaryId = binaryEvidence.evidenceId();
			requireThat(store.readImmutableBytes("binaries", binaryId), "binaryRoundTrip").isEqualTo(binary);
			requireThat(store.appendImmutableBytes("binaries", binary), "binaryIdempotence").isEqualTo(binaryEvidence);
			byte[] corrupted = {1, 2, 3};
			Files.write(directory.resolve("binaries").resolve(binaryId + ".blob"), corrupted);
			try
			{
				store.appendImmutableBytes("binaries", binary);
				throw new AssertionError("Evidence collision must fail");
			}
			catch (IOException _)
			{
				requireThat(Files.readAllBytes(directory.resolve("binaries").resolve(binaryId + ".blob")),
					"collisionBytes").isEqualTo(corrupted);
			}
			try
			{
				store.readImmutableBytes("binaries", binaryId);
				throw new AssertionError("Mismatched evidence must fail");
			}
			catch (IOException _)
			{
			}
			try
			{
				store.readImmutableBytes("binaries", "0".repeat(64));
				throw new AssertionError("Missing evidence must fail");
			}
			catch (IOException _)
			{
			}
			try
			{
				store.readImmutableBytes("binaries", "../outside");
				throw new AssertionError("Malformed evidence id must fail");
			}
			catch (IllegalArgumentException _)
			{
			}
			byte[] json = "{\"evidence\":true}".getBytes(StandardCharsets.UTF_8);
			Path legacyJson = store.appendImmutable("contracts", json);
			requireThat(legacyJson.getFileName().toString(), "jsonName").isEqualTo(sha256(json) + ".json");
			EvidenceRef jsonReference = new EvidenceRef("contracts", sha256(json));
			requireThat(store.appendImmutableJson("contracts", json), "jsonCompatibility").
				isEqualTo(jsonReference);
			String jsonId = legacyJson.getFileName().toString().replace(".json", "");
			requireThat(store.readImmutableJson("contracts", jsonId), "jsonRoundTrip").isEqualTo(json);
		}
	}

	/**
	 * Verifies a native-call evidence reader can retrieve either retained encoding by content address.
	 *
	 * @throws IOException if the test-owned store cannot be read or written
	 */
	@Test
	public void readsBothImmutableEncodings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-evidence-reader-"));
			CampaignStore store = prepared(directory);
			byte[] binary = {(byte) 0x80, 0, (byte) 0xff};
			byte[] json = "{\"role\":\"authority\"}".getBytes(StandardCharsets.UTF_8);
			EvidenceRef manifestRef = store.appendImmutableBytes("sterile-workspace-manifests", binary);
			EvidenceRef requestRef = store.appendImmutableJson("evaluator-requests", json);

			requireThat(store.readImmutableEvidence(manifestRef), "binaryManifest").isEqualTo(binary);
			requireThat(store.readImmutableEvidence(requestRef), "jsonRequest").isEqualTo(json);
		}
	}

	/**
	 * A retained evidence reader rejects a campaign identity changed after the reader was opened.
	 *
	 * @throws IOException if fixture evidence cannot be read or written
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*another campaign.*")
	public void evidenceReaderRechecksCampaignIdentity() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-evidence-binding-"));
			CampaignStore store = prepared(directory);
			EvidenceRef reference = store.appendImmutableJson("evaluator-requests",
				"{}".getBytes(StandardCharsets.UTF_8));
			EvidenceReader reader = store.evidenceReader(store.binding());
			requireThat(reader.read(reference), "originalEvidence").isEqualTo("{}".getBytes(StandardCharsets.UTF_8));

			Path campaignFile = directory.resolve("campaign.json");
			JsonMapper json = JsonMapper.builder().build();
			ObjectNode campaign = (ObjectNode) json.readTree(Files.readAllBytes(campaignFile));
			campaign.put("campaignId", "another-campaign");
			Files.write(campaignFile, json.writeValueAsBytes(campaign));
			reader.read(reference);
		}
	}

	/**
	 * Verifies simultaneous idempotent binary appends publish the complete content-addressed bytes.
	 *
	 * @throws IOException if the fixture cannot be used
	 * @throws InterruptedException if concurrent test execution is interrupted
	 * @throws ExecutionException if concurrent test execution fails unexpectedly
	 */
	@Test
	public void preservesConcurrentImmutableBytes() throws IOException, InterruptedException, ExecutionException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-store-"));
			byte[] content = new byte[65_537];
			for (int index = 0; index < content.length; ++index)
				content[index] = (byte) index;
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService executor = Executors.newFixedThreadPool(2))
			{
				Future<EvidenceRef> first = executor.submit(() ->
					appendConcurrently(new CampaignStore(directory), content, ready, start));
				Future<EvidenceRef> second = executor.submit(() ->
					appendConcurrently(new CampaignStore(directory), content, ready, start));
				requireThat(ready.await(10, TimeUnit.SECONDS), "appendReady").isTrue();
				start.countDown();
				EvidenceRef firstPath = first.get();
				requireThat(second.get(), "idempotentPath").isEqualTo(firstPath);
				requireThat(Files.readAllBytes(
					directory.resolve("binaries").resolve(firstPath.evidenceId() + ".blob")),
					"publishedBytes").isEqualTo(content);
				requireThat(new CampaignStore(directory).readImmutableBytes("binaries", sha256(content)),
					"verifiedBytes").isEqualTo(content);
			}
		}
	}

	/**
	 * Verifies every prospective trial status survives a cold campaign read.
	 *
	 * @throws IOException if a fixture cannot be used
	 */
	@Test
	public void loadsProspectiveTrialStates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			for (TrialStatus status : TrialStatus.values())
			{
				Path directory = cleanup.add(Files.createTempDirectory("campaign-state-"));
				TrialState trial = trialAt(status);
				CampaignStore store = prepared(directory);
				ProspectiveCampaignState.withTrials(store, List.of(trial));
				Campaign loaded = new CampaignStore(directory).load();
				requireThat(loaded.trials(), "trials").isEqualTo(List.of(trial));
				requireThat(loaded.transitionCounter(), "counter").isEqualTo(2L);
			}
		}
	}

	/**
	 * Verifies a cold campaign read rejects missing state.
	 *
	 * @throws IOException if a fixture cannot be used
	 */
	@Test
	public void rejectsMissingCampaign() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-state-"));
			CampaignStore store = new CampaignStore(directory);
			try
			{
				store.load();
				throw new AssertionError("Missing state must fail");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("missing");
			}
		}
	}

	/**
	 * Verifies one lineage rejects a repeated candidate identity.
	 */
	@Test
	public void rejectsDuplicateCandidateAllowance()
	{
		try
		{
			new LineageSummary("lineage", "contract", 3, List.of(new CandidateAllowance("candidate", 1),
				new CandidateAllowance("candidate", 2)));
			throw new AssertionError("Duplicate candidate must fail");
		}
		catch (IllegalArgumentException _)
		{
		}
	}

	/**
	 * Creates an active campaign with a fresh lineage.
	 *
	 * @param directory fixture directory
	 * @return prepared store
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
	 * Waits for the local writer race and appends immutable binary evidence.
	 *
	 * @param store campaign store
	 * @param content binary evidence
	 * @param ready writers ready to start
	 * @param start writer-start signal
	 * @return persisted evidence path
	 * @throws IOException if persistence fails
	 * @throws InterruptedException if the writer is interrupted
	 */
	private static EvidenceRef appendConcurrently(CampaignStore store, byte[] content, CountDownLatch ready,
		CountDownLatch start)
		throws IOException, InterruptedException
	{
		ready.countDown();
		start.await();
		return store.appendImmutableBytes("binaries", content);
	}

	/**
	 * Computes an expected SHA-256 evidence id independently from the store implementation.
	 *
	 * @param content evidence bytes
	 * @return lowercase SHA-256 evidence id
	 */
	private static String sha256(byte[] content)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Creates a valid state for one source transition.
	 *
	 * @param status source status
	 * @return trial state
	 */
	private static TrialState trialAt(TrialStatus status)
	{
		EvidenceRef outcomeEvidence = null;
		if (TrialState.terminal(status))
			outcomeEvidence = new EvidenceRef("outcomes", "2".repeat(64));
		return new TrialState("trial", INITIAL_LINEAGE_ID, CANDIDATE_A, EvidencePurpose.DEVELOPMENT, status,
			TrialState.expectedFailureKind(status), new EvidenceRef("selections", "1".repeat(64)),
			outcomeEvidence);
	}
}
