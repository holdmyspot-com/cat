package io.github.cowwoc.cat.common.optimizer.runner;

import java.nio.file.Path;

/** Supported native harness identities and their installed plugin manifests. */
public enum NativeHarness
{
	/** Codex's retained native runner. */
	CODEX("codex", "Codex", ".codex-plugin/plugin.json"),
	/** Claude's retained native runner. */
	CLAUDE("claude", "Claude", ".claude-plugin/plugin.json");

	private final String id;
	private final String displayName;
	private final Path pluginManifest;

	/**
	 * Defines one supported harness's persistence namespace and packaged manifest.
	 *
	 * @param id persistence namespace
	 * @param displayName name used in diagnostics
	 * @param pluginManifest relative packaged manifest
	 */
	NativeHarness(String id, String displayName, String pluginManifest)
	{
		this.id = id;
		this.displayName = displayName;
		this.pluginManifest = Path.of(pluginManifest);
	}

	/**
	 * Returns the namespace used by the owned-run repository.
	 *
	 * @return persistence namespace
	 */
	public String id()
	{
		return id;
	}

	/**
	 * Returns the harness name used in diagnostics.
	 *
	 * @return display name
	 */
	public String displayName()
	{
		return displayName;
	}

	/**
	 * Returns the manifest required when validating the selected installed artifact.
	 *
	 * @return relative plugin manifest
	 */
	public Path pluginManifest()
	{
		return pluginManifest;
	}
}
