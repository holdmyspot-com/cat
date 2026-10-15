package io.github.cowwoc.cat.common.optimizer.inventory.test;

import static io.github.cowwoc.cat.common.optimizer.inventory.test.Inventories.file;
import static io.github.cowwoc.cat.common.optimizer.inventory.test.Inventories.inventory;
import static io.github.cowwoc.cat.common.optimizer.inventory.test.Inventories.link;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.AttemptReview;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning.Choice;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.ForbiddenOperation;
import io.github.cowwoc.cat.common.optimizer.inventory.InventoryDiff;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Operation;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetWrite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies the edit authority that a confirmed contract grants to prompt author attempts.
 */
public final class AuthorityTest
{
	private static final FileInventory NO_LINKS = inventory(Map.of());

	/**
	 * Creates the test.
	 */
	public AuthorityTest()
	{
	}

	/**
	 * Returns the authority used by most tests: prompt sources {@code prompts} and {@code .claude}, loader configuration
	 * {@code .claude/settings.json}.
	 *
	 * @param fork the files recorded at the fork
	 * @return the authority
	 */
	private static Authority promptAuthority(FileInventory fork)
	{
		return new Authority(Set.of(Path.of("prompts"), Path.of(".claude")), Set.of(Path.of(".claude/settings.json")),
			fork);
	}

	/**
	 * Verifies that in-project prompt files permit content edits, creates and deletes, while their modes and symbolic
	 * links stay fixed.
	 */
	@Test
	public void promptFilesPermitOrdinaryChanges()
	{
		// Requirement: R-W4
		Authority authority = promptAuthority(NO_LINKS);
		Path prompt = Path.of("prompts/a.md");

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, prompt), "edit").isEmpty();
		requireThat(authority.limitOf(Operation.CREATE, prompt), "create").isEmpty();
		requireThat(authority.limitOf(Operation.DELETE, prompt), "delete").isEmpty();
		requireThat(authority.limitOf(Operation.CHANGE_MODE, prompt), "mode").isEqualTo(Optional.of(Limit.MODE_FIXED));
		requireThat(authority.limitOf(Operation.CHANGE_LINK, prompt), "link").isEqualTo(Optional.of(Limit.LINK_FIXED));
	}

	/**
	 * Verifies that paths outside the prompt sources and loader configuration inside them reject every edit.
	 */
	@Test
	public void loaderConfigurationAndOtherFilesAreFixed()
	{
		// Requirement: R-W4
		Authority authority = promptAuthority(NO_LINKS);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("src/Main.java")), "otherEdit").
			isEqualTo(Optional.of(Limit.OUTSIDE_PROMPT_SOURCES));
		requireThat(authority.limitOf(Operation.CREATE, Path.of("promptsExtra.md")), "siblingCreate").
			isEqualTo(Optional.of(Limit.OUTSIDE_PROMPT_SOURCES));
		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of(".claude/settings.json")), "loaderEdit").
			isEqualTo(Optional.of(Limit.LOADER_CONFIGURATION_FIXED));
		requireThat(authority.limitOf(Operation.DELETE, Path.of(".claude/settings.json")), "loaderDelete").
			isEqualTo(Optional.of(Limit.LOADER_CONFIGURATION_FIXED));
	}

	/**
	 * Verifies that every operation on an external file, whether it is reached through a link or read directly,
	 * returns {@link Limit#EXTERNAL_TARGET_READ_ONLY}.
	 */
	@Test
	public void externalFilesAreReadOnly()
	{
		// Requirement: R-W4
		Authority authority = promptAuthority(NO_LINKS);
		Path shared = Path.of("/external/shared.md").toAbsolutePath();
		Optional<Limit> readOnly = Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, shared), "edit").isEqualTo(readOnly);
		requireThat(authority.limitOf(Operation.CREATE, Path.of("/external/new.md").toAbsolutePath()),
			"create").isEqualTo(readOnly);
		requireThat(authority.limitOf(Operation.DELETE, shared), "delete").isEqualTo(readOnly);
		requireThat(authority.limitOf(Operation.CHANGE_MODE, shared), "mode").isEqualTo(readOnly);
		requireThat(authority.limitOf(Operation.CHANGE_LINK, shared), "retarget").isEqualTo(readOnly);
	}

	/**
	 * Verifies that the warning about each needed operation on an external file, reached through a link or named
	 * directly, offers no choice that edits the file.
	 */
	@Test
	public void externalWarningOffersNoEdit()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/shared.md", link("/external/shared.md"),
			"/external/shared.md", file("shared")));
		Authority authority = promptAuthority(fork);
		List<Choice> withoutEdit = List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP_CAMPAIGN, Choice.REVISE_CAMPAIGN);

		for (Operation operation : Operation.values())
		{
			Optional<AuthorityWarning> throughLink = authority.diagnose("R-3", Path.of("prompts/shared.md"), operation,
				fork);
			requireThat(throughLink, "throughLink." + operation).isEqualTo(Optional.of(new AuthorityWarning("R-3",
				Path.of("prompts/shared.md"), Path.of("/external/shared.md").toAbsolutePath(), Limit.EXTERNAL_TARGET_READ_ONLY,
				withoutEdit)));

			Optional<AuthorityWarning> direct = authority.diagnose("R-3", Path.of("/external/shared.md").toAbsolutePath(),
				operation, fork);
			requireThat(direct, "direct." + operation).isEqualTo(Optional.of(new AuthorityWarning("R-3",
				Path.of("/external/shared.md").toAbsolutePath(), Path.of("/external/shared.md").toAbsolutePath(),
				Limit.EXTERNAL_TARGET_READ_ONLY, withoutEdit)));
		}
	}

	/**
	 * Verifies that the authority rejects a prompt source given as an absolute path, because prompt sources lie inside
	 * the project.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*/project/prompts.*")
	public void rejectsAbsolutePromptSource()
	{
		new Authority(Set.of(Path.of("/project/prompts").toAbsolutePath()), Set.of(), NO_LINKS);
	}

	/**
	 * Verifies that an attempt that edits one target differently through a link path and the target path is rejected
	 * as ambiguous.
	 */
	@Test
	public void rejectsAmbiguousDualPathEdit()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/alias.md", link("a.md")));
		FileInventory before = inventory(Map.of("prompts/a.md", file("a"), "prompts/alias.md", file("a")));
		FileInventory after = inventory(Map.of("prompts/a.md", file("x"), "prompts/alias.md", file("y")));

		AttemptReview review = InventoryDiff.between(before, after).review(fork, promptAuthority(fork));

		requireThat(review.isPermitted(), "isPermitted").isFalse();
		requireThat(review.forbidden(), "forbidden").isEqualTo(List.of(
			new ForbiddenOperation(Path.of("prompts/a.md"), Path.of("prompts/a.md"), Operation.EDIT_CONTENT,
				Limit.AMBIGUOUS_EDIT),
			new ForbiddenOperation(Path.of("prompts/alias.md"), Path.of("prompts/a.md"), Operation.EDIT_CONTENT,
				Limit.AMBIGUOUS_EDIT)));
	}

	/**
	 * Verifies that an edit made at a link path applies to the link's resolved target, and that the same edit made
	 * through both paths is not ambiguous.
	 */
	@Test
	public void editAtLinkPathAppliesToTarget()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a"), "prompts/alias.md", link("a.md")));
		FileInventory before = inventory(Map.of("prompts/a.md", file("a"), "prompts/alias.md", file("a")));
		FileInventory linkEdit = inventory(Map.of("prompts/a.md", file("a"), "prompts/alias.md", file("x")));
		FileInventory sameEdit = inventory(Map.of("prompts/a.md", file("x"), "prompts/alias.md", file("x")));

		AttemptReview linkReview = InventoryDiff.between(before, linkEdit).review(fork, promptAuthority(fork));
		AttemptReview sameReview = InventoryDiff.between(before, sameEdit).review(fork, promptAuthority(fork));

		requireThat(linkReview.forbidden(), "linkForbidden").isEmpty();
		requireThat(linkReview.changes(), "linkChanges").isEqualTo(List.of(
			new TargetWrite(Path.of("prompts/a.md"), Path.of("prompts/alias.md"), file("x"))));
		requireThat(sameReview.forbidden(), "sameForbidden").isEmpty();
		requireThat(sameReview.changes(), "sameChanges").isEqualTo(List.of(
			new TargetWrite(Path.of("prompts/a.md"), Path.of("prompts/a.md"), file("x"))));
	}

	/**
	 * Verifies that diagnosis produces no warning for an edit inside the authority.
	 */
	@Test
	public void noWarningWithinAuthority()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of("prompts/a.md", file("a")));

		Optional<AuthorityWarning> warning = promptAuthority(fork).diagnose("R-5", Path.of("prompts/a.md"),
			Operation.EDIT_CONTENT, fork);

		requireThat(warning, "warning").isEmpty();
	}

	/**
	 * Verifies that the in-project target of a symbolic link at a prompt source is a prompt file: an edit, create or
	 * delete is authorized at the target's own path and at the link's path, while the target's mode and the link
	 * itself stay fixed, and an unrelated file next to the target stays outside the prompt sources.
	 */
	@Test
	public void promptSourceLinkTargetIsEditable()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/handbook.md", link("../shared/handbook.md"),
			"shared/handbook.md", file("handbook"),
			"shared/other.md", file("other")));
		Authority authority = promptAuthority(fork);
		Path target = Path.of("shared/handbook.md");

		for (Operation operation : List.of(Operation.EDIT_CONTENT, Operation.CREATE, Operation.DELETE))
		{
			requireThat(authority.limitOf(operation, target), "target." + operation).isEmpty();
			requireThat(authority.diagnose("R-3", Path.of("prompts/handbook.md"), operation, fork),
				"link." + operation).isEmpty();
			requireThat(authority.diagnose("R-3", target, operation, fork), "direct." + operation).isEmpty();
		}
		requireThat(authority.limitOf(Operation.CHANGE_MODE, target), "mode").isEqualTo(Optional.of(Limit.MODE_FIXED));
		requireThat(authority.limitOf(Operation.CHANGE_LINK, target), "retarget").
			isEqualTo(Optional.of(Limit.LINK_FIXED));
		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("shared/other.md")), "unrelated").
			isEqualTo(Optional.of(Limit.OUTSIDE_PROMPT_SOURCES));
	}

	/**
	 * Verifies that an attempt that edits a prompt-source link's in-project target is permitted, whether the edit is
	 * made at the link path or at the target path.
	 */
	@Test
	public void attemptEditingLinkTargetIsPermitted()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/handbook.md", link("../shared/handbook.md"),
			"shared/handbook.md", file("handbook")));
		FileInventory before = inventory(Map.of(
			"prompts/handbook.md", file("handbook"),
			"shared/handbook.md", file("handbook")));
		FileInventory atLink = inventory(Map.of(
			"prompts/handbook.md", file("edited"),
			"shared/handbook.md", file("handbook")));
		FileInventory atTarget = inventory(Map.of(
			"prompts/handbook.md", file("handbook"),
			"shared/handbook.md", file("edited")));
		Authority authority = promptAuthority(fork);

		AttemptReview linkReview = InventoryDiff.between(before, atLink).review(fork, authority);
		AttemptReview targetReview = InventoryDiff.between(before, atTarget).review(fork, authority);

		requireThat(linkReview.forbidden(), "linkForbidden").isEmpty();
		requireThat(linkReview.changes(), "linkChanges").isEqualTo(List.of(
			new TargetWrite(Path.of("shared/handbook.md"), Path.of("prompts/handbook.md"), file("edited"))));
		requireThat(targetReview.forbidden(), "targetForbidden").isEmpty();
		requireThat(targetReview.changes(), "targetChanges").isEqualTo(List.of(
			new TargetWrite(Path.of("shared/handbook.md"), Path.of("shared/handbook.md"), file("edited"))));
	}

	/**
	 * Verifies that the final in-project target of a chain of two links at a prompt source is editable.
	 */
	@Test
	public void promptSourceLinkChainTargetIsEditable()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/first.md", link("../middle.md"),
			"middle.md", link("shared/final.md"),
			"shared/final.md", file("final")));
		Authority authority = promptAuthority(fork);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("shared/final.md")), "edit").isEmpty();
		requireThat(authority.diagnose("R-3", Path.of("prompts/first.md"), Operation.EDIT_CONTENT, fork), "atLink").
			isEmpty();
	}

	/**
	 * Verifies that every file below the in-project directory that a prompt-source link reaches is editable.
	 */
	@Test
	public void directoryLinkTargetIsEditable()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/shared", link("../shared/rules"),
			"shared/rules/a.md", file("a"),
			"shared/rules/nested/b.md", file("b"),
			"shared/rulesExtra.md", file("extra")));
		Authority authority = promptAuthority(fork);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("shared/rules/a.md")), "child").isEmpty();
		requireThat(authority.limitOf(Operation.CREATE, Path.of("shared/rules/nested/c.md")), "nestedCreate").
			isEmpty();
		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("shared/rulesExtra.md")), "sibling").
			isEqualTo(Optional.of(Limit.OUTSIDE_PROMPT_SOURCES));
	}

	/**
	 * Verifies that a prompt-source link to an external file keeps that file read-only, at the link path and at the
	 * target path.
	 */
	@Test
	public void externalLinkTargetStaysReadOnly()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/shared.md", link("/external/shared.md"),
			"/external/shared.md", file("shared")));
		Authority authority = promptAuthority(fork);
		Optional<Limit> readOnly = Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("/external/shared.md").toAbsolutePath()), "target").
			isEqualTo(readOnly);
		requireThat(authority.diagnose("R-3", Path.of("prompts/shared.md"), Operation.EDIT_CONTENT, fork).
			map(AuthorityWarning::limit), "atLink").isEqualTo(readOnly);
	}

	/**
	 * Verifies that a prompt-source link to loader configuration leaves that file fixed, even though the link makes it
	 * a prompt file.
	 */
	@Test
	public void loaderLinkTargetStaysFixed()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/loader.md", link("../config/loader.json"),
			"config/loader.json", file("{}")));
		Authority authority = new Authority(Set.of(Path.of("prompts")), Set.of(Path.of("config/loader.json")), fork);
		Optional<Limit> fixed = Optional.of(Limit.LOADER_CONFIGURATION_FIXED);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("config/loader.json")), "target").
			isEqualTo(fixed);
		requireThat(authority.diagnose("R-3", Path.of("prompts/loader.md"), Operation.EDIT_CONTENT, fork).
			map(AuthorityWarning::limit), "atLink").isEqualTo(fixed);
	}

	/**
	 * Verifies that a link outside every prompt source does not make its in-project target editable, whether the edit
	 * names the link path or the target path.
	 */
	@Test
	public void outsideLinkTargetStaysOutside()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"docs/alias.md", link("../shared/handbook.md"),
			"shared/handbook.md", file("handbook")));
		Authority authority = promptAuthority(fork);
		Optional<Limit> outside = Optional.of(Limit.OUTSIDE_PROMPT_SOURCES);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("shared/handbook.md")), "target").
			isEqualTo(outside);
		requireThat(authority.diagnose("R-3", Path.of("docs/alias.md"), Operation.EDIT_CONTENT, fork).
			map(AuthorityWarning::limit), "atLink").isEqualTo(outside);
	}

	/**
	 * Verifies that a link cycle at a prompt source makes nothing editable and is still reported as a link cycle.
	 */
	@Test
	public void promptSourceLinkCycleStaysCycle()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/a.md", link("b.md"),
			"prompts/b.md", link("a.md")));
		Authority authority = promptAuthority(fork);

		requireThat(authority.diagnose("R-3", Path.of("prompts/a.md"), Operation.EDIT_CONTENT, fork).
			map(AuthorityWarning::limit), "limit").isEqualTo(Optional.of(Limit.LINK_CYCLE));
	}
}
