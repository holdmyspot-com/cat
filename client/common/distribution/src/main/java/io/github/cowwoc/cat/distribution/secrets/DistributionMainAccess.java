package io.github.cowwoc.cat.distribution.secrets;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.distribution.DistributionMain;
import io.github.cowwoc.cat.distribution.bundle.ArtifactLayer;
import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Exposes the inputs that {@link DistributionMain} assembles a release from.
 */
public interface DistributionMainAccess
{
	/**
	 * Returns the launchers of the Claude image.
	 *
	 * @return the launchers
	 */
	List<JlinkImageBuilder.Launcher> claudeLaunchers();

	/**
	 * Returns the launchers of the Codex image.
	 *
	 * @return the launchers
	 */
	List<JlinkImageBuilder.Launcher> codexLaunchers();

	/**
	 * Returns the layer of the Codex plugin's own resources.
	 *
	 * @param jar the Codex plugin resource JAR
	 * @return the layer
	 */
	ArtifactLayer codexPluginLayer(Path jar);

	/**
	 * Builds the Codex image.
	 *
	 * @param builder  the image builder
	 * @param modules  the module-path entries of the image
	 * @param output   the image's directory
	 * @param platform the platform that the image runs on
	 * @return the image's directory
	 * @throws IOException if the image cannot be built
	 */
	Path codexImage(JlinkImageBuilder builder, List<Path> modules, Path output, Platform platform) throws IOException;

	/**
	 * Returns the Maven coordinates of a third-party dependency JAR that a distribution ships.
	 *
	 * @param groupId the Maven group identifier
	 * @param artifactId the Maven artifact identifier
	 * @param path the JAR, named {@code <artifactId>-<version>.jar}
	 * @return {@code <groupId>:<artifactId>:<version>}, with the version taken from the file name
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if:
	 *   <ul>
	 *     <li>{@code groupId} or {@code artifactId} are blank</li>
	 *     <li>the file name of {@code path} is not {@code <artifactId>-<version>.jar}</li>
	 *   </ul>
	 */
	String dependencyCoordinates(String groupId, String artifactId, Path path);
}
