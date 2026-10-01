package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.content;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.file;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.inventory;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.link;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;

import java.nio.file.Path;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Verifies candidate overlays, their identities and the snapshots they describe.
 */
public final class OverlayTest
{
	/**
	 * Creates the test.
	 */
	public OverlayTest()
	{
	}

	/**
	 * Verifies that two overlays holding the same edits have the same candidate identity regardless of the order in
	 * which the edits were recorded.
	 */
	@Test
	public void identityIgnoresRecordingOrder()
	{
		// Requirement: R-W3
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/b.md", file("b")));

		Overlay first = Overlay.of(fork).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE).
			delete(Path.of("prompts/b.md"));
		Overlay second = Overlay.of(fork).
			delete(Path.of("prompts/b.md")).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE);

		requireThat(first.identity(), "first").isEqualTo(second.identity());
	}

	/**
	 * Verifies that different overlay content on the same fork produces different candidate identities.
	 */
	@Test
	public void identityDependsOnOverlay()
	{
		// Requirement: R-W3
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a")));

		Overlay first = Overlay.of(fork).write(Path.of("prompts/a.md"), content("A"), READ_WRITE);
		Overlay second = Overlay.of(fork).write(Path.of("prompts/a.md"), content("B"), READ_WRITE);
		Overlay created = Overlay.of(fork).write(Path.of("prompts/new.md"), content("A"), READ_WRITE);
		Overlay executable = Overlay.of(fork).write(Path.of("prompts/new.md"), content("A"), EXECUTABLE);

		requireThat(first.identity(), "first").isNotEqualTo(second.identity(), "second");
		requireThat(first.identity(), "first").isNotEqualTo(created.identity(), "created");
		requireThat(created.identity(), "created").isNotEqualTo(executable.identity(), "executable");
		requireThat(first.identity(), "first").isNotEqualTo(Overlay.of(fork).identity(), "incumbent");
	}

	/**
	 * Verifies that the same overlay applied to forks with different recorded content produces different candidate
	 * identities.
	 */
	@Test
	public void identityDependsOnFork()
	{
		// Requirement: R-W3
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/b.md", file("b")));
		FileInventory otherFork = inventory(Map.of("prompts/a.md", file("a"), "prompts/b.md", file("other")));

		Overlay candidate = Overlay.of(fork).write(Path.of("prompts/a.md"), content("A"), READ_WRITE);
		Overlay otherCandidate = Overlay.of(otherFork).write(Path.of("prompts/a.md"), content("A"), READ_WRITE);

		requireThat(candidate.identity(), "candidate").isNotEqualTo(otherCandidate.identity(), "otherCandidate");
		requireThat(Overlay.of(fork).identity(), "incumbent").isNotEqualTo(Overlay.of(otherFork).identity(),
			"otherIncumbent");
	}

	/**
	 * Verifies that restoring an edited file's recorded content removes the edit, so the candidate is again the
	 * incumbent.
	 */
	@Test
	public void revertRestoresIncumbent()
	{
		// Requirement: R-W3
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a")));

		Overlay reverted = Overlay.of(fork).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE).
			write(Path.of("prompts/a.md"), content("a"), READ_WRITE).
			write(Path.of("prompts/new.md"), content("new"), READ_WRITE).
			delete(Path.of("prompts/new.md"));

		requireThat(reverted.entries(), "entries").isEmpty();
		requireThat(reverted.identity(), "reverted").isEqualTo(Overlay.of(fork).identity());
	}

	/**
	 * Verifies that an overlay records content edits, creates and deletes relative to its fork, and that its snapshot
	 * applies them to the fork's inventory.
	 */
	@Test
	public void snapshotAppliesEntries()
	{
		FileInventory fork = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/old.md", file("old"),
			"README.md", file("readme")));

		Overlay candidate = Overlay.of(fork).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE).
			delete(Path.of("prompts/old.md")).
			write(Path.of("prompts/new.md"), content("old"), EXECUTABLE);

		requireThat(candidate.entries(), "entries").isEqualTo(Map.of(
			Path.of("prompts/a.md"), new Write(content("A"), READ_WRITE),
			Path.of("prompts/old.md"), new Delete(),
			Path.of("prompts/new.md"), new Write(content("old"), EXECUTABLE)));
		requireThat(candidate.snapshot(), "snapshot").isEqualTo(inventory(Map.of(
			"prompts/a.md", file("A"),
			"prompts/new.md", file("old", EXECUTABLE),
			"README.md", file("readme"))));
	}

	/**
	 * Verifies that an overlay rejects an edit at a path that the fork reaches through a symbolic link, because the
	 * edit belongs to the link's resolved target.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*alias\\.md.*prompts/a\\.md.*")
	public void rejectsWriteAtLinkPath()
	{
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "alias.md", link("prompts/a.md")));

		Overlay.of(fork).write(Path.of("alias.md"), content("A"), READ_WRITE);
	}
}
