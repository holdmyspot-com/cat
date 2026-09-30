package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.runner.HarnessRunObservation;
import io.github.cowwoc.cat.common.runner.HarnessTranscriptObservation;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Settles a completed bridge with no reserved native turns using durable no-dispatch evidence. */
public final class StoppedBridgeResultReader
{
	/**
	 * Verifies the owned run, bridge, and artifacts before retaining an interruption without native dispatch.
	 *
	 * @param request original logical call
	 * @param owned completed owned run with zero reserved turns
	 * @param bridge terminal transcript observation for that exact run
	 * @param transcript source transcript retained in bridge-conflict diagnostics
	 * @param store campaign evidence repository
	 * @return interrupted settlement with content-addressed no-dispatch proof
	 * @throws IOException if the observations or artifacts contradict a completed zero-turn run
	 */
	public static NestedRunCompletion.Failed read(NestedRunRequest request, HarnessRunObservation owned,
		HarnessTranscriptObservation bridge, Path transcript, CampaignStore store) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(owned, "owned");
		Objects.requireNonNull(bridge, "bridge");
		Objects.requireNonNull(transcript, "transcript");
		Objects.requireNonNull(store, "store");
		String harness = owned.run().harness();
		String display = Character.toUpperCase(harness.charAt(0)) + harness.substring(1);
		if (owned.state() != HarnessRunObservation.State.COMPLETED || owned.turnNumber() != 0)
			throw new IOException("Retained " + display + " run does not prove completed zero native turns");
		if (!bridge.runId().equals(owned.run().id()) ||
			bridge.state() != HarnessTranscriptObservation.SessionState.COMPLETED || !bridge.turns().isEmpty())
			throw new IOException("Retained " + display + " bridge contradicts zero native turns: " + transcript);
		Path artifacts = owned.run().directory().resolve("artifacts");
		if (!Files.isDirectory(artifacts, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Retained " + display + " artifacts are not an owned directory: " + artifacts);
		try (DirectoryStream<Path> turnArtifacts = Files.newDirectoryStream(artifacts, "turn-*"))
		{
			if (turnArtifacts.iterator().hasNext())
				throw new IOException("Retained " + display + " native turn artifacts contradict zero native turns: " +
					artifacts);
		}
		String runId = owned.run().id();
		NativeDispatchCompletion receipt = new NativeDispatchCompletion(1, request.logicalCallId(),
			Optional.of(runId), owned.finishedAt().orElseThrow());
		EvidenceRef reference = store.appendImmutableBytes("native-dispatch-completions", receipt.bytes());
		NoNativeTurnDispatchProof proof = new NoNativeTurnDispatchProof(request.logicalCallId(),
			Optional.of(runId), reference);
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.INTERRUPTED,
			request.logicalCallId(), Optional.of(runId), display + " call stopped before its first native turn",
			List.of(), Optional.empty(), Optional.of(proof));
		return new NestedRunCompletion.Failed(failure);
	}

	/** Prevents construction. */
	private StoppedBridgeResultReader()
	{
	}
}
