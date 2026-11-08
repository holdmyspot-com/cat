package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the paths of a Claude Code session's settings to the files that they name, as the release's sandbox does.
 * <p>
 * An entry that grants access counts only when it names exactly one folder or file. An entry that denies access
 * denies every path that it may name, and a pattern denies each existing path that it matches, as the release's
 * sandbox matches patterns against the files that exist.
 * <p>
 * When the session's paths are Windows paths, an entry that starts at a drive, such as {@code C:\Users} or
 * {@code C:/Users}, or at a network share, such as {@code \\server\share}, names that absolute path in every
 * syntax, and a permission rule names a drive as Claude Code does, as {@code //c/Users} for {@code C:\Users}. Names
 * are separated by {@code /} wherever a pattern is matched.
 */
final class ClaudePaths
{
	/**
	 * The characters that make a path a pattern.
	 */
	private static final String GLOB_CHARACTERS = "*?[{";
	/**
	 * The most files that matching one pattern visits. Past it, the pattern denies the whole folder that its literal
	 * part names.
	 */
	private static final int MAXIMUM_VISITS = 10_000;
	/**
	 * The fix of a refusal that an unresolvable path causes.
	 */
	private static final String PATH_FIX = "Spell the path from the root folder or from \"~/\", then start a new " +
		"session";
	/**
	 * A path that starts at a Windows drive, such as {@code C:\Users} or {@code C:/Users}, or at a Windows network
	 * share, such as {@code \\server\share}.
	 */
	private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\[^\\\\])");
	/**
	 * The form that a permission rule gives a Windows drive, as {@code /c/Users} names {@code C:\Users}.
	 */
	private static final Pattern RULE_DRIVE = Pattern.compile("^/([A-Za-z])(/.*)?$");
	/**
	 * The project folder of the session.
	 */
	private final Path projectDirectory;
	/**
	 * The user's home folder.
	 */
	private final Path userHome;
	/**
	 * {@code true} if the paths of the session are Windows paths, which separate names with a backslash and start at a
	 * drive or a network share.
	 */
	private final boolean windows;

	/**
	 * The syntaxes of paths in Claude Code settings.
	 */
	enum Syntax
	{
		/**
		 * A path of the settings' sandbox section: {@code /} starts an absolute path, {@code ~/} a path in the home
		 * folder, and any other path is relative to the settings' root.
		 */
		SANDBOX,
		/**
		 * The path of a permission rule, such as {@code Read(path)}: {@code //} starts an absolute path, {@code ~/} a
		 * path in the home folder, {@code /} a path relative to the settings' root, and any other path is relative to
		 * the project folder; a name without {@code /} matches at any depth of the project folder.
		 */
		RULE
	}

	/**
	 * Creates a resolver.
	 *
	 * @param projectDirectory the absolute project folder of the session
	 * @param userHome         the user's absolute home folder
	 */
	ClaudePaths(Path projectDirectory, Path userHome)
	{
		assert projectDirectory.isAbsolute() : projectDirectory;
		assert userHome.isAbsolute() : userHome;
		this.projectDirectory = projectDirectory;
		this.userHome = userHome;
		this.windows = projectDirectory.getFileSystem().getSeparator().equals("\\");
	}

	/**
	 * Returns the path that an entry granting access names, if it names exactly one.
	 *
	 * @param entry        the entry
	 * @param syntax       the syntax of the entry
	 * @param settingsRoot the absolute root of the settings that hold the entry
	 * @return the path, or empty if the entry may name more than one path, is a pattern other than a folder followed by
	 *   {@code /**}, or cannot be resolved
	 */
	Optional<Path> granted(String entry, Syntax syntax, Path settingsRoot)
	{
		Candidates candidates = candidates(entry, syntax, settingsRoot);
		if (candidates == null || !candidates.exact() || candidates.paths().size() != 1)
			return Optional.empty();
		String path = candidates.paths().getFirst();
		if (path.endsWith("/**"))
			path = path.substring(0, path.length() - "/**".length());
		if (path.isEmpty())
			path = "/";
		if (isPattern(path))
			return Optional.empty();
		return Optional.of(Path.of(path).normalize());
	}

	/**
	 * Returns the paths that an entry denying access denies.
	 *
	 * @param entry        the entry
	 * @param syntax       the syntax of the entry
	 * @param settingsRoot the absolute root of the settings that hold the entry
	 * @param source       the settings that hold the entry, as the user knows them
	 * @return the paths
	 * @throws IOException   if a folder cannot be listed while matching a pattern
	 * @throws PolicyRefusal if the entry names another user's home folder, which CAT cannot resolve
	 */
	List<Path> denied(String entry, Syntax syntax, Path settingsRoot, String source) throws IOException, PolicyRefusal
	{
		Candidates candidates = candidates(entry, syntax, settingsRoot);
		if (candidates == null)
		{
			throw new PolicyRefusal("the denied path \"" + entry + "\" in " + source + " names another user's " +
				"home folder", PATH_FIX);
		}
		Set<Path> paths = new LinkedHashSet<>();
		for (String candidate : candidates.paths())
		{
			if (isPattern(candidate))
				paths.addAll(matches(candidate));
			else
				paths.add(Path.of(candidate).normalize());
		}
		return List.copyOf(paths);
	}

	/**
	 * Returns the absolute paths or patterns that an entry may name.
	 *
	 * @param entry        the entry
	 * @param syntax       the syntax of the entry
	 * @param settingsRoot the absolute root of the settings that hold the entry
	 * @return the candidates, or null if the entry names another user's home folder
	 */
	private Candidates candidates(String entry, Syntax syntax, Path settingsRoot)
	{
		// A Windows path names its folders with backslashes, which this class reads as forward slashes
		String spelled = entry;
		if (windows)
		{
			if (WINDOWS_ABSOLUTE.matcher(entry).find())
				return new Candidates(List.of(entry.replace('\\', '/')), true);
			spelled = entry.replace('\\', '/');
		}
		if (spelled.equals("~") || spelled.startsWith("~/"))
			return new Candidates(List.of(join(userHome, spelled.substring(1))), true);
		if (spelled.startsWith("~"))
			return null;
		return switch (syntax)
		{
			case SANDBOX -> sandboxCandidates(spelled, settingsRoot);
			case RULE -> ruleCandidates(spelled, settingsRoot);
		};
	}

	/**
	 * Returns the absolute paths or patterns that an entry of the settings' sandbox section may name.
	 *
	 * @param entry        the entry, which does not start with {@code ~}
	 * @param settingsRoot the absolute root of the settings that hold the entry
	 * @return the candidates
	 */
	private Candidates sandboxCandidates(String entry, Path settingsRoot)
	{
		// "//" may start a network path, which the release does not treat as a local folder
		if (entry.startsWith("//"))
			return new Candidates(List.of(entry.substring(1)), false);
		if (entry.startsWith("/"))
			return new Candidates(List.of(entry), true);
		String fromRoot = join(settingsRoot, "/" + entry);
		String fromProject = join(projectDirectory, "/" + entry);
		if (fromRoot.equals(fromProject))
			return new Candidates(List.of(fromRoot), true);
		return new Candidates(List.of(fromRoot, fromProject), false);
	}

	/**
	 * Returns the absolute paths or patterns that the path of a permission rule may name.
	 *
	 * @param entry        the path, which does not start with {@code ~}
	 * @param settingsRoot the absolute root of the settings that hold the rule
	 * @return the candidates
	 */
	private Candidates ruleCandidates(String entry, Path settingsRoot)
	{
		if (entry.startsWith("//"))
			return new Candidates(List.of(ruleDrive(entry.substring(1))), true);
		if (entry.startsWith("/"))
		{
			String fromRoot = join(settingsRoot, entry);
			if (fromRoot.equals(entry))
				return new Candidates(List.of(entry), true);
			// Whether such a path is relative to the settings' root or absolute is not certain
			return new Candidates(List.of(fromRoot, entry), false);
		}
		String relative = entry;
		if (relative.startsWith("./"))
			relative = relative.substring(2);
		else if (!relative.contains("/"))
		{
			String atTop = join(projectDirectory, "/" + relative);
			String atAnyDepth = join(projectDirectory, "/**/" + relative);
			return new Candidates(List.of(atTop, atAnyDepth), false);
		}
		return new Candidates(List.of(join(projectDirectory, "/" + relative)), true);
	}

	/**
	 * Returns the path that a permission rule names with the form of a POSIX path, as the path of the rule's Windows
	 * drive when the session's paths are Windows paths, such as {@code C:/Users} for {@code /c/Users}.
	 *
	 * @param path the path, starting with {@code /}
	 * @return the path that the session's file system reaches
	 */
	private String ruleDrive(String path)
	{
		if (!windows)
			return path;
		Matcher drive = RULE_DRIVE.matcher(path);
		if (!drive.matches())
			return path;
		String rest = drive.group(2);
		if (rest == null)
			rest = "/";
		return Character.toUpperCase(drive.group(1).charAt(0)) + ":" + rest;
	}

	/**
	 * Appends a path to a folder.
	 *
	 * @param folder the folder
	 * @param path   the path, empty or starting with {@code /}
	 * @return the joined path, which separates names with {@code /}
	 */
	private String join(Path folder, String path)
	{
		String prefix = folder.toString();
		if (windows)
			prefix = prefix.replace('\\', '/');
		if (prefix.endsWith("/"))
			prefix = prefix.substring(0, prefix.length() - 1);
		String joined = prefix + path;
		if (joined.isEmpty())
			return "/";
		return joined;
	}

	/**
	 * Indicates whether a path is a pattern.
	 *
	 * @param path the path
	 * @return {@code true} if the path holds a glob character
	 */
	private static boolean isPattern(String path)
	{
		for (int i = 0; i < path.length(); ++i)
		{
			if (GLOB_CHARACTERS.indexOf(path.charAt(i)) >= 0)
				return true;
		}
		return false;
	}

	/**
	 * Returns the existing paths that a pattern matches.
	 *
	 * @param pattern an absolute pattern
	 * @return the matching paths, or the folder that the pattern's literal part names if the folder holds too many
	 *   files to match them all
	 * @throws IOException if the folder cannot be listed
	 */
	private static List<Path> matches(String pattern) throws IOException
	{
		Path base = literalAncestor(pattern);
		if (Files.notExists(base))
			return List.of();
		List<PathMatcher> matchers = new ArrayList<>();
		matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
		// "**/" also matches no folder at all
		if (pattern.contains("/**/"))
			matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace("/**/", "/")));
		List<Path> matched = new ArrayList<>();
		int[] visits = {0};
		boolean[] tooMany = {false};
		Files.walkFileTree(base, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
			new SimpleFileVisitor<>()
			{
				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				{
					return visit(directory);
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				{
					return visit(file);
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException failure)
				{
					// The release covers a folder that it cannot list whole
					matched.add(file);
					return FileVisitResult.CONTINUE;
				}

				/**
				 * Matches a path against the pattern.
				 *
				 * @param path the path
				 * @return whether to continue
				 */
				private FileVisitResult visit(Path path)
				{
					++visits[0];
					if (visits[0] > MAXIMUM_VISITS)
					{
						tooMany[0] = true;
						return FileVisitResult.TERMINATE;
					}
					for (PathMatcher matcher : matchers)
					{
						if (matcher.matches(path))
						{
							matched.add(path);
							break;
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		if (tooMany[0])
			return List.of(base);
		return matched;
	}

	/**
	 * Returns the folder that the literal part of a pattern names.
	 *
	 * @param pattern an absolute pattern
	 * @return the folder before the first name that holds a glob character
	 */
	private static Path literalAncestor(String pattern)
	{
		int firstGlob = pattern.length();
		for (int i = 0; i < pattern.length(); ++i)
		{
			if (GLOB_CHARACTERS.indexOf(pattern.charAt(i)) >= 0)
			{
				firstGlob = i;
				break;
			}
		}
		String literal = pattern.substring(0, pattern.lastIndexOf('/', firstGlob) + 1);
		if (literal.isEmpty())
			literal = "/";
		return Path.of(literal).normalize();
	}

	/**
	 * The absolute paths or patterns that an entry may name.
	 *
	 * @param paths the candidates
	 * @param exact {@code true} if the entry names exactly these, rather than one of them or something CAT cannot
	 *              resolve
	 */
	private record Candidates(List<String> paths, boolean exact)
	{
	}
}
