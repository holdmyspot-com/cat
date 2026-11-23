package com.holdmyspot.cat.common.runner;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Carries the symbolic-link declarations of a selected project directory as regular files in a session result.
 */
public final class ResultProjectLinks
{
	/**
	 * The reserved session-result directory that holds the names and literal targets of the selected links.
	 */
	static final Path DIRECTORY = Path.of("project-link-declarations");
	static final String FIELD = "result_project_links";
	private static final int MAXIMUM_TARGET_BYTES = 128 * 1024;

	/**
	 * Prevents construction.
	 */
	private ResultProjectLinks()
	{
	}

	/**
	 * Checks the portable single-component name accepted for a selected project directory.
	 *
	 * @param name the proposed directory name
	 * @return whether the name consists of letters, numbers, hyphens, or underscores
	 */
	static boolean isDirectoryName(String name)
	{
		return name.matches("[A-Za-z0-9_-]+");
	}

	/**
	 * Captures the link declarations without resolving or reading any link's target.
	 *
	 * @param session the completed session directory
	 * @param result the result directory that receives the declarations
	 * @param name one directory name relative to the session's project
	 * @throws IOException if a directory or link cannot be read, or the declarations cannot be published
	 */
	static void capture(Path session, Path result, Path name) throws IOException
	{
		Path project = session.resolve("project");
		if (!Files.isDirectory(project, NOFOLLOW_LINKS))
			throw new IOException("The completed session has no project directory: " + project);
		Path source = project.resolve(name);
		if (!Files.isDirectory(source, NOFOLLOW_LINKS))
			throw new IOException("The selected result link directory is missing or is not a directory: " + source);
		Path directory = Files.createDirectory(result.resolve(DIRECTORY));
		Path destination = Files.createDirectory(directory.resolve(name));
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (attributes.isSymbolicLink())
				{
					Path declaration = destination.resolve(source.relativize(file));
					Files.createDirectories(declaration.getParent());
					Files.writeString(declaration, Files.readSymbolicLink(file).toString());
				}
				return CONTINUE;
			}
		});
	}

	/**
	 * Restores a draft's link declarations returned alongside a nested session's project copy.
	 *
	 * @param project the returned project directory
	 * @param name the name of the draft directory
	 * @throws IOException if the declarations cannot be read or the links cannot safely be created
	 */
	public static void restore(Path project, String name) throws IOException
	{
		if (!isDirectoryName(name))
			throw new IllegalArgumentException("Expected a single project directory name: " + name);
		Path root = project.toAbsolutePath().normalize();
		Path directory = root.resolveSibling(DIRECTORY);
		if (Files.notExists(directory, NOFOLLOW_LINKS))
			return;
		Path declared = directory.resolve(name);
		Path draft = root.resolve(name);
		for (Path path : new Path[]{directory, declared, root, draft})
			if (!Files.isDirectory(path, NOFOLLOW_LINKS))
				throw new IOException("The returned draft link declarations require a directory at " + path);

		Map<Path, Path> declarations = new LinkedHashMap<>();
		Files.walkFileTree(declared, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException
			{
				Path parent = draft.resolve(declared.relativize(path));
				if (!Files.isDirectory(parent, NOFOLLOW_LINKS))
					throw new IOException("The returned draft has no directory at " + parent);
				return CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!attributes.isRegularFile())
					throw new IOException("Expected a regular file for the draft link declaration at " + file);
				Path link = draft.resolve(declared.relativize(file));
				Path target = readTarget(file);
				if (!Files.notExists(link, NOFOLLOW_LINKS) &&
					(!Files.isSymbolicLink(link) || !Files.readSymbolicLink(link).equals(target)))
					throw new IOException("The returned draft link conflicts with an entry at " + link);
				declarations.put(link, target);
				return CONTINUE;
			}
		});
		ProjectTree.validateDeclaredLinks(draft, declarations);
		for (Entry<Path, Path> declaration : declarations.entrySet())
			if (!Files.isSymbolicLink(declaration.getKey()))
				Files.createSymbolicLink(declaration.getKey(), declaration.getValue());
	}

	/**
	 * Reads the target of one symbolic link.
	 *
	 * @param declaration the regular file containing the literal target
	 * @return the nonempty target
	 * @throws IOException if the target cannot be read or is an invalid path
	 */
	private static Path readTarget(Path declaration) throws IOException
	{
		String target = BoundedInput.readUtf8(Files.newInputStream(declaration, NOFOLLOW_LINKS), declaration,
			MAXIMUM_TARGET_BYTES, "draft link declaration");
		if (target.isEmpty())
			throw new IOException("The draft link declaration has an empty target at " + declaration);
		try
		{
			return Path.of(target);
		}
		catch (InvalidPathException e)
		{
			throw new IOException("The draft link declaration has an invalid target at " + declaration, e);
		}
	}
}
