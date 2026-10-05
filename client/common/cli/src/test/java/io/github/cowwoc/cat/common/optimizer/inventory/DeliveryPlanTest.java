package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.content;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.file;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.inventory;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.link;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict.Kind;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.testng.annotations.Test;

/**
 * Verifies delivery preflight: the three-way comparison of the fork baseline, the current state, and the candidate.
 */
public final class DeliveryPlanTest
{
	/**
	 * Creates the test.
	 */
	public DeliveryPlanTest()
	{
	}

	/**
	 * Verifies that a base-only edit to another file survives: the plan has no conflict, changes only the candidate's
	 * file and reports the base-only path.
	 */
	@Test
	public void disjointBaseEditSurvives()
	{
		// Requirement: R-W8
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/b.md", file("b")));
		Overlay candidate = Overlay.of(fork).write(Path.of("prompts/a.md"), content("A"), READ_WRITE);
		FileInventory current = inventory(Map.of("prompts/a.md", file("a"), "prompts/b.md", file("B")));

		DeliveryPlan plan = DeliveryPlan.of(candidate, current);

		requireThat(plan.conflicts(), "conflicts").isEmpty();
		requireThat(plan.changes(), "changes").isEqualTo(List.of(
			new Edit(0, Path.of("prompts/a.md"), file("a"), content("A"), READ_WRITE)));
		requireThat(plan.baseOnlyChanges(), "baseOnlyChanges").isEqualTo(List.of(Path.of("prompts/b.md")));
	}

	/**
	 * Verifies that a file changed by both the candidate and the base, or deleted by the base, stops delivery with no
	 * change to apply.
	 */
	@Test
	public void sameFileChangeConflicts()
	{
		// Requirement: R-W8
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/b.md", file("b")));
		Overlay candidate = Overlay.of(fork).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE).
			write(Path.of("prompts/b.md"), content("B"), READ_WRITE);
		FileInventory current = inventory(Map.of("prompts/a.md", file("base edit")));

		DeliveryPlan plan = DeliveryPlan.of(candidate, current);

		requireThat(plan.conflicts(), "conflicts").isEqualTo(List.of(
			new DeliveryConflict(Kind.BOTH_CHANGED, Path.of("prompts/a.md"), Path.of("prompts/a.md")),
			new DeliveryConflict(Kind.BOTH_CHANGED, Path.of("prompts/b.md"), Path.of("prompts/b.md"))));
		requireThat(plan.changes(), "changes").isEmpty();
	}

	/**
	 * Verifies that a created file collides with a base file at the same path, below a base file, or above base files.
	 */
	@Test
	public void structuralCollisionConflicts()
	{
		// Requirement: R-W8
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a")));
		Overlay candidate = Overlay.of(fork).
			write(Path.of("prompts/new.md"), content("new"), READ_WRITE).
			write(Path.of("prompts/notes/n.md"), content("n"), READ_WRITE).
			write(Path.of("prompts/dir"), content("d"), READ_WRITE);
		FileInventory current = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/new.md", file("base new"),
			"prompts/notes", file("notes"),
			"prompts/dir/inner.md", file("inner")));

		DeliveryPlan plan = DeliveryPlan.of(candidate, current);

		requireThat(plan.conflicts(), "conflicts").isEqualTo(List.of(
			new DeliveryConflict(Kind.STRUCTURAL_COLLISION, Path.of("prompts/dir"), Path.of("prompts/dir/inner.md")),
			new DeliveryConflict(Kind.STRUCTURAL_COLLISION, Path.of("prompts/new.md"), Path.of("prompts/new.md")),
			new DeliveryConflict(Kind.STRUCTURAL_COLLISION, Path.of("prompts/notes/n.md"), Path.of("prompts/notes"))));
		requireThat(plan.changes(), "changes").isEmpty();
	}

	/**
	 * Verifies that delivery stops when the base changed the destination of a link through which the candidate's
	 * target was reached, or when the target path now resolves to a different file.
	 */
	@Test
	public void linkChangesConflict()
	{
		// Requirement: R-W8
		FileInventory fork = inventory(Map.of(
			"prompts/a.md", file("a"),
			"prompts/shared", link("../library"),
			"library/s.md", file("s")));
		Overlay candidate = Overlay.of(fork).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE).
			write(Path.of("library/s.md"), content("S"), READ_WRITE);
		FileInventory current = inventory(Map.of(
			"prompts", link("/elsewhere"),
			"library/s.md", file("s")));

		DeliveryPlan plan = DeliveryPlan.of(candidate, current);

		requireThat(plan.conflicts(), "conflicts").isEqualTo(List.of(
			new DeliveryConflict(Kind.LINK_DESTINATION_CHANGED, Path.of("library/s.md"), Path.of("prompts/shared")),
			new DeliveryConflict(Kind.TARGET_IDENTITY_MISMATCH, Path.of("prompts/a.md"), Path.of("prompts"))));
		requireThat(plan.changes(), "changes").isEmpty();
	}

	/**
	 * Verifies that a conflict-free plan orders deletes before writes, numbers each change, and gives each change the
	 * live state it expects before and after it is applied, and leaves a read-only external participant unchanged.
	 */
	@Test
	public void ordersChangesWithPreimages()
	{
		// Requirement: R-W8
		FileInventory fork = inventory(Map.of(
			"prompts/m.md", file("m"),
			"prompts/z.md", file("z"),
			"/external/s.md", file("s")));
		Overlay candidate = Overlay.of(fork).
			write(Path.of("prompts/m.md"), content("M"), READ_WRITE).
			delete(Path.of("prompts/z.md")).
			write(Path.of("prompts/a.md"), content("A"), EXECUTABLE);

		DeliveryPlan plan = DeliveryPlan.of(candidate, fork);

		requireThat(plan.conflicts(), "conflicts").isEmpty();
		requireThat(plan.baseOnlyChanges(), "baseOnlyChanges").isEmpty();
		List<DeliveryChange> changes = plan.changes();
		requireThat(changes, "changes").isEqualTo(List.of(
			new Delete(0, Path.of("prompts/z.md"), file("z")),
			new Create(1, Path.of("prompts/a.md"), content("A"), EXECUTABLE),
			new Edit(2, Path.of("prompts/m.md"), file("m"), content("M"), READ_WRITE)));
		requireThat(changes.get(0).preimage(), "deletePreimage").isEqualTo(Optional.of(file("z")));
		requireThat(changes.get(0).postimage(), "deletePostimage").isEmpty();
		requireThat(changes.get(1).preimage(), "createPreimage").isEmpty();
		requireThat(changes.get(1).postimage(), "createPostimage").isEqualTo(Optional.of(file("A", EXECUTABLE)));
		requireThat(changes.get(2).postimage(), "editPostimage").isEqualTo(Optional.of(file("M")));
	}

	/**
	 * Verifies that a candidate that changes an external file is refused before any plan is made, because external
	 * files are read-only and delivery writes only files inside the project.
	 */
	@Test
	public void refusesExternalChange()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/a.md", file("a"),
			"/external/s.md", file("s")));
		Overlay candidate = Overlay.of(fork).
			write(Path.of("prompts/a.md"), content("A"), READ_WRITE).
			write(Path.of("/external/s.md"), content("S"), READ_WRITE);

		IllegalArgumentException refusal = null;
		try
		{
			DeliveryPlan.of(candidate, fork);
		}
		catch (IllegalArgumentException e)
		{
			refusal = e;
		}
		requireThat(refusal, "refusal").isNotNull();
		requireThat(refusal.getMessage(), "message").contains("/external/s.md").contains("read-only");
	}
}
