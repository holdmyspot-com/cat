package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RetainedEventArchive;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.StoppedBridgeResultReader;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunObservation;
import io.github.cowwoc.cat.common.runner.HarnessRunWorkspace;
import io.github.cowwoc.cat.common.runner.HarnessTranscriptObservation;
import io.github.cowwoc.cat.common.runner.NativePromptPathObservation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Joins an owned Claude run, bridge responses, and retained native turns into a typed settlement. */
final class RunResultReader
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

	/**
	 * Reads a successful run only when all requested turns agree with their retained native results.
	 *
	 * @param request original logical call and execution context
	 * @param manager owned-run repository
	 * @param runId retained run identifier
	 * @param transcript retained bridge transcript
	 * @param store immutable campaign evidence repository
	 * @param runtimeManifestSha256 runtime identity retained before native start
	 * @return successful typed observations with explicit coverage limitations
	 * @throws IOException if completion or agreement cannot be established
	 */
	static NestedRunResult readSucceeded(NestedRunRequest request, HarnessRunManager manager, String runId,
		Path transcript, CampaignStore store, String runtimeManifestSha256) throws IOException
	{
		NestedRunCompletion completion = readCompleted(request, manager, runId, transcript, store,
			runtimeManifestSha256);
		if (completion instanceof NestedRunCompletion.Succeeded success)
			return success.result();
		throw new IOException("Retained Claude run does not prove successful completion: " + runId);
	}

	/**
	 * Settles successful turns, native terminal failure, or a zero-turn stop from matching retained observations.
	 *
	 * @param request original logical call
	 * @param manager owned-run repository
	 * @param runId retained run identifier
	 * @param transcript retained bridge transcript
	 * @param store immutable evidence repository
	 * @param runtimeManifestSha256 prestart runtime identity
	 * @return typed settlement
	 * @throws IOException if retained sources disagree or do not prove settlement
	 */
	static NestedRunCompletion readCompleted(NestedRunRequest request, HarnessRunManager manager, String runId,
		Path transcript, CampaignStore store, String runtimeManifestSha256) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(manager, "manager");
		HarnessRun.requireValidId(runId);
		Objects.requireNonNull(transcript, "transcript");
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(runtimeManifestSha256, "runtimeManifestSha256");
		if (!runtimeManifestSha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Invalid runner runtime manifest SHA-256");

		HarnessRunObservation owned = manager.observe("claude", runId).
			orElseThrow(() -> new IOException("Retained Claude run is missing: " + runId));
		if (owned.state() == HarnessRunObservation.State.RUNNING)
			throw new IOException("Retained Claude run is still running: " + runId);
		Path project = HarnessRunWorkspace.executionProject(owned.run(), request, store::readImmutableEvidence);
		HarnessTranscriptObservation bridge = HarnessTranscriptObservation.read(JSON, transcript, runId).
			orElseThrow(() -> new IOException("Retained Claude bridge transcript is missing: " + transcript));
		if (owned.turnNumber() > request.turns().size())
			throw new IOException("Retained Claude run exceeds the requested turn count");
		if (owned.state() == HarnessRunObservation.State.FAILED &&
			bridge.state() == HarnessTranscriptObservation.SessionState.IN_PROGRESS)
		{
			if (owned.turnNumber() == 0 || bridge.state() != HarnessTranscriptObservation.SessionState.IN_PROGRESS ||
				bridge.turns().size() != owned.turnNumber() - 1)
				throw new IOException("Retained Claude failed run has no matching preceding bridge sequence: " + transcript);
			return failed(request, owned.run(), bridge, store);
		}
		if (bridge.state() != HarnessTranscriptObservation.SessionState.COMPLETED ||
			bridge.turns().size() != owned.turnNumber())
			throw new IOException("Retained Claude bridge has no matching completed sequence: " + transcript);
		if (owned.turnNumber() == 0)
			return StoppedBridgeResultReader.read(request, owned, bridge, transcript, store);
		if (!bridge.turns().isEmpty() &&
			bridge.turns().getLast().state() == HarnessTranscriptObservation.TurnState.INTERRUPTED)
			return interrupted(request, owned.run(), bridge, store);

		ObservedRun observed = archiveTurns(owned.run(), bridge, owned.turnNumber(), store);
		if (bridge.turns().size() != request.turns().size())
			throw new IOException("Completed Claude turn count differs from the requested conversation");
		return new NestedRunCompletion.Succeeded(result(owned.run(), project, observed, store, runtimeManifestSha256));
	}

	/**
	 * Archives each native turn once and binds completed bridge responses to exact native terminal records.
	 *
	 * @param run owned run
	 * @param bridge retained completed prefix or full completed sequence
	 * @param nativeCount number of completed native turns to validate
	 * @param store immutable record repository
	 * @return validated turns, global native ordinals, and observed parent metadata
	 * @throws IOException if a native turn contradicts the bridge or another turn
	 */
	private static ObservedRun archiveTurns(HarnessRun run, HarnessTranscriptObservation bridge,
		int nativeCount, CampaignStore store) throws IOException
	{
		Path artifacts = run.directory().resolve("artifacts");
		if (!Files.isDirectory(artifacts, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Retained Claude artifacts are not an owned directory: " + artifacts);
		List<NativeEventEvidence> events = new ArrayList<>();
		List<TurnResult> turns = new ArrayList<>(bridge.turns().size());
		Optional<ClaudeSessionInitialization> previous = Optional.empty();
		String model = null;
		for (int ordinal = 1; ordinal <= nativeCount; ++ordinal)
		{
			HarnessTranscriptObservation.Turn bridgeTurn = bridge.turns().get(ordinal - 1);
			RetainedEventArchive.Archive archive = RetainedEventArchive.archive(
				List.of(artifacts.resolve("turn-" + ordinal + ".events")), store);
			ClaudeArchivedTurnReader.ObservedTurn observed = ClaudeArchivedTurnReader.read(archive, store, previous);
			ClaudeTurnObservation nativeTurn = observed.turn();
			String turnModel = completedModel(bridgeTurn, nativeTurn);
			if (model != null && !model.equals(turnModel))
				throw new IOException("Claude reported parent model changed across turns");
			model = turnModel;
			int offset = events.size();
			for (int index = 0; index < archive.events().size(); ++index)
				events.add(new NativeEventEvidence(nativeTurn.sessionId(), offset + index + 1L, archive.events().get(index)));
			NativeEventEvidence terminal = events.get(Math.toIntExact(offset + observed.terminalArchiveOrdinal() - 1));
			turns.add(new TurnResult(ordinal, nativeTurn.sessionId(), nativeTurn.response().orElseThrow(), terminal));
			previous = Optional.of(observed.initialization());
		}
		return new ObservedRun(previous, Optional.ofNullable(model), List.copyOf(turns),
			List.copyOf(events));
	}

	/**
	 * Retains a failed owned run's final output even when native termination preceded its result record.
	 *
	 * @param request original logical call
	 * @param run owned failed run
	 * @param bridge retained completed prefix
	 * @param store immutable evidence repository
	 * @return infrastructure failure without a semantic result or no-dispatch claim
	 * @throws IOException if the prefix or final native records contradict failure
	 */
	private static NestedRunCompletion.Failed failed(NestedRunRequest request, HarnessRun run,
		HarnessTranscriptObservation bridge, CampaignStore store) throws IOException
	{
		ObservedRun prefix = archiveTurns(run, bridge, bridge.turns().size(), store);
		RetainedEventArchive.Archive archive = RetainedEventArchive.archive(
			List.of(run.directory().resolve("artifacts/turn-" + (bridge.turns().size() + 1) + ".events")), store,
			RetainedEventArchive.EmptyTurnPolicy.RETAIN_SOURCE_HASH);
		Optional<JsonNode> terminal = terminalRecord(archive, store);
		if (terminal.isPresent())
		{
			if (ClaudeResultDisposition.read(terminal.orElseThrow()) == ClaudeResultDisposition.COMPLETED)
				throw new IOException("Claude successful native result has no completed bridge response");
			ClaudeArchivedTurnReader.ObservedTurn observed = ClaudeArchivedTurnReader.read(archive, store,
				prefix.initialization());
			if (observed.turn().status() != ClaudeTurnObservation.Status.FAILED)
				throw new IOException("Claude failed owned run contradicts native terminal status");
		}
		return new NestedRunCompletion.Failed(new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED,
			request.logicalCallId(), Optional.of(run.id()), "Claude native turn failed", nativeReferences(prefix, archive),
			Optional.empty(), Optional.empty()));
	}

	/**
	 * Retains an acknowledged interruption after validating every preceding completed turn.
	 *
	 * @param request original logical call
	 * @param run owned completed run
	 * @param bridge completed bridge ending in an interrupted turn
	 * @param store immutable evidence repository
	 * @return interruption with available native records and no no-dispatch claim
	 * @throws IOException if the preceding sequence or interrupted artifacts contradict the bridge
	 */
	private static NestedRunCompletion.Failed interrupted(NestedRunRequest request, HarnessRun run,
		HarnessTranscriptObservation bridge, CampaignStore store) throws IOException
	{
		HarnessTranscriptObservation.Turn last = bridge.turns().getLast();
		if (bridge.state() != HarnessTranscriptObservation.SessionState.COMPLETED || last.exitCode() == 0)
			throw new IOException("Claude interruption has no matching terminal bridge acknowledgement");
		ObservedRun prefix = archiveTurns(run, bridge, last.ordinal() - 1, store);
		RetainedEventArchive.Archive archive = RetainedEventArchive.archive(
			List.of(run.directory().resolve("artifacts/turn-" + last.ordinal() + ".events")), store,
			RetainedEventArchive.EmptyTurnPolicy.RETAIN_SOURCE_HASH);
		Optional<JsonNode> terminal = terminalRecord(archive, store);
		if (terminal.isPresent() &&
			ClaudeResultDisposition.read(terminal.orElseThrow()) != ClaudeResultDisposition.ABORTED_STREAMING)
			throw new IOException("Claude native terminal result contradicts the interrupted bridge turn");
		return new NestedRunCompletion.Failed(new NestedRunFailure(NestedRunFailureKind.INTERRUPTED,
			request.logicalCallId(), Optional.of(run.id()), "Claude native turn was interrupted",
			nativeReferences(prefix, archive),
			Optional.empty(), Optional.empty()));
	}

	/**
	 * Reads a terminal result when present without requiring one after unsuccessful native dispatch.
	 *
	 * @param archive exact final turn records
	 * @param store immutable evidence repository
	 * @return optional final result record
	 * @throws IOException if records are malformed or follow a terminal result
	 */
	private static Optional<JsonNode> terminalRecord(RetainedEventArchive.Archive archive, CampaignStore store)
		throws IOException
	{
		Optional<JsonNode> terminal = Optional.empty();
		for (int index = 0; index < archive.events().size(); ++index)
		{
			JsonNode event;
			try
			{
				event = JSON.readTree(store.readImmutableBytes(archive.events().get(index)));
			}
			catch (JacksonException e)
			{
				throw new IOException("Claude unsuccessful native record " + (index + 1) + " is malformed", e);
			}
			if (event == null || !event.isObject())
				throw new IOException("Claude unsuccessful native record " + (index + 1) + " requires an object");
			if (event.path("type").asString().equals("result"))
			{
				if (index != archive.events().size() - 1)
					throw new IOException("Claude native records follow their terminal result");
				terminal = Optional.of(event);
			}
		}
		return terminal;
	}

	/**
	 * Keeps preceding and final native references in their original order.
	 *
	 * @param prefix validated completed turns
	 * @param archive final unsuccessful turn archive
	 * @return all available native references
	 */
	private static List<EvidenceRef> nativeReferences(ObservedRun prefix, RetainedEventArchive.Archive archive)
	{
		List<EvidenceRef> references = new ArrayList<>(prefix.events().size() + archive.events().size());
		for (NativeEventEvidence event : prefix.events())
			references.add(event.evidence());
		references.addAll(archive.events());
		return List.copyOf(references);
	}

	/**
	 * Requires successful status and exact response agreement before selecting reported model evidence.
	 *
	 * @param bridge completed bridge turn
	 * @param nativeTurn decoded terminal native turn
	 * @return reported parent model
	 * @throws IOException if the native result does not prove that bridge response
	 */
	private static String completedModel(HarnessTranscriptObservation.Turn bridge,
		ClaudeTurnObservation nativeTurn) throws IOException
	{
		if (bridge.state() != HarnessTranscriptObservation.TurnState.COMPLETED || bridge.exitCode() != 0 ||
			nativeTurn.status() != ClaudeTurnObservation.Status.COMPLETED ||
			!bridge.assistantResponse().equals(nativeTurn.response()))
			throw new IOException("Claude native turn does not match the completed bridge response");
		return nativeTurn.reportedModelId().orElseThrow();
	}

	/**
	 * Builds observations while explicitly retaining the limits of native settings and tool evidence.
	 *
	 * @param run owned run
	 * @param project verified execution project
	 * @param observed matching native and bridge turns
	 * @param store immutable record repository
	 * @param runtimeManifestSha256 prestart runtime identity
	 * @return successful typed result
	 * @throws IOException if prompt-source evidence cannot be read
	 */
	private static NestedRunResult result(HarnessRun run, Path project, ObservedRun observed,
		CampaignStore store, String runtimeManifestSha256) throws IOException
	{
		NativePromptPathObservation prompt = new ClaudeNativePromptPathCollector(project).collect(run.id(),
			observed.initialization().orElseThrow().sessionId(), observed.events(), store::readImmutableBytes);
		List<EvidenceRef> references = observed.events().stream().map(NativeEventEvidence::evidence).toList();
		List<ObservationCoverage> coverage = List.of(prompt.coverage(),
			new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.LIMITED,
				Optional.of("Direct tool executions were not derived from Claude native events"), references),
			new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.LIMITED,
				Optional.of("External source executions were not derived from Claude native events"), references));
		Map<String, String> configuration = observed.initialization().orElseThrow().nativeVersion().
			<Map<String, String>>map(version -> Map.of("claudeVersion", version)).orElseGet(Map::of);
		RunnerIdentity identity = new RunnerIdentity("claude", PluginInfo.CAT_VERSION, runtimeManifestSha256,
			Optional.empty(), configuration, List.of("Claude did not expose a model provider identity",
				"Claude did not expose effective reasoning settings"));
		return new NestedRunResult("claude", run.id(), project, observed.turns(), observed.events(), identity,
			new ModelProfile(observed.model().orElseThrow(), Optional.empty(), Map.of()), prompt.toRuntimePromptSources(),
			List.of(), List.of(), coverage);
	}

	/**
	 * Matching observed native metadata, completed turns, and global evidence ordinals.
	 *
	 * @param initialization latest observed parent metadata, absent before any completed turn
	 * @param model stable reported parent model, absent if failure preceded a model response
	 * @param turns completed bridge/native joins
	 * @param events exact archived records in run order
	 */
	private record ObservedRun(Optional<ClaudeSessionInitialization> initialization, Optional<String> model,
		List<TurnResult> turns, List<NativeEventEvidence> events)
	{
	}

	/** Prevents construction. */
	private RunResultReader()
	{
	}
}
