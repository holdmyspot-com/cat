package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.NativeEventReader;
import io.github.cowwoc.cat.common.runner.NativePromptPathCollector;
import io.github.cowwoc.cat.common.runner.NativePromptPathEvent;
import io.github.cowwoc.cat.common.runner.NativePromptPathObservation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Decodes retained Claude InstructionsLoaded events into project-relative native path observations.
 */
public final class ClaudeNativePromptPathCollector implements NativePromptPathCollector
{
	private final Path projectRoot;

	/**
	 * Creates a decoder for one copied run project.
	 *
	 * @param projectRoot the absolute copied project root
	 */
	public ClaudeNativePromptPathCollector(Path projectRoot)
	{
		if (!projectRoot.isAbsolute())
			throw new IllegalArgumentException("projectRoot must be absolute");
		this.projectRoot = projectRoot.normalize();
	}

	/**
	 * Decodes only retained, matching-session hook records and retains every distinct valid arrival.
	 *
	 * @param runId the owned run identifier
	 * @param conversationId the native Claude session identifier
	 * @param retainedNativeEvents event references in retention order
	 * @param reader immutable raw event reader
	 * @return path observations with explicit limited coverage where native timing cannot be proven
	 * @throws IOException if a claimed retained event is missing or hash-mismatched
	 */
	@Override
	public NativePromptPathObservation collect(String runId, String conversationId,
		List<NativeEventEvidence> retainedNativeEvents, NativeEventReader reader) throws IOException
	{
		if (runId.isBlank() || conversationId.isBlank())
			throw new IllegalArgumentException("Run and conversation IDs must not be blank");
		List<NativePromptPathEvent> events = new ArrayList<>();
		List<io.github.cowwoc.cat.common.optimizer.api.EvidenceRef> evidence = new ArrayList<>();
		int rejected = 0;
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			for (NativeEventEvidence retained : retainedNativeEvents)
			{
				byte[] bytes = NativePromptPathCollector.readIndexedEvent(retained, reader);
				evidence.add(retained.evidence());
				if (!conversationId.equals(retained.conversationId()))
				{
					rejected += 1;
					continue;
				}
				try
				{
					JsonNode raw = scope.json().readTree(bytes);
					if (!"InstructionsLoaded".equals(raw.path("hook_event_name").asString()) ||
						!conversationId.equals(raw.path("session_id").asString()))
						throw new IllegalArgumentException("Hook event or native session did not match");
					String rawPath = raw.path("file_path").asString();
					Path path = Path.of(rawPath);
					if (!path.isAbsolute() || !path.normalize().startsWith(projectRoot))
						throw new IllegalArgumentException("Hook path was not in the copied project");
					events.add(new NativePromptPathEvent(conversationId, retained.eventOrdinal(),
						projectRoot.relativize(path.normalize()), optionalText(raw, "load_reason"),
						optionalPath(raw, "trigger_file_path"), optionalPath(raw, "parent_file_path"),
						retained.evidence()));
				}
				catch (IllegalArgumentException | tools.jackson.core.JacksonException _)
				{
					rejected += 1;
				}
			}
		}
		String limitation = "Claude InstructionsLoaded hooks are asynchronous; arrival does not prove " +
			"complete load coverage";
		if (events.isEmpty())
			limitation = "No valid project InstructionsLoaded path signal was retained";
		else if (rejected > 0)
			limitation += "; " + rejected + " raw event(s) could not be mapped to this project session";
		return new NativePromptPathObservation(events, new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
			ObservationCoverageStatus.LIMITED, Optional.of(limitation), evidence));
	}

	/**
	 * Returns nonblank optional text from native JSON.
	 *
	 * @param raw native event
	 * @param field field name
	 * @return optional text
	 */
	private static Optional<String> optionalText(JsonNode raw, String field)
	{
		JsonNode value = raw.path(field);
		if (!value.isString() || value.stringValue().isBlank())
			return Optional.empty();
		return Optional.of(value.stringValue());
	}

	/**
	 * Returns an optional absolute native path.
	 *
	 * @param raw native event
	 * @param field field name
	 * @return optional path
	 */
	private Optional<Path> optionalPath(JsonNode raw, String field)
	{
		Optional<String> text = optionalText(raw, field);
		if (text.isEmpty())
			return Optional.empty();
		Path path = Path.of(text.orElseThrow());
		if (!path.isAbsolute() || !path.normalize().startsWith(projectRoot))
			throw new IllegalArgumentException("Native " + field + " path must be inside the copied project");
		return Optional.of(projectRoot.relativize(path.normalize()));
	}
}
