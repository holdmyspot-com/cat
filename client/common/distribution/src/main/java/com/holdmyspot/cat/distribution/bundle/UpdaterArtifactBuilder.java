package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Writes the trusted updater's application directory: its version, modules, launchers, default policy, runtime
 * requirement and legal materials. No other thread or process modifies the directory during assembly.
 */
public final class UpdaterArtifactBuilder
{
	/**
	 * The directory of the application directory containing the default policy that setup reads.
	 */
	private static final Path CONFIGURATION_DIRECTORY = Path.of("conf/cat-updater");
	/**
	 * The file from which setup obtains the release keys and approval limits.
	 */
	private static final String DEFAULT_POLICY = "default-policy.json";
	/**
	 * Launcher templates in the updater module's resource namespace.
	 */
	private static final String LAUNCHER_RESOURCES = "com/holdmyspot/cat/common/updater/cli/";
	/**
	 * Release launchers are executable by their owner and by users allowed to read the distributed application.
	 */
	private static final Set<PosixFilePermission> LAUNCHER_PERMISSIONS =
		Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x"));
	/**
	 * The file of the application directory from which setup obtains its updater version.
	 */
	private static final String VERSION_FILE = "VERSION";
	/**
	 * The updater executable's base name on every platform.
	 */
	private static final String LAUNCHER_NAME = "cat-updater";
	/**
	 * The file of the application directory that names the Java runtime that the updater needs.
	 */
	private static final String RUNTIME_REQUIREMENT = "runtime-requirement.json";
	private final JvmScope scope;
	private final String version;
	private final TrustPolicy policy;

	/**
	 * Creates an assembler for one release's updater.
	 *
	 * @param scope     the build's waiting policy for atomic file replacement and its JSON writer
	 * @param version   the release version, consisting of two or three decimal numbers
	 * @param publicKey the base64 X.509 Ed25519 release key, or empty for a development build that trusts no key
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the version or release key is invalid
	 */
	public UpdaterArtifactBuilder(JvmScope scope, String version, String publicKey) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(publicKey, "publicKey").isNotNull();
		try
		{
			ReleaseVersion release = ReleaseVersion.parse(version);
			List<TrustedKey> keys = List.of();
			if (!publicKey.isEmpty())
				keys = List.of(TrustedKey.decode("cat-release", publicKey));
			policy = TrustPolicy.of(keys, URI.create("https://github.com/holdmyspot-com/cat/releases/download"),
				release, release.major(), Map.of());
		}
		catch (TrustException e)
		{
			throw new IOException("Cannot generate the updater's default policy: " + e.getMessage(), e);
		}
		this.scope = scope;
		this.version = version;
	}

	/**
	 * Writes the updater's application directory, which holds no Java runtime: the launchers run the shared runtime in
	 * the sibling directory {@code runtime}, both in the extracted release archive and once installed.
	 *
	 * @param versionDirectory the new directory that receives the application, named after its version
	 * @param updaterJar       the updater module JAR containing its versioned launcher templates
	 * @param application      the updater's modules, whose JARs it runs from {@code app/} and whose system modules
	 *                         form its runtime requirement
	 * @param legalMaterials   the directory containing the generated release license, notices and licenses directory
	 * @return {@code versionDirectory}
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if {@code versionDirectory} exists, a launcher template is absent or the application
	 *                              cannot be written
	 */
	public Path build(Path versionDirectory, Path updaterJar, ApplicationModules application, Path legalMaterials)
		throws IOException
	{
		requireThat(versionDirectory, "versionDirectory").isNotNull();
		requireThat(updaterJar, "updaterJar").isRegularFile();
		requireThat(application, "application").isNotNull();
		requireThat(legalMaterials, "legalMaterials").isDirectory();
		if (Files.exists(versionDirectory, NOFOLLOW_LINKS))
			throw new IOException("The updater's application directory already exists: " + versionDirectory);
		Files.createDirectories(versionDirectory);
		Files.writeString(versionDirectory.resolve(VERSION_FILE), version + "\n");
		Path app = Files.createDirectories(versionDirectory.resolve("app"));
		for (Path jar : application.jars())
			Files.copy(jar, app.resolve(jar.getFileName()));
		Path configuration = Files.createDirectories(versionDirectory.resolve(CONFIGURATION_DIRECTORY));
		policy.write(configuration.resolve(DEFAULT_POLICY), FileReplacer.retrying(scope.waiting()));
		application.writeRequirement(versionDirectory.resolve(RUNTIME_REQUIREMENT), scope.json());
		Path bin = Files.createDirectories(versionDirectory.resolve("bin"));
		try (JarFile jar = new JarFile(updaterJar.toFile()))
		{
			copyLauncher(jar, "versioned-launcher.sh", bin.resolve(LAUNCHER_NAME));
			copyLauncher(jar, "versioned-launcher.ps1", bin.resolve(LAUNCHER_NAME + ".ps1"));
		}
		copyTree(legalMaterials, Files.createDirectories(versionDirectory.resolve("legal")));
		return versionDirectory;
	}

	/**
	 * Copies one launcher's template without interpreting its shell syntax.
	 *
	 * @param jar         the updater's resource JAR
	 * @param resource    the template's name
	 * @param destination the packaged launcher's path
	 * @throws IOException if the resource is missing or the launcher cannot be written or made executable
	 */
	private static void copyLauncher(JarFile jar, String resource, Path destination) throws IOException
	{
		JarEntry entry = jar.getJarEntry(LAUNCHER_RESOURCES + resource);
		if (entry == null)
			throw new IOException("The updater JAR has no launcher template: " + resource);
		try (InputStream input = jar.getInputStream(entry))
		{
			Files.copy(input, destination, REPLACE_EXISTING);
		}
		PosixFileAttributeView attributes = Files.getFileAttributeView(destination, PosixFileAttributeView.class);
		if (attributes != null)
			attributes.setPermissions(LAUNCHER_PERMISSIONS);
	}

	/**
	 * Copies release legal materials without following symbolic links.
	 *
	 * @param source      the legal materials directory
	 * @param destination the directory that receives them
	 * @throws IOException if the files cannot be copied
	 */
	private static void copyTree(Path source, Path destination) throws IOException
	{
		Files.walkFileTree(source, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
			{
				Files.createDirectories(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Path target = destination.resolve(source.relativize(file));
				if (Files.isSymbolicLink(file))
					Files.createSymbolicLink(target, Files.readSymbolicLink(file));
				else
					Files.copy(file, target, COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
