package io.github.cowwoc.cat.distribution.license.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.distribution.license.LegalMaterialsBuilder;
import io.github.cowwoc.cat.distribution.license.ThirdPartyComponent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.testng.annotations.Test;

/**
 * Tests {@link LegalMaterialsBuilder}.
 */
public final class LegalMaterialsBuilderTest
{
	/** Creates a test instance. */
	public LegalMaterialsBuilderTest()
	{
	}

	/**
	 * Verifies CAT material precedes the exact dependency mapping and embedded notices.
	 */
	@Test
	public void generatesCatAndThirdPartyMaterials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-legal-materials-"));
		Path catLicense = Files.writeString(temporary.resolve("CAT-LICENSE.md"), "CAT TERMS");
		Path licenses = Files.createDirectories(temporary.resolve("licenses"));
		Files.writeString(licenses.resolve("Apache-2.0.txt"), "APACHE TERMS");
		Files.writeString(licenses.resolve("MIT.txt"), "MIT TERMS");
		Path jackson = jar(temporary.resolve("jackson.jar"), "META-INF/NOTICE", "JACKSON NOTICE");

		Path output = new LegalMaterialsBuilder("1.0").build(catLicense,
			List.of(new ThirdPartyComponent("Jackson Core", "tools.jackson.core:jackson-core:3.1.4", "Apache-2.0.txt",
				jackson, List.of("META-INF/NOTICE")), new ThirdPartyComponent("SLF4J API", "org.slf4j:slf4j-api:2.0.17",
				"MIT.txt", jackson, List.of())), licenses, temporary.resolve("output"));

		String license = Files.readString(output.resolve("LICENSE.txt"));
		requireThat(license.indexOf("CAT TERMS"), "catLicenseIndex").isLessThan(license.indexOf("Jackson Core"));
		requireThat(license, "license").contains("licenses/Apache-2.0.txt").contains("licenses/MIT.txt");
		String notice = Files.readString(output.resolve("NOTICE.txt"));
		requireThat(notice.indexOf("CAT version 1.0"), "catNoticeIndex").isLessThan(notice.indexOf("Jackson Core"));
		requireThat(notice, "notice").contains("JACKSON NOTICE").contains("licenses/MIT.txt");
		requireThat(Files.readString(output.resolve("licenses/Apache-2.0.txt")), "apacheLicense").
			isEqualTo("APACHE TERMS");
			requireThat(Files.readString(output.resolve("licenses/MIT.txt")), "mitLicense").isEqualTo("MIT TERMS");
		}
	}

	/**
	 * Verifies missing embedded legal material fails artifact assembly.
	 */
	@Test
	public void rejectsMissingEmbeddedMaterial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-missing-legal-material-"));
		Path catLicense = Files.writeString(temporary.resolve("CAT-LICENSE.md"), "CAT TERMS");
		Path licenses = Files.createDirectories(temporary.resolve("licenses"));
		Files.writeString(licenses.resolve("Apache-2.0.txt"), "APACHE TERMS");
		Path component = jar(temporary.resolve("component.jar"), "content", "content");

		try
		{
			new LegalMaterialsBuilder("1.0").build(catLicense,
				List.of(new ThirdPartyComponent("Component", "example:component:1.0", "Apache-2.0.txt", component,
					List.of("META-INF/NOTICE"))), licenses, temporary.resolve("output"));
			throw new AssertionError("Expected missing legal-material entry");
		}
			catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("META-INF/NOTICE");
		}
		}
	}

	/**
	 * Creates a JAR containing one entry.
	 *
	 * @param path the JAR path
	 * @param entryName the entry name
	 * @param content the entry content
	 * @return the created JAR
	 * @throws IOException if the JAR cannot be written
	 */
	private static Path jar(Path path, String entryName, String content) throws IOException
	{
		try (OutputStream output = Files.newOutputStream(path);
			JarOutputStream jar = new JarOutputStream(output))
		{
			jar.putNextEntry(new JarEntry(entryName));
			jar.write(content.getBytes(UTF_8));
			jar.closeEntry();
		}
		return path;
	}
}
