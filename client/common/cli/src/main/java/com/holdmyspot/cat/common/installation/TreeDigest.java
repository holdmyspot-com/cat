package com.holdmyspot.cat.common.installation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Computes the SHA-256 digest of a directory tree's relative paths, file contents, executable bits and link targets.
 * The digest does not depend on the order in which the file system lists the tree, so an unchanged tree always has
 * the same digest.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TreeDigest
{
	/**
	 * Prevents instantiation.
	 */
	private TreeDigest()
	{
	}

	/**
	 * Returns the digest of a tree.
	 *
	 * @param tree the tree
	 * @return the digest in lower-case hexadecimal
	 * @throws NullPointerException if {@code tree} is null
	 * @throws IOException          if the tree is missing or cannot be read
	 */
	public static String of(Path tree) throws IOException
	{
		requireThat(tree, "tree").isNotNull();
		return digest(tree, null);
	}

	/**
	 * Returns the digest of a tree, leaving out one file.
	 *
	 * @param tree     the tree
	 * @param excluded the file to leave out
	 * @return the digest in lower-case hexadecimal
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the tree is missing or cannot be read
	 */
	public static String of(Path tree, Path excluded) throws IOException
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(excluded, "excluded").isNotNull();
		return digest(tree, excluded);
	}

	/**
	 * Returns the digest of a tree.
	 *
	 * @param tree     the tree
	 * @param excluded a file to leave out, or null
	 * @return the digest in lower-case hexadecimal
	 * @throws IOException if the tree is missing or cannot be read
	 */
	private static String digest(Path tree, Path excluded) throws IOException
	{
		if (!Files.isDirectory(tree, NOFOLLOW_LINKS))
			throw new IOException("The installed directory is missing: " + tree);
		MessageDigest tree256 = sha256();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(tree))
		{
			files = walk.filter(path -> !Files.isDirectory(path, NOFOLLOW_LINKS) && !path.equals(excluded)).
				sorted().toList();
		}
		for (Path file : files)
		{
			String relative = tree.relativize(file).toString().replace('\\', '/');
			String content;
			if (Files.isSymbolicLink(file))
				content = "link " + Files.readSymbolicLink(file);
			else
				content = "file " + Files.isExecutable(file) + " " + fileDigest(file);
			tree256.update((relative + "\0" + content + "\n").getBytes(UTF_8));
		}
		return HexFormat.of().formatHex(tree256.digest());
	}

	/**
	 * Returns the SHA-256 digest of a file.
	 *
	 * @param file the file
	 * @return the digest in lower-case hexadecimal
	 * @throws IOException if the file cannot be read
	 */
	private static String fileDigest(Path file) throws IOException
	{
		MessageDigest digest = sha256();
		try (InputStream input = Files.newInputStream(file, NOFOLLOW_LINKS))
		{
			byte[] buffer = new byte[64 * 1024];
			for (int count = input.read(buffer); count != -1; count = input.read(buffer))
				digest.update(buffer, 0, count);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Returns a SHA-256 digest.
	 *
	 * @return the digest
	 */
	private static MessageDigest sha256()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("The Java platform cannot provide SHA-256", e);
		}
	}
}
