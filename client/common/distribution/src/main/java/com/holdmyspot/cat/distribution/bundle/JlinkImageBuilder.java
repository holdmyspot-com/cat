package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uses the JDK's {@code jlink} tool to build a release's Java runtime, which each of the release's plugins holds for
 * its installer and its other launchers. The runtime holds only JDK modules and no launchers; each application's
 * modules stay in its own JARs, on its launchers' module path.
 */
public final class JlinkImageBuilder
{
	private final ToolProvider jlink;

	/**
	 * Creates a builder backed by the current JDK's jlink tool.
	 *
	 * @throws IllegalStateException if the JDK does not provide jlink
	 */
	public JlinkImageBuilder()
	{
		this.jlink = ToolProvider.findFirst("jlink").
			orElseThrow(() -> new IllegalStateException("The JDK does not provide jlink"));
	}

	/**
	 * Builds the runtime: links the JDK modules without debug information, header files or manual pages, with
	 * the default class data sharing archive of those modules, which shortens the start-up of every program, replaces
	 * each symbolic link that {@code jlink} writes with a copy of its target, and writes the runtime's description and
	 * manifest.
	 *
	 * @param modules the JDK modules that the runtime holds
	 * @param output  the runtime's directory, which must not exist
	 * @param json    the JSON writer
	 * @return the runtime's directory
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code modules} is empty
	 * @throws IOException              if jlink fails, or the runtime cannot be completed
	 */
	public Path build(Set<String> modules, Path output, JsonMapper json) throws IOException
	{
		requireThat(modules, "modules").isNotEmpty();
		requireThat(output, "output").isNotNull();
		requireThat(json, "json").isNotNull();

		ToolInvocation.run(jlink, "jlink", arguments(modules, output));
		complete(output, json);
		return output;
	}

	/**
	 * Returns the arguments that link the runtime: the JDK modules, sorted, without debug information, header files
	 * or manual pages, with the default class data sharing archive of those modules, which shortens the start-up of
	 * every program.
	 *
	 * @param modules the JDK modules that the runtime holds
	 * @param output  the runtime's directory
	 * @return the arguments of {@code jlink}
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code modules} is empty
	 */
	public static List<String> arguments(Set<String> modules, Path output)
	{
		requireThat(modules, "modules").isNotEmpty();
		requireThat(output, "output").isNotNull();
		return List.of("--add-modules", String.join(",", new TreeSet<>(modules)), "--output",
			output.toAbsolutePath().normalize().toString(), "--strip-debug", "--no-header-files", "--no-man-pages",
			"--generate-cds-archive");
	}

	/**
	 * Completes a runtime image that {@code jlink} wrote: replaces each symbolic link with a copy of its target, and
	 * writes the runtime's description and manifest.
	 *
	 * @param image the runtime-image directory
	 * @param json  the JSON writer
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if a link does not resolve to a regular file, the image lacks the information that
	 *                              the description needs, or the image cannot be read or changed
	 */
	public static void complete(Path image, JsonMapper json) throws IOException
	{
		requireThat(image, "image").isNotNull();
		requireThat(json, "json").isNotNull();
		replaceLinksWithCopies(image);
		RuntimeDescription.write(image, json);
	}

	/**
	 * Replaces every symbolic link in a runtime image with a copy of the file that it points to. jlink links some
	 * modules' license files to the {@code java.base} module's copies, and an installer that drops symbolic links, such
	 * as Codex's plugin installer, or a platform without them would otherwise leave those modules without their license
	 * files.
	 *
	 * @param image the runtime-image directory
	 * @throws NullPointerException if {@code image} is null
	 * @throws IOException if a link does not resolve to a regular file, or if the image cannot be read or changed
	 */
	public static void replaceLinksWithCopies(Path image) throws IOException
	{
		requireThat(image, "image").isNotNull();
		List<Path> links;
		try (Stream<Path> paths = Files.walk(image))
		{
			links = paths.filter(Files::isSymbolicLink).toList();
		}
		for (Path link : links)
		{
			if (!Files.isRegularFile(link))
				throw new IOException("The symbolic link does not point to a regular file: " + link);
		}
		for (Path link : links)
		{
			Path target = link.resolveSibling(Files.readSymbolicLink(link));
			Files.delete(link);
			Files.copy(target, link);
		}
	}
}
