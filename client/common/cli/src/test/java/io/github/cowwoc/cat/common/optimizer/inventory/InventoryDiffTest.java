package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.content;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.file;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.inventory;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.link;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange.Added;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange.Modified;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange.Removed;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetDelete;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetWrite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies inventory comparison and the classification of author-attempt changes as permitted or forbidden.
 */
public final class InventoryDiffTest
{
	/**
	 * Creates the test.
	 */
	public InventoryDiffTest()
	{
	}

	/**
	 * Returns an authority whose only prompt source is {@code prompts} and whose loader configuration is
	 * {@code prompts/loader.json}.
	 *
	 * @return the authority
	 */
	private static Authority promptAuthority()
	{
		return new Authority(Set.of(Path.of("prompts")), Set.of(Path.of("prompts/loader.json")));
	}

	/**
	 * Verifies that a comparison reports added and removed paths, content, mode and link-destination changes, and
	 * nothing for unchanged paths.
	 */
	@Test
	public void reportsEveryChangedPath()
	{
		// Requirement: R-W7
		FileInventory before = inventory(Map.of(
			"same.md", file("same"),
			"content.md", file("old"),
			"mode.md", file("mode"),
			"link.md", link("same.md"),
			"removed.md", file("removed")));
		FileInventory after = inventory(Map.of(
			"same.md", file("same"),
			"content.md", file("new"),
			"mode.md", file("mode", EXECUTABLE),
			"link.md", link("content.md"),
			"added.md", file("added")));

		InventoryDiff diff = InventoryDiff.between(before, after);

		requireThat(diff.isEmpty(), "isEmpty").isFalse();
		requireThat(diff.changes(), "changes").isEqualTo(List.of(
			new Added(Path.of("added.md"), file("added")),
			new Modified(Path.of("content.md"), file("old"), file("new")),
			new Modified(Path.of("link.md"), link("same.md"), link("content.md")),
			new Modified(Path.of("mode.md"), file("mode"), file("mode", EXECUTABLE)),
			new Removed(Path.of("removed.md"), file("removed"))));
	}

	/**
	 * Verifies that an optimization worktree matching the working candidate's snapshot has no unowned change, and that
	 * a file written outside CAT's recorded operations is reported.
	 */
	@Test
	public void detectsUnownedWorktreeChange()
	{
		// Requirement: R-W7
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a")));
		Overlay working = Overlay.of(fork).write(Path.of("prompts/a.md"), content("A"), READ_WRITE);
		FileInventory recorded = inventory(Map.of("prompts/a.md", file("A")));
		FileInventory unowned = inventory(Map.of("prompts/a.md", file("A"), "notes.md", file("notes")));

		requireThat(InventoryDiff.between(working.snapshot(), recorded).isEmpty(), "recordedIsEmpty").isTrue();
		requireThat(InventoryDiff.between(working.snapshot(), unowned).changes(), "unowned").isEqualTo(List.of(
			new Added(Path.of("notes.md"), file("notes"))));
	}

	/**
	 * Verifies that content edits, creates and deletes of prompt files are permitted and become target changes.
	 */
	@Test
	public void permitsPromptFileChanges()
	{
		// Requirement: R-W7
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/old.md", file("old")));
		FileInventory after = inventory(Map.of("prompts/a.md", file("A"), "prompts/new.md", file("old")));

		AttemptReview review = InventoryDiff.between(fork, after).review(fork, promptAuthority());

		requireThat(review.isPermitted(), "isPermitted").isTrue();
		requireThat(review.changes(), "changes").isEqualTo(List.of(
			new TargetWrite(Path.of("prompts/a.md"), Path.of("prompts/a.md"), file("A")),
			new TargetWrite(Path.of("prompts/new.md"), Path.of("prompts/new.md"), file("old")),
			new TargetDelete(Path.of("prompts/old.md"))));
	}

	/**
	 * Verifies that a mode change, a created symbolic link, a created special file and an edit outside the prompt
	 * sources are each classified as forbidden, and that any one of them rejects the whole attempt.
	 */
	@Test
	public void classifiesForbiddenOperations()
	{
		// Requirement: R-W7
		FileInventory fork = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/b.md", file("b"),
			"prompts/loader.json", file("{}"),
			"src/Main.java", file("main")));
		FileInventory after = inventory(Map.of(
			"prompts/a.md", file("A"),
			"prompts/b.md", file("b", EXECUTABLE),
			"prompts/loader.json", file("{\"x\":1}"),
			"prompts/link.md", link("a.md"),
			"prompts/pipe", new SpecialFile(),
			"src/Main.java", file("changed")));

		AttemptReview review = InventoryDiff.between(fork, after).review(fork, promptAuthority());

		requireThat(review.isPermitted(), "isPermitted").isFalse();
		requireThat(review.forbidden(), "forbidden").isEqualTo(List.of(
			new ForbiddenOperation(Path.of("prompts/b.md"), Path.of("prompts/b.md"), Operation.CHANGE_MODE,
				Limit.MODE_FIXED),
			new ForbiddenOperation(Path.of("prompts/link.md"), Path.of("prompts/link.md"), Operation.CHANGE_LINK,
				Limit.LINK_FIXED),
			new ForbiddenOperation(Path.of("prompts/loader.json"), Path.of("prompts/loader.json"),
				Operation.EDIT_CONTENT, Limit.LOADER_CONFIGURATION_FIXED),
			new ForbiddenOperation(Path.of("prompts/pipe"), Path.of("prompts/pipe"), Operation.CREATE,
				Limit.NOT_ORDINARY_FILE),
			new ForbiddenOperation(Path.of("src/Main.java"), Path.of("src/Main.java"), Operation.EDIT_CONTENT,
				Limit.OUTSIDE_PROMPT_SOURCES)));
	}

	/**
	 * Verifies operations on paths that the fork reaches through links, as seen in an author copy where every link is
	 * replaced by a copy of its target: deleting a link path changes a link, editing an external file or creating one
	 * inside a linked external directory is read-only, and a path whose links form a cycle cannot be resolved.
	 */
	@Test
	public void classifiesOperationsThroughLinks()
	{
		// Requirement: R-W7
		FileInventory fork = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/alias.md", link("a.md"),
			"prompts/shared.md", link("/external/shared.md"),
			"prompts/library", link("/external/library"),
			"prompts/loop", link("loop"),
			"/external/shared.md", file("shared"),
			"/external/library/x.md", file("x")));
		FileInventory before = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/alias.md", file("a"),
			"prompts/shared.md", file("shared"),
			"prompts/library/x.md", file("x")));
		FileInventory after = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/shared.md", file("SHARED"),
			"prompts/library/x.md", file("x"),
			"prompts/library/y.md", file("y"),
			"prompts/loop/z.md", file("z")));

		AttemptReview review = InventoryDiff.between(before, after).review(fork, promptAuthority());

		requireThat(review.forbidden(), "forbidden").isEqualTo(List.of(
			new ForbiddenOperation(Path.of("prompts/alias.md"), Path.of("prompts/a.md"), Operation.CHANGE_LINK,
				Limit.LINK_FIXED),
			new ForbiddenOperation(Path.of("prompts/library/y.md"), Path.of("/external/library/y.md"), Operation.CREATE,
				Limit.EXTERNAL_TARGET_READ_ONLY),
			new ForbiddenOperation(Path.of("prompts/loop/z.md"), Path.of("prompts/loop/z.md"), Operation.CREATE,
				Limit.LINK_CYCLE),
			new ForbiddenOperation(Path.of("prompts/shared.md"), Path.of("/external/shared.md"), Operation.EDIT_CONTENT,
				Limit.EXTERNAL_TARGET_READ_ONLY)));
	}
}
