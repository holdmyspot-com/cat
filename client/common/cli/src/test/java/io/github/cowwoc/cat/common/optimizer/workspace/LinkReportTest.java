package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.file;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport.Entry;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport.Status;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Verifies the symbolic-link report that a proposal shows before confirmation, using in-memory inventories.
 */
public final class LinkReportTest
{
	private static final Path ROOT = Path.of("/project");
	private static final Path EXTERNAL = Path.of("/shared/rules.md");

	/**
	 * Creates the test.
	 */
	public LinkReportTest()
	{
	}

	/**
	 * Verifies that a link to a file inside the project reports its link path, its resolved target, that the target
	 * lies inside the project, and the target's edit authority.
	 */
	@Test
	public void reportsLinkInsideProject()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("prompts/main.md"), file("main"),
			Path.of("alias.md"), new SymbolicLink(Path.of("prompts/main.md"))));
		Authority authority = new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of());

		LinkReport report = LinkReport.of(inventory, authority);

		requireThat(report.entries(), "entries").isEqualTo(List.of(
			new Entry(Path.of("alias.md"), Optional.of(Path.of("prompts/main.md")), Status.RESOLVED, Optional.empty())));
		requireThat(report.entries().getFirst().isInsideProject(), "isInsideProject").isTrue();
		requireThat(report.blocking(), "blocking").isEmpty();
	}

	/**
	 * Verifies that a link to an unauthorized external file reports that the target lies outside the project and is
	 * read-only, and that authorizing the target removes the limit.
	 */
	@Test
	public void reportsExternalTargetAuthority()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("rules.md"), new SymbolicLink(EXTERNAL),
			EXTERNAL, file("rules")));

		LinkReport unauthorized = LinkReport.of(inventory, new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of()));
		LinkReport authorized = LinkReport.of(inventory, new Authority(Set.of(), Set.of(), Set.of(EXTERNAL)));

		Entry entry = unauthorized.entries().getFirst();
		requireThat(entry.target(), "target").isEqualTo(Optional.of(EXTERNAL));
		requireThat(entry.isInsideProject(), "isInsideProject").isFalse();
		requireThat(entry.status(), "status").isEqualTo(Status.RESOLVED);
		requireThat(entry.editLimit(), "editLimit").isEqualTo(Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY));
		requireThat(authorized.entries().getFirst().editLimit(), "authorizedLimit").isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that a link to a project file outside the prompt sources reports that limit.
	 */
	@Test
	public void reportsTargetOutsidePromptSources()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("notes/n.md"), file("notes"),
			Path.of("alias.md"), new SymbolicLink(Path.of("notes/n.md"))));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(Path.of("prompts")), Set.of(), Set.of()));

		requireThat(report.entries().getFirst().editLimit(), "editLimit").
			isEqualTo(Optional.of(Limit.OUTSIDE_PROMPT_SOURCES));
	}

	/**
	 * Verifies that a chain of links reports the final target.
	 */
	@Test
	public void reportsFinalTargetOfLinkChain()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("final.md"), file("final"),
			Path.of("middle.md"), new SymbolicLink(Path.of("final.md")),
			Path.of("first.md"), new SymbolicLink(Path.of("middle.md"))));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(Path.of("final.md")), Set.of(), Set.of()));

		requireThat(report.entries().stream().map(Entry::link).toList(), "links").
			isEqualTo(List.of(Path.of("first.md"), Path.of("middle.md")));
		requireThat(report.entries().stream().map(Entry::target).toList(), "targets").
			isEqualTo(List.of(Optional.of(Path.of("final.md")), Optional.of(Path.of("final.md"))));
	}

	/**
	 * Verifies that links that form a cycle report no target and block confirmation.
	 */
	@Test
	public void blocksLinkCycle()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("a"), new SymbolicLink(Path.of("b")),
			Path.of("b"), new SymbolicLink(Path.of("a"))));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(), Set.of(), Set.of()));

		requireThat(report.blocking().stream().map(Entry::link).toList(), "blockingLinks").
			isEqualTo(List.of(Path.of("a"), Path.of("b")));
		Entry entry = report.entries().getFirst();
		requireThat(entry.status(), "status").isEqualTo(Status.CYCLE);
		requireThat(entry.target(), "target").isEqualTo(Optional.empty());
		requireThat(entry.isInsideProject(), "isInsideProject").isFalse();
		requireThat(entry.editLimit(), "editLimit").isEqualTo(Optional.of(Limit.LINK_CYCLE));
	}

	/**
	 * Verifies that a directory link whose target contains the link itself is a cycle, because copying the target
	 * would copy the link again.
	 */
	@Test
	public void blocksDirectoryLinkToItsOwnAncestor()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("docs/a.md"), file("a"),
			Path.of("docs/self"), new SymbolicLink(Path.of("."))));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(), Set.of(), Set.of()));

		Entry entry = report.entries().getFirst();
		requireThat(entry.link(), "link").isEqualTo(Path.of("docs/self"));
		requireThat(entry.status(), "status").isEqualTo(Status.CYCLE);
		requireThat(report.blocking(), "blocking").isEqualTo(List.of(entry));
	}

	/**
	 * Verifies that two directory links that contain each other are a cycle.
	 */
	@Test
	public void blocksMutuallyContainingDirectoryLinks()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("left/l.md"), file("l"),
			Path.of("left/to-right"), new SymbolicLink(Path.of("../right")),
			Path.of("right/r.md"), file("r"),
			Path.of("right/to-left"), new SymbolicLink(Path.of("../left"))));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(), Set.of(), Set.of()));

		requireThat(report.blocking(), "blocking").isNotEmpty();
		requireThat(report.blocking().stream().map(Entry::status).distinct().toList(), "statuses").
			isEqualTo(List.of(Status.CYCLE));
	}

	/**
	 * Verifies that a link to a path that the inventory does not record is reported as a missing target and blocks
	 * confirmation.
	 */
	@Test
	public void blocksMissingTarget()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(
			Path.of("gone.md"), new SymbolicLink(Path.of("nowhere.md")),
			Path.of("outside.md"), new SymbolicLink(Path.of("/not/recorded.md"))));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(), Set.of(), Set.of()));

		requireThat(report.blocking().stream().map(Entry::status).toList(), "statuses").
			isEqualTo(List.of(Status.MISSING_TARGET, Status.MISSING_TARGET));
		requireThat(report.entries().getFirst().target(), "target").isEqualTo(Optional.of(Path.of("nowhere.md")));
		requireThat(report.entries().get(1).target(), "externalTarget").
			isEqualTo(Optional.of(Path.of("/not/recorded.md")));
	}

	/**
	 * Verifies that an inventory without links yields an empty report, and that the staging consequence that every
	 * result records names the replacement of links by copies.
	 */
	@Test
	public void recordsStagingConsequence()
	{
		// Requirement: R-W6
		FileInventory inventory = inventory(Map.of(Path.of("main.md"), file("main")));

		LinkReport report = LinkReport.of(inventory, new Authority(Set.of(), Set.of(), Set.of()));

		requireThat(report.entries(), "entries").isEmpty();
		requireThat(LinkReport.STAGING_CONSEQUENCE, "consequence").contains("regular copy").contains("link metadata").
			contains("resolved paths");
	}

	/**
	 * Returns an inventory of the project at {@link #ROOT}.
	 *
	 * @param entries the recorded entries
	 * @return the inventory
	 */
	private static FileInventory inventory(Map<Path, FileEntry> entries)
	{
		return new FileInventory(ROOT, new HashMap<>(entries));
	}
}
