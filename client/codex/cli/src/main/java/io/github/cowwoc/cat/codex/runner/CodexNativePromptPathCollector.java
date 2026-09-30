package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.NativeEventReader;
import io.github.cowwoc.cat.common.runner.NativePromptPathCollector;
import io.github.cowwoc.cat.common.runner.NativePromptPathObservation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records the limits of Codex app-server prompt-path observation for {@link NativePromptPathCollector}.
 * Codex currently exposes no native notification identifying which instruction file it loaded.
 */
public final class CodexNativePromptPathCollector implements NativePromptPathCollector
{
	private static final String LIMITATION = "Codex app-server exposed no native instruction-file path signal";

	/**
	 * Checks retained native evidence and reports the absence of an authenticated prompt-path signal.
	 *
	 * @param runId the owned run identifier
	 * @param conversationId the native Codex thread identifier
	 * @param retainedNativeEvents native events already retained by the runner
	 * @param reader reads exact retained native-event bytes
	 * @return limited prompt-source coverage without synthetic paths
	 * @throws IOException if referenced native evidence is missing or has a mismatched digest
	 */
	@Override
	public NativePromptPathObservation collect(String runId, String conversationId,
		List<NativeEventEvidence> retainedNativeEvents, NativeEventReader reader) throws IOException
	{
		requireThat(runId, "runId").isNotBlank();
		requireThat(conversationId, "conversationId").isNotBlank();
		requireThat(retainedNativeEvents, "retainedNativeEvents").isNotNull();
		requireThat(reader, "reader").isNotNull();
		List<EvidenceRef> references = new ArrayList<>(retainedNativeEvents.size());
		for (NativeEventEvidence event : retainedNativeEvents)
		{
			if (!conversationId.equals(event.conversationId()))
				throw new IOException("Codex native event belongs to another conversation: " + event.conversationId());
			EvidenceRef reference = event.evidence();
			NativePromptPathCollector.readIndexedEvent(event, reader);
			references.add(reference);
		}
		ObservationCoverage coverage = new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
			ObservationCoverageStatus.LIMITED, Optional.of(LIMITATION), references);
		return new NativePromptPathObservation(List.of(), coverage);
	}

	/**
	 * Seals the native index after the Codex process closes, including when no path event was emitted.
	 *
	 * @param run the owned Codex run
	 * @throws IOException if the index cannot be written or an existing claim is invalid
	 */
	public static void seal(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		Path index = indexPath(run);
		if (Files.exists(index, LinkOption.NOFOLLOW_LINKS))
		{
			validateIndex(run);
			return;
		}
		Path directory = Files.createDirectories(index.getParent());
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ObjectNode value = scope.json().createObjectNode();
			value.put("schemaVersion", 1);
			value.put("runId", run.id());
			value.put("producer", "codex-native-event-stream");
			value.put("enabled", true);
			value.put("state", "LIMITED");
			value.put("limitation", LIMITATION);
			value.put("pendingFileCount", 0);
			value.putArray("events");
			Path temporary = Files.createTempFile(directory, ".index-", ".tmp");
			try
			{
				Files.writeString(temporary, scope.json().writeValueAsString(value));
				Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE);
			}
			finally
			{
				Files.deleteIfExists(temporary);
			}
		}
	}

	/**
	 * Validates the exact zero-event index claimed by a settled Codex run.
	 *
	 * @param run the owned Codex run
	 * @throws IOException if the claimed index is missing, malformed, or belongs to another run
	 */
	public static void validateIndex(HarnessRun run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		Path index = indexPath(run);
		if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Codex native prompt-path index is missing: " + index);
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			JsonNode value = scope.json().readTree(Files.readString(index));
			if (value == null || !value.isObject() || value.size() != 8 ||
				!value.path("schemaVersion").isIntegralNumber() || value.path("schemaVersion").intValue() != 1 ||
				!value.path("runId").isString() || !run.id().equals(value.path("runId").asString()) ||
				!value.path("producer").isString() ||
				!"codex-native-event-stream".equals(value.path("producer").asString()) ||
				!value.path("enabled").isBoolean() || !value.path("enabled").booleanValue() ||
				!value.path("state").isString() || !"LIMITED".equals(value.path("state").asString()) ||
				!value.path("limitation").isString() ||
				!LIMITATION.equals(value.path("limitation").asString()) ||
				!value.path("pendingFileCount").isIntegralNumber() ||
				value.path("pendingFileCount").intValue() != 0 || !value.path("events").isArray() ||
				!value.path("events").isEmpty())
				throw new IOException("Codex native prompt-path index is corrupt or mismatched: " + index);
		}
		catch (JacksonException e)
		{
			throw new IOException("Codex native prompt-path index is malformed: " + index, e);
		}
	}

	/**
	 * Returns the retained native index location for the owned run.
	 *
	 * @param run the owned Codex run
	 * @return the index path
	 */
	public static Path indexPath(HarnessRun run)
	{
		requireThat(run, "run").isNotNull();
		return run.directory().resolve("artifacts/native-instructions-loaded/index.json");
	}

	/**
	 * Rejects a claimed raw event because Codex has no genuine prompt-path event to index.
	 *
	 * @param run the owned Codex run
	 * @param ordinal the claimed event ordinal
	 * @return never returns bytes
	 * @throws IOException if the index is invalid or no event has that ordinal
	 */
	public static byte[] readRaw(HarnessRun run, int ordinal) throws IOException
	{
		validateIndex(run);
		throw new IOException("Codex native prompt-path ordinal is absent: " + ordinal);
	}
}
