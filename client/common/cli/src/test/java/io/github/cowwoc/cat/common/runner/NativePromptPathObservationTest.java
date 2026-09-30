package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverageStatus;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.PromptSourceOrigin;
import io.github.cowwoc.cat.common.optimizer.runner.RuntimePromptSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests {@link NativePromptPathObservation} projection of retained native events.
 */
public final class NativePromptPathObservationTest
{
	/**
	 * Creates the test fixture.
	 */
	public NativePromptPathObservationTest()
	{
	}

	/**
	 * Preserves duplicate paths as distinct events without assigning an inferred origin or load mode.
	 */
	@Test
	public void projectsRepeatedNativePaths()
	{
		EvidenceRef reference = new EvidenceRef("native-events", "a".repeat(64));
		NativePromptPathEvent first = new NativePromptPathEvent("session", 1, Path.of("CLAUDE.md"),
			Optional.of("session-start"), Optional.empty(), Optional.empty(), reference);
		NativePromptPathEvent second = new NativePromptPathEvent("session", 2, Path.of("CLAUDE.md"),
			Optional.of("nested"), Optional.of(Path.of("task.md")), Optional.empty(), reference);
		ObservationCoverage coverage = new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
			ObservationCoverageStatus.COMPLETE, Optional.empty(), List.of(reference));

		List<RuntimePromptSource> sources = new NativePromptPathObservation(List.of(first, second), coverage).
			toRuntimePromptSources();

		requireThat(sources.size(), "sources.size").isEqualTo(2);
		requireThat(sources.getFirst().sourceId(), "first.sourceId").isNotEqualTo(sources.getLast().sourceId());
		requireThat(sources.getFirst().origin(), "first.origin").isEqualTo(PromptSourceOrigin.OTHER);
		requireThat(sources.getFirst().loadMode(), "first.loadMode").isEqualTo(Optional.empty());
		requireThat(sources.getLast().triggerPath(), "second.triggerPath").isEqualTo(Optional.of(Path.of("task.md")));
	}

	/**
	 * Copies the event list so later mutation cannot change a sealed observation.
	 */
	@Test
	public void snapshotsEvents()
	{
		List<NativePromptPathEvent> mutable = new ArrayList<>();
		ObservationCoverage coverage = new ObservationCoverage(ObservationDomain.PROMPT_SOURCE,
			ObservationCoverageStatus.LIMITED, Optional.of("no native path signal"), List.of());
		NativePromptPathObservation observation = new NativePromptPathObservation(mutable, coverage);
		mutable.add(new NativePromptPathEvent("session", 1, Path.of("CLAUDE.md"), Optional.empty(),
			Optional.empty(), Optional.empty(), new EvidenceRef("native-events", "a".repeat(64))));

		requireThat(observation.events(), "events").isEmpty();
		requireThat(observation.toRuntimePromptSources(), "sources").isEmpty();
	}

	/**
	 * Refuses an invalid project-relative event path.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "path must be normalized and relative")
	public void rejectsAbsoluteEventPath()
	{
		new NativePromptPathEvent("session", 1, Path.of("/outside.md"), Optional.empty(), Optional.empty(),
			Optional.empty(), new EvidenceRef("native-events", "a".repeat(64)));
	}
}
