package com.holdmyspot.cat.distribution.license;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Generates the CAT and third-party legal files bundled at the root of one plugin artifact.
 */
public final class LegalMaterialsBuilder
{
	private static final int MAXIMUM_LEGAL_INPUT_BYTES = 1_048_576;
	private final String catVersion;

	/**
	 * Creates a legal-material builder.
	 *
	 * @param catVersion the CAT version named in {@code NOTICE.txt}
	 * @throws NullPointerException if {@code catVersion} is null
	 * @throws IllegalArgumentException if {@code catVersion} is blank
	 */
	public LegalMaterialsBuilder(String catVersion)
	{
		requireThat(catVersion, "catVersion").isNotBlank();
		this.catVersion = catVersion;
	}

	/**
	 * Generates {@code LICENSE.txt}, {@code NOTICE.txt}, and the canonical {@code licenses/} directory.
	 *
	 * @param catLicenseFile the repository's CAT license file whose terms appear first in {@code LICENSE.txt}
	 * @param components the third-party components included in the artifact
	 * @param canonicalLicenseDirectory the source directory of deduplicated third-party license texts
	 * @param outputDirectory the empty output directory that receives the generated legal materials
	 * @return {@code outputDirectory}
	 * @throws NullPointerException if {@code catLicenseFile}, {@code components}, {@code canonicalLicenseDirectory}, or
	 *         {@code outputDirectory} are null
	 * @throws IOException if a source file cannot be read, a component's required JAR entry is missing, or the materials
	 *         cannot be generated
	 */
	public Path build(Path catLicenseFile, List<ThirdPartyComponent> components, Path canonicalLicenseDirectory,
		Path outputDirectory) throws IOException
	{
		requireThat(catLicenseFile, "catLicenseFile").isNotNull();
		requireThat(components, "components").isNotNull();
		requireThat(canonicalLicenseDirectory, "canonicalLicenseDirectory").isNotNull();
		requireThat(outputDirectory, "outputDirectory").isNotNull();
		requireThat(catLicenseFile, "catLicenseFile").isRegularFile();
		requireThat(canonicalLicenseDirectory, "canonicalLicenseDirectory").isDirectory();
		if (Files.exists(outputDirectory))
			throw new IOException("Legal-material output already exists: " + outputDirectory);
		Files.createDirectories(outputDirectory);

		validateCanonicalLicenseFiles(components, canonicalLicenseDirectory);
		copyCanonicalLicenses(canonicalLicenseDirectory, outputDirectory.resolve("licenses"));
		Files.writeString(outputDirectory.resolve("LICENSE.txt"), licenseText(catLicenseFile, components));
		Files.writeString(outputDirectory.resolve("NOTICE.txt"), noticeText(components));
		return outputDirectory;
	}

	/**
	 * Copies the deduplicated third-party license texts into the generated materials.
	 *
	 * @param source the canonical-license directory
	 * @param destination the generated {@code licenses/} directory
	 * @throws IOException if the license texts cannot be copied
	 */
	private static void copyCanonicalLicenses(Path source, Path destination) throws IOException
	{
		Files.createDirectories(destination);
		try (Stream<Path> files = Files.list(source))
		{
			for (Path file : files.sorted().toList())
			{
				if (Files.isRegularFile(file))
					Files.copy(file, destination.resolve(file.getFileName()));
			}
		}
	}

	/**
	 * Verifies every component mapping refers to a canonical license text.
	 *
	 * @param components the bundled third-party components
	 * @param licenseDirectory the canonical-license directory
	 * @throws IOException if a mapped license text is missing
	 */
	private static void validateCanonicalLicenseFiles(List<ThirdPartyComponent> components, Path licenseDirectory)
		throws IOException
	{
		for (ThirdPartyComponent component : components)
		{
			Path license = licenseDirectory.resolve(component.licenseFile());
			if (!Files.isRegularFile(license))
				throw new IOException("Missing canonical license text for " + component.name() + ": " + license);
		}
	}

	/**
	 * Creates the root license text with CAT's complete terms before the dependency license index.
	 *
	 * @param catLicenseFile the repository's CAT license file
	 * @param components the bundled third-party components
	 * @return the generated license text
	 * @throws IOException if the CAT license cannot be read
	 */
	private static String licenseText(Path catLicenseFile, List<ThirdPartyComponent> components) throws IOException
	{
		String catLicense = BoundedInput.readUtf8(catLicenseFile, MAXIMUM_LEGAL_INPUT_BYTES, "CAT license").strip();
		StringBuilder result = new StringBuilder(1024);
		result.append("""
			CAT LICENSE
			===========

			%s

			THIRD-PARTY LICENSES
			====================

			The following components are included in this distribution. Their license text is in licenses/.

			""".formatted(catLicense));
		for (ThirdPartyComponent component : components)
			appendComponent(result, component);
		return result.toString();
	}

	/**
	 * Creates the root notice text with CAT's notice before component mappings and embedded notices.
	 *
	 * @param components the bundled third-party components
	 * @return the generated notice text
	 * @throws IOException if embedded notice material cannot be read
	 */
	private String noticeText(List<ThirdPartyComponent> components) throws IOException
	{
		StringBuilder result = new StringBuilder(1024);
		result.append("""
			CAT NOTICE
			==========

			CAT version %s is distributed under the CAT license in LICENSE.txt.

			THIRD-PARTY COMPONENTS
			======================

			The following components are included in this distribution. Their license text is in licenses/.

			""".formatted(catVersion));
		for (ThirdPartyComponent component : components)
			appendComponent(result, component);

		for (ThirdPartyComponent component : components)
			appendEmbeddedNotices(result, component);
		return result.toString();
	}

	/**
	 * Appends one component-to-license mapping.
	 *
	 * @param output the generated legal text
	 * @param component the mapped component
	 */
	private static void appendComponent(StringBuilder output, ThirdPartyComponent component)
	{
		output.append("""
			%s
			Source: %s
			License: licenses/%s

			""".formatted(component.name(), component.origin(), component.licenseFile()));
	}

	/**
	 * Appends one component's required embedded notice and license material.
	 *
	 * @param output the generated notice text
	 * @param component the component that owns the embedded material
	 * @throws IOException if an entry is missing or cannot be read
	 */
	private static void appendEmbeddedNotices(StringBuilder output, ThirdPartyComponent component) throws IOException
	{
		if (component.noticeEntries().isEmpty())
			return;
		try (JarFile jar = new JarFile(component.jar().toFile()))
		{
			output.append("""
				EMBEDDED MATERIAL: %s
				===================================

				""".formatted(component.name()));
			for (String entryName : component.noticeEntries())
			{
				JarEntry entry = jar.getJarEntry(entryName);
				if (entry == null)
					throw new IOException("Missing legal-material entry " + entryName + " in " + component.jar());
				try (InputStream input = jar.getInputStream(entry))
				{
					String content = BoundedInput.readUtf8(input, component.jar() + "!" + entryName,
						MAXIMUM_LEGAL_INPUT_BYTES, "Embedded legal material").strip();
					output.append("""
						%s
						%s

						%s

						""".formatted(entryName, "-".repeat(entryName.length()), content));
				}
			}
		}
	}
}
