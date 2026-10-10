package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives user-selectable product surfaces from {@link History}'s immutable source facts.
 */
public final class EntryPoints
{
	/**
	 * Prevents construction of static inventory operations.
	 */
	private EntryPoints()
	{
	}

	/**
	 * Derives the selectable product entry points changed by immutable inventory facts.
	 *
	 * @param inventory the source-path facts captured for one replay range
	 * @param manifests immutable manifest contents selected by blob identity
	 * @return one harness-neutral entry point for each affected product surface
	 * @throws IOException if a captured agent-manifest blob cannot be read
	 */
	public static List<ExposedEntryPoint> collect(List<History.InventoryEntry> inventory, ManifestContents manifests)
		throws IOException
	{
		Map<String, Set<String>> pathsByEntryPoint = new HashMap<>();
		for (History.InventoryEntry item : inventory)
		{
			if (isCustomAgentManifest(item.path()))
			{
				for (String blob : List.of(item.beforeBlob(), item.afterBlob()))
				{
					if (blob.equals("0000000000000000000000000000000000000000"))
						continue;
					addExposedEntryPoint(pathsByEntryPoint, "agent:" + customAgentName(manifests.read(blob), item.path()),
						item.path());
				}
				continue;
			}
			String entryPoint = declaredEntryPoint(item.path());
			if (entryPoint != null)
				addExposedEntryPoint(pathsByEntryPoint, entryPoint, item.path());
		}
		return pathsByEntryPoint.entrySet().stream().map(entry -> new ExposedEntryPoint(entry.getKey(), "custom_agent",
			entry.getValue().stream().sorted().toList())).sorted(Comparator.comparing(ExposedEntryPoint::id)).toList();
	}

	/**
	 * Adds one immutable path to a logical entry point.
	 *
	 * @param pathsByEntryPoint paths indexed by logical entry point
	 * @param entryPoint the logical entry point
	 * @param path the changed path that exposes it
	 */
	private static void addExposedEntryPoint(Map<String, Set<String>> pathsByEntryPoint, String entryPoint, String path)
	{
		pathsByEntryPoint.computeIfAbsent(entryPoint, ignored -> new HashSet<>()).add(path);
	}

	/**
	 * Returns the logical name of one non-agent product entry point.
	 *
	 * @param path a repository-relative changed path
	 * @return the entry point, or {@code null} when the path does not directly declare one
	 */
	private static String declaredEntryPoint(String path)
	{
		String skillPrefix = "client/";
		if (path.matches("client/(codex|claude)/skills/src/main/resources/skills/[^/]+/SKILL\\.md"))
		{
			String[] segments = path.substring(skillPrefix.length()).split("/");
			return "skill:" + segments[6];
		}
		String installerPrefix = "client/lifecycle/src/main/resources/install/";
		if (path.matches(installerPrefix + "(bootstrap(-[a-z]+)?|install-[a-z]+|uninstall-[a-z]+)\\.sh"))
			return "installer:" + path.substring(installerPrefix.length(), path.length() - ".sh".length());
		if (path.equals("client/common/cli/src/main/java/io/github/cowwoc/cat/common/git/replay/GitTopicReplayEditor.java"))
			return "command:git-topic-replay";
		return null;
	}

	/**
	 * Indicates whether a path declares a harness-specific custom agent.
	 *
	 * @param path a repository-relative path
	 * @return whether the path is a supported custom-agent manifest
	 */
	private static boolean isCustomAgentManifest(String path)
	{
		return path.matches("client/(codex|claude)/plugin/src/main/resources/agents/(codex|claude)/[^/]+\\.(toml|md)");
	}

	/**
	 * Reads the selectable agent name from one immutable custom-agent manifest.
	 *
	 * @param contents the immutable manifest contents
	 * @param path the manifest path for diagnostics
	 * @return the harness-neutral selectable name
	 * @throws IOException if the manifest has no supported name declaration
	 */
	private static String customAgentName(String contents, String path) throws IOException
	{
		for (String line : contents.split("\\R"))
		{
			String trimmed = line.trim();
			if (trimmed.startsWith("name = \"") && trimmed.endsWith("\""))
				return removeCatPrefix(trimmed.substring("name = \"".length(), trimmed.length() - 1));
			if (trimmed.startsWith("name:"))
			{
				String name = trimmed.substring("name:".length()).trim();
				if (!name.isEmpty())
					return removeCatPrefix(name.replace("\"", ""));
			}
		}
		throw new IOException("Custom-agent manifest must declare a selectable name: " + path);
	}

	/**
	 * Normalizes the harness-specific CAT prefix from an agent name.
	 *
	 * @param name a harness manifest's selectable agent name
	 * @return the shared logical agent name
	 */
	private static String removeCatPrefix(String name)
	{
		if (name.startsWith("cat-"))
			return name.substring("cat-".length());
		return name;
	}

	/**
	 * Supplies immutable custom-agent manifest contents to {@link EntryPoints}.
	 */
	@FunctionalInterface
	public interface ManifestContents
	{
		/**
		 * Reads the immutable manifest identified by a captured blob.
		 *
		 * @param blob the immutable manifest blob identity
		 * @return the manifest contents
		 * @throws IOException if the manifest cannot be read
		 */
		String read(String blob) throws IOException;
	}
}
