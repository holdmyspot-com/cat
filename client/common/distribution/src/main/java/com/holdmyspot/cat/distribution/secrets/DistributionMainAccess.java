package com.holdmyspot.cat.distribution.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.distribution.DistributionMain;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.Launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the inputs that {@link DistributionMain} assembles a release from.
 */
public interface DistributionMainAccess
{
	/**
	 * Returns the launchers of the Claude plugin.
	 *
	 * @return the launchers
	 */
	List<Launcher> claudeLaunchers();

	/**
	 * Returns the launchers of the Codex plugin.
	 *
	 * @return the launchers
	 */
	List<Launcher> codexLaunchers();

	/**
	 * Returns the layer of the Codex plugin's own resources.
	 *
	 * @param jar the Codex plugin resource JAR
	 * @return the layer
	 */
	ArtifactLayer codexPluginLayer(Path jar);

	/**
	 * Writes the launchers of the Codex plugin's {@code bin} folder.
	 *
	 * @param bin      the folder that becomes the plugin's {@code bin} folder
	 * @param platform the platform that the plugin runs on
	 * @throws IOException if the launchers cannot be written
	 */
	void writeCodexLaunchers(Path bin, Platform platform) throws IOException;

	/**
	 * Stages the folder that becomes a plugin's {@code bin} folder with the release's Java runtime, the plugin's modules
	 * beside the runtime's own folders, and the plugin's runtime requirement.
	 *
	 * @param application the plugin's modules
	 * @param runtime     the release's Java runtime
	 * @param bin         the folder, which must not exist
	 * @param json        the JSON writer
	 * @return {@code bin}
	 * @throws IOException if the folder cannot be written, or the runtime already holds the folder of the modules
	 */
	Path stageBin(ApplicationModules application, Path runtime, Path bin, JsonMapper json) throws IOException;

	/**
	 * Binds a plugin assembled in the source checkout to the release runtime that the build wrote.
	 *
	 * @param plugin  the assembled plugin
	 * @param runtime the release runtime
	 * @throws IOException if the binding cannot be written
	 */
	void writeRuntimeBinding(Path plugin, Path runtime) throws IOException;

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

	/**
	 * Writes the description of how an installation trains a plugin's startup cache into the plugin's {@code bin}
	 * folder.
	 *
	 * @param bin     the folder that becomes the plugin's {@code bin} folder
	 * @param harness the plugin's harness, {@code claude} or {@code codex}
	 * @param json    the JSON writer
	 * @throws IOException if the description cannot be written
	 */
	void writeStartupCacheTraining(Path bin, String harness, JsonMapper json) throws IOException;
}
