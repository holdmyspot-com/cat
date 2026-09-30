package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tests evidence-bearing C1 lifecycle and restart boundaries. */
public final class CampaignStoreCapabilityTest
{
	private static final String CONTRACT = "d".repeat(64);
	private static final String LINEAGE = "lineage-1-" + CONTRACT;
	private static final String CANDIDATE = "a".repeat(64);

	/** Creates the test fixture. */
	public CampaignStoreCapabilityTest()
	{
	}

	/**
	 * A campaign-bound reader returns rehashed JSON from its pinned store.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readerReturnsPinnedJsonEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-reader-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			EvidenceRef reference = store.appendImmutableJson("native-run-completions", bytes("{}"));
			EvidenceReader reader = store.evidenceReader(store.binding());
			requireThat(reader.read(reference), "verifiedJson").isEqualTo(bytes("{}"));
		}
	}

	/**
	 * The reader refuses a foreign binding and rechecks the binding and evidence on every read.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readerRejectsForeignOrChangedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-reader-guard-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			EvidenceRef reference = store.appendImmutableJson("native-run-completions", bytes("{}"));
			try
			{
				store.evidenceReader(new CampaignBinding("foreign", directory));
				throw new AssertionError("Foreign campaign must not create a reader");
			}
			catch (IOException _)
			{
			}
			EvidenceReader reader = store.evidenceReader(binding);
			Files.write(directory.resolve(reference.collection()).resolve(reference.evidenceId() + ".json"),
				bytes("{\"tampered\":true}"));
			try
			{
				reader.read(reference);
				throw new AssertionError("Corrupt completion must not be read");
			}
			catch (IOException _)
			{
			}
			Files.write(directory.resolve("campaign.json"), JsonMapper.builder().build().
				writeValueAsBytes(Campaign.create("foreign",
					new ModelProfile("test-model", Optional.empty(), Map.of()))));
			try
			{
				reader.read(reference);
				throw new AssertionError("Retargeted campaign identity must not be read");
			}
			catch (IOException _)
			{
			}
		}
	}

	/**
	 * Persists the active development root across restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void persistsTypedEvidenceAndDevelopmentRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-capability-"));
			CampaignStore store = prepared(directory);
			EvidenceRef root = store.appendImmutableJson("development-roots", bytes("root"));

			ProspectiveCampaignState.developmentRoot(store, root);
			CampaignStore restarted = new CampaignStore(directory);
			requireThat(restarted.load().activeDevelopmentRoot(), "restartedRoot").isEqualTo(root);
		}
	}

	/**
	 * Production initialization preserves its optimization profile through a state transition and restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preservesOptimizationProfile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-profile-"));
			ModelProfile profile = new ModelProfile("model-1", Optional.of("high"), Map.of("temperature", "0"));
			CampaignStore store = CampaignStore.initialize(directory, "campaign", profile);
			ProspectiveCampaignState.addLineage(store, CONTRACT);
			requireThat(CampaignStore.open(directory).load().optimizationProfile(), "restartedProfile").
				isEqualTo(profile);
		}
	}

	/**
	 * A lineage index rehashes its evidence and refuses a different lineage or target on replay.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void lineageIndexBindsAndRehashesItsEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-lineage-index-"));
			CampaignStore store = prepared(directory);
			LineageKey lineage = new LineageKey(LINEAGE, CONTRACT);
			LineageEvidenceRef first = new LineageEvidenceRef(lineage,
				store.appendImmutableJson("candidate-native-attempts", bytes("first")));
			LineageEvidenceRef other = new LineageEvidenceRef(lineage,
				store.appendImmutableJson("candidate-native-attempts", bytes("other")));
			requireThat(store.appendCreateOnceIndex("candidate-transport-attempts", "call-1", first),
				"firstIndex").isEqualTo(first);
			try (Stream<Path> entries = Files.list(directory.resolve("candidate-transport-attempts")))
			{
				String entryBytes = Files.readString(entries.findFirst().orElseThrow());
				requireThat(entryBytes, "flatAttemptSchema").contains("\"lineageId\"");
				requireThat(entryBytes, "attemptRefField").contains("\"attemptRef\"");
			}
			requireThat(new CampaignStore(directory).readCreateOnceIndex("candidate-transport-attempts", "call-1",
				lineage), "restartedIndex").isEqualTo(first);
			requireThat(store.appendCreateOnceIndex("candidate-transport-attempts", "call-1", first),
				"equalReplay").isEqualTo(first);
			try
			{
				store.appendCreateOnceIndex("candidate-transport-attempts", "call-1", other);
				throw new AssertionError("A different attempt must not replace a published call");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "conflict").contains("collision");
			}
			try
			{
				store.readCreateOnceIndex("candidate-transport-attempts", "call-1",
					new LineageKey("foreign", CONTRACT));
				throw new AssertionError("A foreign lineage must not read the index");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "foreignLineage").contains("lineage");
			}
		}
	}

	/**
	 * A missing target cannot acquire a durable call index.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMissingIndexTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-index-missing-"));
			CampaignStore store = prepared(directory);
			LineageEvidenceRef missing = new LineageEvidenceRef(new LineageKey(LINEAGE, CONTRACT),
				new EvidenceRef("candidate-native-attempts", "f".repeat(64)));
			try
			{
				store.appendCreateOnceIndex("candidate-transport-attempts", "call-1", missing);
				throw new AssertionError("Missing evidence must not receive an index");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "missingTarget").contains("Indexed evidence is missing");
			}
			try (Stream<Path> entries = Files.list(directory.resolve("candidate-transport-attempts")))
			{
				requireThat(entries.count(), "publishedIndexes").isEqualTo(0L);
			}
		}
	}

	/**
	 * A claim-bound attempt index accepts only its producer's evidence collection.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*candidate-native-attempts.*")
	public void rejectsWrongAttemptCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = prepared(cleanup.add(Files.createTempDirectory("campaign-index-kind-")));
			LineageEvidenceRef wrong = new LineageEvidenceRef(new LineageKey(LINEAGE, CONTRACT),
				store.appendImmutableJson("other-attempts", bytes("attempt")));
			store.appendCreateOnceIndex("candidate-transport-attempts", "call-1", wrong);
		}
	}

	/**
	 * A source-change index cannot point at another kind of evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*contract-preparation-source-changes.*")
	public void rejectsWrongSourceChangeCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-source-index-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			EvidenceRef wrong = store.appendImmutableJson("contract-inspection-attempts", bytes("{}"));
			store.appendPreconfirmationIndex(store.binding(), PreconfirmationIndexKind.CONTRACT_SOURCE_CHANGE,
				"cycle:REVIEWED_SOURCE_CHANGED", wrong);
		}
	}

	/**
	 * A published index does not remain authoritative after its target bytes are corrupted.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*content address.*")
	public void lineageIndexRejectsCorruptTarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-index-corrupt-"));
			CampaignStore store = prepared(directory);
			LineageKey lineage = new LineageKey(LINEAGE, CONTRACT);
			EvidenceRef evidence = store.appendImmutableJson("candidate-native-attempts", bytes("attempt"));
			store.appendCreateOnceIndex("candidate-transport-attempts", "call-1",
				new LineageEvidenceRef(lineage, evidence));
			Files.write(directory.resolve(evidence.collection()).resolve(evidence.evidenceId() + ".json"),
				bytes("corrupted"));
			store.readCreateOnceIndex("candidate-transport-attempts", "call-1", lineage);
		}
	}

	/**
	 * Preconfirmation indexing retains one campaign's immutable inspection attempt without creating a lineage.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preconfirmationIndexStaysCampaignBound() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-preconfirmation-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			CampaignBinding binding = store.binding();
			EvidenceRef first = store.appendImmutableJson("contract-inspection-attempts", bytes("first"));
			EvidenceRef other = store.appendImmutableJson("contract-inspection-attempts", bytes("other"));
			requireThat(store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
				"inspection-1", first), "firstEntry").isEqualTo(first);
			requireThat(new CampaignStore(directory).readPreconfirmationIndex(binding,
				PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT, "inspection-1").orElseThrow(),
				"restartedEntry").isEqualTo(first);
			requireThat(store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
				"inspection-1", first), "equalReplay").isEqualTo(first);
			requireThat(store.load().activeLineageId(), "noLineage").isNull();
			try
			{
				store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
					"inspection-1", other);
				throw new AssertionError("A different attempt must conflict");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "conflict").contains("collision");
			}
		}
	}

	/**
	 * Inspection indexing refuses evidence outside the inspection attempt collection.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*contract-inspection-attempts.*")
	public void inspectionIndexRejectsWrongCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = new CampaignStore(cleanup.add(Files.createTempDirectory("campaign-inspection-kind-")));
			ProspectiveCampaignState.initialize(store, "campaign");
			EvidenceRef wrong = store.appendImmutableJson("other-attempts", bytes("wrong"));
			store.appendPreconfirmationIndex(store.binding(), PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
				"inspection-1", wrong);
		}
	}

	/**
	 * A confirmed campaign cannot publish a new preconfirmation attempt.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*unconfirmed campaign.*")
	public void rejectsPreconfirmationAfterConfirmation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CampaignStore store = prepared(cleanup.add(Files.createTempDirectory("campaign-confirmed-index-")));
			EvidenceRef evidence = store.appendImmutableJson("contract-inspection-attempts", bytes("attempt"));
			store.appendPreconfirmationIndex(store.binding(), PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
				"inspection-2", evidence);
		}
	}

	/**
	 * A prospective formal outcome retains its closure reference after a store restart.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsProspectiveOutcomeAcrossRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-capability-"));
			CampaignStore store = prepared(directory);
			EvidenceRef closure = store.appendImmutableJson("trial-closures", bytes("closure"));
			TrialState formal = assigned("formal", EvidencePurpose.FORMAL);
			TrialState completed = new TrialState(formal.logicalTrialId(), formal.lineageId(), formal.candidateId(),
				formal.purpose(), TrialStatus.COMPLIANT, null, formal.selectionEvidence(), closure);
			Campaign current = store.load();
			LineageSummary lineage = new LineageSummary(LINEAGE, CONTRACT, 2,
				List.of(new CandidateAllowance(CANDIDATE, 1)));
			Campaign prospective = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(),
				CampaignStatus.FORMAL_TESTING, current.activeLineageId(), CANDIDATE, CANDIDATE,
				current.activeDevelopmentRoot(), current.transitionCounter() + 1, null, List.of(lineage),
				List.of(completed), current.history(), current.optimizationProfile(), current.transitionReceipts(),
				current.scheduler(), current.preparationScope(), current.activeFormalFailureTriggerRef(),
				current.measurementState(), current.terminalResults(), current.acknowledgedInterruptionStopRef());
			ProspectiveCampaignState.write(store, prospective);

			requireThat(new CampaignStore(directory).load().trials().getFirst().outcomeEvidence(),
				"restartedClosure").isEqualTo(closure);
		}
	}

	/**
	 * Verifies that an opened store continues to read its original campaign after its input alias is retargeted.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsCampaignAfterAliasRetarget() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-store-alias-"));
			Path first = Files.createDirectory(parent.resolve("first"));
			Path second = Files.createDirectory(parent.resolve("second"));
			Path alias = parent.resolve("active");
			Files.createSymbolicLink(alias, first.getFileName());
			CampaignStore opened = new CampaignStore(alias);
			ProspectiveCampaignState.initialize(opened, "first");
			ProspectiveCampaignState.initialize(new CampaignStore(second), "second");
			Files.delete(alias);
			Files.createSymbolicLink(alias, second.getFileName());

			requireThat(opened.load().campaignId(), "openedCampaign").isEqualTo("first");
			requireThat(new CampaignStore(alias).load().campaignId(), "newlyOpenedCampaign").isEqualTo("second");
		}
	}

	/**
	 * Rejects a symlink installed at a not-yet-created campaign path before writing evidence.
	 *
	 * @throws IOException if the campaign path is replaced
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*campaign directory.*")
	public void rejectsReplacedCampaignPathBeforeWrite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-store-create-"));
			Path requested = parent.resolve("campaign");
			Path other = Files.createDirectory(parent.resolve("other"));
			CampaignStore store = new CampaignStore(requested);
			Files.createSymbolicLink(requested, other.getFileName());
			try
			{
				store.appendImmutableBytes("binaries", bytes("unreachable"));
			}
			finally
			{
				requireThat(Files.notExists(other.resolve("binaries")), "otherCampaignEvidence").isTrue();
			}
		}
	}

	/**
	 * Rejects a moved campaign directory whose original path now points at another campaign.
	 *
	 * @throws IOException if the opened store detects the replaced directory
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*campaign directory.*")
	public void rejectsReplacedCampaignPathOnLoad() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-store-move-"));
			Path first = Files.createDirectory(parent.resolve("first"));
			Path second = Files.createDirectory(parent.resolve("second"));
			CampaignStore opened = new CampaignStore(first);
			ProspectiveCampaignState.initialize(opened, "first");
			ProspectiveCampaignState.initialize(new CampaignStore(second), "second");
			Files.move(first, parent.resolve("moved-first"));
			Files.createSymbolicLink(first, second.getFileName());

			opened.load();
		}
	}

	/**
	 * Rejects an evidence read through a campaign directory replaced after the store opened.
	 *
	 * @throws IOException if the opened store detects the replaced directory
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*campaign directory.*")
	public void rejectsReplacedDirectoryEvidenceRead() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-evidence-move-"));
			Path first = Files.createDirectory(parent.resolve("first"));
			Path second = Files.createDirectory(parent.resolve("second"));
			CampaignStore opened = new CampaignStore(first);
			EvidenceRef evidence = opened.appendImmutableBytes("binaries", bytes("same"));
			new CampaignStore(second).appendImmutableBytes("binaries", bytes("same"));
			Files.move(first, parent.resolve("moved-first"));
			Files.createSymbolicLink(first, second.getFileName());

			opened.readImmutableBytes(evidence);
		}
	}

	/**
	 * Rejects an index read through a campaign directory replaced after the store opened.
	 *
	 * @throws IOException if the opened store detects the replaced directory
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*campaign directory.*")
	public void rejectsReplacedDirectoryIndexRead() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-index-move-"));
			Path first = Files.createDirectory(parent.resolve("first"));
			Path second = Files.createDirectory(parent.resolve("second"));
			CampaignStore opened = prepared(first);
			CampaignStore other = prepared(second);
			EvidenceRef target = opened.appendImmutableJson("candidate-native-attempts", bytes("same"));
			other.appendImmutableJson("candidate-native-attempts", bytes("same"));
			LineageKey lineage = new LineageKey(LINEAGE, CONTRACT);
			LineageEvidenceRef indexedTarget = new LineageEvidenceRef(lineage, target);
			opened.appendCreateOnceIndex("candidate-transport-attempts", "key", indexedTarget);
			other.appendCreateOnceIndex("candidate-transport-attempts", "key", indexedTarget);
			Files.move(first, parent.resolve("moved-first"));
			Files.createSymbolicLink(first, second.getFileName());

			opened.readCreateOnceIndex("candidate-transport-attempts", "key", lineage);
		}
	}

	/**
	 * A symlinked evidence collection cannot redirect immutable bytes into another campaign directory.
	 *
	 * @throws IOException if the collection is rejected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*collection directory.*")
	public void rejectsSymlinkedEvidenceCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-evidence-collection-"));
			Path campaign = Files.createDirectory(parent.resolve("campaign"));
			Path other = Files.createDirectory(parent.resolve("other"));
			CampaignStore store = new CampaignStore(campaign);
			ProspectiveCampaignState.initialize(store, "campaign");
			Files.createSymbolicLink(campaign.resolve("binaries"), other);
			try
			{
				store.appendImmutableBytes("binaries", bytes("payload"));
			}
			finally
			{
				try (Stream<Path> entries = Files.list(other))
				{
					requireThat(entries.count(), "foreignWrites").isEqualTo(0L);
				}
			}
		}
	}

	/**
	 * A symlinked index collection cannot publish a call mapping outside the pinned campaign.
	 *
	 * @throws IOException if the collection is rejected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*collection directory.*")
	public void rejectsSymlinkedIndexCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-index-collection-"));
			Path campaign = Files.createDirectory(parent.resolve("campaign"));
			Path other = Files.createDirectory(parent.resolve("other"));
			CampaignStore store = prepared(campaign);
			EvidenceRef attempt = store.appendImmutableJson("candidate-native-attempts", bytes("attempt"));
			Files.createSymbolicLink(campaign.resolve("candidate-transport-attempts"), other);
			store.appendCreateOnceIndex("candidate-transport-attempts", "call-1",
				new LineageEvidenceRef(new LineageKey(LINEAGE, CONTRACT), attempt));
		}
	}

	/**
	 * A real directory replaced by another real directory cannot inherit an opened store's authority.
	 *
	 * @throws IOException if the original directory is replaced
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*campaign directory.*")
	public void rejectsReplacedRealDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("campaign-real-replacement-"));
			Path first = Files.createDirectory(parent.resolve("first"));
			Path second = Files.createDirectory(parent.resolve("second"));
			CampaignStore opened = new CampaignStore(first);
			ProspectiveCampaignState.initialize(opened, "first");
			ProspectiveCampaignState.initialize(new CampaignStore(second), "second");
			Files.move(first, parent.resolve("moved-first"));
			Files.move(second, first);
			opened.load();
		}
	}

	/**
	 * Creates one confirmed developing campaign.
	 *
	 * @param directory campaign directory
	 * @return initialized store
	 * @throws IOException if state cannot be persisted
	 */
	private static CampaignStore prepared(Path directory) throws IOException
	{
		CampaignStore store = new CampaignStore(directory);
		ProspectiveCampaignState.initialize(store, "campaign");
		ProspectiveCampaignState.addLineage(store, CONTRACT);
		return store;
	}

	/**
	 * Creates one assigned trial.
	 *
	 * @param logicalId logical trial identity
	 * @param purpose evidence purpose
	 * @return assignment
	 */
	private static TrialState assigned(String logicalId, EvidencePurpose purpose)
	{
		return new TrialState(logicalId, LINEAGE, CANDIDATE, purpose, TrialStatus.ASSIGNED, null,
			new EvidenceRef("selections", "1".repeat(64)), null);
	}

	/**
	 * Returns UTF-8 fixture bytes.
	 *
	 * @param value fixture text
	 * @return UTF-8 bytes
	 */
	private static byte[] bytes(String value)
	{
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
