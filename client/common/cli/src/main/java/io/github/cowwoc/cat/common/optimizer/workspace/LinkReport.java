package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Operation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every symbolic link that a proposal finds in the prompt system or in the staged fixtures: the link path, its
 * resolved target, whether the target lies inside the project, its edit authority, and whether the link can be staged.
 * <p>
 * A link cycle and a link to a missing target block confirmation until the user resolves them.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class LinkReport
{
	/**
	 * The consequence of staging that the proposal shows for every link and that every result records as a
	 * limitation.
	 */
	public static final String STAGING_CONSEQUENCE = "Trials see a regular copy at each link path, so a prompt or " +
		"tool that inspects link metadata or resolved paths may behave differently in trials than in production.";
	private final List<Entry> entries;

	/**
	 * Creates a report.
	 *
	 * @param entries the links, in path order
	 */
	private LinkReport(List<Entry> entries)
	{
		this.entries = List.copyOf(entries);
	}

	/**
	 * Reports the links in an inventory.
	 *
	 * @param inventory the fork's inventory, which covers the prompt system and the staged fixtures
	 * @param authority the edit authority that decides each target's limit
	 * @return the report
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static LinkReport of(FileInventory inventory, Authority authority)
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(authority, "authority").isNotNull();
		Map<Path, Status> problems = Dereferenced.of(inventory).problems();
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<Path, FileEntry> entry : inventory.entries().entrySet())
		{
			if (!(entry.getValue() instanceof SymbolicLink))
				continue;
			Path link = entry.getKey();
			Status status = problems.getOrDefault(link, Status.RESOLVED);
			Optional<Path> target = inventory.resolve(link);
			entries.add(new Entry(link, target, status, limitOf(authority, target.orElse(null), status)));
		}
		return new LinkReport(entries);
	}

	/**
	 * Returns the edit limit of a link's target.
	 *
	 * @param authority the edit authority
	 * @param target the resolved target, or null if the links on the path form a cycle
	 * @param status whether the link can be staged
	 * @return the limit, or empty if the target's content may be edited
	 */
	private static Optional<Limit> limitOf(Authority authority, Path target, Status status)
	{
		if (target == null || status == Status.CYCLE)
			return Optional.of(Limit.LINK_CYCLE);
		// The project directory as a whole is not a prompt source that a content edit can name
		if (target.toString().isEmpty())
			return Optional.of(Limit.OUTSIDE_PROMPT_SOURCES);
		return authority.limitOf(Operation.EDIT_CONTENT, target);
	}

	/**
	 * Returns every link.
	 *
	 * @return the links, in path order
	 */
	public List<Entry> entries()
	{
		return entries;
	}

	/**
	 * Returns the links that block confirmation: cycles and links to missing targets.
	 *
	 * @return the links that cannot be staged, in path order
	 */
	public List<Entry> blocking()
	{
		return entries.stream().filter(entry -> entry.status() != Status.RESOLVED).toList();
	}

	@Override
	public String toString()
	{
		return "LinkReport" + entries;
	}

	/**
	 * The reason that a link can or cannot be staged.
	 */
	public enum Status
	{
		/**
		 * The link resolves to a recorded file or directory that can be copied.
		 */
		RESOLVED,
		/**
		 * The link resolves to a path that the fork does not record.
		 */
		MISSING_TARGET,
		/**
		 * The links on the link's path form a cycle, or the link's target contains the link itself.
		 */
		CYCLE
	}

	/**
	 * One symbolic link.
	 *
	 * @param link the link's path, relative to the project directory or absolute for a link inside an external
	 *   directory
	 * @param target the resolved target after every link on the path is followed, relative to the project directory
	 *   or absolute for an external target, or empty if the links on the path form a cycle
	 * @param status indicates whether the link can be staged
	 * @param editLimit why the target's content cannot be edited, or empty if it may be edited
	 */
	public record Entry(Path link, Optional<Path> target, Status status, Optional<Limit> editLimit)
	{
		/**
		 * Creates an entry.
		 *
		 * @param link the link's path
		 * @param target the resolved target, or empty if the links on the path form a cycle
		 * @param status whether the link can be staged
		 * @param editLimit why the target's content cannot be edited, or empty if it may be edited
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Entry
		{
			requireThat(link, "link").isNotNull();
			requireThat(target, "target").isNotNull();
			requireThat(status, "status").isNotNull();
			requireThat(editLimit, "editLimit").isNotNull();
		}

		/**
		 * Indicates whether the target lies inside the project.
		 *
		 * @return {@code false} for an external target or when there is no target
		 */
		public boolean isInsideProject()
		{
			return target.isPresent() && !target.get().isAbsolute();
		}
	}
}
