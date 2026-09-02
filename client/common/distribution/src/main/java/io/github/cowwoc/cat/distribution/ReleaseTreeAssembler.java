package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines the native release trees built on all supported platforms into each edition's repository tree.
 */
public final class ReleaseTreeAssembler
{
	/**
	 * Prevents construction of this static utility owner.
	 */
	private ReleaseTreeAssembler()
	{
	}

	/**
	 * Assembles both complete edition trees from platform-specific native build inputs.
	 *
	 * <p>Each native input must be rooted at {@code inputs/PLATFORM/EDITION/HARNESS/RELEASE_PLATFORM}. The result
	 * contains every target defined by {@link ReleaseManifest}; no target may be inferred from the publishing host.
	 *
	 * @param version the exact release version
	 * @param inputsRoot the directory containing one native build tree for each platform
	 * @param releaseRoot the nonexistent output directory
	 * @throws IOException if an expected input is missing, unsafe, or cannot be materialized
	 */
	public static void assemble(String version, Path inputsRoot, Path releaseRoot) throws IOException
	{
		requireThat(version, "version").isNotBlank();
		requireThat(inputsRoot, "inputsRoot").isNotNull();
		requireThat(releaseRoot, "releaseRoot").isNotNull();
		Path normalizedInputs = inputsRoot.toAbsolutePath().normalize();
		Path normalizedOutput = releaseRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalizedInputs, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Native release inputs are not a directory: " + normalizedInputs);
		if (Files.exists(normalizedOutput, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Release output already exists: " + normalizedOutput);
		EditionArtifactMaterializer materializer = new EditionArtifactMaterializer(version);
		for (ReleaseManifest.Edition edition : ReleaseManifest.Edition.values())
		{
			Path editionRoot = normalizedOutput.resolve(edition.externalValue());
			copyRepositoryDocuments(normalizedInputs, editionRoot);
			List<Path> targetRoots = new ArrayList<>();
			for (ReleaseManifest.ReleaseTarget target : ReleaseManifest.targets(edition))
			{
				Path source = inputTarget(normalizedInputs, target);
				if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Native release target is missing: " + source);
				targetRoots.add(materializer.materialize(target, source, editionRoot));
			}
			materializer.writeReleaseManifest(targetRoots, editionRoot);
		}
	}

	/**
	 * Copies the two repository-level documents supplied with the native build inputs.
	 *
	 * <p>The documents are input once rather than copied from an arbitrary target. This prevents a native artifact from
	 * silently defining legal or product text for a different edition.
	 *
	 * @param inputsRoot the normalized native-input root
	 * @param editionRoot the destination edition root
	 * @throws IOException if a required document is missing, unsafe, or cannot be copied
	 */
	private static void copyRepositoryDocuments(Path inputsRoot, Path editionRoot) throws IOException
	{
		Files.createDirectories(editionRoot);
		for (String name : List.of("LICENSE.md", "README.md"))
		{
			Path source = inputsRoot.resolve(name);
			if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
				throw new IOException("Native release inputs are missing a safe repository document: " + source);
			Files.copy(source, editionRoot.resolve(name));
		}
	}

	/**
	 * Resolves one native build tree's target directory.
	 *
	 * @param inputsRoot the normalized native-input root
	 * @param target the required target
	 * @return the exact source target directory
	 */
	private static Path inputTarget(Path inputsRoot, ReleaseManifest.ReleaseTarget target)
	{
		Platform platform = target.platform();
		return inputsRoot.resolve(platform.id()).resolve(target.edition().externalValue()).resolve(
			target.repositoryDirectory());
	}
}
