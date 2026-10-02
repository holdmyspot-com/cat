package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.CandidateOverlays;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the {@link CandidateOverlays} contract against {@link FileCandidateOverlays}, and tests its file layout, crash
 * consistency and rejection of stored overlays that do not match the requested snapshot.
 */
public final class FileCandidateOverlaysTest extends CandidateOverlaysContract
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public FileCandidateOverlaysTest()
	{
	}

	@Override
	@SuppressWarnings("PMD.CloseResource")
	protected OverlaysFixture open(FileInventory fork) throws IOException
	{
		// The returned fixture owns the directory and closes it
		TemporaryDirectories cleanup = new TemporaryDirectories();
		Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-"));
		FileCandidateOverlays overlays = new FileCandidateOverlays(campaign, fork, JSON);
		return new OverlaysFixture()
		{
			@Override
			public CandidateOverlays overlays()
			{
				return overlays;
			}

			@Override
			public CandidateOverlays reopen()
			{
				return new FileCandidateOverlays(campaign, fork, JSON);
			}

			@Override
			public void close() throws IOException
			{
				cleanup.close();
			}
		};
	}

	/**
	 * An overlay saved on a fork that the workspace recorded is found by a new instance over the fork that a restarted
	 * process reopens from campaign storage, and the overlay file holds the format version and the fork's identity but
	 * not the fork's files.
	 *
	 * @throws IOException if the overlays or the fork fail
	 */
	@Test
	public void reloadsOnReopenedFork() throws IOException
	{
		// Requirement: R-E1, R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-base-"));
			Path campaign = base.resolve(".cat/work/optimizer/campaign-1");
			Files.writeString(base.resolve("prompt.md"), "original", UTF_8);
			Files.writeString(base.resolve("unchanged.md"), "unchanged", UTF_8);
			FileInventory recorded = ForkTree.record(base, campaign, Set.of(Path.of("prompt.md"),
				Path.of("unchanged.md"))).inventory();
			Overlay overlay = Overlay.of(recorded).write(Path.of("prompt.md"), content("edited"), READ_WRITE);
			new FileCandidateOverlays(campaign, recorded, JSON).save(overlay);

			FileInventory reopened = ForkTree.open(base, campaign).inventory();
			Overlay loaded = new FileCandidateOverlays(campaign, reopened, JSON).require(overlay.identity());
			requireThat(loaded, "loaded").isEqualTo(overlay);

			Path file = campaign.resolve("overlays/" + overlay.identity().hex() + ".json");
			JsonNode document = JSON.readTree(Files.readString(file, UTF_8));
			requireThat(document.get("format_version").asInt(), "formatVersion").isEqualTo(1);
			requireThat(document.get("fork").asString(), "fork").isEqualTo(recorded.identity().hex());
			requireThat(document.toString(), "document").doesNotContain("unchanged");
		}
	}

	/**
	 * A crash after any write step of a save leaves either no overlay or the complete overlay, and a later save
	 * completes.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void faultAfterEachWriteStep() throws IOException
	{
		// Requirement: R-E1, R-W1
		FileInventory fork = fork();
		Overlay overlay = candidate(fork, "crash");
		for (WriteStep crashStep : WriteStep.values())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-fault-"));
				FileCandidateOverlays crashing = new FileCandidateOverlays(campaign, fork, JSON, step ->
				{
					if (step == crashStep)
						throw new SimulatedCrash(step);
				});
				boolean crashed = false;
				try
				{
					crashing.save(overlay);
				}
				catch (SimulatedCrash _)
				{
					crashed = true;
				}
				requireThat(crashed, "crashed").withContext(crashStep, "crashStep").isTrue();

				Optional<Overlay> expected;
				if (crashStep.compareTo(WriteStep.RENAME) >= 0)
					expected = Optional.of(overlay);
				else
					expected = Optional.empty();
				FileCandidateOverlays restarted = new FileCandidateOverlays(campaign, fork, JSON);
				requireThat(restarted.find(overlay.identity()), "found").withContext(crashStep, "crashStep").
					isEqualTo(expected);

				restarted.save(overlay);
				requireThat(new FileCandidateOverlays(campaign, fork, JSON).find(overlay.identity()), "resaved").
					withContext(crashStep, "crashStep").isEqualTo(Optional.of(overlay));
			}
		}
	}

	/**
	 * Saving an overlay that is already saved writes nothing.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void savingSavedOverlayWritesNothing() throws IOException
	{
		// Requirement: R-E1
		FileInventory fork = fork();
		Overlay overlay = candidate(fork, "saved");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-saved-"));
			new FileCandidateOverlays(campaign, fork, JSON).save(overlay);
			new FileCandidateOverlays(campaign, fork, JSON, step ->
			{
				throw new SimulatedCrash(step);
			}).save(overlay);
		}
	}

	/**
	 * A stored overlay file that is not valid JSON is rejected with an exception that names the file.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void corruptFileIsRejected() throws IOException
	{
		// Requirement: R-W3
		FileInventory fork = fork();
		Overlay overlay = candidate(fork, "corrupt");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-corrupt-"));
			new FileCandidateOverlays(campaign, fork, JSON).save(overlay);
			Path file = overlayFile(campaign, overlay.identity());
			Files.writeString(file, "{\"format_version\": 1, \"fork\": ", UTF_8);

			requireRejected(new FileCandidateOverlays(campaign, fork, JSON), overlay.identity(), file);
		}
	}

	/**
	 * A stored overlay whose entries no longer produce the requested snapshot is rejected with an exception that names
	 * the file: one stored under another candidate's snapshot, and one whose file content was altered.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void identityMismatchIsRejected() throws IOException
	{
		// Requirement: R-W3
		FileInventory fork = fork();
		Overlay first = candidate(fork, "first");
		Overlay second = candidate(fork, "second");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-mismatch-"));
			FileCandidateOverlays overlays = new FileCandidateOverlays(campaign, fork, JSON);
			overlays.save(first);
			overlays.save(second);
			Path secondFile = overlayFile(campaign, second.identity());
			Files.copy(overlayFile(campaign, first.identity()), secondFile, REPLACE_EXISTING);
			requireRejected(new FileCandidateOverlays(campaign, fork, JSON), second.identity(), secondFile);

			Path firstFile = overlayFile(campaign, first.identity());
			ObjectNode document = (ObjectNode) JSON.readTree(Files.readString(firstFile, UTF_8));
			ObjectNode entry = (ObjectNode) ((ArrayNode) document.get("entries")).get(0);
			entry.put("content", "dGFtcGVyZWQ=");
			Files.writeString(firstFile, JSON.writeValueAsString(document), UTF_8);
			requireRejected(new FileCandidateOverlays(campaign, fork, JSON), first.identity(), firstFile);
		}
	}

	/**
	 * An overlay stored for a different fork is rejected with an exception that names the file and says it does not
	 * describe an overlay on the campaign's fork.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test
	public void differentForkIsRejected() throws IOException
	{
		// Requirement: R-W3
		FileInventory fork = fork();
		Overlay overlay = candidate(fork, "fork");
		FileInventory otherFork = Overlay.of(fork).write(Path.of("prompt.md"), content("other fork"), READ_WRITE).
			snapshot();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-fork-"));
			new FileCandidateOverlays(campaign, fork, JSON).save(overlay);

			String message = requireRejected(new FileCandidateOverlays(campaign, otherFork, JSON),
				overlay.identity(), overlayFile(campaign, overlay.identity()));
			requireThat(message, "message").contains("does not describe an overlay on this campaign's fork");
		}
	}

	/**
	 * Saving an overlay on a fork other than the one the overlays were opened for is rejected before anything is
	 * written.
	 *
	 * @throws IOException if the overlays fail
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void saveRejectsOverlayOnOtherFork() throws IOException
	{
		FileInventory fork = fork();
		FileInventory otherFork = Overlay.of(fork).write(Path.of("prompt.md"), content("other fork"), READ_WRITE).
			snapshot();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path campaign = cleanup.add(Files.createTempDirectory("cat-candidate-overlays-other-"));
			new FileCandidateOverlays(campaign, fork, JSON).save(candidate(otherFork, "other"));
		}
	}

	/**
	 * Returns the file that holds a snapshot's overlay.
	 *
	 * @param campaign the campaign storage directory
	 * @param snapshot the snapshot
	 * @return the file
	 */
	private static Path overlayFile(Path campaign, Sha256 snapshot)
	{
		return campaign.resolve("overlays/" + snapshot.hex() + ".json");
	}

	/**
	 * Requires that looking up a snapshot fails with an exception that names a file.
	 *
	 * @param overlays the overlays
	 * @param snapshot the snapshot
	 * @param file the file that the exception must name
	 * @return the exception's message
	 */
	private static String requireRejected(CandidateOverlays overlays, Sha256 snapshot, Path file)
	{
		try
		{
			overlays.find(snapshot);
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains(file.toString());
			return e.getMessage();
		}
		throw new AssertionError("Looking up " + snapshot.hex() + " was not rejected");
	}
}
