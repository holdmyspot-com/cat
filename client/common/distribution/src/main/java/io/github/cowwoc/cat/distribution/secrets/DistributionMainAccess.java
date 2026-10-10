package io.github.cowwoc.cat.distribution.secrets;

import io.github.cowwoc.cat.distribution.DistributionMain;
import io.github.cowwoc.cat.distribution.bundle.ArtifactLayer;
import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;

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
