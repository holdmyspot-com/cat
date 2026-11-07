package com.holdmyspot.cat.common.installation.test;

import com.holdmyspot.cat.common.installation.HarnessInstallation;
import java.nio.file.Path;

/**
 * Where a generic harness keeps CAT's installation: its marketplace in {@code marketplace}, with the plugin in
 * {@code marketplace/plugin}, its cache in {@code cache}, the version folders in {@code versions} and the plugin data
 * in {@code data}. The {@link com.holdmyspot.cat.common.installation.contract.test.HarnessInstallationContract} runs
 * on it in {@link GenericInstallationContractTest}.
 *
 * @param home the absolute, normalized configuration directory
 */
public record GenericInstallation(Path home) implements HarnessInstallation
{
	/**
	 * Creates the layout.
	 *
	 * @param home the absolute configuration directory
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public GenericInstallation
	{
		if (!home.isAbsolute())
			throw new IllegalArgumentException("home must be absolute: " + home);
		home = home.normalize();
	}

	@Override
	public Path marketplaceRoot()
	{
		return home.resolve("marketplace");
	}

	@Override
	public Path marketplacePlugin()
	{
		return marketplaceRoot().resolve("plugin");
	}

	@Override
	public Path cacheRoot()
	{
		return home.resolve("cache");
	}

	@Override
	public Path versionFolders()
	{
		return home.resolve("versions");
	}

	@Override
	public Path dataDirectory()
	{
		return home.resolve("data");
	}
}
