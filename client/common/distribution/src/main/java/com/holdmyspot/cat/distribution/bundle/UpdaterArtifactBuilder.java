package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
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
 * Completes the trusted updater's runtime image with its launchers, default policy and legal materials.
 * The caller supplies an image that no other thread or process modifies during assembly.
 */
public final class UpdaterArtifactBuilder
{
	/**
	 * The directory in the runtime image containing the default policy that setup reads.
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
	 * Release launchers are executable by their owner and by users allowed to read the distributed image.
	 */
	private static final Set<PosixFilePermission> LAUNCHER_PERMISSIONS =
		Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x"));
	/**
	 * The image-root file from which setup obtains its updater version.
	 */
	private static final String VERSION_FILE = "VERSION";
	/**
	 * The updater executable's base name on every platform.
	 */
	private static final String LAUNCHER_NAME = "cat-updater";
	private final JvmScope scope;
	private final String version;
	private final TrustPolicy policy;

	/**
	 * Creates an assembler for one release's updater.
	 *
	 * @param scope     the build's waiting policy for atomic file replacement
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
	 * Adds the updater's distributable files to a newly built runtime image.
	 *
	 * @param image          the existing runtime image, modified in place
	 * @param updaterJar     the updater module JAR containing its versioned launcher templates
	 * @param legalMaterials the directory containing the generated release license, notices and licenses directory
	 * @return the completed runtime image
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if a launcher template is absent or the artifact cannot be written
	 */
	public Path build(Path image, Path updaterJar, Path legalMaterials) throws IOException
	{
		requireThat(image, "image").isDirectory();
		requireThat(updaterJar, "updaterJar").isRegularFile();
		requireThat(legalMaterials, "legalMaterials").isDirectory();
		Path configuration = Files.createDirectories(image.resolve(CONFIGURATION_DIRECTORY));
		policy.write(configuration.resolve(DEFAULT_POLICY), FileReplacer.retrying(scope.waiting()));
		Files.writeString(image.resolve(VERSION_FILE), version + "\n");
		Path bin = Files.createDirectories(image.resolve("bin"));
		try (JarFile jar = new JarFile(updaterJar.toFile()))
		{
			copyLauncher(jar, "versioned-launcher.sh", bin.resolve(LAUNCHER_NAME));
			copyLauncher(jar, "versioned-launcher.cmd", bin.resolve(LAUNCHER_NAME + ".cmd"));
		}
		copyTree(legalMaterials, image);
		return image;
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
	 * Copies release legal materials into the runtime image without following symbolic links.
	 *
	 * @param source      the legal materials directory
	 * @param destination the runtime image
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
