package com.holdmyspot.cat.common.updater.trust;

/**
 * What a release asset installs.
 */
public enum AssetKind
{
	/**
	 * The CAT plugin for one harness, named {@code cat-<harness>-v<version>-<platform>.tar.gz}.
	 */
	PLUGIN,
	/**
	 * The trusted updater, named {@code cat-updater-v<version>-<platform>.tar.gz}. It belongs to no harness.
	 */
	UPDATER
}
