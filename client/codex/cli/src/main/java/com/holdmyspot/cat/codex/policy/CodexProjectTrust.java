package com.holdmyspot.cat.codex.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The trust that a Codex 0.160.0 user's configuration gives the folders of the session's project.
 * <p>
 * A folder's decision is its own {@code [projects."<path>"]} entry's {@code trust_level}, else that of the project
 * root, else that of the root of the Git repository whose checkout holds the folder. The project root is the nearest
 * folder at or above the working directory that holds one of the {@code project_root_markers}, by default
 * {@code .git}, or else the working directory.
 *
 * @param projectRoot the project root
 * @param repoRoot    the root of the Git repository, or empty if the working directory is outside one
 * @param cwd         the working directory
 * @param levels      the trust level of each folder that the configuration names, by its normalized path
 */
public record CodexProjectTrust(Path projectRoot, Optional<Path> repoRoot, Path cwd, Map<Path, String> levels)
{
	/**
	 * Creates the trust.
	 *
	 * @param projectRoot the project root
	 * @param repoRoot    the root of the Git repository, or empty if the working directory is outside one
	 * @param cwd         the working directory
	 * @param levels      the trust level of each folder that the configuration names, by its normalized path
	 */
	public CodexProjectTrust
	{
		levels = Map.copyOf(levels);
	}

	/**
	 * Reads the trust from the configuration layers below the projects' layers.
	 *
	 * @param merged the merged values of the layers
	 * @param cwd    the session's working directory
	 * @return the trust
	 * @throws IOException if a folder's real path cannot be read
	 */
	public static CodexProjectTrust of(JsonNode merged, Path cwd) throws IOException
	{
		List<String> markers = new ArrayList<>();
		JsonNode configured = merged.path("project_root_markers");
		if (configured.isArray())
		{
			for (JsonNode marker : configured)
			{
				if (marker.isString())
					markers.add(marker.stringValue());
			}
		}
		else
			markers.add(".git");
		Path projectRoot = cwd;
		for (Path folder = cwd; folder != null; folder = folder.getParent())
		{
			if (holdsAny(folder, markers))
			{
				projectRoot = folder;
				break;
			}
		}
		Map<Path, String> levels = new HashMap<>();
		for (Entry<String, JsonNode> project : merged.path("projects").properties())
		{
			JsonNode level = project.getValue().path("trust_level");
			if (!level.isString())
				continue;
			try
			{
				levels.put(normalize(Path.of(project.getKey())), level.stringValue());
			}
			catch (InvalidPathException _)
			{
				// A key that is not a path names no folder of the session
			}
		}
		return new CodexProjectTrust(projectRoot, repoRoot(cwd), cwd, levels);
	}

	/**
	 * Indicates whether a folder holds one of the markers.
	 *
	 * @param folder  the folder
	 * @param markers the names of the markers
	 * @return {@code true} if a file or folder of one of the names exists in {@code folder}
	 */
	private static boolean holdsAny(Path folder, List<String> markers)
	{
		for (String marker : markers)
		{
			if (Files.exists(folder.resolve(marker)))
				return true;
		}
		return false;
	}

	/**
	 * Finds the root of the Git repository whose checkout holds a folder. A linked worktree's {@code .git} file
	 * names a folder below the main checkout's {@code .git} folder, whose parent is the root.
	 *
	 * @param cwd the folder
	 * @return the root, or empty if no folder at or above {@code cwd} holds {@code .git}
	 * @throws IOException if a {@code .git} file cannot be read
	 */
	private static Optional<Path> repoRoot(Path cwd) throws IOException
	{
		for (Path folder = cwd; folder != null; folder = folder.getParent())
		{
			Path git = folder.resolve(".git");
			if (Files.isDirectory(git))
				return Optional.of(folder);
			if (!Files.isRegularFile(git))
				continue;
			String text = Files.readString(git).strip();
			if (text.startsWith("gitdir:"))
			{
				Path gitDir = folder.resolve(text.substring("gitdir:".length()).strip()).normalize();
				for (Path ancestor = gitDir; ancestor != null; ancestor = ancestor.getParent())
				{
					Path name = ancestor.getFileName();
					if (name != null && name.toString().equals(".git") && ancestor.getParent() != null)
						return Optional.of(ancestor.getParent());
				}
			}
			return Optional.of(folder);
		}
		return Optional.empty();
	}

	/**
	 * Returns the folders whose {@code .codex/config.toml} Codex reads, from the project root down to the working
	 * directory.
	 *
	 * @return the folders
	 */
	public List<Path> projectFolders()
	{
		List<Path> folders = new ArrayList<>();
		for (Path folder = cwd; folder != null; folder = folder.getParent())
		{
			folders.addFirst(folder);
			if (folder.equals(projectRoot))
				break;
		}
		return folders;
	}

	/**
	 * Indicates whether the configuration trusts a folder.
	 *
	 * @param folder the folder
	 * @return {@code true} if the folder's decision is {@code trusted}
	 * @throws IOException if a folder's real path cannot be read
	 */
	public boolean trusts(Path folder) throws IOException
	{
		return decision(folder).filter(level -> level.equals("trusted")).isPresent();
	}

	/**
	 * Indicates whether the configuration decides the trust of a folder.
	 *
	 * @param folder the folder
	 * @return {@code true} if the folder's decision is {@code trusted} or {@code untrusted}
	 * @throws IOException if a folder's real path cannot be read
	 */
	public boolean decides(Path folder) throws IOException
	{
		return decision(folder).isPresent();
	}

	/**
	 * Returns a folder's trust decision.
	 *
	 * @param folder the folder
	 * @return the trust level, or empty if the configuration decides none
	 * @throws IOException if a folder's real path cannot be read
	 */
	private Optional<String> decision(Path folder) throws IOException
	{
		List<Path> keys = new ArrayList<>(List.of(folder, projectRoot));
		repoRoot.ifPresent(keys::add);
		for (Path key : keys)
		{
			String level = levels.get(normalize(key));
			if (level != null)
				return Optional.of(level);
		}
		return Optional.empty();
	}

	/**
	 * Returns the real path of a folder that exists, or else the normalized absolute path.
	 *
	 * @param folder the folder
	 * @return the path
	 * @throws IOException if the real path of an existing folder cannot be read
	 */
	private static Path normalize(Path folder) throws IOException
	{
		Path absolute = folder.toAbsolutePath().normalize();
		if (Files.exists(absolute))
			return absolute.toRealPath();
		return absolute;
	}
}
