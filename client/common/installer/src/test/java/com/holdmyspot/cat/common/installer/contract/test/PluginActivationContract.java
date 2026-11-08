package com.holdmyspot.cat.common.installer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks that a {@link PluginActivation} reads which version its harness loads, and lists what uninstall deletes, as
 * the installer relies on: a home without CAT reports no version, a version that the harness installed is reported
 * consistently, a partial installation is reported as inconsistent, and deleting the listed paths leaves the data
 * directory and no installation.
 */
public final class PluginActivationContract
{
	/**
	 * The first version that the checks install.
	 */
	private static final PluginVersion VERSION = PluginVersion.parse("1.0+b0123456789ab");
	/**
	 * The version that replaces {@link #VERSION}.
	 */
	private static final PluginVersion NEXT = PluginVersion.parse("1.1+bfedcba987654");
	/**
	 * The form of a harness identifier.
	 */
	private static final Pattern HARNESS = Pattern.compile("[a-z][a-z0-9-]{0,31}");

	/**
	 * The activation under contract and how its harness installs a version.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one activation.
	 *
	 * @param subject the activation under contract and how its harness installs a version
	 */
	public PluginActivationContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that the harness identifier has the documented form and that the plugin manifest is a relative path
	 * written with {@code /}.
	 */
	public void namesHarness()
	{
		PluginActivation activation = subject.activation();

		requireThat(HARNESS.matcher(activation.harness()).matches(), "harnessForm").
			withContext(activation.harness(), "harness").isTrue();
		String manifest = activation.pluginManifest();
		requireThat(manifest, "manifest").isNotBlank().doesNotStartWith("/").doesNotContain("\\");
		requireThat(Path.of(manifest).isAbsolute(), "manifestAbsolute").isFalse();
	}

	/**
	 * Verifies that a home in which the harness never installed CAT reports no version, consistently.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be read
	 */
	public void freshHomeHasNoVersion(Path home) throws IOException
	{
		requireThat(subject.activation().installed(home), "installed").
			isEqualTo(new InstalledState(Optional.empty(), true));
	}

	/**
	 * Verifies that the version that the harness installed last is reported, consistently.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be written or read
	 */
	public void readsInstalledVersion(Path home) throws IOException
	{
		PluginActivation activation = subject.activation();

		subject.install(home, VERSION);
		requireThat(activation.installed(home), "first").
			isEqualTo(new InstalledState(Optional.of(VERSION.toString()), true));
		subject.install(home, NEXT);
		requireThat(activation.installed(home), "next").
			isEqualTo(new InstalledState(Optional.of(NEXT.toString()), true));
	}

	/**
	 * Verifies that an installation whose version folder was deleted is reported as inconsistent.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be written or read
	 */
	public void missingVersionFolderIsInconsistent(Path home) throws IOException
	{
		subject.install(home, VERSION);
		TemporaryDirectories.deleteRecursively(subject.installation(home).versionFolder(VERSION));

		requireThat(subject.activation().installed(home).consistent(), "consistent").isFalse();
	}

	/**
	 * Verifies that a marketplace that the harness left without an installed version is reported as inconsistent.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be written or read
	 */
	public void leftoverMarketplaceIsInconsistent(Path home) throws IOException
	{
		Files.createDirectories(subject.installation(home).marketplaceRoot());

		requireThat(subject.activation().installed(home), "installed").
			isEqualTo(new InstalledState(Optional.empty(), false));
	}

	/**
	 * Verifies that the data directory is the installation's and holds the data version file.
	 *
	 * @param home an empty absolute directory
	 */
	public void keepsDataInDataDirectory(Path home)
	{
		PluginActivation activation = subject.activation();
		Path data = activation.dataDirectory(home);

		requireThat(data, "data").isEqualTo(subject.installation(home).dataDirectory());
		Path versionFile = activation.migrationVersionFile(home);
		requireThat(versionFile.isAbsolute(), "versionFileAbsolute").isTrue();
		requireThat(versionFile.getParent(), "versionFileFolder").isEqualTo(data);
	}

	/**
	 * Verifies that the removal paths of an installed version lie under the home and outside the data directory, and
	 * that deleting them once the harness unregistered CAT leaves no installation and keeps the data.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be written or read
	 */
	public void removalLeavesNoInstallation(Path home) throws IOException
	{
		PluginActivation activation = subject.activation();
		subject.install(home, VERSION);
		Path data = activation.dataDirectory(home);
		Path kept = Files.writeString(Files.createDirectories(data).resolve("kept"), "data");

		subject.uninstall(home);
		List<Path> paths = activation.removalPaths(home);
		for (Path path : paths)
		{
			requireThat(path, "path").isEqualTo(path.normalize());
			requireThat(path.startsWith(home), "underHome").withContext(path, "path").isTrue();
			requireThat(path, "path").isNotEqualTo(home);
			requireThat(data.startsWith(path), "holdsData").withContext(path, "path").isFalse();
		}
		for (Path path : paths)
			TemporaryDirectories.deleteRecursively(path);

		requireThat(activation.installed(home), "installed").isEqualTo(new InstalledState(Optional.empty(), true));
		requireThat(Files.exists(kept, LinkOption.NOFOLLOW_LINKS), "dataKept").isTrue();
	}

	/**
	 * Verifies that the companion files of an installed version can be installed again.
	 *
	 * @param home an empty absolute directory
	 * @throws IOException if the home cannot be written or read
	 */
	public void installsCompanionsAgain(Path home) throws IOException
	{
		PluginActivation activation = subject.activation();
		subject.install(home, VERSION);
		Path versionFolder = subject.installation(home).versionFolder(VERSION);

		activation.installCompanions(home, versionFolder);
		activation.installCompanions(home, versionFolder);

		requireThat(activation.installed(home), "installed").
			isEqualTo(new InstalledState(Optional.of(VERSION.toString()), true));
	}

	/**
	 * The activation under contract and how its harness installs a version.
	 */
	public interface Subject
	{
		/**
		 * Returns the activation under contract.
		 *
		 * @return the activation
		 */
		PluginActivation activation();

		/**
		 * Returns where the activation's harness keeps CAT's installation.
		 *
		 * @param home the absolute harness home
		 * @return the installation
		 */
		HarnessInstallation installation(Path home);

		/**
		 * Installs a version as install does: it writes the plugin and has the harness install it.
		 *
		 * @param home    the absolute harness home
		 * @param version the version
		 * @throws IOException if the version cannot be installed
		 */
		void install(Path home, PluginVersion version) throws IOException;

		/**
		 * Has the harness unregister CAT, keeping its data, as uninstall does before it deletes the removal paths.
		 *
		 * @param home the absolute harness home
		 * @throws IOException if CAT cannot be unregistered
		 */
		void uninstall(Path home) throws IOException;
	}
}
