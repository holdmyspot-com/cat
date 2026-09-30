package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
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
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/** Checks C3 sealing through C1's real retained slot and typed completion index. */
public final class GenerationToolCallSealingTest
{
	private static final String RUN_ID = ProspectiveCampaignState.nativeRunId("logical");

	/** An indexed completion and verified zero callbacks produces an empty seal. */
	@Test
	public void sealsEmptyCallbackInventory() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			GenerationToolCallIndex sealed = session.sealGenerationToolCalls(fixture.completion,
				fixture.callbackIndex(List.of()));
			requireThat(sealed.orderedToolCallAndDispositionRefs(), "emptyCalls").isEmpty();
			requireThat(sealed.slotClaimRef(), "slotClaim").isEqualTo(fixture.claim);
		}
	}

	/** A recorded callback without an edit remains explicitly unresolved in the seal. */
	@Test
	public void sealsRecordedCallAsUnresolved() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			session.recordToolCall(RUN_ID, "session", "turn", "native-call", fixture.nativeEvent);
			GenerationToolCallIndex sealed = session.sealGenerationToolCalls(fixture.completion,
				fixture.callbackIndex(List.of(new NativeCallEvidence("native-call", fixture.nativeEvent))));
			EvidenceRef dispositionRef = sealed.orderedToolCallAndDispositionRefs().getFirst().dispositionRef();
			GenerationToolDisposition disposition = fixture.evidence.read(dispositionRef,
				"generation-tool-dispositions", GenerationToolDisposition.class);
			requireThat(disposition.status(), "status").isEqualTo(GenerationToolDisposition.Status.UNRESOLVED);
		}
	}

	/** A native pathname rejection remains rejected after typed completion sealing. */
	@Test
	public void sealsRawPathDenial() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			EvidenceRef call = session.recordToolCall(RUN_ID, "session", "turn", "native-call",
				fixture.nativeEvent);
			session.rejectRawOperation("bad-path", GenerationEditOperation.Kind.CREATE,
				"../outside.md", Optional.empty(), "INVALID_PATH", call);
			GenerationToolCallIndex sealed = session.sealGenerationToolCalls(fixture.completion,
				fixture.callbackIndex(List.of(new NativeCallEvidence("native-call", fixture.nativeEvent))));
			EvidenceRef dispositionRef = sealed.orderedToolCallAndDispositionRefs().getFirst().dispositionRef();
			GenerationToolDisposition disposition = fixture.evidence.read(dispositionRef,
				"generation-tool-dispositions", GenerationToolDisposition.class);
			requireThat(disposition.status(), "status").isEqualTo(GenerationToolDisposition.Status.REJECTED);
		}
	}

	/** A callback inventory that omits a recorded C3 call cannot seal. */
	@Test
	public void rejectsOmittedRecordedCall() throws IOException
	{
		try (Fixture fixture = new Fixture(); GenerationEditSession session = fixture.session())
		{
			session.recordToolCall(RUN_ID, "session", "turn", "native-call", fixture.nativeEvent);
			try
			{
				session.sealGenerationToolCalls(fixture.completion, fixture.callbackIndex(List.of()));
				throw new AssertionError("Expected omitted callback rejection");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "omittedCall").contains("omits");
			}
		}
	}

	/** A real C1 claim, completion index, checkpoint, and C9 callback fixture. */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final CampaignStore store;
		private final CampaignBinding binding;
		private final CandidateEvidenceRepository evidence;
		private final EvidenceRef checkpoint;
		private final EvidenceRef request;
		private final EvidenceRef claim;
		private final EvidenceRef nativeEvent;
		private final EvidenceRef completion;

		/** Creates the exact C1 and C3 evidence handoff. */
		private Fixture() throws IOException
		{
			Path parent = cleanup.add(Files.createTempDirectory("generation-seal-"));
			Path project = Files.createDirectory(parent.resolve("project")).toRealPath();
			Files.createDirectory(project.resolve("prompts"));
			store = new CampaignStore(parent.resolve("campaign-campaign"));
			ProspectiveCampaignState.initialize(store, "campaign");
			ProspectiveCampaignState.addLineage(store, "d".repeat(64));
			binding = store.binding();
			CampaignStopInbox inbox = ProspectiveCampaignState.inbox(store);
			EvidenceRef registration = inbox.beginInvocation(binding, "invocation");
			inbox.publishHostObservation(binding, "invocation", registration,
				("{\"schemaVersion\":1,\"tokenDigest\":\"" + "a".repeat(64) +
					"\",\"hostSessionId\":\"session\",\"hostTurnId\":\"turn\"}").
					getBytes(StandardCharsets.UTF_8));
			request = store.appendImmutableJson("run-requests",
				"{\"logicalCallId\":\"logical\"}".getBytes(StandardCharsets.UTF_8));
			nativeEvent = store.appendImmutableJson("native-events", "{}".getBytes(StandardCharsets.UTF_8));
			try (SchedulerLease lease = ProspectiveCampaignState.lease(store).acquire(binding, "invocation"))
			{
				CallSlot slot = ProspectiveCampaignState.claim(lease, "logical", request);
				claim = slot.slotClaimRef();
				ProspectiveCampaignState.prepareStart(store, lease, "logical");
				lease.activate("logical", RUN_ID);
				NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
					"logical", Optional.of(RUN_ID), "transport failed", List.of(nativeEvent), Optional.empty(),
					Optional.empty());
				NativeRunCompletionEvidence completed = new NativeRunCompletionEvidence("campaign", "logical",
					request, claim, Optional.of(RUN_ID), NativeRunCompletionEvidence.Outcome.FAILED,
					Optional.empty(), Optional.of(failure), List.of(nativeEvent), Optional.empty(), Optional.empty());
				completion = store.appendImmutableJson("native-run-completions",
					NativeRunCompletionCodec.encode(completed, store.evidenceReader(binding)));
				store.publishNativeCompletionIndex(binding, "logical", completion);
			}
			evidence = new CandidateEvidenceRepository(store.directory(), binding);
			List<EditableRoot> editableRoots = List.of(new EditableRoot(Path.of("prompts"),
				EditableRoot.Kind.DIRECTORY));
			ConfirmedEditableRoots roots = new ConfirmedEditableRoots(2, project,
				CandidateTestRoots.contract(store.directory()),
				CandidateTestRoots.baseline(project, store.directory(), editableRoots), editableRoots);
			EvidenceRef rootsRef = evidence.publish("confirmed-editable-roots", roots);
			LiveGenerationCheckpoint value = new LiveGenerationCheckpoint(1, "checkpoint", "logical", project,
				"4".repeat(64), rootsRef, new EvidenceRef("git-file-inventories", "5".repeat(64)),
				Optional.empty());
			checkpoint = evidence.publish("live-generation-checkpoints", value);
		}

		/**
		 * Opens the C1 validated generation session.
		 *
		 * @return pinned session
		 */
		private GenerationEditSession session() throws IOException
		{
			return new GenerationEditSession(store.directory(), binding, checkpoint, request, claim, "logical");
		}

		/**
		 * Publishes a C9 callback inventory for this native event root.
		 *
		 * @param calls native callbacks in observed order
		 * @return immutable callback index
		 */
		private EvidenceRef callbackIndex(List<NativeCallEvidence> calls) throws IOException
		{
			return evidence.publish("generation-native-callback-indexes", new GenerationNativeCallbackIndex(1,
				"logical", request, RUN_ID, nativeEvent, calls, List.of()));
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
