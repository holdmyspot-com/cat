package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materializes one edition-specific end-user tree from an assembled harness artifact.
 */
public final class EditionArtifactMaterializer
{
	/** Commercial-only launchers omitted from Personal releases. */
	public static final Set<String> COMMERCIAL_LAUNCHERS = Set.of("certification-contract", "certification",
		"certificate-history", "feedback", "certification-process");

	private static final Set<String> ALLOWED_ROOT_ENTRIES = Set.of(".claude-plugin", ".codex-plugin", "agents",
		"client", "hooks", "install", "licenses", "rules", "skills", "LICENSE.txt", "NOTICE.txt");
	private static final Set<String> FORBIDDEN_NAMES = Set.of(".git", ".github", ".mvn", "pom.xml", "build.gradle",
		"build.gradle.kts", "settings.gradle", "settings.gradle.kts", "Makefile", "mvnw", "mvnw.cmd");
	private static final Set<String> FORBIDDEN_SEGMENTS = Set.of("src", "test", "tests", "fixture", "fixtures",
		"target", "build", "reports", "logs");
	private static final Set<String> FORBIDDEN_SUFFIXES = Set.of(".java", ".kt", ".kts", ".groovy", ".gradle");
	private static final Set<String> PERSONAL_EXCLUDED_DIRECTORIES = Set.of("skills/prompt-optimizer",
		"skills/feedback");

	private final String version;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates an edition-tree materializer.
	 *
	 * @param version the exact CAT release version
	 */
	public EditionArtifactMaterializer(String version)
	{
		requireThat(version, "version").isNotBlank();
		this.version = version;
	}

	/**
	 * Creates and verifies one target directory below an edition's staged release root.
	 *
	 * @param target the exact edition, harness, and platform tuple
	 * @param assembledArtifact the complete assembled artifact for the target harness and platform
	 * @param editionRoot the staged root of one edition's release repository
	 * @return the materialized target directory
	 * @throws IOException if the input contains a forbidden path or the output cannot be created
	 */
	public Path materialize(ReleaseManifest.ReleaseTarget target, Path assembledArtifact, Path editionRoot)
		throws IOException
	{
		requireThat(target, "target").isNotNull();
		requireThat(assembledArtifact, "assembledArtifact").isNotNull();
		requireThat(editionRoot, "editionRoot").isNotNull();
		Path sourceRoot = assembledArtifact.toAbsolutePath().normalize();
		if (!Files.isDirectory(sourceRoot, NOFOLLOW_LINKS))
			throw new IOException("Assembled release input is not a directory: " + sourceRoot);
		validateInput(sourceRoot);

		Path normalizedEditionRoot = editionRoot.toAbsolutePath().normalize();
		Path output = normalizedEditionRoot.resolve(target.repositoryDirectory()).normalize();
		if (!output.startsWith(normalizedEditionRoot))
			throw new IOException("Release target escapes its edition root: " + target.repositoryDirectory());
		if (Files.exists(output, NOFOLLOW_LINKS))
			throw new IOException("Release target already exists: " + output);
		Files.createDirectories(output);
		copySelectedTree(target.edition(), sourceRoot, output);
		return output;
	}

	/**
	 * Writes repository-level file-integrity evidence for one edition without an independent signature.
	 *
	 * <p>Installers obtain this file from a pinned immutable Git tag. It lists every regular target file, except the
	 * manifest itself, so an installer can reject additions as well as changes.
	 *
	 * @param targetRoots materialized target roots belonging to the same edition repository
	 * @param editionRoot the edition repository root
	 * @return the written release manifest
	 * @throws IOException if a target is outside the edition root or the manifest cannot be written
	 */
	public Path writeReleaseManifest(Collection<Path> targetRoots, Path editionRoot) throws IOException
	{
		requireThat(targetRoots, "targetRoots").isNotEmpty();
		requireThat(editionRoot, "editionRoot").isNotNull();
		Path normalizedRoot = editionRoot.toAbsolutePath().normalize();
		ObjectNode manifest = json.createObjectNode();
		manifest.put("schema_version", "cat-release-v1");
		manifest.put("version", version);
		manifest.put("edition", normalizedRoot.getFileName().toString());
		ArrayNode files = manifest.putArray("files");
		List<Path> regularFiles = releaseFiles(targetRoots, normalizedRoot);
		for (Path file : regularFiles)
		{
			String relative = relativePath(normalizedRoot, file);
			ObjectNode entry = files.addObject();
			entry.put("path", relative);
			entry.put("target", targetPath(relative));
			entry.put("sha256", sha256(file));
			entry.put("size", Files.size(file));
		}
		Path manifestFile = normalizedRoot.resolve("release-manifest.json");
		json.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		return manifestFile;
	}

	/**
	 * Gets the sorted regular files contained by the complete release tree.
	 *
	 * @param targetRoots materialized target roots
	 * @param editionRoot the normalized edition root
	 * @return sorted regular files
	 * @throws IOException if a target cannot be inspected
	 */
	private static List<Path> releaseFiles(Collection<Path> targetRoots, Path editionRoot) throws IOException
	{
		for (String requiredFile : List.of("LICENSE.md", "README.md"))
		{
			Path file = editionRoot.resolve(requiredFile);
			if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
				throw new IOException("Release tree is missing required root file: " + requiredFile);
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(editionRoot))
		{
			for (Path path : paths.toList())
			{
				if (Files.isSymbolicLink(path))
					throw new IOException("Release tree contains a symbolic link: " + relativePath(editionRoot, path));
				if (Files.isRegularFile(path, NOFOLLOW_LINKS) && !path.getFileName().toString().equals("release-manifest.json"))
					files.add(path);
			}
		}
		for (Path targetRoot : targetRoots)
		{
			Path normalizedTarget = targetRoot.toAbsolutePath().normalize();
			if (!normalizedTarget.startsWith(editionRoot))
				throw new IOException("Release target is outside its edition root: " + targetRoot);
		}
		files.sort(Comparator.comparing(file -> relativePath(editionRoot, file)));
		return files;
	}

	/**
	 * Converts an edition-relative file path to the portable manifest representation.
	 *
	 * @param editionRoot the normalized edition root
	 * @param file the file below the root
	 * @return the slash-separated relative path
	 */
	private static String relativePath(Path editionRoot, Path file)
	{
		return editionRoot.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Gets the release-target directory which contains one manifest file.
	 *
	 * @param relativePath the portable edition-relative file path
	 * @return the harness and platform target path
	 * @throws IOException if a file does not belong to a target directory
	 */
	private static String targetPath(String relativePath) throws IOException
	{
		String[] segments = relativePath.split("/");
		if (segments.length == 1 && (segments[0].equals("LICENSE.md") || segments[0].equals("README.md")))
			return "root";
		if (segments.length < 3 || segments[0].isBlank() || segments[1].isBlank())
			throw new IOException("Release file is not below a harness and platform target: " + relativePath);
		return segments[0] + "/" + segments[1];
	}

	/**
	 * Validates every source path before any path is copied.
	 *
	 * @param root the assembled artifact root
	 * @throws IOException if a path is not an end-user release input
	 */
	private static void validateInput(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted().toList())
			{
				Path relative = root.relativize(path);
				if (relative.toString().isEmpty())
					continue;
				if (Files.isSymbolicLink(path))
					throw new IOException("Release input contains a symbolic link: " + relative);
				if (!Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS))
					throw new IOException("Release input contains an unsupported filesystem entry: " + relative);
				if (isForbiddenBuildOrSourcePath(relative))
					throw new IOException("Release input contains forbidden build or source material: " + relative);
				String first = relative.getName(0).toString();
				if (!ALLOWED_ROOT_ENTRIES.contains(first))
					throw new IOException("Release input contains an unlisted root entry: " + relative);
			}
		}
	}

	/**
	 * Determines whether a path names source, a test, or a build input rather than an end-user runtime file.
	 *
	 * @param relative the path relative to the assembled artifact
	 * @return {@code true} if the path is forbidden
	 */
	private static boolean isForbiddenBuildOrSourcePath(Path relative)
	{
		for (Path segment : relative)
		{
			String name = segment.toString();
			String lower = name.toLowerCase(Locale.ROOT);
			if (FORBIDDEN_NAMES.contains(name) || FORBIDDEN_SEGMENTS.contains(lower))
				return true;
			for (String suffix : FORBIDDEN_SUFFIXES)
			{
				if (lower.endsWith(suffix))
					return true;
			}
		}
		return false;
	}

	/**
	 * Copies the selected edition files and preserves safe runtime links.
	 *
	 * @param edition the selected edition
	 * @param sourceRoot the validated assembled artifact
	 * @param outputRoot the empty target directory
	 * @throws IOException if a selected path cannot be copied
	 */
	private static void copySelectedTree(ReleaseManifest.Edition edition, Path sourceRoot, Path outputRoot)
		throws IOException
	{
		try (Stream<Path> paths = Files.walk(sourceRoot))
		{
			for (Path source : paths.sorted().toList())
			{
				Path relative = sourceRoot.relativize(source);
				if (relative.toString().isEmpty() || isExcluded(edition, relative))
					continue;
				Path target = outputRoot.resolve(relative);
				if (Files.isDirectory(source, NOFOLLOW_LINKS))
					Files.createDirectories(target);
				else
				{
					Files.createDirectories(target.getParent());
					Files.copy(source, target, COPY_ATTRIBUTES);
				}
			}
		}
	}

	/**
	 * Determines whether an edition excludes one otherwise valid artifact path.
	 *
	 * @param edition the selected edition
	 * @param relative the artifact-relative path
	 * @return {@code true} if the path must be omitted
	 */
	private static boolean isExcluded(ReleaseManifest.Edition edition, Path relative)
	{
		if (edition != ReleaseManifest.Edition.PERSONAL)
			return false;
		String canonical = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
		for (String directory : PERSONAL_EXCLUDED_DIRECTORIES)
		{
			if (canonical.equals(directory) || canonical.startsWith(directory + "/"))
				return true;
		}
		return canonical.startsWith("client/bin/") && COMMERCIAL_LAUNCHERS.contains(relative.getFileName().toString());
	}

	/**
	 * Calculates one file's SHA-256 digest.
	 *
	 * @param file the regular file
	 * @return the lowercase hexadecimal digest
	 * @throws IOException if the file cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java implementation must provide SHA-256", e);
		}
		try (InputStream input = Files.newInputStream(file))
		{
			byte[] buffer = new byte[64 * 1024];
			for (int read = input.read(buffer); read != -1; read = input.read(buffer))
				digest.update(buffer, 0, read);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
