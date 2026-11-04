package com.holdmyspot.cat.common.installer.trust;

/**
 * What a release asset installs.
 */
public enum AssetKind
{
	/**
	 * The CAT plugin for one harness, named {@code cat-<harness>-<platform>-v<version>.tar.gz}.
	 */
	PLUGIN,
	/**
	 * The trusted installer, named {@code cat-updater-v<version>-<platform>.tar.gz}. It belongs to no harness.
	 */
	INSTALLER
}
