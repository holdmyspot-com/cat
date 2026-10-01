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
	 * {@code .claude/settings.json}, and no authorized external target.
	 *
	 * @return the authority
	 */
	private static Authority promptAuthority()
	{
		return new Authority(Set.of(Path.of("prompts"), Path.of(".claude")), Set.of(Path.of(".claude/settings.json")),
			Set.of());
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
	 * Verifies that an external target stays read-only until the user authorizes it, and that authorization permits
	 * only content edits.
	 */
	@Test
	public void externalTargetsAllowOnlyContentEdits()
	{
		// Requirement: R-W4
		Path shared = Path.of("/external/shared.md");
		Authority readOnly = promptAuthority();
		Authority authorized = new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of(shared));

		requireThat(readOnly.limitOf(Operation.EDIT_CONTENT, shared), "readOnlyEdit").
			isEqualTo(Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY));
		requireThat(authorized.limitOf(Operation.EDIT_CONTENT, shared), "authorizedEdit").isEmpty();
		requireThat(authorized.limitOf(Operation.CREATE, Path.of("/external/new.md")), "authorizedCreate").
			isEqualTo(Optional.of(Limit.EXTERNAL_STRUCTURE_FIXED));
		requireThat(authorized.limitOf(Operation.DELETE, shared), "authorizedDelete").
			isEqualTo(Optional.of(Limit.EXTERNAL_STRUCTURE_FIXED));
		requireThat(authorized.limitOf(Operation.CHANGE_MODE, shared), "authorizedMode").
			isEqualTo(Optional.of(Limit.MODE_FIXED));
		requireThat(authorized.limitOf(Operation.CHANGE_LINK, shared), "authorizedLink").
			isEqualTo(Optional.of(Limit.LINK_FIXED));
	}

	/**
	 * Verifies that the authority rejects a prompt source given as an absolute path, because prompt sources lie inside
	 * the project.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*/project/prompts.*")
	public void rejectsAbsolutePromptSource()
	{
		new Authority(Set.of(Path.of("/project/prompts")), Set.of(), Set.of());
	}

	/**
	 * Verifies that the authority rejects an authorized target given as a project-relative path, because only
	 * external targets need authorization.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*shared\\.md.*")
	public void rejectsRelativeAuthorizedTarget()
	{
		new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of(Path.of("shared.md")));
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
	 * Verifies that diagnosis warns about a needed content edit to an unauthorized external target, naming the
	 * requirement, link path, resolved target, limit and every viable choice, including authorizing the target.
	 */
	@Test
	public void warnsAboutUnauthorizedExternalTarget()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/shared.md", link("/external/shared.md"),
			"/external/shared.md", file("shared")));

		Optional<AuthorityWarning> warning = promptAuthority().diagnose("R-3", Path.of("prompts/shared.md"),
			Operation.EDIT_CONTENT, fork);

		requireThat(warning, "warning").isEqualTo(Optional.of(new AuthorityWarning("R-3", Path.of("prompts/shared.md"),
			Path.of("/external/shared.md"), Limit.EXTERNAL_TARGET_READ_ONLY,
			List.of(Choice.AUTHORIZE_TARGET, Choice.USE_AUTHORIZED_SOURCE, Choice.STOP_CAMPAIGN,
				Choice.REVISE_CAMPAIGN))));
	}

	/**
	 * Verifies that diagnosis of a structural edit to an authorized external target does not offer authorization,
	 * because reauthorizing the target cannot permit it.
	 */
	@Test
	public void structuralWarningOmitsAuthorization()
	{
		// Requirement: R-W4
		FileInventory fork = inventory(Map.of(
			"prompts/shared", link("/external/shared"),
			"/external/shared/a.md", file("shared")));
		Authority authority = new Authority(Set.of(Path.of("prompts")), Set.of(),
			Set.of(Path.of("/external/shared/a.md")));

		Optional<AuthorityWarning> warning = authority.diagnose("R-4", Path.of("prompts/shared/new.md"),
			Operation.CREATE, fork);

		requireThat(warning, "warning").isEqualTo(Optional.of(new AuthorityWarning("R-4",
			Path.of("prompts/shared/new.md"), Path.of("/external/shared/new.md"), Limit.EXTERNAL_STRUCTURE_FIXED,
			List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP_CAMPAIGN, Choice.REVISE_CAMPAIGN))));
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

	/**
	 * Verifies that authorizing an external target again, after the campaign already authorized it, permits its
	 * content edits but never a structural edit or a mode change, and that diagnosing such an edit does not offer
	 * authorization again.
	 */
	@Test
	public void reauthorizingKeepsStructureFixed()
	{
		// Requirement: R-W4
		Path target = Path.of("/external/shared.md");
		FileInventory fork = inventory(Map.of(
			"prompts/shared.md", link("/external/shared.md"),
			"/external/shared.md", file("shared")));
		Authority authorized = promptAuthority().withAuthorizedTargets(Set.of(target));
		Authority reauthorized = authorized.withAuthorizedTargets(Set.of(target));

		requireThat(reauthorized.limitOf(Operation.EDIT_CONTENT, target), "edit").isEmpty();
		requireThat(reauthorized.limitOf(Operation.DELETE, target), "delete").
			isEqualTo(Optional.of(Limit.EXTERNAL_STRUCTURE_FIXED));
		requireThat(reauthorized.limitOf(Operation.CREATE, target), "create").
			isEqualTo(Optional.of(Limit.EXTERNAL_STRUCTURE_FIXED));
		requireThat(reauthorized.limitOf(Operation.CHANGE_MODE, target), "mode").
			isEqualTo(Optional.of(Limit.MODE_FIXED));
		requireThat(reauthorized.diagnose("R-6", Path.of("prompts/shared.md"), Operation.EDIT_CONTENT, fork),
			"editWarning").isEmpty();
		requireThat(reauthorized.diagnose("R-6", Path.of("prompts/shared.md"), Operation.DELETE, fork),
			"deleteWarning").isEqualTo(Optional.of(new AuthorityWarning("R-6", Path.of("prompts/shared.md"), target,
				Limit.EXTERNAL_STRUCTURE_FIXED,
				List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP_CAMPAIGN, Choice.REVISE_CAMPAIGN))));
	}

	/**
	 * Verifies that replacing the authorized targets keeps the prompt sources and loader configuration.
	 */
	@Test
	public void authorizedTargetsReplaceOnlyTargets()
	{
		// Requirement: R-W4
		Path target = Path.of("/external/shared.md");
		Authority authority = promptAuthority().withAuthorizedTargets(Set.of(target));

		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of("prompts/a.md")), "prompt").isEmpty();
		requireThat(authority.limitOf(Operation.EDIT_CONTENT, Path.of(".claude/settings.json")), "loader").
			isEqualTo(Optional.of(Limit.LOADER_CONFIGURATION_FIXED));
		requireThat(promptAuthority().limitOf(Operation.EDIT_CONTENT, target), "before").
			isEqualTo(Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY));
		requireThat(authority.limitOf(Operation.EDIT_CONTENT, target), "after").isEmpty();
	}
}
