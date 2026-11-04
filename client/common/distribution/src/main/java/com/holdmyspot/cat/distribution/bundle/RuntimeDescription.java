package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the files that describe a shared Java runtime: {@value #DESCRIPTION}, which names its Java version and
 * modules, and {@value #MANIFEST}, which holds the SHA-256 digest of every other file in the runtime.
 */
public final class RuntimeDescription
{
	/**
	 * The file in the runtime that names its Java version and modules.
	 */
	public static final String DESCRIPTION = "cat-runtime.json";
	/**
	 * The file in the runtime that holds the digest of every other file in the runtime.
	 */
	public static final String MANIFEST = "cat-runtime.manifest";
	/**
	 * The first line of the manifest.
	 */
	private static final String MANIFEST_HEADER = "CAT runtime manifest v1";
	/**
	 * The version of the description's format.
	 */
	private static final int DESCRIPTION_FORMAT = 1;
	/**
	 * The largest {@code release} file that the JDK writes, with ample room.
	 */
	private static final int MAXIMUM_RELEASE_BYTES = 65_536;
	private static final Pattern JAVA_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"([^\"]+)\"$");
	private static final Pattern MODULES = Pattern.compile("(?m)^MODULES=\"([^\"]+)\"$");

	/**
	 * Prevents instantiation.
	 */
	private RuntimeDescription()
	{
	}

	/**
	 * Writes the description and then the manifest of a runtime image, taking its Java version and modules from the
	 * {@code release} file that {@code jlink} writes.
	 *
	 * @param runtime the runtime image, which holds no symbolic links and which no other process changes meanwhile
	 * @param json    the JSON writer
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the {@code release} file lacks the Java version or the modules, the runtime holds
	 *                              a symbolic link, or a file cannot be read or written
	 */
	public static void write(Path runtime, JsonMapper json) throws IOException
	{
		requireThat(runtime, "runtime").isNotNull();
		requireThat(json, "json").isNotNull();
		Path releaseFile = runtime.resolve("release");
		String release = BoundedInput.readUtf8(releaseFile, MAXIMUM_RELEASE_BYTES, "Java runtime release file");
		String javaVersion = find(JAVA_VERSION, release, releaseFile);
		List<String> modules = Arrays.stream(find(MODULES, release, releaseFile).split(" ")).sorted().toList();

		ObjectNode description = json.createObjectNode();
		description.put("format", DESCRIPTION_FORMAT);
		description.put("java_version", javaVersion);
		description.put("java_feature", Runtime.Version.parse(javaVersion).feature());
		ArrayNode names = description.putArray("modules");
		modules.forEach(names::add);
		Files.writeString(runtime.resolve(DESCRIPTION), json.writeValueAsString(description) + "\n", UTF_8);
		writeManifest(runtime);
	}

	/**
	 * Returns the value that a {@code release} file assigns to a property.
	 *
	 * @param property    matches the property's line and captures its value
	 * @param release     the file's content
	 * @param releaseFile the file, named in the error message
	 * @return the value
	 * @throws IOException if the file does not assign the property
	 */
	private static String find(Pattern property, String release, Path releaseFile) throws IOException
	{
		Matcher matcher = property.matcher(release);
		if (!matcher.find())
			throw new IOException("The Java runtime's release file does not match " + property + ": " + releaseFile);
		return matcher.group(1);
	}

	/**
	 * Writes the manifest: its header, then one line per regular file except the manifest, holding the file's SHA-256
	 * digest, a tab and its path relative to the runtime with {@code /} separators, sorted by path.
	 *
	 * @param runtime the runtime image
	 * @throws IOException if the runtime holds a symbolic link, or a file cannot be read or written
	 */
	private static void writeManifest(Path runtime) throws IOException
	{
		Path manifest = runtime.resolve(MANIFEST);
		SortedMap<String, String> digests = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(runtime))
		{
			for (Path path : paths.toList())
			{
				if (Files.isSymbolicLink(path))
					throw new IOException("The Java runtime holds a symbolic link: " + path);
				if (path.equals(manifest) || !Files.isRegularFile(path, NOFOLLOW_LINKS))
					continue;
				String relative = runtime.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				digests.put(relative, sha256(path));
			}
		}
		StringBuilder content = new StringBuilder(MANIFEST_HEADER).append('\n');
		for (Map.Entry<String, String> digest : digests.entrySet())
			content.append(digest.getValue()).append('\t').append(digest.getKey()).append('\n');
		Files.writeString(manifest, content, UTF_8);
	}

	/**
	 * Calculates the SHA-256 digest of a file.
	 *
	 * @param file the file
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
			int read = input.read(buffer);
			while (read != -1)
			{
				digest.update(buffer, 0, read);
				read = input.read(buffer);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
