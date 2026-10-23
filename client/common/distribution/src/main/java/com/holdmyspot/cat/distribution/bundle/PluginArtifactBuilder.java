package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.MarkdownIncludes;
import com.holdmyspot.cat.common.SourceOnlyContent;
import com.holdmyspot.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Assembles the installable directory for one harness's CAT plugin.
 */
public final class PluginArtifactBuilder
{
	private static final int MAXIMUM_PROMPT_FILE_BYTES = 1_048_576;
	private final String version;

	/**
	 * Creates an artifact builder.
	 *
	 * @param version the CAT version
	 * @throws NullPointerException if {@code version} is null
	 * @throws IllegalArgumentException if {@code version} is blank
	 */
	public PluginArtifactBuilder(String version)
	{
		requireThat(version, "version").isNotBlank();
		this.version = version;
	}

	/**
	 * Builds a plugin artifact from its resources, Java runtime, generated legal materials, and installer scripts.
	 *
	 * @param resourceLayers resource JARs extracted into the artifact in list order; a later JAR can replace an earlier
	 *        resource only when its {@link ArtifactLayer#overrides()} declares the path
	 * @param runtimeDirectory the jlink-generated Java runtime directory copied to {@code client/}
	 * @param agentLaunchers the names of the runtime's launchers that agents run, which move from {@code client/bin/}
	 *        to {@code bin/}; the other launchers, such as the harness's hooks, stay in {@code client/bin/} with
	 *        {@code java}
	 * @param legalMaterialsDirectory the directory containing generated {@code LICENSE.txt}, {@code NOTICE.txt}, and
	 *        {@code licenses/} copied to the top level of the assembled plugin directory
	 * @param installerDirectories ordered directories of common and harness-specific installer resources copied to
	 *        {@code install/}
	 * @param artifactDirectory the new directory that receives the assembled artifact; unlike
	 *        {@code installerDirectories}, it is an output rather than an input
	 * @return {@code artifactDirectory}
	 * @throws NullPointerException if {@code resourceLayers}, {@code runtimeDirectory}, {@code agentLaunchers},
	 *         {@code legalMaterialsDirectory}, {@code installerDirectories}, or {@code artifactDirectory} are null
	 * @throws IOException if the artifact cannot be assembled, or the runtime lacks one of {@code agentLaunchers}
	 */
	public Path build(List<ArtifactLayer> resourceLayers, Path runtimeDirectory, Set<String> agentLaunchers,
		Path legalMaterialsDirectory, List<Path> installerDirectories, Path artifactDirectory)
		throws IOException
	{
		requireThat(resourceLayers, "resourceLayers").isNotNull();
		requireThat(runtimeDirectory, "runtimeDirectory").isNotNull();
		requireThat(agentLaunchers, "agentLaunchers").isNotNull();
		requireThat(legalMaterialsDirectory, "legalMaterialsDirectory").isNotNull();
		requireThat(installerDirectories, "installerDirectories").isNotNull();
		requireThat(artifactDirectory, "artifactDirectory").isNotNull();
		if (Files.exists(artifactDirectory, NOFOLLOW_LINKS))
			throw new IOException("Artifact output already exists: " + artifactDirectory);
		Files.createDirectories(artifactDirectory);

		Map<String, String> owners = new HashMap<>();
		for (ArtifactLayer layer : resourceLayers)
			extractLayer(layer, artifactDirectory, owners);
		resolvePromptIncludes(artifactDirectory);
		Path client = artifactDirectory.resolve("client");
		copyTree(runtimeDirectory, client);
		JlinkImageBuilder.moveLaunchers(agentLaunchers, client, artifactDirectory.resolve("bin"));
		Files.writeString(client.resolve("VERSION"), version + "\n");
		copyTree(legalMaterialsDirectory, artifactDirectory);
		Path installDirectory = artifactDirectory.resolve("install");
		for (Path installerDirectory : installerDirectories)
			copyTree(installerDirectory, installDirectory);
		RunnerRuntimeManifest.write(artifactDirectory);
		return artifactDirectory;
	}

	/**
	 * Removes source-only content from a staged prompt bundle and expands its agent and skill includes.
	 *
	 * <p>
	 * Prompt-only repackaging copies current model-facing resources into a staging directory without rebuilding the
	 * Java runtime. It must still apply the same steps as a complete artifact build so a harness never receives
	 * design goals, maintainer comments, source-only bodies or unresolved directives. Source-only content is removed
	 * before includes expand, while each file's own headings still delimit it.
	 *
	 * @param artifactDirectory the staged plugin directory containing agents and skills
	 * @throws NullPointerException if {@code artifactDirectory} is null
	 * @throws IOException if a Markdown file contains a comment that never ends, or an include is ambiguous, unsafe,
	 *         or unreadable
	 */
	public static void resolvePromptIncludes(Path artifactDirectory) throws IOException
	{
		requireThat(artifactDirectory, "artifactDirectory").isNotNull();
		SourceOnlyContent.removeFromTree(artifactDirectory);
		resolveSkillIncludes(artifactDirectory);
		resolveAgentIncludes(artifactDirectory);
	}

	/**
	 * Expands one skill-body include in each assembled skill and removes the source-only body files.
	 *
	 * @param artifactDirectory the assembled plugin directory
	 * @throws IOException if a skill include is ambiguous, unsafe, or unreadable
	 */
	private static void resolveSkillIncludes(Path artifactDirectory) throws IOException
	{
		Path skills = artifactDirectory.resolve("skills");
		if (!Files.isDirectory(skills, NOFOLLOW_LINKS))
			return;
		Set<Path> sourceOnlyBodies = new HashSet<>();
		try (Stream<Path> paths = Files.walk(skills))
		{
			for (Path skill : paths.filter(path -> path.getFileName().toString().equals("SKILL.md")).sorted().toList())
			{
				String template = BoundedInput.readUtf8(skill, MAXIMUM_PROMPT_FILE_BYTES, "Skill template");
				MatchResult include = findOnlyInclude(template, skill, "Skill");
				if (include == null)
					continue;
				Path body = MarkdownIncludes.resolve(skill, include.group(1), "Skill include", skills);
				String expanded = template.substring(0, include.start()) + BoundedInput.readUtf8(body,
					MAXIMUM_PROMPT_FILE_BYTES, "Skill body") +
					template.substring(include.end());
				Files.writeString(skill, expanded);
				sourceOnlyBodies.add(body);
			}
		}
		for (Path sourceOnlyBody : sourceOnlyBodies)
			Files.delete(sourceOnlyBody);
	}

	/**
	 * Expands shared agent bodies into each harness-specific agent and removes the source-only common files.
	 *
	 * @param artifactDirectory the assembled plugin directory
	 * @throws IOException if an agent include is ambiguous, unsafe, or unreadable
	 */
	private static void resolveAgentIncludes(Path artifactDirectory) throws IOException
	{
		Path agents = artifactDirectory.resolve("agents");
		Path common = agents.resolve("common");
		if (!Files.isDirectory(agents, NOFOLLOW_LINKS) || !Files.isDirectory(common, NOFOLLOW_LINKS))
			return;
		try (Stream<Path> paths = Files.walk(agents))
		{
			for (Path agent : paths.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).
				filter(path -> !path.startsWith(common)).sorted().toList())
				Files.writeString(agent, expandAgentIncludes(agent, agents, artifactDirectory, new HashSet<>()));
		}
		try (Stream<Path> paths = Files.walk(common))
		{
			for (Path sourceOnlyFile : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(sourceOnlyFile);
		}
	}

	/**
	 * Expands the one shared-body include in an agent instruction recursively.
	 *
	 * @param agent the instruction or shared body to expand
	 * @param agents the agent root
	 * @param artifactDirectory the assembled plugin root
	 * @param expanding files being expanded, used to reject cycles
	 * @return the expanded instruction
	 * @throws IOException if an include is ambiguous, unsafe, unreadable, or cyclic
	 */
	private static String expandAgentIncludes(Path agent, Path agents, Path artifactDirectory, Set<Path> expanding)
		throws IOException
	{
		Path normalized = agent.normalize();
		if (!expanding.add(normalized))
			throw new IOException("Cyclic agent include: " + normalized);
		String template = BoundedInput.readUtf8(normalized, MAXIMUM_PROMPT_FILE_BYTES, "Agent instruction");
		MatchResult include = findOnlyInclude(template, normalized, "Agent");
		if (include == null)
		{
			expanding.remove(normalized);
			return template;
		}
		Path rules = artifactDirectory.resolve("rules");
		Path body = MarkdownIncludes.resolve(normalized, include.group(1), "Agent include", agents, rules);
		String expanded = template.substring(0, include.start()) +
			expandAgentIncludes(body, agents, artifactDirectory, expanding) + template.substring(include.end());
		expanding.remove(normalized);
		return expanded;
	}

	/**
	 * Finds the include directive of a prompt file that may contain at most one.
	 *
	 * @param template the prompt file's content
	 * @param file the prompt file, named in the error message
	 * @param kind the kind of prompt file named in the error message, such as {@code "Skill"}
	 * @return the directive, or {@code null} if {@code template} contains none
	 * @throws IOException if {@code template} contains more than one directive
	 */
	private static MatchResult findOnlyInclude(String template, Path file, String kind) throws IOException
	{
		Matcher matcher = MarkdownIncludes.DIRECTIVE.matcher(template);
		if (!matcher.find())
			return null;
		MatchResult include = matcher.toMatchResult();
		if (matcher.find())
			throw new IOException(kind + " contains more than one cat:include directive: " + file);
		return include;
	}

	/**
	 * Extracts a resource layer into an artifact, replacing an earlier layer's path only when this layer declares that
	 * path as an override.
	 *
	 * @param layer the layer to extract
	 * @param output the artifact output directory
	 * @param owners maps each resource path relative to the assembled plugin directory to the name of the layer that
	 *        last copied it
	 * @throws IOException if the layer cannot be extracted
	 */
	private static void extractLayer(ArtifactLayer layer, Path output, Map<String, String> owners) throws IOException
	{
		try (JarFile jar = new JarFile(layer.jar().toFile()))
		{
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements())
			{
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.startsWith("META-INF/"))
					continue;
				Path artifactPath = parseJarEntryExtractionPath(layer, name);
				String canonicalName = artifactPath.toString().replace(artifactPath.getFileSystem().getSeparator(), "/");
				Path destination = output.resolve(artifactPath);
				if (entry.isDirectory())
				{
					Files.createDirectories(destination);
					continue;
				}
				String previousOwner = owners.putIfAbsent(canonicalName, layer.name());
				if (previousOwner != null)
				{
					if (!layer.overrides().contains(canonicalName))
					{
						throw new IOException("Resource collision at " + canonicalName + " while applying layer " + layer.name() +
							"; already supplied by " + previousOwner);
					}
					owners.put(canonicalName, layer.name());
				}
				Files.createDirectories(destination.getParent());
				try (InputStream input = jar.getInputStream(entry))
				{
					Files.copy(input, destination, REPLACE_EXISTING);
				}
				if (layer.executables().contains(canonicalName))
					makeExecutable(destination);
			}
		}
	}

	/**
	 * Converts a resource JAR entry name into its destination path inside the assembled plugin directory.
	 *
	 * <p>
	 * Accepts a non-empty, forward-slash-separated relative name, optionally ending in {@code /}. Rejects absolute
	 * names, backslashes, empty segments, and {@code .}/{@code ..} segments so extraction cannot create files outside
	 * the plugin directory.
	 *
	 * @param layer the resource layer that contains the entry
	 * @param name the resource JAR entry name
	 * @return the entry's destination path relative to the plugin directory
	 * @throws IOException if the entry cannot be extracted inside the plugin directory
	 */
	private static Path parseJarEntryExtractionPath(ArtifactLayer layer, String name) throws IOException
	{
		if (name.isBlank())
			throw invalidJarEntry(layer, name, "entry names must not be empty");
		if (name.startsWith("/") || name.startsWith("\\"))
			throw invalidJarEntry(layer, name, "entry names must be relative to the plugin directory, not absolute");
		if (name.contains("\\"))
			throw invalidJarEntry(layer, name, "entry names must use forward slashes instead of backslashes");
		String candidate = name;
		if (name.endsWith("/"))
			candidate = name.substring(0, name.length() - 1);
		for (String segment : candidate.split("/", -1))
		{
			if (segment.isEmpty())
				throw invalidJarEntry(layer, name, "entry names must not contain an empty path segment");
			if (segment.equals("."))
				throw invalidJarEntry(layer, name, "entry names must not contain \".\" path segments");
			if (segment.equals(".."))
				throw invalidJarEntry(layer, name, "entry names must not contain \"..\" path segments");
		}
		try
		{
			return Path.of(candidate);
		}
		catch (InvalidPathException _)
		{
			throw invalidJarEntry(layer, name, "entry names must be valid filesystem paths");
		}
	}

	/**
	 * Creates an actionable error for an entry that cannot be extracted into the plugin directory.
	 *
	 * @param layer the resource layer that contains the entry
	 * @param name the invalid resource JAR entry name
	 * @param reason the failed entry-name constraint
	 * @return the extraction failure
	 */
	private static IOException invalidJarEntry(ArtifactLayer layer, String name, String reason)
	{
		return new IOException("Resource layer \"" + layer.name() + "\" contains invalid JAR entry \"" + name +
			"\": " + reason + ". Rebuild or replace this resource JAR with a relative entry name.");
	}

	/**
	 * Adds execute permissions for every POSIX principal when supported.
	 *
	 * @param path the file to make executable
	 * @throws IOException if permissions cannot be read or written
	 */
	private static void makeExecutable(Path path) throws IOException
	{
		try
		{
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS);
			permissions.add(OWNER_EXECUTE);
			permissions.add(GROUP_EXECUTE);
			permissions.add(OTHERS_EXECUTE);
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (UnsupportedOperationException _)
		{
			// CAT distributions target POSIX filesystems; other filesystems retain their default permissions.
		}
	}

	/**
	 * Copies a directory tree without following symbolic links.
	 *
	 * @param source the source directory
	 * @param destination the destination directory
	 * @throws IOException if the tree cannot be copied
	 */
	private static void copyTree(Path source, Path destination) throws IOException
	{
		if (!Files.isDirectory(source, NOFOLLOW_LINKS))
			throw new IOException("Copy source is not a directory: " + source);
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.sorted().toList())
			{
				Path target = destination.resolve(source.relativize(path));
				if (Files.isSymbolicLink(path))
				{
					Files.createDirectories(target.getParent());
					Files.createSymbolicLink(target, Files.readSymbolicLink(path));
				}
				else if (Files.isDirectory(path, NOFOLLOW_LINKS))
					Files.createDirectories(target);
				else
				{
					Files.createDirectories(target.getParent());
					Files.copy(path, target, COPY_ATTRIBUTES);
					copyPosixPermissions(path, target);
				}
			}
		}
	}

	/**
	 * Copies POSIX permissions when the filesystem supports them.
	 *
	 * @param source the source file
	 * @param target the destination file
	 * @throws IOException if permissions cannot be read or written
	 */
	private static void copyPosixPermissions(Path source, Path target) throws IOException
	{
		try
		{
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source, NOFOLLOW_LINKS);
			Files.setPosixFilePermissions(target, permissions);
		}
		catch (UnsupportedOperationException _)
		{
			// The supported build platforms provide POSIX permissions; other filesystems keep the attributes that
			// Files.copy() copied.
		}
	}
}
