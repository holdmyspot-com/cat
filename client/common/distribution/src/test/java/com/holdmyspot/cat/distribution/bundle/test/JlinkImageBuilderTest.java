package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;
import com.holdmyspot.cat.distribution.bundle.secrets.JlinkImageBuilderSecrets;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
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
	 * or launchers.
	 */
	@Test
	public void linksOnlyJdkModules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			FakeToolProvider tool = new FakeToolProvider(0, "");
			Path output = cleanup.add(Files.createTempDirectory("jlink-builder-test")).resolve("runtime");
			JlinkImageBuilderSecrets.getJlinkImageBuilderAccess().create(tool).
				build(Set.of("java.net.http", "java.base"), output, scope.json());

			requireThat(tool.arguments, "jlinkArguments").isEqualTo(List.of("--add-modules", "java.base,java.net.http",
				"--output", output.toAbsolutePath().normalize().toString(), "--strip-debug", "--no-header-files",
				"--no-man-pages"));
		}
	}

	/**
	 * Verifies that the runtime holds a description of its Java version and modules, and a manifest of the digest of
	 * every other file, with the files that jlink links replaced by copies.
	 */
	@Test
	public void describesRuntime() throws IOException, NoSuchAlgorithmException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path output = cleanup.add(Files.createTempDirectory("jlink-builder-test")).resolve("runtime");
			JlinkImageBuilderSecrets.getJlinkImageBuilderAccess().create(new FakeToolProvider(0, "")).
				build(Set.of("java.base", "java.xml"), output, scope.json());

			JsonNode description = scope.json().readTree(output.resolve("cat-runtime.json").toFile());
			requireThat(description.get("format").asInt(), "format").isEqualTo(1);
			requireThat(description.get("java_version").asString(), "javaVersion").isEqualTo("27.0.1");
			requireThat(description.get("java_feature").asInt(), "javaFeature").isEqualTo(27);
			requireThat(description.get("modules").toString(), "modules").isEqualTo("[\"java.base\",\"java.xml\"]");
			Path link = output.resolve("legal/java.xml/LICENSE");
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
	 * Verifies jlink diagnostics are included in failures.
	 */
	@Test
	public void reportsToolDiagnostics() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope())
		{
			JlinkImageBuilderSecrets.getJlinkImageBuilderAccess().
				create(new FakeToolProvider(7, "bad module")).build(Set.of("java.base"), Path.of("target/runtime"),
					scope.json());
			throw new AssertionError("Expected jlink failure");
		}
		catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("exit code 7").contains("bad module");
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

	/**
	 * A controllable in-process jlink tool that writes a small runtime: its {@code release} file, {@code bin/java}, and
	 * a license file that another module's license links to, as jlink links them.
	 */
	private static final class FakeToolProvider implements ToolProvider
	{
		private final int exitCode;
		private final String diagnostic;
		private List<String> arguments = List.of();

		/**
		 * Creates a tool provider with a predetermined result.
		 *
		 * @param exitCode   the exit code to return
		 * @param diagnostic the diagnostic text to write
		 */
		private FakeToolProvider(int exitCode, String diagnostic)
		{
			assert that(diagnostic, "diagnostic").isNotNull().elseThrow();
			this.exitCode = exitCode;
			this.diagnostic = diagnostic;
		}

		@Override
		public String name()
		{
			return "jlink";
		}

		@Override
		public int run(PrintWriter out, PrintWriter err, String... args)
		{
			requireThat(out, "out").isNotNull();
			requireThat(err, "err").isNotNull();
			requireThat(args, "args").isNotNull();
			arguments = new ArrayList<>(List.of(args));
			err.print(diagnostic);
			if (exitCode != 0)
				return exitCode;
			try
			{
				Path output = Path.of(arguments.get(arguments.indexOf("--output") + 1));
				String modules = arguments.get(arguments.indexOf("--add-modules") + 1).replace(',', ' ');
				Files.createDirectories(output.resolve("bin"));
				Files.writeString(output.resolve("bin/java"), "java");
				Files.writeString(output.resolve("release"), "IMPLEMENTOR=\"Test\"\nJAVA_VERSION=\"27.0.1\"\n" +
					"MODULES=\"" + modules + "\"\n");
				Path license = output.resolve("legal/java.base/LICENSE");
				Files.createDirectories(license.getParent());
				Files.writeString(license, "license text");
				Path link = output.resolve("legal/java.xml/LICENSE");
				Files.createDirectories(link.getParent());
				Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));
			}
			catch (IOException exception)
			{
				throw new AssertionError(exception);
			}
			return exitCode;
		}
	}
}
