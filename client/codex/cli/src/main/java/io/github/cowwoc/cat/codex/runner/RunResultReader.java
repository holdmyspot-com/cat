package io.github.cowwoc.cat.codex.runner;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.StoppedBridgeResultReader;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailure;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunFailureKind;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RetainedEventArchive;
import io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceKind;
import io.github.cowwoc.cat.common.optimizer.runner.TurnResult;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunWorkspace;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunObservation;
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

import tools.jackson.databind.json.JsonMapper;

/**
 * Settles retained Codex bridge turns into typed optimizer observations or an interrupted failure.
 */
final class RunResultReader
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Reads a terminal owned run, including an interrupted native turn.
	 *
	 * @param request the original logical native call
	 * @param manager the owned-run manager
	 * @param runId the preallocated native run identity
	 * @param transcript the retained bridge transcript
	 * @param store the campaign evidence repository
	 * @param runtimeManifestSha256 the runtime identity retained before native start
	 * @return the typed terminal native settlement
	 * @throws IOException if the retained run cannot prove a terminal conversation
	 */
	static NestedRunCompletion readCompleted(NestedRunRequest request, HarnessRunManager manager, String runId,
		Path transcript, CampaignStore store, String runtimeManifestSha256) throws IOException
	{
		Objects.requireNonNull(request, "request");
		validateReadInputs(manager, runId, transcript, store, runtimeManifestSha256);
		HarnessRunObservation owned = manager.observe("codex", runId).
			orElseThrow(() -> new IOException("Retained Codex run is missing: " + runId));
		Path executionProject = HarnessRunWorkspace.executionProject(owned.run(), request,
			store::readImmutableEvidence);
		if (owned.state() == HarnessRunObservation.State.COMPLETED && owned.turnNumber() == 0)
			return stoppedBeforeFirstTurn(request, runId, transcript, store, owned);
		ObservedRun observed = readEvidence(manager, runId, transcript, store);
		List<HarnessTranscriptObservation.Turn> bridgeTurns = observed.bridge().turns();
		List<TurnProfileReader.TerminalTurn> nativeTurns = observed.profile().terminalTurns();
		if (bridgeTurns.size() != nativeTurns.size() || bridgeTurns.size() > request.turns().size())
			throw new IOException("Codex native turn count differs from the requested bridge conversation");
		HarnessTranscriptObservation.Turn lastBridge = bridgeTurns.getLast();
		if (lastBridge.state() == HarnessTranscriptObservation.TurnState.INTERRUPTED)
		{
			observedTurns(observed.bridge(), observed.profile(), observed.nativeEvents(), bridgeTurns.size() - 1);
			TurnProfileReader.TerminalTurn lastNative = nativeTurns.getLast();
			if (lastNative.status() != TurnProfileReader.TurnStatus.INTERRUPTED ||
				lastNative.turnOrdinal() != bridgeTurns.size() ||
				lastNative.eventOrdinal() > observed.nativeEvents().size())
				throw new IOException("Codex native interruption does not match the bridge response");
			List<EvidenceRef> nativeEvidence = observed.nativeEvents().stream().
				map(NativeEventEvidence::evidence).toList();
			NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.INTERRUPTED,
				request.logicalCallId(), Optional.of(runId), "Codex native turn was interrupted", nativeEvidence,
				Optional.empty(), Optional.empty());
			return new NestedRunCompletion.Failed(failure);
		}
		if (bridgeTurns.size() != request.turns().size())
			throw new IOException("Completed Codex turn count differs from the requested conversation");
		NestedRunResult result = succeeded(observed, store, runtimeManifestSha256, executionProject);
		if (request.context() instanceof SterileWorkspaceContext sterile &&
			sterile.kind() == SterileWorkspaceKind.EVALUATOR &&
			observed.profile().allCommandExecutionsFailed())
		{
			List<EvidenceRef> nativeEvidence = result.nativeEventEvidence().stream().
				map(NativeEventEvidence::evidence).toList();
			NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.RECEIPT_FAILED,
				request.logicalCallId(), Optional.of(runId),
				"Every Codex evaluator command failed before frozen inputs could be read", nativeEvidence,
				Optional.empty(), Optional.empty());
			return new NestedRunCompletion.Failed(failure);
		}
		return new NestedRunCompletion.Succeeded(result);
	}

	/**
	 * Settles a completed bridge that never reserved a native turn using its retained terminal timestamp.
	 *
	 * @param request the original logical call
	 * @param runId the retained native run identity
	 * @param transcript the bridge's terminal transcript
	 * @param store the immutable campaign evidence repository
	 * @param owned the completed owned run with zero turns
	 * @return an interruption with content-addressed no-dispatch evidence
	 * @throws IOException if the bridge does not prove completed zero-turn execution
	 */
	private static NestedRunCompletion stoppedBeforeFirstTurn(NestedRunRequest request, String runId,
		Path transcript, CampaignStore store, HarnessRunObservation owned) throws IOException
	{
		HarnessTranscriptObservation bridge = HarnessTranscriptObservation.read(JSON, transcript, runId).
			orElseThrow(() -> new IOException("Retained Codex bridge transcript is missing: " + transcript));
		return StoppedBridgeResultReader.read(request, owned, bridge, transcript, store);
	}

	/**
	 * Reads a completed owned run and its exact native and bridge evidence.
	 *
	 * @param manager the owned-run manager
	 * @param runId the preallocated native run identity
	 * @param transcript the retained bridge transcript
	 * @param store the campaign evidence repository
	 * @param runtimeManifestSha256 the runtime identity retained before native start
	 * @return the typed successful native run
	 * @throws IOException if the retained run cannot prove a successful conversation
	 */
	static NestedRunResult readSucceeded(HarnessRunManager manager, String runId, Path transcript, CampaignStore store,
		String runtimeManifestSha256) throws IOException
	{
		validateReadInputs(manager, runId, transcript, store, runtimeManifestSha256);
		ObservedRun observed = readEvidence(manager, runId, transcript, store);
		return succeeded(observed, store, runtimeManifestSha256,
			observed.run().directory().resolve("project"));
	}

	/**
	 * Checks caller inputs before any native event is copied into campaign evidence.
	 *
	 * @param manager the owned-run manager
	 * @param runId the retained native run identity
	 * @param transcript the retained bridge transcript path
	 * @param store the campaign evidence repository
	 * @param runtimeManifestSha256 the verified prestart runner identity
	 */
	private static void validateReadInputs(HarnessRunManager manager, String runId, Path transcript,
		CampaignStore store, String runtimeManifestSha256)
	{
		Objects.requireNonNull(manager, "manager");
		HarnessRun.requireValidId(runId);
		Objects.requireNonNull(transcript, "transcript");
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(runtimeManifestSha256, "runtimeManifestSha256");
		if (!runtimeManifestSha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Invalid runner runtime manifest SHA-256");
	}

	/**
	 * Observes and archives the exact native records for one terminal bridge conversation.
	 *
	 * @param manager the owned-run manager
	 * @param runId the exact retained run identity
	 * @param transcript the retained bridge transcript
	 * @param store the immutable campaign evidence repository
	 * @return matching owned, bridge, and native observations
	 * @throws IOException if a source is incomplete or changes while being retained
	 */
	private static ObservedRun readEvidence(HarnessRunManager manager, String runId, Path transcript,
		CampaignStore store) throws IOException
	{
		HarnessRunObservation owned = manager.observe("codex", runId).
			orElseThrow(() -> new IOException("Retained Codex run is missing: " + runId));
		if (owned.state() == HarnessRunObservation.State.RUNNING)
			throw new IOException("Retained Codex run is still running: " + runId);
		HarnessRun run = owned.run();
		HarnessTranscriptObservation bridge = HarnessTranscriptObservation.read(JSON, transcript, runId).
			orElseThrow(() -> new IOException("Retained Codex bridge transcript is missing: " + transcript));
		if (bridge.state() != HarnessTranscriptObservation.SessionState.COMPLETED || bridge.turns().isEmpty() ||
			bridge.turns().size() != owned.turnNumber())
			throw new IOException("Retained Codex bridge has no matching completed turn sequence: " + transcript);
		Path artifacts = run.directory().resolve("artifacts");
		if (!Files.isDirectory(artifacts, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Retained Codex artifacts are not an owned directory: " + artifacts);

		List<Path> eventFiles = new ArrayList<>(bridge.turns().size());
		for (int index = 1; index <= bridge.turns().size(); ++index)
			eventFiles.add(artifacts.resolve("turn-" + index + ".events"));
		TurnProfileReader.Observation profile = TurnProfileReader.read(eventFiles);
		RetainedEventArchive.Archive archive = RetainedEventArchive.archive(eventFiles, store);
		if (!profile.sourceSha256().equals(archive.sourceSha256()))
			throw new IOException("Codex native events changed between profile observation and evidence retention");

		List<NativeEventEvidence> nativeEvents = new ArrayList<>(archive.events().size());
		for (int index = 0; index < archive.events().size(); ++index)
			nativeEvents.add(new NativeEventEvidence(profile.conversationId(), index + 1, archive.events().get(index)));
		return new ObservedRun(run, bridge, profile, nativeEvents);
	}

	/**
	 * Builds the successful typed result after every native turn has been validated.
	 *
	 * @param observed retained bridge and native evidence
	 * @param store immutable campaign evidence repository
	 * @param runtimeManifestSha256 verified prestart runner identity
	 * @param executionProject the project selected for native execution
	 * @return a typed successful result
	 * @throws IOException if a bridge turn and native terminal event disagree
	 */
	private static NestedRunResult succeeded(ObservedRun observed, CampaignStore store,
		String runtimeManifestSha256, Path executionProject) throws IOException
	{
		HarnessRun run = observed.run();
		HarnessTranscriptObservation bridge = observed.bridge();
		TurnProfileReader.Observation profile = observed.profile();
		List<NativeEventEvidence> nativeEvents = observed.nativeEvents();
		List<TurnResult> turns = observedTurns(bridge, profile, nativeEvents, bridge.turns().size());
		NativePromptPathObservation prompt = new CodexNativePromptPathCollector().collect(run.id(),
			profile.conversationId(), nativeEvents, store::readImmutableBytes);
		List<EvidenceRef> eventRefs = nativeEvents.stream().map(NativeEventEvidence::evidence).toList();
		List<ObservationCoverage> coverage = List.of(prompt.coverage(),
			new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.LIMITED,
				Optional.of("Direct tool executions were not derived from Codex native events"), eventRefs),
			new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.LIMITED,
				Optional.of("External source executions were not derived from Codex native events"), eventRefs));
		List<String> limitations = List.of();
		if (profile.providerIdentity().isEmpty())
			limitations = List.of("Codex did not expose a model provider identity");
		Map<String, String> configuration = profile.nativeVersion().<Map<String, String>>map(version ->
			Map.of("codexVersion", version)).orElseGet(Map::of);
		RunnerIdentity identity = new RunnerIdentity("codex", PluginInfo.CAT_VERSION, runtimeManifestSha256,
			profile.providerIdentity(), configuration, limitations);
		return new NestedRunResult("codex", run.id(), executionProject, turns, nativeEvents,
			identity, profile.profile(), prompt.toRuntimePromptSources(), List.of(), List.of(), coverage);
	}

	/**
	 * Joins bridge responses to the exact native events that completed their turns.
	 *
	 * @param bridge the retained bridge terminal sequence
	 * @param profile the matching native turn sequence
	 * @param events immutable native event references
	 * @param completedCount the number of initial completed turns to join
	 * @return ordered completed turns with their native terminal evidence
	 * @throws IOException if either source disagrees about turn count or status
	 */
	private static List<TurnResult> observedTurns(HarnessTranscriptObservation bridge,
		TurnProfileReader.Observation profile, List<NativeEventEvidence> events, int completedCount) throws IOException
	{
		if (bridge.turns().size() != profile.terminalTurns().size())
			throw new IOException("Codex native turn count differs from the bridge transcript");
		List<TurnResult> result = new ArrayList<>(completedCount);
		for (int index = 0; index < completedCount; ++index)
		{
			HarnessTranscriptObservation.Turn bridgeTurn = bridge.turns().get(index);
			TurnProfileReader.TerminalTurn nativeTurn = profile.terminalTurns().get(index);
			if (bridgeTurn.state() != HarnessTranscriptObservation.TurnState.COMPLETED ||
				bridgeTurn.exitCode() != 0 || nativeTurn.status() != TurnProfileReader.TurnStatus.COMPLETED ||
				nativeTurn.turnOrdinal() != index + 1 || nativeTurn.eventOrdinal() > events.size())
				throw new IOException("Codex native turn does not match the completed bridge response");
			if (!bridgeTurn.assistantResponse().orElseThrow().equals(nativeTurn.responseText()))
				throw new IOException("Codex native answer differs from the completed bridge response");
			NativeEventEvidence terminal = events.get(Math.toIntExact(nativeTurn.eventOrdinal() - 1));
			result.add(new TurnResult(index + 1, profile.conversationId(),
				bridgeTurn.assistantResponse().orElseThrow(), terminal));
		}
		return List.copyOf(result);
	}

	/**
	 * Matching retained sources for one terminal native run.
	 *
	 * @param run the owned runner directory
	 * @param bridge the retained bridge turn sequence
	 * @param profile the native profile and terminal event observation
	 * @param nativeEvents immutable native event references
	 */
	private record ObservedRun(HarnessRun run, HarnessTranscriptObservation bridge,
		TurnProfileReader.Observation profile, List<NativeEventEvidence> nativeEvents)
	{
	}

	/**
	 * Prevents construction.
	 */
	private RunResultReader()
	{
	}
}
