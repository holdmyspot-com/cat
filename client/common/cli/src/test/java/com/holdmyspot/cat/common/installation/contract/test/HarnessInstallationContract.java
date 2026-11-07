package com.holdmyspot.cat.common.installation.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks that a {@link HarnessInstallation} places CAT's folders inside the harness home as the installer and the
 * runners rely on: the version folders are named after their versions, the harness's own copies of a version are read
 * back as that version, and the data directory lies outside every version's folders so that it outlasts them.
 */
public final class HarnessInstallationContract
{
	/**
	 * The version that the checks name folders after.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.2+b0123456789ab");
	/**
	 * A name that names no plugin version in any folder.
	 */
	private static final String NOT_A_VERSION = "notes";

	/**
	 * The installation under contract and how its harness names its copies.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one installation layout.
	 *
	 * @param subject the installation under contract and how its harness names its copies
	 */
	public HarnessInstallationContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that an installation's home is the absolute directory that it was created for, normalized.
	 *
	 * @param home an absolute directory
	 */
	public void keepsNormalizedHome(Path home)
	{
		HarnessInstallation installation = subject.installation(home.resolve("harness/../harness"));

		requireThat(installation.home(), "home").isEqualTo(home.resolve("harness"));
	}

	/**
	 * Verifies that every folder lies inside the home and that the marketplace plugin lies inside the marketplace.
	 *
	 * @param home an absolute directory
	 */
	public void placesFoldersInsideHome(Path home)
	{
		HarnessInstallation installation = subject.installation(home);

		Map<String, Path> folders = Map.of("marketplaceRoot", installation.marketplaceRoot(), "marketplacePlugin",
			installation.marketplacePlugin(), "cacheRoot", installation.cacheRoot(), "versionFolders",
			installation.versionFolders(), "dataDirectory", installation.dataDirectory());
		for (Map.Entry<String, Path> folder : folders.entrySet())
		{
			requireThat(folder.getValue().startsWith(home), folder.getKey() + "InsideHome").isTrue();
			requireThat(folder.getValue(), folder.getKey()).isNotEqualTo(home);
		}
		requireThat(installation.marketplacePlugin().startsWith(installation.marketplaceRoot()),
			"pluginInsideMarketplace").isTrue();
	}

	/**
	 * Verifies that a version's folder is named after the version, that the version folders come first among the
	 * folders of copies, and that a child of the version folders is read back as the version that it is named after.
	 *
	 * @param home an absolute directory
	 */
	public void namesVersionFolders(Path home)
	{
		HarnessInstallation installation = subject.installation(home);
		Path folders = installation.versionFolders();

		requireThat(installation.versionFolder(VERSION), "versionFolder").
			isEqualTo(folders.resolve(VERSION.toString()));
		requireThat(installation.versionCopyFolders().getFirst(), "firstCopyFolder").isEqualTo(folders);
		requireThat(installation.copiedVersion(folders, VERSION.toString()), "copiedVersion").
			isEqualTo(Optional.of(VERSION));
		requireThat(installation.copiedVersion(folders, NOT_A_VERSION), "notAVersion").isEmpty();
	}

	/**
	 * Verifies that the harness's own copy of a version, in each folder of copies after the version folders, is read
	 * back as that version, and that another name is read as no version.
	 *
	 * @param home an absolute directory
	 */
	public void readsHarnessCopies(Path home)
	{
		HarnessInstallation installation = subject.installation(home);
		List<Path> folders = installation.versionCopyFolders();

		for (Path folder : folders.subList(1, folders.size()))
		{
			requireThat(folder.startsWith(home), "copyFolderInsideHome").isTrue();
			requireThat(installation.copiedVersion(folder, subject.copyName(installation, folder, VERSION)),
				"copiedVersion").isEqualTo(Optional.of(VERSION));
			requireThat(installation.copiedVersion(folder, NOT_A_VERSION), "notAVersion").isEmpty();
		}
	}

	/**
	 * Verifies that the data directory lies outside the marketplace and the version folders, which change with each
	 * version, and that the installer's policy, state and release cache lie in it.
	 *
	 * @param home an absolute directory
	 */
	public void keepsDataApart(Path home)
	{
		HarnessInstallation installation = subject.installation(home);
		Path data = installation.dataDirectory();

		requireThat(data.startsWith(installation.marketplaceRoot()), "dataInMarketplace").isFalse();
		for (Path folder : installation.versionCopyFolders())
			requireThat(data.startsWith(folder), "dataInCopies").isFalse();
		requireThat(installation.policy(), "policy").isEqualTo(data.resolve("policy.json"));
		requireThat(installation.stateDirectory(), "stateDirectory").isEqualTo(data.resolve("state"));
		requireThat(installation.releases(), "releases").isEqualTo(data.resolve("releases"));
	}

	/**
	 * An installation layout under contract and how its harness names its own copies of a version.
	 */
	public interface Subject
	{
		/**
		 * Returns the installation at a home.
		 *
		 * @param home the absolute harness home
		 * @return the installation
		 */
		HarnessInstallation installation(Path home);

		/**
		 * Returns the name under which the harness keeps its own copy of a version in a folder of copies.
		 *
		 * @param installation the installation
		 * @param folder       one of the installation's folders of copies after the version folders
		 * @param version      the version
		 * @return the copy's name
		 */
		String copyName(HarnessInstallation installation, Path folder, PluginVersion version);
	}
}
