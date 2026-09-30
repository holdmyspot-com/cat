package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.optimizer.state.CallSlot;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStopInbox;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.ProspectiveCampaignState;
import io.github.cowwoc.cat.common.optimizer.state.SchedulerLease;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Exercises real C1 completion indexes, gateway edits, capture, recovery, and restoration. */
public final class LiveGenerationCaptureFlowTest
{
	private static final String RUN_ID = ProspectiveCampaignState.nativeRunId("call");

	/** An admitted edit survives a C3 restart and restores the original applied bytes. */
	@Test
	public void capturesRecoversAndRestores() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			LiveCandidateCapture captured = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			requireThat(Files.readString(fixture.prompt), "candidateBytes").isEqualTo("candidate");
			CandidateDelivery restarted = fixture.reopen();
			requireThat(restarted.recoverLiveCapture(call.checkpoint().checkpointRef()).orElseThrow(),
				"recoveredCapture").isEqualTo(captured);
			EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", captured);
			LiveGenerationRestore restored = restarted.restoreLiveGeneration(call.checkpoint().checkpointRef(),
				captureRef);
			requireThat(restored.restoredSnapshotRef(), "restoredSnapshot").isEqualTo(
				call.checkpoint().incumbentSnapshotRef());
			requireThat(Files.readString(fixture.prompt), "incumbentBytes").isEqualTo("incumbent");
			requireThat(restarted.recoverLiveCapture(call.checkpoint().checkpointRef()).orElseThrow(),
				"captureAfterRestore").isEqualTo(captured);
		}
	}

	/** Restart adopts the applied restore transaction when its separate restore index was not published. */
	@Test
	public void adoptsAppliedRestoreBeforeIndex() throws IOException
	{
		for (boolean receiptIndexed: List.of(false, true))
		{
			try (Fixture fixture = new Fixture())
			{
				CallEvidence call = fixture.run(false);
				LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
					call.toolIndexRef(), call.completionRef());
				EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", capture);
				LiveGenerationRestore first = fixture.delivery.restoreLiveGeneration(call.checkpoint().checkpointRef(),
					captureRef);
				Path index = fixture.store.directory().resolve("live-generation-restore-indexes").resolve(
					call.checkpoint().checkpointRef().evidenceId() + ".json");
				Files.delete(index);
				if (!receiptIndexed)
				{
					DeliveryReceipt transactionReceipt = fixture.evidence.read(first.transactionReceiptRef(),
						"delivery-receipts", DeliveryReceipt.class);
					Files.delete(fixture.store.directory().resolve("delivery-receipt-indexes").resolve(
						transactionReceipt.transactionId() + ".json"));
				}
				LiveGenerationRestore recovered = fixture.reopen().restoreLiveGeneration(
					call.checkpoint().checkpointRef(), captureRef);
				requireThat(recovered, "recoveredRestore").isEqualTo(first);
				requireThat(Files.readString(fixture.prompt), "incumbentBytes").isEqualTo("incumbent");
			}
		}
	}

	/** An unrelated reverse selection cannot stand in for this capture's missing restore transaction. */
	@Test
	public void refusesUnrelatedReverseSelection() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", capture);
			fixture.delivery.reconcile(capture.candidateSnapshotRef(), capture.candidateSnapshotRef(),
				capture.incumbentSnapshotRef());
			try
			{
				fixture.reopen().restoreLiveGeneration(call.checkpoint().checkpointRef(), captureRef);
				throw new AssertionError("Unrelated selection was adopted as this capture's restore");
			}
			catch (CandidateDeliveryException expected)
			{
				requireThat(expected.getMessage(), "unrelatedSelection").contains("Live state differs");
			}
		}
	}

	/** A restarted capture cannot rely on a caller-supplied index after its durable seal disappears. */
	@Test
	public void refusesMissingSealAfterRestart() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			Path seal = GenerationEditSession.indexPath(fixture.store.directory(),
				call.checkpoint().checkpointRef(), call.checkpoint().logicalCallId(), "seals",
				call.completionRef().evidenceId());
			Files.delete(seal);
			try
			{
				fixture.reopen().captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					call.completionRef());
				throw new AssertionError("Candidate capture accepted a missing durable tool-call seal");
			}
			catch (IOException _)
			{
				requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
			}
		}
	}

	/**
	 * A restarted candidate admission rehashes the indexed typed completion instead of trusting its old success.
	 */
	@Test
	public void refusesCorruptCompletionAfterRestart() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			Path completion = fixture.store.directory().resolve("native-run-completions").resolve(
				call.completionRef().evidenceId() + ".json");
			Files.writeString(completion, "{}");
			CandidateDelivery restarted = fixture.reopen();
			try
			{
				restarted.captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					call.completionRef());
				throw new AssertionError("Corrupt typed completion admitted a candidate");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "completionFailure").contains("content address");
			}
			requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
			requireThat(restarted.recoverLiveCapture(call.checkpoint().checkpointRef()).isEmpty(),
				"noCandidateAfterRestart").isTrue();
		}
	}

	/** Restart rehashes the sealed native event root and raw callback before adopting an indexed capture. */
	@Test
	public void rejectsCorruptNativeCallbackEvidence() throws IOException
	{
		for (boolean corruptRoot: List.of(false, true))
		{
			try (Fixture fixture = new Fixture())
			{
				CallEvidence call = fixture.run(false);
				fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					call.completionRef());
				GenerationToolCallIndex index = fixture.evidence.read(call.toolIndexRef(),
					"generation-tool-call-indexes", GenerationToolCallIndex.class);
				GenerationNativeCallbackIndex callback = fixture.evidence.read(index.nativeCallbackIndexRef(),
					"generation-native-callback-indexes", GenerationNativeCallbackIndex.class);
				EvidenceRef damaged = callback.orderedNativeCallIdAndRawEvidenceRefs().getFirst().rawEvidenceRef();
				if (corruptRoot)
					damaged = callback.nativeEventRootRef();
				Files.writeString(fixture.store.directory().resolve(damaged.collection()).resolve(
					damaged.evidenceId() + ".json"), "corrupt");
				try
				{
					fixture.reopen().recoverLiveCapture(call.checkpoint().checkpointRef());
					throw new AssertionError("Corrupt sealed native callback was adopted");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "callbackFailure").contains("content address");
				}
			}
		}
	}

	/** Restart rehashes a sealed native denial before using it to reconcile failed generation. */
	@Test
	public void rejectsCorruptNativeDenialEvidence() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false, true, false);
			GenerationToolCallIndex index = fixture.evidence.read(call.toolIndexRef(),
				"generation-tool-call-indexes", GenerationToolCallIndex.class);
			GenerationNativeCallbackIndex callback = fixture.evidence.read(index.nativeCallbackIndexRef(),
				"generation-native-callback-indexes", GenerationNativeCallbackIndex.class);
			EvidenceRef denial = callback.orderedNativeWriteDenialRefs().getFirst();
			Files.writeString(fixture.store.directory().resolve(denial.collection()).resolve(
				denial.evidenceId() + ".json"), "corrupt");
			try
			{
				fixture.reopen().captureFailedGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					call.completionRef());
				throw new AssertionError("Corrupt sealed native denial was adopted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "denialFailure").contains("content address");
			}
		}
	}

	/**
	 * A completion from another pinned campaign cannot admit this campaign's otherwise valid live edits.
	 */
	@Test
	public void refusesForeignCompletionAfterRestart() throws IOException
	{
		try (Fixture fixture = new Fixture(); Fixture other = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			CallEvidence foreign = other.run(false);
			CandidateDelivery restarted = fixture.reopen();
			try
			{
				restarted.captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					foreign.completionRef());
				throw new AssertionError("Foreign typed completion admitted a candidate");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "completionFailure").isNotBlank();
			}
			requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
			requireThat(restarted.recoverLiveCapture(call.checkpoint().checkpointRef()).isEmpty(),
				"noCandidateAfterRestart").isTrue();
		}
	}

	/** A substituted durable seal cannot authorize a different caller-supplied index. */
	@Test
	public void refusesConflictingSealAfterRestart() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			Path seal = GenerationEditSession.indexPath(fixture.store.directory(),
				call.checkpoint().checkpointRef(), call.checkpoint().logicalCallId(), "seals",
				call.completionRef().evidenceId());
			EvidenceRef conflicting = new EvidenceRef("generation-tool-call-indexes", "0".repeat(64));
			Files.write(seal, SnapshotJson.mapper().writeValueAsBytes(conflicting));
			try
			{
				fixture.reopen().captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					call.completionRef());
				throw new AssertionError("Candidate capture accepted a conflicting durable tool-call seal");
			}
			catch (IOException _)
			{
				requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
			}
		}
	}

	/** A live candidate carries the exact confirmed read-only source through the freeze handoff. */
	@Test
	public void freezesReadOnlyBaselineSource() throws IOException
	{
		try (Fixture fixture = new Fixture(false))
		{
			CallEvidence call = fixture.run(false);
			LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			PromptSnapshot candidate = fixture.snapshots.load(capture.candidateSnapshotRef());
			requireThat(candidate.entries().containsKey(Path.of("sources/system.md")), "hasReadOnlySource").isTrue();
			requireThat(candidate.entries().get(Path.of("sources/system.md")).hash(), "readOnlyHash").isEqualTo(
				PromptSnapshot.sha256("read-only".getBytes(StandardCharsets.UTF_8)));
			EvidenceRef freeze = new CandidateFreezeRepository(fixture.store.directory(), fixture.binding).freeze(
				fixture.project, capture.candidateSnapshotRef(), fixture.rootsRef);
			requireThat(freeze.collection(), "freezeCollection").isEqualTo("candidate-freezes");
		}
	}

	/** A selected candidate keeps a confirmed read-only source omitted from the original applied snapshot. */
	@Test
	public void appliesCandidateWithReadOnlySource() throws IOException
	{
		try (Fixture fixture = new Fixture(false))
		{
			CallEvidence call = fixture.run(false);
			LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", capture);
			fixture.reopen().restoreLiveGeneration(call.checkpoint().checkpointRef(), captureRef);
			CandidateApplication applied = fixture.reopen().reconcile(call.checkpoint().incumbentSnapshotRef(), null,
				capture.candidateSnapshotRef());
			DeliveryReceipt receipt = fixture.evidence.read(new EvidenceRef("delivery-receipts", applied.receiptId()),
				"delivery-receipts", DeliveryReceipt.class);
			requireThat(receipt.selectedCandidateId(), "selectedCandidate").isEqualTo(capture.candidateSnapshotRef());
			requireThat(Files.readString(fixture.prompt), "selectedPrompt").isEqualTo("candidate");
			requireThat(Files.readString(fixture.readOnly), "readOnlySource").isEqualTo("read-only");
		}
	}

	/** An empty successful call retains the incumbent identity even when confirmation added read-only sources. */
	@Test
	public void emptyCallKeepsIncumbentIdentity() throws IOException
	{
		try (Fixture fixture = new Fixture(false))
		{
			PromptSnapshot incumbent = PromptSnapshot.capture(fixture.project, List.of(Path.of("prompts/rule.md")));
			fixture.snapshots.save(incumbent);
			LiveGenerationCheckpoint checkpoint = fixture.delivery.prepareLiveGeneration(fixture.project,
				incumbent.id(), fixture.rootsRef, "empty-call");
			CallEvidence call = fixture.runEmptyRetry(checkpoint);
			LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(checkpoint.checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			requireThat(capture.candidateSnapshotRef(), "candidateIdentity").isEqualTo(incumbent.id());
			requireThat(capture.affectedPaths(), "changedPaths").isEmpty();
			EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", capture);
			LiveGenerationRestore first = fixture.delivery.restoreLiveGeneration(checkpoint.checkpointRef(), captureRef);
			Files.delete(fixture.store.directory().resolve("live-generation-restore-indexes").resolve(
				checkpoint.checkpointRef().evidenceId() + ".json"));
			requireThat(fixture.reopen().restoreLiveGeneration(checkpoint.checkpointRef(), captureRef),
				"recoveredEmptyRestore").isEqualTo(first);
		}
	}

	/**
	 * A regular read-only source change or creation of an absent known source rejects capture and retains accepted edits.
	 */
	@Test
	public void rejectsKnownSourceDriftAtCapture() throws IOException
	{
		for (Path source: List.of(Path.of("sources/system.md"), Path.of("sources/lazy.md")))
		{
			try (Fixture fixture = new Fixture())
			{
				CallEvidence call = fixture.run(false);
				Files.writeString(fixture.project.resolve(source), "outside edit");
				try
				{
					fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
						call.completionRef());
					throw new AssertionError("Known-source drift was admitted: " + source);
				}
				catch (CandidateDeliveryException _)
				{
					requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
					requireThat(Files.readString(fixture.project.resolve(source)), "sourceEditRetained").
						isEqualTo("outside edit");
				}
			}
		}
	}

	/** Removing a known read-only source after the native call prevents candidate capture. */
	@Test
	public void rejectsRemovedSourceAtCapture() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			Files.delete(fixture.readOnly);
			try
			{
				fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
					call.completionRef());
				throw new AssertionError("Removed source became a candidate");
			}
			catch (CandidateDeliveryException _)
			{
				requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
				requireThat(Files.notExists(fixture.readOnly), "removedSourceRetained").isTrue();
			}
		}
	}

	/** A read-only source changed after restore prevents later selection from applying the saved candidate. */
	@Test
	public void rejectsSourceDriftAtSelection() throws IOException
	{
		try (Fixture fixture = new Fixture(false))
		{
			CallEvidence call = fixture.run(false);
			LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", capture);
			fixture.reopen().restoreLiveGeneration(call.checkpoint().checkpointRef(), captureRef);
			Files.writeString(fixture.readOnly, "later source edit");
			try
			{
				fixture.reopen().reconcile(call.checkpoint().incumbentSnapshotRef(), null,
					capture.candidateSnapshotRef());
				throw new AssertionError("Selected candidate ignored source drift");
			}
			catch (CandidateDeliveryException _)
			{
				requireThat(Files.readString(fixture.prompt), "incumbentRetained").isEqualTo("incumbent");
				requireThat(Files.readString(fixture.readOnly), "sourceEditRetained").isEqualTo("later source edit");
			}
		}
	}

	/**
	 * A known source changed after failure capture prevents a retry checkpoint and retains accepted edits.
	 */
	@Test
	public void rejectsKnownSourceDriftAtRetry() throws IOException
	{
		for (Path source: List.of(Path.of("sources/system.md"), Path.of("sources/lazy.md")))
		{
			try (Fixture fixture = new Fixture())
			{
				CallEvidence call = fixture.run(true);
				LiveGenerationFailureCapture failure = fixture.delivery.captureFailedGeneration(
					call.checkpoint().checkpointRef(), call.toolIndexRef(), call.completionRef());
				EvidenceRef failureRef = fixture.evidence.publish("live-generation-failures", failure);
				Files.writeString(fixture.project.resolve(source), "outside edit");
				try
				{
					fixture.reopen().prepareRetryGeneration(failureRef, "retry-call");
					throw new AssertionError("Known-source drift was admitted: " + source);
				}
				catch (CandidateDeliveryException _)
				{
					requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
					requireThat(Files.readString(fixture.project.resolve(source)), "sourceEditRetained").
						isEqualTo("outside edit");
				}
			}
		}
	}

	/** Removing a known source after failed generation prevents retry without discarding accepted edits. */
	@Test
	public void rejectsRemovedSourceAtRetry() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(true);
			LiveGenerationFailureCapture failure = fixture.delivery.captureFailedGeneration(
				call.checkpoint().checkpointRef(), call.toolIndexRef(), call.completionRef());
			EvidenceRef failureRef = fixture.evidence.publish("live-generation-failures", failure);
			Files.delete(fixture.readOnly);
			try
			{
				fixture.reopen().prepareRetryGeneration(failureRef, "retry-call");
				throw new AssertionError("Removed source allowed another call");
			}
			catch (CandidateDeliveryException _)
			{
				requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
				requireThat(Files.notExists(fixture.readOnly), "removedSourceRetained").isTrue();
			}
		}
	}

	/**
	 * A known source changed after capture cannot be frozen or accepted from the capture index after restart.
	 */
	@Test
	public void rejectsDriftAtFreezeAndRecovery() throws IOException
	{
		for (Path source: List.of(Path.of("sources/system.md"), Path.of("sources/lazy.md")))
		{
			try (Fixture fixture = new Fixture())
			{
				CallEvidence call = fixture.run(false);
				LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(
					call.checkpoint().checkpointRef(), call.toolIndexRef(), call.completionRef());
				Files.writeString(fixture.project.resolve(source), "outside edit");
				try
				{
					new CandidateFreezeRepository(fixture.store.directory(), fixture.binding).freeze(
						fixture.project, capture.candidateSnapshotRef(), fixture.rootsRef);
					throw new AssertionError("Drifted candidate was frozen: " + source);
				}
				catch (CandidateDeliveryException _)
				{
					requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
				}
				try
				{
					fixture.reopen().recoverLiveCapture(call.checkpoint().checkpointRef());
					throw new AssertionError("Drifted capture was recovered: " + source);
				}
				catch (CandidateDeliveryException _)
				{
					requireThat(Files.readString(fixture.project.resolve(source)), "sourceEditRetained").
						isEqualTo("outside edit");
				}
			}
		}
	}

	/** A denied call retains accepted live bytes and creates a fresh retry checkpoint. */
	@Test
	public void retainsFailureAndPreparesRetry() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(true);
			LiveGenerationFailureCapture failure = fixture.delivery.captureFailedGeneration(
				call.checkpoint().checkpointRef(), call.toolIndexRef(), call.completionRef());
			requireThat(Files.readString(fixture.prompt), "retainedLiveBytes").isEqualTo("candidate");
			EvidenceRef failureRef = fixture.evidence.publish("live-generation-failures", failure);
			LiveGenerationCheckpoint retry = fixture.reopen().prepareRetryGeneration(failureRef, "retry-call");
			requireThat(retry.failedAttemptBaseRef().orElseThrow(), "retryBase").isEqualTo(failureRef);
			requireThat(retry.incumbentSnapshotRef(), "appliedIncumbent").isEqualTo(
				call.checkpoint().incumbentSnapshotRef());
			requireThat(Files.readString(fixture.prompt), "unrestoredLiveBytes").isEqualTo("candidate");
		}
	}

	/**
	 * An empty successful retry cannot turn accepted edits from its failed predecessor into a candidate.
	 */
	@Test
	public void refusesZeroEditRetryCapture() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence failed = fixture.run(true);
			LiveGenerationFailureCapture failure = fixture.delivery.captureFailedGeneration(
				failed.checkpoint().checkpointRef(), failed.toolIndexRef(), failed.completionRef());
			EvidenceRef failureRef = fixture.evidence.publish("live-generation-failures", failure);
			LiveGenerationCheckpoint retry = fixture.reopen().prepareRetryGeneration(failureRef, "retry-call");
			CallEvidence emptyRetry = fixture.runEmptyRetry(retry);
			try
			{
				fixture.reopen().captureLiveGeneration(retry.checkpointRef(), emptyRetry.toolIndexRef(),
					emptyRetry.completionRef());
				throw new AssertionError("Zero-edit retry promoted failed-attempt edits");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "failureReason").contains("zero-edit retry");
			}
			requireThat(Files.readString(fixture.prompt), "retainedFailedEdit").isEqualTo("candidate");
			requireThat(fixture.reopen().recoverLiveCapture(retry.checkpointRef()).isEmpty(),
				"noCandidateAfterRestart").isTrue();
		}
	}

	/** A successful native response cannot admit edits after a gateway or direct-write denial, even after restart. */
	@Test
	public void deniedWriteAfterSuccessIsNotCandidate() throws IOException
	{
		for (boolean nativeDenied: List.of(false, true))
		{
			try (Fixture fixture = new Fixture())
			{
				CallEvidence call = fixture.run(!nativeDenied, nativeDenied, false);
				CandidateDelivery restarted = fixture.reopen();
				try
				{
					restarted.captureLiveGeneration(call.checkpoint().checkpointRef(), call.toolIndexRef(),
						call.completionRef());
					throw new AssertionError("Denied write became a candidate after native success");
				}
				catch (IOException _)
				{
					requireThat(Files.readString(fixture.prompt), "acceptedEditRetained").isEqualTo("candidate");
				}
				LiveGenerationFailureCapture failure = restarted.captureFailedGeneration(
					call.checkpoint().checkpointRef(), call.toolIndexRef(), call.completionRef());
				requireThat(failure.denialRefs().isEmpty(), "denialRecorded").isFalse();
				requireThat(restarted.recoverLiveCapture(call.checkpoint().checkpointRef()).isEmpty(),
					"noCandidateAfterRestart").isTrue();
			}
		}
	}

	/** A user write after candidate capture prevents restoration and remains visible. */
	@Test(expectedExceptions = CandidateDeliveryException.class)
	public void refusesConcurrentRestore() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(false);
			LiveCandidateCapture capture = fixture.delivery.captureLiveGeneration(call.checkpoint().checkpointRef(),
				call.toolIndexRef(), call.completionRef());
			EvidenceRef captureRef = fixture.evidence.publish("live-generation-captures", capture);
			Files.writeString(fixture.prompt, "user edit");
			try
			{
				fixture.reopen().restoreLiveGeneration(call.checkpoint().checkpointRef(), captureRef);
			}
			finally
			{
				requireThat(Files.readString(fixture.prompt), "preservedUserEdit").isEqualTo("user edit");
			}
		}
	}

	/** A user write after failure capture prevents a fresh retry checkpoint. */
	@Test(expectedExceptions = CandidateDeliveryException.class)
	public void refusesConcurrentRetry() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CallEvidence call = fixture.run(true);
			LiveGenerationFailureCapture failure = fixture.delivery.captureFailedGeneration(
				call.checkpoint().checkpointRef(), call.toolIndexRef(), call.completionRef());
			EvidenceRef failureRef = fixture.evidence.publish("live-generation-failures", failure);
			Files.writeString(fixture.prompt, "user edit");
			try
			{
				fixture.reopen().prepareRetryGeneration(failureRef, "retry-call");
			}
			finally
			{
				requireThat(Files.readString(fixture.prompt), "preservedUserEdit").isEqualTo("user edit");
			}
		}
	}

	/** Owns a genuine Git worktree, campaign claim and C3 evidence repository. */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path project;
		private final Path prompt;
		private final Path readOnly;
		private final CampaignStore store;
		private final CampaignBinding binding;
		private final PromptSnapshotRepository snapshots;
		private final EvidenceRef rootsRef;
		private final CandidateEvidenceRepository evidence;
		private final CandidateDelivery delivery;
		private final String baselineRef;
		private final boolean completeIncumbent;

		/** Creates one test-owned campaign and active Git worktree. */
		private Fixture() throws IOException
		{
			this(true);
		}

		/**
		 * Creates a fixture with a complete or selected-target-only applied incumbent.
		 *
		 * @param completeIncumbent whether applied snapshot includes the read-only source
		 */
		private Fixture(boolean completeIncumbent) throws IOException
		{
			this.completeIncumbent = completeIncumbent;
			Path owner = cleanup.add(Files.createTempDirectory("capture-flow-"));
			project = Files.createDirectory(owner.resolve("project"));
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Candidate Test");
			Files.createDirectory(project.resolve("prompts"));
			Files.createDirectory(project.resolve("sources"));
			prompt = project.resolve("prompts/rule.md");
			readOnly = project.resolve("sources/system.md");
			Files.writeString(prompt, "incumbent");
			Files.writeString(readOnly, "read-only");
			git(project, "add", ".");
			git(project, "commit", "-m", "incumbent");
			store = new CampaignStore(owner.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			binding = store.binding();
			snapshots = new PromptSnapshotRepository(store.directory());
			EditableRoot root = new EditableRoot(Path.of("prompts/rule.md"), EditableRoot.Kind.FILE);
			evidence = new CandidateEvidenceRepository(store.directory(), binding);
			PromptSnapshot initial = PromptSnapshot.capture(project, List.of(Path.of("prompts/rule.md"),
				Path.of("sources/system.md"), Path.of("sources/lazy.md")));
			snapshots.save(initial);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project,
				List.of(Path.of("prompts/rule.md"), Path.of("sources/system.md"),
					Path.of("sources/lazy.md")), initial);
			baselineRef = new ConfirmedSourceBaselineRepository(store.directory()).save(baseline);
			rootsRef = evidence.publish("confirmed-editable-roots", new ConfirmedEditableRoots(2,
				project.toRealPath(), CandidateTestRoots.contract(store.directory()), baselineRef, List.of(root)));
			delivery = reopen();
		}

		/**
		 * Reopens the production delivery boundary after a simulated process restart.
		 *
		 * @return reopened delivery boundary
		 * @throws IOException if campaign evidence is invalid
		 */
		private CandidateDelivery reopen() throws IOException
		{
			return new CandidateDelivery(project, store.directory(), binding, snapshots, rootsRef);
		}

		/**
		 * Runs one claimed native turn and seals its complete gateway callback evidence.
		 *
		 * @param fail whether the call ends with a gateway denial and typed failure
		 * @return sealed call evidence
		 * @throws IOException if native or gateway evidence cannot be retained
		 */
		private CallEvidence run(boolean fail) throws IOException
		{
			return run(fail, false, fail);
		}

		/**
		 * Runs a claimed call with independently selected denial and native outcome evidence.
		 *
		 * @param denyGateway whether the gateway rejects a later edit
		 * @param denyNative whether the native callback records a direct-write denial
		 * @param failNative whether the typed native completion fails
		 * @return sealed call evidence
		 * @throws IOException if native or gateway evidence cannot be retained
		 */
		private CallEvidence run(boolean denyGateway, boolean denyNative, boolean failNative) throws IOException
		{
			List<Path> incumbentPaths = new ArrayList<>(List.of(Path.of("prompts/rule.md")));
			if (completeIncumbent)
				incumbentPaths.add(Path.of("sources/system.md"));
			PromptSnapshot incumbent = PromptSnapshot.capture(project, incumbentPaths);
			snapshots.save(incumbent);
			LiveGenerationCheckpoint checkpoint = delivery.prepareLiveGeneration(project, incumbent.id(),
				rootsRef, "call");
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "a".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"call\"}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef eventRef = store.appendImmutableJson("native-events", "{}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef callbackRoot = store.appendImmutableJson("native-events",
				"{\"callbackRoot\":true}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef rawArguments = store.appendImmutableJson("native-events",
				"{\"rawArguments\":true}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef directDenial = store.appendImmutableJson("native-events",
				"{\"directDenial\":true}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef completionRef;
			EvidenceRef callbackRef;
			EvidenceRef indexRef;
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot claimed = ProspectiveCampaignState.claim(lease, "call", request);
				ProspectiveCampaignState.prepareStart(store, lease, "call");
				lease.activate("call", RUN_ID);
				List<NativeCallEvidence> calls = new ArrayList<>();
				try (GenerationEditSession session = delivery.openGenerationEdit(checkpoint.checkpointRef(), request,
					claimed.slotClaimRef(), "call"))
				{
					EvidenceRef first = session.recordToolCall(RUN_ID, "session", "turn", "edit", rawArguments);
					GenerationEditOperation replace = new GenerationEditOperation("replace",
						GenerationEditOperation.Kind.REPLACE, Path.of("prompts/rule.md"), Optional.empty(),
						GenerationExpectedState.regular(PromptSnapshot.sha256("incumbent".getBytes(
							StandardCharsets.UTF_8))), Optional.empty(), Optional.of(Base64.getEncoder().encodeToString(
							"candidate".getBytes(StandardCharsets.UTF_8))));
					session.apply(replace, first);
					calls.add(new NativeCallEvidence("edit", rawArguments));
					if (denyGateway)
					{
						EvidenceRef second = session.recordToolCall(RUN_ID, "session", "turn", "deny", rawArguments);
						session.rejectRawOperation("deny", GenerationEditOperation.Kind.CREATE, "../escape",
							Optional.empty(), "OUT_OF_ROOT", second);
						calls.add(new NativeCallEvidence("deny", rawArguments));
					}
				}
				List<EvidenceRef> nativeDenials = List.of();
				if (denyNative)
					nativeDenials = List.of(directDenial);
				GenerationNativeCallbackIndex callback = new GenerationNativeCallbackIndex(1, "call", request,
					RUN_ID, callbackRoot, calls, nativeDenials);
				callbackRef = evidence.publish("generation-native-callback-indexes", callback);
				NativeRunCompletionEvidence completion = completion(request, claimed.slotClaimRef(), eventRef,
					failNative);
				completionRef = store.appendImmutableJson("native-run-completions", NativeRunCompletionCodec.encode(
					completion, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, "call", completionRef);
				lease.settle("call", completionRef);
				GenerationToolCallIndex index = delivery.sealGenerationToolCalls(checkpoint.checkpointRef(),
					completionRef, callbackRef);
				indexRef = evidence.publish("generation-tool-call-indexes", index);
				lease.adopt("call", indexRef);
			}
			return new CallEvidence(checkpoint, indexRef, completionRef);
		}

		/**
		 * Settles a fresh retry with no native edit callback while retaining the failed attempt's live bytes.
		 *
		 * @param retry checkpoint tied to the retained failed attempt
		 * @return the retry's sealed empty callback and successful typed completion
		 * @throws IOException if the campaign or native evidence cannot be retained
		 */
		private CallEvidence runEmptyRetry(LiveGenerationCheckpoint retry) throws IOException
		{
			String callId = retry.logicalCallId();
			String retryRunId = ProspectiveCampaignState.nativeRunId(callId);
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "retry-invocation");
			inbox.publishHostObservation(binding, "retry-invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "c".repeat(64) +
					"\",\"hostSessionId\":\"retry-session\",\"hostTurnId\":\"retry-turn\"}").
					getBytes(StandardCharsets.UTF_8));
			EvidenceRef request = store.appendImmutableJson("run-requests",
				("{\"logicalCallId\":\"" + callId + "\"}").getBytes(StandardCharsets.UTF_8));
			EvidenceRef eventRef = store.appendImmutableJson("native-events", "{}".getBytes(StandardCharsets.UTF_8));
			EvidenceRef completionRef;
			EvidenceRef callbackRef;
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "retry-invocation"))
			{
				CallSlot claimed = ProspectiveCampaignState.claim(lease, callId, request);
				ProspectiveCampaignState.prepareStart(store, lease, callId);
				lease.activate(callId, retryRunId);
				GenerationNativeCallbackIndex callback = new GenerationNativeCallbackIndex(1, callId, request,
					retryRunId, eventRef, List.of(), List.of());
				callbackRef = evidence.publish("generation-native-callback-indexes", callback);
				NativeEventEvidence nativeEvent = new NativeEventEvidence("conversation", 1, eventRef);
				NestedRunResult result = new NestedRunResult("codex", retryRunId, project,
					List.of(new TurnResult(1, "conversation", "response", nativeEvent)), List.of(nativeEvent),
					new RunnerIdentity("codex", "version", "b".repeat(64), Optional.of("provider"), Map.of(),
						List.of()), new ModelProfile("model", Optional.empty(), Map.of()), List.of(), List.of(),
					List.of(), List.of(new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
						ObservationCoverageStatus.LIMITED, Optional.of("no path events"), List.of()),
						new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.LIMITED,
							Optional.of("no tool events"), List.of()),
						new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.LIMITED,
							Optional.of("no external events"), List.of())));
				NativeRunCompletionEvidence completion = new NativeRunCompletionEvidence("campaign", callId, request,
					claimed.slotClaimRef(), Optional.of(retryRunId), NativeRunCompletionEvidence.Outcome.SUCCEEDED,
					Optional.of(result), Optional.empty(), List.of(eventRef), Optional.of(eventRef), Optional.empty());
				completionRef = store.appendImmutableJson("native-run-completions", NativeRunCompletionCodec.encode(
					completion, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, callId, completionRef);
				lease.settle(callId, completionRef);
			}
			GenerationToolCallIndex index = delivery.sealGenerationToolCalls(retry.checkpointRef(), completionRef,
				callbackRef);
			return new CallEvidence(retry, evidence.publish("generation-tool-call-indexes", index), completionRef);
		}

		/**
		 * Creates one valid C0RT completion for the claimed turn.
		 *
		 * @param request retained C0R request
		 * @param claim C1 call claim
		 * @param event retained native event
		 * @param fail whether the turn failed
		 * @return valid typed completion
		 */
		private NativeRunCompletionEvidence completion(EvidenceRef request, EvidenceRef claim, EvidenceRef event,
			boolean fail)
		{
			if (fail)
			{
				NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"call", Optional.of(RUN_ID), "failed", List.of(event), Optional.of(event), Optional.empty());
				return new NativeRunCompletionEvidence("campaign", "call", request, claim, Optional.of(RUN_ID),
					NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure),
					List.of(event), Optional.of(event), Optional.empty());
			}
			NativeEventEvidence nativeEvent = new NativeEventEvidence("conversation", 1, event);
			NestedRunResult result = new NestedRunResult("codex", RUN_ID, project,
				List.of(new TurnResult(1, "conversation", "response", nativeEvent)), List.of(nativeEvent),
				new RunnerIdentity("codex", "version", "b".repeat(64), Optional.of("provider"), Map.of(),
					List.of()), new ModelProfile("model", Optional.empty(), Map.of()), List.of(), List.of(),
				List.of(), List.of(new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
					ObservationCoverageStatus.LIMITED, Optional.of("no path events"), List.of()),
					new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.LIMITED,
						Optional.of("no tool events"), List.of()),
					new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.LIMITED,
						Optional.of("no external events"), List.of())));
			return new NativeRunCompletionEvidence("campaign", "call", request, claim, Optional.of(RUN_ID),
				NativeRunCompletionEvidence.Outcome.SUCCEEDED, Optional.of(result), Optional.empty(),
				List.of(event), Optional.of(event), Optional.empty());
		}

		/**
		 * Runs one fixture-owned Git command.
		 *
		 * @param directory test Git worktree
		 * @param arguments Git arguments
		 * @throws IOException if Git fails
		 */
		private static void git(Path directory, String... arguments) throws IOException
		{
			List<String> command = new ArrayList<>(List.of("git"));
			command.addAll(List.of(arguments));
			try (Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).
				start())
			{
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				if (process.waitFor() != 0)
					throw new IOException("Git fixture failed: " + output);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Git fixture interrupted", e);
			}
		}

		/** Removes only fixture-owned paths. */
		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}

	/**
	 * Complete sealed evidence returned by one fixture native call.
	 *
	 * @param checkpoint pre-call live checkpoint
	 * @param toolIndexRef sealed gateway calls
	 * @param completionRef C1 indexed native completion
	 */
	private record CallEvidence(LiveGenerationCheckpoint checkpoint, EvidenceRef toolIndexRef,
		EvidenceRef completionRef)
	{
	}
}
