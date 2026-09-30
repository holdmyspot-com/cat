package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.PromptSourceOrigin;
import io.github.cowwoc.cat.common.optimizer.runner.RuntimePromptSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds native prompt path events and the coverage of their capture boundary.
 *
 * @param events distinct native event observations in retained order
 * @param coverage the prompt-source observation coverage
 */
public record NativePromptPathObservation(List<NativePromptPathEvent> events, ObservationCoverage coverage)
{
	/**
	 * Validates and snapshots the native observation.
	 */
	public NativePromptPathObservation
	{
		events = List.copyOf(events);
		Objects.requireNonNull(coverage, "coverage");
		if (coverage.domain() != ObservationDomain.PROMPT_SOURCE)
			throw new IllegalArgumentException("coverage must describe prompt sources");
	}

	/**
	 * Projects genuine native path events to runtime source observations.
	 *
	 * @return one runtime source per native event, including repeated paths
	 */
	public List<RuntimePromptSource> toRuntimePromptSources()
	{
		List<RuntimePromptSource> result = new ArrayList<>(events.size());
		for (NativePromptPathEvent event : events)
		{
			String path = event.normalizedRelativePath().toString();
			String sourceId = event.conversationId().length() + ":" + event.conversationId() + ":" +
				event.nativeEventOrdinal() + ":" + path.length() + ":" + path;
			result.add(new RuntimePromptSource(event.conversationId(), event.nativeEventOrdinal(), sourceId,
				event.normalizedRelativePath(), PromptSourceOrigin.OTHER, Optional.empty(), event.parentPath(),
				event.nativeReason(), event.triggerPath(), event.retainedNativeEventRef()));
		}
		return List.copyOf(result);
	}
}
