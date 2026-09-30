package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves a reviewed prompt path using only the initial no-follow inventory and retained bytes. */
public final class RetainedPromptSourceResolver
{
	private final LiveGitInventory inventory;
	private final PromptSnapshot snapshot;
	private final Map<Path, LiveGitInventory.Entry> paths = new HashMap<>();

	/**
	 * Opens a resolver for one retained project capture.
	 *
	 * @param inventory initial no-follow worktree paths
	 * @param snapshot initially retained regular-file bytes
	 */
	public RetainedPromptSourceResolver(LiveGitInventory inventory, PromptSnapshot snapshot)
	{
		this.inventory = Objects.requireNonNull(inventory, "inventory");
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
		for (LiveGitInventory.Entry entry: inventory.paths())
			paths.put(entry.path(), entry);
	}

	/**
	 * Resolves an observed logical path without reading the current worktree.
	 *
	 * @param logicalPath observed project-relative source path
	 * @return retained source path, traversed links, and byte identity
	 * @throws IOException if a link escapes the project, a path is absent, or retained bytes differ
	 */
	public PromptSource resolve(Path logicalPath) throws IOException
	{
		DeliveryPath.validateRelative(logicalPath);
		Path current = logicalPath;
		Set<Path> visited = new HashSet<>();
		Set<Path> visitedLinks = new HashSet<>();
		List<PromptSource.DirectoryLink> directoryLinks = new ArrayList<>();
		String fileLinkTarget = "";
		while (visited.add(current))
		{
			boolean followedLink = false;
			for (int count = 1; count <= current.getNameCount(); ++count)
			{
				Path prefix = current.subpath(0, count);
				LiveGitInventory.Entry entry = paths.get(prefix);
				if (entry == null)
					throw new IOException("Prompt source is absent from the initial Git inventory: " + current);
				if (entry.kind() == LiveGitInventory.Entry.Kind.SYMLINK)
				{
					if (!visitedLinks.add(prefix))
						throw new IOException("Prompt source link cycle: " + logicalPath);
					Path resolvedTarget = retainedTarget(entry);
					boolean directoryLink = count < current.getNameCount();
					if (directoryLink)
					{
						LiveGitInventory.Entry resolvedDirectory = paths.get(resolvedTarget);
						if (!resolvedTarget.toString().isEmpty() && (resolvedDirectory == null ||
							resolvedDirectory.kind() != LiveGitInventory.Entry.Kind.DIRECTORY))
							throw new IOException("Prompt source directory link has no retained directory: " + prefix);
						directoryLinks.add(new PromptSource.DirectoryLink(prefix, entry.linkTarget(),
							resolvedTarget));
					}
					else
					{
						if (!fileLinkTarget.isEmpty())
							throw new IOException("Prompt source traverses multiple file links: " + logicalPath);
						fileLinkTarget = entry.linkTarget();
					}
					current = nextPath(entry, current, prefix);
					followedLink = true;
					break;
				}
				if (count < current.getNameCount() && entry.kind() != LiveGitInventory.Entry.Kind.DIRECTORY)
					throw new IOException("Prompt source parent is not a directory: " + prefix);
				if (count == current.getNameCount())
				{
					if (entry.kind() != LiveGitInventory.Entry.Kind.REGULAR)
						throw new IOException("Prompt source is not a file: " + logicalPath);
					PromptSnapshot.Entry retained = snapshot.entries().get(current);
					if (retained == null || !retained.hash().equals(entry.contentSha256()))
						throw new IOException("Prompt source differs from initially retained bytes: " + logicalPath);
					return new PromptSource(logicalPath, current, fileLinkTarget, directoryLinks,
						retained.hash());
				}
			}
			if (!followedLink)
				throw new IOException("Prompt source is not a file: " + logicalPath);
		}
		throw new IOException("Prompt source link cycle: " + logicalPath);
	}

	/**
	 * Reads a symlink's observable resolved target inside the captured project.
	 *
	 * @param link initial symlink entry
	 * @return project-relative resolved target
	 * @throws IOException if the destination is absent or outside the captured project
	 */
	private Path retainedTarget(LiveGitInventory.Entry link) throws IOException
	{
		if (link.resolvedTarget().isEmpty())
			throw new IOException("Prompt source link has no resolved target: " + link.path());
		Path target = Path.of(link.resolvedTarget());
		if (!target.startsWith(inventory.projectRoot()))
			throw new IOException("Prompt source link resolves outside the project: " + link.path());
		return inventory.projectRoot().relativize(target);
	}

	/**
	 * Follows the exact link text while preserving the remaining observed suffix.
	 *
	 * @param link initial symlink entry
	 * @param current path being resolved
	 * @param prefix symlink prefix within current
	 * @return next project-relative path
	 * @throws IOException if following the link escapes the project
	 */
	private Path nextPath(LiveGitInventory.Entry link, Path current, Path prefix) throws IOException
	{
		Path destination = Path.of(link.linkTarget());
		Path project = inventory.projectRoot();
		Path absolute = destination;
		if (!destination.isAbsolute())
			absolute = project.resolve(prefix).getParent().resolve(destination);
		Path next = absolute.resolve(prefix.relativize(current)).normalize();
		if (!next.startsWith(project) || next.equals(project))
			throw new IOException("Prompt source link resolves outside the project: " + prefix);
		return project.relativize(next);
	}
}
