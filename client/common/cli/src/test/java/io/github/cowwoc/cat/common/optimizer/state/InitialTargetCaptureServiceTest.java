package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotManifest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks that contract inspection can resume from the selected target's original source bytes.
 */
public final class InitialTargetCaptureServiceTest
{
	/**
	 * Creates isolated capture tests.
	 */
	public InitialTargetCaptureServiceTest()
	{
	}

	/**
	 * Rejects a purported regular target whose source path differs from its selected path.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*link relationship.*")
	public void rejectsUnmarkedLinkRelationship()
	{
		PreparationAuthority preparation = new PreparationAuthority("campaign", PreparationAuthority.Kind.INITIAL,
			Optional.empty(), Optional.empty(), 1, Optional.empty(), 0);
		new InitialTargetCapture(InitialTargetCapture.SCHEMA_VERSION, "campaign", preparation,
			Path.of("/base"), Path.of("target.md"), Path.of("actual.md"), "", List.of(), "a".repeat(64),
			new EvidenceRef("initial-git-inventories", "b".repeat(64)), "c".repeat(64));
	}

	/**
	 * A selected link to an external file cannot become source authority without external target staging.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 */
	@Test
	public void rejectsUnstagedExternalTargetLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("external-target-capture-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Files.writeString(root.resolve("outside.md"), "outside source\n");
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Capture Test");
			Files.createSymbolicLink(project.resolve("target.md"), Path.of("../outside.md"));
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			locator.createOrFind(new CampaignLookupKey(project, Path.of("target.md")),
				new CampaignCreationRequest("external-target", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-external-target"));
			CampaignBinding binding = store.binding();
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
			try
			{
				sources.capture(binding);
				throw new AssertionError("External target requires a separate staged source-authority path");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "unstagedExternalTarget").contains("outside the project");
			}
			try
			{
				sources.initialCapture(binding);
				throw new AssertionError("Rejected external source must not publish a capture index");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "externalCaptureIndex").contains("capture is missing");
			}
		}
	}

	/**
	 * A selected file reached through a directory link keeps its logical path and retained regular-file bytes.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 */
	@Test
	public void capturesSelectedFileThroughDirectoryLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("directory-linked-target-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Capture Test");
			Path actual = Files.createDirectory(project.resolve("actual-rules"));
			Files.writeString(actual.resolve("target.md"), "original target\n");
			Files.createSymbolicLink(project.resolve("rules"), Path.of("actual-rules"));
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			Path selectedPath = Path.of("rules", "target.md");
			locator.createOrFind(new CampaignLookupKey(project, selectedPath),
				new CampaignCreationRequest("directory-linked-target", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-directory-linked-target"));
			CampaignBinding binding = store.binding();
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);

			EvidenceRef reference = sources.capture(binding);
			InitialTargetCapture captured = new InitialTargetCaptureService(locator,
				CampaignStore.open(store.directory())).load(binding, reference);

			requireThat(captured.targetPath(), "selectedLogicalPath").isEqualTo(selectedPath);
			requireThat(captured.resolvedSourcePath(), "selectedContentPath").isEqualTo(
				Path.of("actual-rules", "target.md"));
			requireThat(captured.selectedLinkTarget(), "selectedFileLinkText").isEmpty();
			requireThat(captured.selectedDirectoryLinks(), "selectedDirectoryLinks").isEqualTo(List.of(
				new PromptSource.DirectoryLink(Path.of("rules"), "actual-rules", Path.of("actual-rules"))));
			Files.writeString(actual.resolve("target.md"), "later target\n");
			Path materialized = sources.materializeProjectSnapshot(binding, reference);
			requireThat(Files.isSymbolicLink(materialized.resolve("rules")), "materializedDirectoryLink").isTrue();
			requireThat(Files.readSymbolicLink(materialized.resolve("rules")), "materializedLinkText").isEqualTo(
				Path.of("actual-rules"));
			requireThat(Files.readString(materialized.resolve(selectedPath)), "materializedSelectedBytes").isEqualTo(
				"original target\n");
		}
	}

	/**
	 * Capturing and preparing before inspection retains the same exact bytes after restart.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 */
	@Test
	public void retainsInitialPreparationAcrossRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("initial-target-capture-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Capture Test");
			Files.writeString(project.resolve("target.md"), "original target\n");
			Files.writeString(project.resolve("source.md"), "original source\n");
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");

			Path workflow = Files.createDirectory(root.resolve("workflow"));
			CampaignLocator locator = new CampaignLocator(workflow);
			CampaignLookupKey key = new CampaignLookupKey(project, Path.of("target.md"));
			locator.createOrFind(key, new CampaignCreationRequest("capture", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-capture"));
			CampaignBinding binding = store.binding();
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
			EvidenceRef first = sources.capture(binding);
			EvidenceRef firstManifest = sources.publishSnapshotManifest(binding, first);
			EvidenceRef firstCycle = new PreparationCycleService(locator, store).prepare(binding);
			Files.writeString(project.resolve("target.md"), "later target\n");
			Files.writeString(project.resolve("source.md"), "later source\n");
			Path materialized = sources.materializeProjectSnapshot(binding, first);
			requireThat(Files.readString(materialized.resolve("target.md")), "materializedTarget").
				isEqualTo("original target\n");
			requireThat(Files.readString(materialized.resolve("source.md")), "materializedSource").
				isEqualTo("original source\n");
			PreparedInspectionCall firstInspection = new InspectionOwnerService(locator, store).prepare(binding);
			requireThat(firstInspection.request().context().snapshot().projectRoot(), "inspectionProject").
				isEqualTo(materialized);
			CampaignStopInbox inbox = locator.openStopInbox(binding);
			EvidenceRef registration = inbox.beginInvocation(binding, "inspection-invocation");
			inbox.publishHostObservation(binding, "inspection-invocation", registration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "8".repeat(64),
					"session-8", "turn-8")));
			EvidenceRef firstAttempt;
			EvidenceRef firstCompletion;
			try (SchedulerLease lease = locator.openSchedulerLease(binding).acquire(binding,
				"inspection-invocation"))
			{
				locator.openMeasurementObserver(binding).beforeInspectionCall(lease, firstInspection);
				ClaimedSlot claimed = locator.openSchedulerLease(binding).claimOrResumeCall(lease,
					firstInspection.owner().logicalCallId(), firstInspection.owner().runRequestRef(),
					Optional.of(firstInspection.ownerRef()), Optional.of(firstCycle));
				requireThat(claimed.slot().state(), "preconfirmationClaim").isEqualTo(CallSlot.State.CLAIMED);
				InspectionAttemptService attempts = new InspectionAttemptService(locator, store);
				EvidenceRef attemptRef = attempts.publish(binding, firstInspection.ownerRef());
				firstAttempt = attemptRef;
				requireThat(attempts.publish(binding, firstInspection.ownerRef()), "sameAttempt").
					isEqualTo(attemptRef);
				ContractInspectionAttempt attempt = attempts.load(binding, attemptRef);
				requireThat(attempt.header().slotClaimRef(), "originalSlotClaim").
					isEqualTo(claimed.slotClaimRef());
				Path artifact = Files.createDirectory(root.resolve("artifact"));
				RunnerRuntimeManifest.write(artifact);
				HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
				HarnessRun copied = manager.create("codex");
				manager.copyProject(firstInspection.request().context().snapshot().projectRoot(), copied);
				requireThat(Files.readString(copied.directory().resolve("project/target.md")),
					"runnerTarget").isEqualTo("original target\n");
				requireThat(Files.readString(copied.directory().resolve("project/source.md")),
					"runnerSource").isEqualTo("original source\n");
				ContractInspectionAttempt unindexed = new ContractInspectionAttempt(attempt.header(),
					attempt.requestRef(), 1);
				EvidenceRef unindexedRef = store.appendImmutableJson("contract-inspection-attempts",
					SnapshotJson.mapper().writeValueAsBytes(unindexed));
				CallPreStartRecord unauthorized = new CallPreStartRecord(3,
					firstInspection.owner().logicalCallId(), firstInspection.owner().runRequestRef(),
					claimed.slotClaimRef(), Optional.of(firstInspection.ownerRef()), Optional.of(firstCycle),
					Optional.empty(),
					ProducerAttemptKind.INSPECTION, unindexedRef, firstInspection.ownerRef(),
					manager.generateRunId(), RunnerRuntimeManifest.fingerprint(artifact));
				EvidenceRef unauthorizedRef = store.appendImmutableJson("call-pre-starts",
					JsonMapper.builder().build().writeValueAsBytes(unauthorized));
				try
				{
					store.publishCallPreStartIndex(binding, firstInspection.owner().logicalCallId(),
						unauthorizedRef);
					throw new AssertionError("An unindexed inspection attempt cannot authorize native start");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "unindexedAttempt").contains("create-once index");
				}
				Path observationIndex = null;
				try (Stream<Path> entries = Files.list(store.directory().resolve("preconfirmation-index")))
				{
					for (Path entry : entries.toList())
						if (Files.readString(entry).contains("CONTRACT_INSPECTION_BEFORE_OBSERVATION"))
							observationIndex = entry;
				}
				if (observationIndex == null)
					throw new AssertionError("Inspection observation index was not published");
				Files.delete(observationIndex);
				try
				{
					locator.openSchedulerLease(binding).prepareCallStart(lease,
						firstInspection.owner().logicalCallId(), ProducerAttemptKind.INSPECTION, attemptRef,
						firstInspection.ownerRef(), manager, artifact);
					throw new AssertionError("Native pre-start must require the indexed observation");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "missingPreStartObservation").contains(
						"measurement observation is missing");
				}
				requireThat(store.readCallSlotClaim(binding,
					firstInspection.owner().logicalCallId()).slot().state(), "deniedPreStart").
					isEqualTo(CallSlot.State.CLAIMED);
				locator.openMeasurementObserver(binding).beforeInspectionCall(lease, firstInspection);
				CallSlot starting = locator.openSchedulerLease(binding).prepareCallStart(lease,
					firstInspection.owner().logicalCallId(), ProducerAttemptKind.INSPECTION, attemptRef,
					firstInspection.ownerRef(), manager, artifact);
				requireThat(starting.state(), "inspectionStarting").isEqualTo(CallSlot.State.STARTING);
				String runId = store.readCallPreStartRecord(binding,
					firstInspection.owner().logicalCallId()).orElseThrow().runId();
				NestedRunFailure nativeFailure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					firstInspection.owner().logicalCallId(), Optional.of(runId), "native completion before activation",
					List.of(), Optional.empty(), Optional.empty());
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence(binding.campaignId(),
					firstInspection.owner().logicalCallId(), firstInspection.owner().runRequestRef(),
					claimed.slotClaimRef(), Optional.of(runId), NativeRunCompletionEvidence.Outcome.FAILED,
					Optional.empty(), Optional.of(nativeFailure), List.of(), Optional.empty(), Optional.empty());
				EvidenceRef completionRef = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completion, store::readImmutableJson));
				store.publishNativeCompletionIndex(binding, firstInspection.owner().logicalCallId(), completionRef);
				firstCompletion = completionRef;
				requireThat(lease.settle(firstInspection.owner().logicalCallId(), completionRef).state(),
					"settledBeforeActivation").isEqualTo(CallSlot.State.SETTLED);
			}

			TemporaryDirectories.deleteRecursively(materialized);
			CampaignStore restarted = CampaignStore.open(store.directory());
			InitialTargetCaptureService resumed = new InitialTargetCaptureService(new CampaignLocator(workflow),
				restarted);
			EvidenceRef second = resumed.capture(binding);
			requireThat(resumed.initialCapture(binding), "indexedInitialCapture").isEqualTo(first);
			EvidenceRef secondManifest = resumed.publishSnapshotManifest(binding, second);
			PreparationCycleService cycles = new PreparationCycleService(new CampaignLocator(workflow), restarted);
			EvidenceRef secondCycle = cycles.prepare(binding);
			PreparedInspectionCall secondInspection = new InspectionOwnerService(new CampaignLocator(workflow),
				restarted).prepare(binding);
			InspectionAttemptService restartedAttempts = new InspectionAttemptService(new CampaignLocator(workflow),
				restarted);
			requireThat(restartedAttempts.publish(binding, secondInspection.ownerRef()), "resumedAttempt").
				isEqualTo(firstAttempt);
			requireThat(restartedAttempts.load(binding, firstAttempt).header().ownerRequestRef(),
				"resumedAttemptOwner").isEqualTo(firstInspection.ownerRef());
			requireThat(restarted.readCallPreStartRecord(binding,
				secondInspection.owner().logicalCallId()).orElseThrow().producerAttemptRef(),
				"resumedPreStart").isEqualTo(firstAttempt);
			requireThat(restarted.readNativeCompletionIndex(binding,
				secondInspection.owner().logicalCallId()), "resumedPreActivationCompletion").
				isEqualTo(Optional.of(firstCompletion));
			CampaignLocator resumedLocator = new CampaignLocator(workflow);
			CampaignStopInbox resumedInbox = resumedLocator.openStopInbox(binding);
			EvidenceRef resumedRegistration = resumedInbox.beginInvocation(binding,
				"preactivation-recovery");
			resumedInbox.publishHostObservation(binding, "preactivation-recovery", resumedRegistration,
				JsonMapper.builder().build().writeValueAsBytes(new Observation(1, "a".repeat(64),
					"session-a", "turn-a")));
			try (SchedulerLease resumedLease = resumedLocator.openSchedulerLease(binding).acquire(binding,
				"preactivation-recovery"))
			{
				EvidenceRef adopted = new InspectionCompletionService(resumedLocator, restarted).adoptIndexed(resumedLease,
					secondInspection.ownerRef());
				JsonMapper mapper = JsonMapper.builder().build();
				requireThat(mapper.readTree(restarted.readImmutableJson(adopted)).path(
					"selectedSourceProposalRef").isNull(), "failedInspectionHasNoSourceProposal").isTrue();
				try
				{
					new InspectionCompletionService(resumedLocator, restarted).loadSelectedSourceProposal(binding,
						adopted);
					throw new AssertionError("A failed inspection has no selected source edit proposal");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "failedSourceProposal").contains("no selected source");
				}
				requireThat(restarted.readCallSlotClaim(binding,
					secondInspection.owner().logicalCallId()).slot().state(), "preActivationAdoption").
					isEqualTo(CallSlot.State.ADOPTED);
			}
			ContractPreparationCycle cycle = cycles.load(binding, secondCycle);
			InitialTargetCapture retained = resumed.load(binding, second);
			requireThat(second, "sameCapture").isEqualTo(first);
			requireThat(secondManifest, "sameManifest").isEqualTo(firstManifest);
			SnapshotManifest manifest = SnapshotJson.mapper().readValue(restarted.readImmutableJson(secondManifest),
				SnapshotManifest.class);
			requireThat(manifest.candidateId(), "manifestSnapshot").isEqualTo(retained.snapshotId());
			requireThat(secondCycle, "sameCycle").isEqualTo(firstCycle);
			requireThat(cycle.sourceCaptureRef(), "cycleSource").isEqualTo(first);
			requireThat(secondInspection.ownerRef(), "sameInspectionOwner").isEqualTo(firstInspection.ownerRef());
			requireThat(Files.readString(materialized.resolve("target.md")), "restoredTarget").
				isEqualTo("original target\n");
			requireThat(Files.readString(materialized.resolve("source.md")), "restoredSource").
				isEqualTo("original source\n");
			requireThat(secondInspection.owner().preparationCycleRef(), "inspectionCycle").isEqualTo(firstCycle);
			requireThat(secondInspection.request().asNested().purpose(), "inspectionPurpose").
				isEqualTo(EvidencePurpose.VALIDATION);
			requireThat(retained.projectRoot(), "projectRoot").isEqualTo(project.toRealPath());
			requireThat(retained.targetPath(), "targetPath").isEqualTo(Path.of("target.md"));
			requireThat(new String(new PromptSnapshotRepository(restarted.directory()).load(
				retained.snapshotId()).entries().get(Path.of("target.md")).bytes(), StandardCharsets.UTF_8),
				"originalBytes").
				isEqualTo("original target\n");
		}
	}

	/**
	 * A registered campaign cannot invent the source capture needed by confirmation.
	 *
	 * @throws IOException if fixture setup fails or the missing capture is rejected
	 */
	@Test
	public void rejectsMissingInitialCapture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("missing-initial-capture-")).toRealPath();
			CampaignStore store = CampaignStore.initialize(root.resolve("campaign-missing"), "missing",
				ProspectiveCampaignState.profile());
			CampaignLocator locator = ProspectiveCampaignState.register(store);
			try
			{
				new InitialTargetCaptureService(locator, store).initialCapture(store.binding());
				throw new AssertionError("A missing initial capture must be rejected");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "message").contains("Initial target capture is missing");
			}
		}
	}

	/**
	 * A binding for another campaign cannot capture sources through this registered store.
	 *
	 * @throws IOException if fixture setup fails or the expected authority rejection occurs
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsForeignCampaignBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-target-capture-")).toRealPath();
			CampaignStore store = CampaignStore.initialize(root.resolve("campaign-campaign"), "campaign",
				ProspectiveCampaignState.profile());
			CampaignLocator locator = ProspectiveCampaignState.register(store);
			new InitialTargetCaptureService(locator, store).capture(new CampaignBinding("foreign", store.directory()));
		}
	}

	/**
	 * Campaign storage inside the project is excluded from the project source snapshot.
	 *
	 * @throws IOException if fixture Git or campaign I/O fails
	 */
	@Test
	public void excludesNestedWorkflowStorage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("nested-workflow-project-")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Capture Test");
			Files.writeString(project.resolve("target.md"), "original target\n");
			git(project, "add", ".");
			git(project, "commit", "-m", "initial");

			Path workflow = Files.createDirectories(project.resolve(".cat/work"));
			CampaignLocator locator = new CampaignLocator(workflow);
			locator.createOrFind(new CampaignLookupKey(project, Path.of("target.md")),
				new CampaignCreationRequest("nested", ProspectiveCampaignState.profile()));
			CampaignStore store = CampaignStore.open(workflow.resolve("campaign-nested"));
			InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
			EvidenceRef captured = sources.capture(store.binding());
			Path materialized = sources.materializeProjectSnapshot(store.binding(), captured);
			requireThat(Files.exists(materialized.resolve(".cat/work")), "nestedWorkflowCopied").isFalse();
			requireThat(Files.readString(materialized.resolve("target.md")), "nestedTarget").
				isEqualTo("original target\n");
			Files.writeString(materialized.resolve("target.md"), "tampered target\n");
			try
			{
				sources.materializeProjectSnapshot(store.binding(), captured);
				throw new AssertionError("A changed materialization must not authorize inspection");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "tamperedMaterialization").contains("conflicts");
			}
		}
	}

	/**
	 * The inventory exclusion requires an absolute, normalized workflow path.
	 *
	 * @throws IOException if an unexpected Git operation begins
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*absolute and normalized.*")
	public void rejectsRelativeWorkflowExclusion() throws IOException
	{
		LiveGitInventory.capture(Path.of("."), Path.of(".cat/work"));
	}

	/**
	 * The preparation index cannot authorize an unrelated evidence collection.
	 *
	 * @throws IOException if fixture evidence cannot be published
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*contract-preparation-cycles.*")
	public void rejectsForeignPreparationIndexCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-preparation-index-")).toRealPath();
			CampaignStore store = CampaignStore.initialize(root.resolve("campaign"), "campaign",
				ProspectiveCampaignState.profile());
			EvidenceRef foreign = store.appendImmutableJson("unrelated-records",
				"{}".getBytes(StandardCharsets.UTF_8));
			store.appendPreconfirmationIndex(store.binding(), PreconfirmationIndexKind.CONTRACT_PREPARATION,
				"INITIAL:1", foreign);
		}
	}

	/**
	 * The inspection owner index cannot authorize evidence from another collection.
	 *
	 * @throws IOException if fixture evidence cannot be published
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*contract-preparation-owners.*")
	public void rejectsForeignInspectionOwnerCollection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("foreign-inspection-owner-")).toRealPath();
			CampaignStore store = CampaignStore.initialize(root.resolve("campaign"), "campaign",
				ProspectiveCampaignState.profile());
			EvidenceRef foreign = store.appendImmutableJson("unrelated-records",
				"{}".getBytes(StandardCharsets.UTF_8));
			store.appendPreconfirmationIndex(store.binding(), PreconfirmationIndexKind.CONTRACT_INSPECTION_OWNER,
				"INITIAL:1:inspection:0", foreign);
		}
	}

	/**
	 * Runs setup Git commands in the isolated fixture project.
	 *
	 * @param project fixture Git root
	 * @param arguments Git arguments
	 * @throws IOException if Git fails
	 */
	private static void git(Path project, String... arguments) throws IOException
	{
		List<String> command = new java.util.ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start())
		{
			byte[] bytes = process.getInputStream().readNBytes(64 * 1024 + 1);
			if (bytes.length > 64 * 1024)
				throw new IOException("Fixture Git output exceeded its bound: " + command);
			String output = new String(bytes, StandardCharsets.UTF_8);
			try
			{
				if (process.waitFor() != 0)
					throw new IOException("Fixture Git command failed: " + command + ": " + output);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Fixture Git command was interrupted: " + command, e);
			}
		}
	}

	/**
	 * Local host registration receipt used to exercise the production lease boundary.
	 *
	 * @param schemaVersion the observation schema
	 * @param tokenDigest the host token digest
	 * @param hostSessionId the host session identity
	 * @param hostTurnId the host turn identity
	 */
	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}
}
