package io.github.cowwoc.cat.common.optimizer.contract;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A file-backed prompt source and its retained byte identity.
 *
 * @param path project-relative path by which the source is selected or observed
 * @param resolvedSourcePath regular file supplying the source bytes; an absolute path identifies an external target
 * @param linkTarget exact file-link destination text, or empty when the source path itself is regular
 * @param directoryLinks directory links traversed to reach the regular file
 * @param sha256 retained byte identity
 */
public record PromptSource(Path path, Path resolvedSourcePath, String linkTarget,
	List<DirectoryLink> directoryLinks, String sha256)
{
	/**
	 * Creates a regular project source whose selected and resolved paths are the same.
	 *
	 * @param path project-relative regular file
	 * @param sha256 retained byte identity
	 */
	public PromptSource(Path path, String sha256)
	{
		this(path, path, "", List.of(), sha256);
	}

	/**
	 * Creates a source with no traversed directory links.
	 *
	 * @param path selected or observed project-relative path
	 * @param resolvedSourcePath regular file supplying the source bytes
	 * @param linkTarget exact file-link destination text, or empty for a regular file
	 * @param sha256 retained byte identity
	 */
	public PromptSource(Path path, Path resolvedSourcePath, String linkTarget, String sha256)
	{
		this(path, resolvedSourcePath, linkTarget, List.of(), sha256);
	}

	/**
	 * Validates a source's logical path, resolved path, and link relationship.
	 */
	public PromptSource
	{
		if (path == null || path.isAbsolute() || path.toString().isEmpty() ||
			!path.equals(path.normalize()) || path.startsWith("..") ||
			resolvedSourcePath == null || resolvedSourcePath.toString().isEmpty() ||
			!resolvedSourcePath.equals(resolvedSourcePath.normalize()) ||
			!resolvedSourcePath.isAbsolute() && resolvedSourcePath.startsWith("..") ||
			linkTarget == null ||
			!ContractValues.sha256(sha256))
			throw new IllegalArgumentException("Prompt source path or hash is invalid");
		directoryLinks = List.copyOf(Objects.requireNonNull(directoryLinks, "directoryLinks"));
		if ((linkTarget.isEmpty() && directoryLinks.isEmpty()) != path.equals(resolvedSourcePath))
			throw new IllegalArgumentException("Prompt source links do not match the resolved path");
	}

	/**
	 * One project directory link traversed while resolving a logical source path.
	 *
	 * @param path project-relative path of the link entry
	 * @param linkTarget exact destination text stored in the link
	 * @param resolvedDirectory project-relative directory reached through the link; empty denotes the project root
	 */
	public record DirectoryLink(Path path, String linkTarget, Path resolvedDirectory)
	{
		/** Validates one retained directory-link identity. */
		public DirectoryLink
		{
			if (path == null || path.isAbsolute() || path.toString().isEmpty() ||
				!path.equals(path.normalize()) || path.startsWith("..") ||
				linkTarget == null || linkTarget.isEmpty() ||
				resolvedDirectory == null || resolvedDirectory.isAbsolute() ||
				!resolvedDirectory.equals(resolvedDirectory.normalize()) ||
				resolvedDirectory.startsWith(".."))
				throw new IllegalArgumentException("Prompt source directory link is invalid");
		}
	}
}
