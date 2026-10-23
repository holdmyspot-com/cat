package com.holdmyspot.cat.distribution.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.distribution.DistributionMain;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
	 * Returns the launchers of the Claude image that agents run, which the plugin holds in {@code bin/}.
	 *
	 * @return the launchers' names
	 */
	Set<String> claudeAgentLaunchers();

	/**
	 * Returns the launchers of the Codex image.
	 *
	 * @return the launchers
	 */
	List<JlinkImageBuilder.Launcher> codexLaunchers();

	/**
	 * Returns the launchers of the Codex image that agents run, which the plugin holds in {@code bin/}.
	 *
	 * @return the launchers' names
	 */
	Set<String> codexAgentLaunchers();

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
