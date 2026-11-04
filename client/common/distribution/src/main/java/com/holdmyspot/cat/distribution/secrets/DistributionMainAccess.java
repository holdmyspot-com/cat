package com.holdmyspot.cat.distribution.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.distribution.DistributionMain;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.ArtifactLayer;
import com.holdmyspot.cat.distribution.bundle.Launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
	 * Returns the launchers of the Claude plugin that agents run, which the plugin holds in {@code bin/}.
	 *
	 * @return the launchers' names
	 */
	Set<String> claudeAgentLaunchers();

	/**
	 * Returns the launchers of the Codex plugin.
	 *
	 * @return the launchers
	 */
	List<Launcher> codexLaunchers();

	/**
	 * Returns the launchers of the Codex plugin that agents run, which the plugin holds in {@code bin/}.
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
	 * Writes the launchers of the Codex plugin's client.
	 *
	 * @param client   the plugin's {@code client} folder
	 * @param platform the platform that the plugin runs on
	 * @throws IOException if the launchers cannot be written
	 */
	void writeCodexLaunchers(Path client, Platform platform) throws IOException;

	/**
	 * Stages a plugin's {@code client} folder: its modules and its runtime requirement.
	 *
	 * @param application the plugin's modules
	 * @param client      the folder, which must not exist
	 * @param json        the JSON writer
	 * @return {@code client}
	 * @throws IOException if the folder cannot be written
	 */
	Path stageClient(ApplicationModules application, Path client, JsonMapper json) throws IOException;

	/**
	 * Binds a plugin assembled in the source checkout to the shared runtime that the build wrote.
	 *
	 * @param plugin  the assembled plugin
	 * @param runtime the shared runtime
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
}
