package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link JlinkImageBuilder}.
 */
public final class JlinkImageBuilderTest
{
	/** Creates a test instance. */
	public JlinkImageBuilderTest()
	{
	}

	/**
	 * Verifies that the runtime links only the JDK modules that it is given, sorted, without an application module path
	 * or launchers, and holds the default class data sharing archive of those modules, which shortens the start-up of
	 * every program that the runtime runs.
	 */
	@Test
	public void linksOnlyJdkModules()
	{
		Path output = Path.of("target/runtime");

		List<String> arguments = JlinkImageBuilder.arguments(Set.of("java.net.http", "java.base"), output);

		requireThat(arguments, "jlinkArguments").isEqualTo(List.of("--add-modules", "java.base,java.net.http",
			"--output", output.toAbsolutePath().normalize().toString(), "--strip-debug", "--no-header-files",
			"--no-man-pages", "--generate-cds-archive"));
	}

	/**
	 * Verifies that the runtime holds a description of its Java version and modules, and a manifest of the digest of
	 * every other file, with the files that jlink links replaced by copies.
	 * <p>
	 * The image is what jlink 27.0.1 (Zulu27.30+107-CA) wrote for {@code --add-modules java.base,java.xml} with the
	 * options of {@link JlinkImageBuilder#arguments}, reduced to the files that the completed runtime is checked on: its
	 * {@code release} file, {@code bin/java}, and the license that {@code java.xml} links to {@code java.base}'s copy.
	 */
	@Test
	public void describesRuntime() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path output = cleanup.add(Files.createTempDirectory("jlink-builder-test")).resolve("runtime");
			Files.createDirectories(output.resolve("bin"));
			Files.writeString(output.resolve("bin/java"), "java");
			Files.writeString(output.resolve("release"),
				"JAVA_VERSION=\"27.0.1\"\nMODULES=\"java.base java.xml\"\n");
			Path license = output.resolve("legal/java.base/LICENSE");
			Files.createDirectories(license.getParent());
			Files.writeString(license, "license text");
			Path link = output.resolve("legal/java.xml/LICENSE");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));

			JlinkImageBuilder.complete(output, scope.json());

			JsonNode description = scope.json().readTree(output.resolve("cat-runtime.json").toFile());
			requireThat(description.get("format").asInt(), "format").isEqualTo(1);
			requireThat(description.get("java_version").asString(), "javaVersion").isEqualTo("27.0.1");
			requireThat(description.get("java_feature").asInt(), "javaFeature").isEqualTo(27);
			requireThat(description.get("modules").toString(), "modules").isEqualTo("[\"java.base\",\"java.xml\"]");
			requireThat(Files.isSymbolicLink(link), "isSymbolicLink").isFalse();
			requireThat(Files.readString(link), "copiedLicense").isEqualTo("license text");

			List<String> manifest = Files.readAllLines(output.resolve("cat-runtime.manifest"));
			List<String> expected = new ArrayList<>();
			expected.add("CAT runtime manifest v1");
			for (String file : List.of("bin/java", "cat-runtime.json", "legal/java.base/LICENSE",
				"legal/java.xml/LICENSE", "release"))
				expected.add(sha256(output.resolve(file)) + "\t" + file);
			requireThat(manifest, "manifest").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a symbolic link in an image becomes a regular file with its target's content, and that the target
	 * stays in place.
	 */
	@Test
	public void replacesLinksWithCopies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path image = cleanup.add(Files.createTempDirectory("jlink-builder-test"));
			Path target = image.resolve("legal/java.base/LICENSE");
			Files.createDirectories(target.getParent());
			Files.writeString(target, "license text");
			Path link = image.resolve("legal/java.xml/LICENSE");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));

			JlinkImageBuilder.replaceLinksWithCopies(image);

			requireThat(Files.isSymbolicLink(link), "isSymbolicLink").isFalse();
			requireThat(Files.readString(link), "copy").isEqualTo("license text");
			requireThat(Files.readString(target), "target").isEqualTo("license text");
		}
	}

	/**
	 * Verifies that a symbolic link whose target is missing is reported instead of being copied or kept.
	 */
	@Test
	public void rejectsBrokenLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path image = cleanup.add(Files.createTempDirectory("jlink-builder-test"));
			Path link = image.resolve("legal/java.xml/LICENSE");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));
			try
			{
				JlinkImageBuilder.replaceLinksWithCopies(image);
				throw new AssertionError("Expected a failure for the broken link");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains(link.toString());
			}
		}
	}

	/**
	 * Calculates the SHA-256 digest of a file.
	 *
	 * @param file the file
	 * @return the lowercase hexadecimal digest
	 * @throws IOException              if the file cannot be read
	 * @throws NoSuchAlgorithmException if the JDK lacks SHA-256
	 */
	private static String sha256(Path file) throws IOException, NoSuchAlgorithmException
	{
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}
}
