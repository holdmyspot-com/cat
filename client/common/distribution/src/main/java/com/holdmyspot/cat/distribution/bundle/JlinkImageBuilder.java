package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.distribution.bundle.secrets.JlinkImageBuilderSecrets;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uses the JDK's {@code jlink} tool to build the shared Java runtime that the trusted updater and every CAT plugin of a
 * platform run on. The runtime holds only JDK modules and no launchers; each application's modules stay in its own
 * JARs, on its launchers' module path.
 */
public final class JlinkImageBuilder
{
	static
	{
		JlinkImageBuilderSecrets.setJlinkImageBuilderAccess(JlinkImageBuilder::new);
	}

	private final ToolProvider jlink;

	/**
	 * Creates a builder backed by the current JDK's jlink tool.
	 *
	 * @throws IllegalStateException if the JDK does not provide jlink
	 */
	public JlinkImageBuilder()
	{
		this(ToolProvider.findFirst("jlink").
			orElseThrow(() -> new IllegalStateException("The JDK does not provide jlink")));
	}

	/**
	 * Creates a builder backed by a tool provider.
	 *
	 * @param jlink the jlink tool provider
	 * @throws NullPointerException if {@code jlink} is null
	 */
	private JlinkImageBuilder(ToolProvider jlink)
	{
		requireThat(jlink, "jlink").isNotNull();
		this.jlink = jlink;
	}

	/**
	 * Builds the shared runtime: links the JDK modules without debug information, header files or manual pages,
	 * replaces each symbolic link that {@code jlink} writes with a copy of its target, and writes the runtime's
	 * description and manifest.
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

		List<String> arguments = List.of("--add-modules", String.join(",", new TreeSet<>(modules)), "--output",
			output.toAbsolutePath().normalize().toString(), "--strip-debug", "--no-header-files", "--no-man-pages");
		StringWriter standardOutput = new StringWriter();
		StringWriter standardError = new StringWriter();
		int exitCode;
		try (PrintWriter out = new PrintWriter(standardOutput);
			PrintWriter err = new PrintWriter(standardError))
		{
			exitCode = jlink.run(out, err, arguments.toArray(String[]::new));
		}
		if (exitCode != 0)
		{
			String diagnostics = (standardError + System.lineSeparator() + standardOutput).trim();
			String details = "";
			if (!diagnostics.isEmpty())
				details = ": " + diagnostics;
			throw new IOException("jlink failed with exit code " + exitCode + details);
		}
		replaceLinksWithCopies(output);
		RuntimeDescription.write(output, json);
		return output;
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
