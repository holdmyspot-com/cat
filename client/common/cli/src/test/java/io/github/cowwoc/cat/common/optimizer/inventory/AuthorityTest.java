package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.file;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.inventory;
import static io.github.cowwoc.cat.common.optimizer.inventory.Inventories.link;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning.Choice;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange.TargetWrite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies the edit authority that a confirmed contract grants to author attempts.
 */
public final class AuthorityTest
{
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
	 * @return the authority
	 */
	private static Authority promptAuthority()
	{
		return new Authority(Set.of(Path.of("prompts"), Path.of(".claude")), Set.of(Path.of(".claude/settings.json")));
	}

	/**
	 * Verifies that in-project prompt files permit content edits, creates and deletes, while their modes and symbolic
	 * links stay fixed.
	 */
	@Test
	public void promptFilesPermitOrdinaryChanges()
	{
		// Requirement: R-W4
		Authority authority = promptAuthority();
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
		Authority authority = promptAuthority();

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
		Authority authority = promptAuthority();
		Path shared = Path.of("/external/shared.md");
		Optional<Limit> readOnly = Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY);

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, shared), "edit").isEqualTo(readOnly);
		requireThat(authority.limitOf(Operation.CREATE, Path.of("/external/new.md")), "create").isEqualTo(readOnly);
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
		Authority authority = promptAuthority();
		List<Choice> withoutEdit = List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP_CAMPAIGN, Choice.REVISE_CAMPAIGN);

		for (Operation operation : Operation.values())
		{
			Optional<AuthorityWarning> throughLink = authority.diagnose("R-3", Path.of("prompts/shared.md"), operation,
				fork);
			requireThat(throughLink, "throughLink." + operation).isEqualTo(Optional.of(new AuthorityWarning("R-3",
				Path.of("prompts/shared.md"), Path.of("/external/shared.md"), Limit.EXTERNAL_TARGET_READ_ONLY,
				withoutEdit)));

			Optional<AuthorityWarning> direct = authority.diagnose("R-3", Path.of("/external/shared.md"), operation,
				fork);
			requireThat(direct, "direct." + operation).isEqualTo(Optional.of(new AuthorityWarning("R-3",
				Path.of("/external/shared.md"), Path.of("/external/shared.md"), Limit.EXTERNAL_TARGET_READ_ONLY,
				withoutEdit)));
		}
	}

	/**
	 * Verifies that the authority rejects a prompt source given as an absolute path, because prompt sources lie inside
	 * the project.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*/project/prompts.*")
	public void rejectsAbsolutePromptSource()
	{
		new Authority(Set.of(Path.of("/project/prompts")), Set.of());
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

		AttemptReview review = InventoryDiff.between(before, after).review(fork, promptAuthority());

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

		AttemptReview linkReview = InventoryDiff.between(before, linkEdit).review(fork, promptAuthority());
		AttemptReview sameReview = InventoryDiff.between(before, sameEdit).review(fork, promptAuthority());

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

		Optional<AuthorityWarning> warning = promptAuthority().diagnose("R-5", Path.of("prompts/a.md"),
			Operation.EDIT_CONTENT, fork);

		requireThat(warning, "warning").isEmpty();
	}
}
