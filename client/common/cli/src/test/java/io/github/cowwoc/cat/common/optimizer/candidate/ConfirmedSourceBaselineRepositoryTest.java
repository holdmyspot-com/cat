package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/** Verifies immutable known-source states across confirmation and restart. */
public final class ConfirmedSourceBaselineRepositoryTest
{
	/** A known absent source remains explicit after repository restart. */
	@Test
	public void roundTripsKnownAbsentAndRegularSources() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("baseline-project-"));
			Path campaign = cleanup.add(Files.createTempDirectory("baseline-campaign-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/selected.md"), "selected");
			List<Path> paths = List.of(Path.of("prompts/lazy.md"), Path.of("prompts/selected.md"));
			PromptSnapshot snapshot = PromptSnapshot.capture(project, paths);
			ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, paths, snapshot);

			ConfirmedSourceBaselineRepository repository = new ConfirmedSourceBaselineRepository(campaign);
			String id = repository.save(baseline);
			ConfirmedSourceBaseline restored = new ConfirmedSourceBaselineRepository(campaign).load(id);

			requireThat(restored, "restoredBaseline").isEqualTo(baseline);
			requireThat(restored.existingSnapshotRef(), "snapshotRef").isEqualTo(snapshot.id());
			requireThat(restored.orderedKnownSourceStates().getFirst().presence(), "lazyPresence").isEqualTo(
				KnownSourcePresence.ABSENT);
			requireThat(restored.orderedKnownSourceStates().getFirst().contentSha256(), "lazyHash").isEqualTo(
				Optional.empty());
		}
	}

	/** A snapshot captured before a source changes cannot become the confirmed baseline. */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*does not match.*")
	public void rejectsChangedSourceBeforeConfirmation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("baseline-project-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/selected.md"), "before");
			List<Path> paths = List.of(Path.of("prompts/selected.md"));
			PromptSnapshot snapshot = PromptSnapshot.capture(project, paths);
			Files.writeString(project.resolve("prompts/selected.md"), "after");
			ConfirmedSourceBaseline.capture(project, paths, snapshot);
		}
	}

	/** A regular source must carry a hash and an absent source must not. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*hash.*")
	public void rejectsRegularSourceWithoutHash()
	{
		new KnownSourceState(Path.of("prompts/source.md"), KnownSourcePresence.REGULAR, Optional.empty());
	}
}
