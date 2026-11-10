package com.holdmyspot.cat.common.runner;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.PortableFiles;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The record of the read-only copies that the symbolic links of a run's project whose targets lie outside the project
 * lead to, and the check that tells, once the run's session has ended, whether the session changed one of them. Each
 * copy lies in a folder of the run's {@code link-targets} directory, beside the project. The run keeps the record in
 * its own directory, which the session's sandbox does not let it write.
 * <p>
 * The record is a JSON Lines file. Its first line is {@code {"format_version": 1}}. Each further line describes one
 * file or directory of a copy: its {@code path} relative to the run's directory, with {@code /} between names; its
 * {@code type}, {@code file} or {@code directory}; its {@code mode}, the POSIX permissions such as {@code r--r--r--}
 * where the file system records them and otherwise {@code read_only} or {@code writable}; whether it is a
 * {@code copy_root}, the file or directory that a link leads to; and, for a file, the {@code sha256} digest of its
 * content in lower-case hexadecimal. The lines of each copy follow its root, in the order of a walk that visits each
 * directory before its entries and the entries of a directory in the order of their names.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
final class LinkCopies
{
	/**
	 * The name of the record in a run's directory.
	 */
	static final String FILE_NAME = "link-copies.jsonl";
	private static final int VERSION = 1;
	/**
	 * The mode of a read-only file where the file system records no POSIX permissions.
	 */
	private static final String READ_ONLY = "read_only";
	/**
	 * The mode of any other file or directory there.
	 */
	private static final String WRITABLE = "writable";
	/**
	 * The most changed paths that a diagnostic names; it counts the rest.
	 */
	private static final int MAXIMUM_NAMED_CHANGES = 20;
	private static final Set<String> FIELDS = Set.of("path", "type", "mode", "copy_root", "sha256");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Records the copies of a run, as they are before its session starts.
	 *
	 * @param run    the run's directory
	 * @param roots  the path of each copy relative to {@code run}, none inside another
	 * @param record the record to write
	 * @throws IOException if a copy cannot be read or the record cannot be written
	 */
	static void write(Path run, List<Path> roots, Path record) throws IOException
	{
		assert run != null;
		assert roots != null;
		assert record != null;
		try (BufferedWriter lines = Files.newBufferedWriter(record, UTF_8))
		{
			lines.write(JSON.writeValueAsString(JSON.createObjectNode().put("format_version", VERSION)));
			lines.newLine();
			for (Path root : roots)
			{
				Walk walk = new Walk(run, name(root));
				boolean first = true;
				for (Present entry = walk.next(); entry != null; entry = walk.next())
				{
					ObjectNode line = JSON.createObjectNode().put("path", entry.path()).
						put("type", entry.type().text).put("mode", entry.mode()).put("copy_root", first);
					if (entry.type() == Type.FILE)
						line.put("sha256", entry.sha256());
					lines.write(JSON.writeValueAsString(line));
					lines.newLine();
					first = false;
				}
				if (first)
					throw new IOException("The run's copy of a link target is missing: " + root);
			}
		}
	}

	/**
	 * Compares the copies of a run with their record: a copy changed if a file or directory of it was added, removed,
	 * replaced by one of another type, given other content, or given another mode.
	 *
	 * @param run    the run's directory
	 * @param record the record
	 * @return a diagnostic that names each changed path, or empty if no copy changed
	 * @throws IOException if the record is malformed or a copy cannot be read
	 */
	static Optional<String> changes(Path run, Path record) throws IOException
	{
		assert run != null;
		assert record != null;
		Changes changes = new Changes();
		// The record holds one line per file of the copies, so it is read one line at a time; each line is bounded by
		// the length of a path, since CAT wrote the record where the session may not write
		try (BufferedReader lines = Files.newBufferedReader(record, UTF_8))
		{
			Records records = new Records(lines);
			Recorded recorded = records.next();
			while (recorded != null)
			{
				if (!recorded.root())
					throw records.malformed("the first entry of a copy is not its root");
				Walk walk = new Walk(run, recorded.path());
				Present present = walk.next();
				boolean first = true;
				while (present != null || recorded != null && (first || !recorded.root()))
				{
					boolean inCopy = recorded != null && (first || !recorded.root());
					int order;
					if (!inCopy)
						order = 1;
					else if (present == null)
						order = -1;
					else
						order = compare(recorded.path(), present.path());
					if (order < 0)
					{
						changes.add(recorded.path(), "removed");
						recorded = records.skipBelow(recorded.path());
						first = false;
					}
					else if (order > 0)
					{
						changes.add(present.path(), "added");
						walk.skipEntries();
						present = walk.next();
					}
					else
					{
						compare(recorded, present, changes);
						if (recorded.type() != present.type())
						{
							recorded = records.skipBelow(recorded.path());
							walk.skipEntries();
						}
						else
							recorded = records.next();
						present = walk.next();
						first = false;
					}
				}
			}
		}
		return changes.diagnostic();
	}

	/**
	 * Compares a recorded file or directory with the one at its path now.
	 *
	 * @param recorded the recorded file or directory
	 * @param present  the file or directory at its path now
	 * @param changes  receives each change
	 * @throws IOException if the file cannot be read
	 */
	private static void compare(Recorded recorded, Present present, Changes changes) throws IOException
	{
		if (recorded.type() != present.type())
		{
			changes.add(present.path(), "replaced by " + present.type().noun);
			return;
		}
		List<String> differences = new ArrayList<>();
		if (recorded.type() == Type.FILE)
		{
			try
			{
				if (!recorded.sha256().equals(present.sha256()))
					differences.add("content changed");
			}
			catch (AccessDeniedException _)
			{
				differences.add("made unreadable");
			}
		}
		String mode = present.mode();
		if (!writable(recorded.mode()) && writable(mode))
			differences.add("made writable");
		else if (!recorded.mode().equals(mode))
			differences.add("mode changed from " + recorded.mode() + " to " + mode);
		if (!differences.isEmpty())
			changes.add(present.path(), String.join(", ", differences));
	}

	/**
	 * Indicates whether a mode lets its owner write.
	 *
	 * @param mode a mode as the record holds it
	 * @return {@code true} if the owner may write
	 */
	private static boolean writable(String mode)
	{
		return mode.equals(WRITABLE) || mode.length() == 9 && mode.charAt(1) == 'w';
	}

	/**
	 * Orders two paths as a walk visits them: name by name, so that a directory comes before its entries and the
	 * entries of a directory come in the order of their names.
	 *
	 * @param first  a path relative to the run's directory, with {@code /} between names
	 * @param second another such path
	 * @return a negative number if a walk visits {@code first} first, zero if they are the same, and a positive
	 *   number otherwise
	 */
	private static int compare(String first, String second)
	{
		String[] firstNames = first.split("/");
		String[] secondNames = second.split("/");
		for (int index = 0; index < Math.min(firstNames.length, secondNames.length); ++index)
		{
			int order = firstNames[index].compareTo(secondNames[index]);
			if (order != 0)
				return order;
		}
		return Integer.compare(firstNames.length, secondNames.length);
	}

	/**
	 * Returns the name of a path relative to the run's directory, with {@code /} between names.
	 *
	 * @param relative the path
	 * @return its name
	 */
	private static String name(Path relative)
	{
		return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Returns the SHA-256 digest of a file's content.
	 *
	 * @param file the file
	 * @return the digest in lower-case hexadecimal
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
			throw new AssertionError("The Java platform cannot provide SHA-256", e);
		}
		try (InputStream input = Files.newInputStream(file, NOFOLLOW_LINKS))
		{
			byte[] buffer = new byte[64 * 1024];
			for (int count = input.read(buffer); count != -1; count = input.read(buffer))
				digest.update(buffer, 0, count);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Prevents construction.
	 */
	private LinkCopies()
	{
	}

	/**
	 * The type of a file or directory of a copy.
	 */
	private enum Type
	{
		/**
		 * A regular file.
		 */
		FILE("file", "a file"),
		/**
		 * A directory.
		 */
		DIRECTORY("directory", "a directory"),
		/**
		 * A symbolic link, which no copy holds.
		 */
		LINK("link", "a symbolic link"),
		/**
		 * Any other file, which no copy holds.
		 */
		OTHER("other", "a special file");

		private final String text;
		private final String noun;

		/**
		 * Creates a type.
		 *
		 * @param text its name in the record
		 * @param noun how a diagnostic names a file of the type
		 */
		Type(String text, String noun)
		{
			this.text = text;
			this.noun = noun;
		}
	}

	/**
	 * A file or directory of a copy as the record describes it.
	 *
	 * @param path     the path relative to the run's directory, with {@code /} between names
	 * @param type     the type, a file or a directory
	 * @param mode     its mode
	 * @param root     whether it is the root of its copy
	 * @param sha256   the digest of a file's content, or an empty string for a directory
	 */
	private record Recorded(String path, Type type, String mode, boolean root, String sha256)
	{
	}

	/**
	 * The lines of a record after its first.
	 */
	private static final class Records
	{
		private final BufferedReader lines;
		private int number = 1;

		/**
		 * Reads the first line of a record.
		 *
		 * @param lines the record's lines
		 * @throws IOException if the first line is not the record's version
		 */
		private Records(BufferedReader lines) throws IOException
		{
			this.lines = lines;
			JsonNode header = parse(lines.readLine());
			if (header.size() != 1 || header.path("format_version").asInt(0) != VERSION ||
				!header.path("format_version").isInt())
			{
				throw malformed("its first line is not {\"format_version\": " + VERSION + "}");
			}
		}

		/**
		 * Reads the next file or directory.
		 *
		 * @return the file or directory, or {@code null} at the end of the record
		 * @throws IOException if the line is malformed or cannot be read
		 */
		private Recorded next() throws IOException
		{
			String line = lines.readLine();
			if (line == null)
				return null;
			++number;
			JsonNode node = parse(line);
			for (String field : node.propertyNames())
			{
				if (!FIELDS.contains(field))
					throw malformed("it has the unknown field " + field);
			}
			if (!node.path("path").isString() || node.path("path").asString().isEmpty() ||
				!node.path("mode").isString() || !node.path("copy_root").isBoolean())
			{
				throw malformed("it lacks a path, mode or copy_root");
			}
			Type type = switch (node.path("type").asString(""))
			{
				case "file" -> Type.FILE;
				case "directory" -> Type.DIRECTORY;
				default -> throw malformed("its type is neither file nor directory");
			};
			String sha256 = "";
			if (type == Type.FILE)
			{
				if (!node.path("sha256").isString())
					throw malformed("its file has no sha256");
				sha256 = node.path("sha256").asString();
			}
			else if (node.has("sha256"))
				throw malformed("its directory has a sha256");
			return new Recorded(node.path("path").asString(), type, node.path("mode").asString(),
				node.path("copy_root").asBoolean(), sha256);
		}

		/**
		 * Reads past the entries of a recorded directory.
		 *
		 * @param directory the directory's path
		 * @return the first file or directory that the record holds after them, or {@code null} at its end
		 * @throws IOException if a line is malformed or cannot be read
		 */
		private Recorded skipBelow(String directory) throws IOException
		{
			Recorded recorded = next();
			while (recorded != null && !recorded.root() && recorded.path().startsWith(directory + "/"))
				recorded = next();
			return recorded;
		}

		/**
		 * Parses one line.
		 *
		 * @param line the line, or {@code null} at the end of the record
		 * @return its JSON object
		 * @throws IOException if the line is missing or is not a JSON object
		 */
		private JsonNode parse(String line) throws IOException
		{
			if (line == null)
				throw malformed("it ends early");
			try
			{
				JsonNode node = JSON.readTree(line);
				if (!node.isObject())
					throw malformed("it is not a JSON object");
				return node;
			}
			catch (JacksonException e)
			{
				IOException failure = malformed("it is not JSON");
				failure.initCause(e);
				throw failure;
			}
		}

		/**
		 * Returns the failure of a malformed line.
		 *
		 * @param reason why the line is malformed
		 * @return the failure
		 */
		private IOException malformed(String reason)
		{
			return new IOException("The record of the run's copies of link targets is malformed at line " + number +
				": " + reason);
		}
	}

	/**
	 * A file or directory of a copy as the run holds it now.
	 *
	 * @param path the path relative to the run's directory, with {@code /} between names
	 * @param file the file or directory
	 * @param type its type
	 */
	private record Present(String path, Path file, Type type)
	{
		/**
		 * Returns the mode of the file or directory.
		 *
		 * @return its POSIX permissions, such as {@code r--r--r--}, where its file system records them, and otherwise
		 *   {@code read_only} if {@link PortableFiles#isReadOnly(Path)} tells that it is read-only and
		 *   {@code writable} if not
		 * @throws IOException if its mode or attributes cannot be read
		 */
		String mode() throws IOException
		{
			if (PortableFiles.hasModes(file))
				return PosixFilePermissions.toString(Files.getPosixFilePermissions(file, NOFOLLOW_LINKS));
			if (PortableFiles.isReadOnly(file))
				return READ_ONLY;
			return WRITABLE;
		}

		/**
		 * Returns the digest of a file's content.
		 *
		 * @return the digest in lower-case hexadecimal
		 * @throws IOException if the file cannot be read
		 */
		String sha256() throws IOException
		{
			return LinkCopies.sha256(file);
		}
	}

	/**
	 * Visits one copy in a run: its root, then each directory before its entries and the entries of a
	 * directory in the order of their names, without following symbolic links. A copy whose root, or a directory above
	 * it, is missing or is no longer a directory has nothing to visit.
	 */
	private static final class Walk
	{
		private final Path run;
		private final Deque<Iterator<Path>> levels = new ArrayDeque<>();
		/**
		 * The directory that the walk visited last, whose entries it visits next, or {@code null}.
		 */
		private Path entered;

		/**
		 * Creates a walk.
		 *
		 * @param run  the run's directory
		 * @param root the copy's path relative to the run's directory, with {@code /} between names
		 */
		private Walk(Path run, String root)
		{
			this.run = run;
			Path path = run;
			String[] names = root.split("/");
			for (int index = 0; index < names.length; ++index)
			{
				if (index > 0 && !Files.isDirectory(path, NOFOLLOW_LINKS))
					return;
				path = path.resolve(names[index]);
			}
			if (Files.exists(path, NOFOLLOW_LINKS))
				levels.push(List.of(path).iterator());
		}

		/**
		 * Returns the next file or directory.
		 *
		 * @return the file or directory, or {@code null} once the walk has visited the whole copy
		 * @throws IOException if a directory cannot be listed or a file's attributes cannot be read
		 */
		private Present next() throws IOException
		{
			if (entered != null)
			{
				List<Path> entries;
				try (Stream<Path> list = Files.list(entered))
				{
					// The entries are sorted, so all of them are held, but only for each directory that the walk is
					// inside
					entries = new ArrayList<>(list.toList());
				}
				catch (AccessDeniedException _)
				{
					// The directory's mode tells that it changed, and the entries that it held count as removed
					entries = new ArrayList<>();
				}
				entries.sort((first, second) -> first.getFileName().toString().
					compareTo(second.getFileName().toString()));
				levels.push(entries.iterator());
			}
			while (!levels.isEmpty())
			{
				Iterator<Path> level = levels.peek();
				if (!level.hasNext())
				{
					levels.pop();
					continue;
				}
				Path file = level.next();
				BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
				Type type;
				Path enter = null;
				if (attributes.isSymbolicLink())
					type = Type.LINK;
				else if (attributes.isDirectory())
				{
					type = Type.DIRECTORY;
					enter = file;
				}
				else if (attributes.isRegularFile())
					type = Type.FILE;
				else
					type = Type.OTHER;
				entered = enter;
				return new Present(name(run.relativize(file)), file, type);
			}
			entered = null;
			return null;
		}

		/**
		 * Leaves out the entries of the directory that {@link #next()} returned last.
		 */
		private void skipEntries()
		{
			entered = null;
		}
	}

	/**
	 * The changes that a comparison found.
	 */
	private static final class Changes
	{
		private final StringJoiner named = new StringJoiner(", ");
		private int count;

		/**
		 * Adds one changed path.
		 *
		 * @param path   the path relative to the run's directory, with {@code /} between names
		 * @param change how it changed
		 */
		private void add(String path, String change)
		{
			++count;
			if (count <= MAXIMUM_NAMED_CHANGES)
				named.add(path + " (" + change + ")");
		}

		/**
		 * Returns the diagnostic of the changes.
		 *
		 * @return the diagnostic, or empty if nothing changed
		 */
		private Optional<String> diagnostic()
		{
			if (count == 0)
				return Optional.empty();
			String rest = "";
			if (count > MAXIMUM_NAMED_CHANGES)
				rest = ", and " + (count - MAXIMUM_NAMED_CHANGES) + " more";
			return Optional.of("The session changed read-only copies of symbolic link targets outside the project, " +
				"which the project's links lead to: " + named + rest + ". The run fails, because its result " +
				"would rest on files that differ from the targets.");
		}
	}
}
