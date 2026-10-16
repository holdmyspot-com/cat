package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.stream.Stream;

/**
 * Copies the bundled rule trees on the test class path into a plugin directory that a test owns.
 */
public final class BundledRules
{
	/**
	 * Prevents instantiation.
	 */
	private BundledRules()
	{
	}

	/**
	 * Copies every bundled rule tree on the test class path into one plugin rule directory.
	 *
	 * @param target the plugin's {@code rules} directory
	 * @throws NullPointerException if {@code target} is null
	 * @throws IOException if a rule tree cannot be read or copied
	 * @throws URISyntaxException if the class path returns a malformed resource location
	 */
	public static void copy(Path target) throws IOException, URISyntaxException
	{
		requireThat(target, "target").isNotNull();
		Enumeration<URL> roots = BundledRules.class.getClassLoader().getResources("rules");
		requireThat(roots.hasMoreElements(), "hasBundledRules").isTrue();
		while (roots.hasMoreElements())
		{
			URI root = roots.nextElement().toURI();
			if (!root.getScheme().equals("jar"))
			{
				copyTree(Path.of(root), target);
				continue;
			}
			String location = root.getSchemeSpecificPart();
			Path jar = Path.of(new URI(location.substring(0, location.indexOf("!/"))));
			try (FileSystem contents = FileSystems.newFileSystem(jar))
			{
				copyTree(contents.getPath("/rules"), target);
			}
		}
	}

	/**
	 * Copies the regular files below one directory, preserving their relative paths.
	 *
	 * @param source the directory to copy from
	 * @param target the directory to copy into
	 * @throws IOException if a file cannot be read or written
	 */
	private static void copyTree(Path source, Path target) throws IOException
	{
		assert that(source, "source").isNotNull().elseThrow();
		assert that(target, "target").isNotNull().elseThrow();
		try (Stream<Path> files = Files.walk(source))
		{
			for (Path file : files.filter(Files::isRegularFile).toList())
			{
				Path destination = target.resolve(source.relativize(file).toString());
				Files.createDirectories(destination.getParent());
				Files.copy(file, destination);
			}
		}
	}
}
