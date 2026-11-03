package com.holdmyspot.cat.common.release.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.TarGzExtractor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link TarGzExtractor}: which archives it extracts, which hostile archives it refuses, and that a refused
 * archive leaves the target directory empty and writes nothing outside it.
 * <p>
 * Each test builds its archive in memory and extracts it into an empty directory {@code target}, which lies in a
 * directory of the test's own. After a refusal, that directory must hold only {@code target}, and {@code target} must
 * be empty, so that a file written beside the target or below it shows up.
 */
public final class TarGzExtractorTest
{
	private static final String TOP = "cat-harness-a-v1.0-linux-x64";

	/**
	 * Creates a test instance.
	 */
	public TarGzExtractorTest()
	{
	}

	/**
	 * Verifies that an archive with a regular file and a symbolic link to it inside the top-level directory extracts
	 * with the link kept as a relative link that reaches the file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void relativeLinkInsideExtracts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path target = target(cleanup, "tar-valid-link-");
			byte[] archive = archive(directory(TOP), directory(TOP + "/sub"), file(TOP + "/sub/file.txt", 5),
				link(TOP + "/link", "sub/file.txt"));

			extract(archive, target);

			Path link = target.resolve(TOP + "/link");
			requireThat(Files.readSymbolicLink(link), "linkTarget").isEqualTo(Path.of("sub/file.txt"));
			requireThat(Files.readString(link), "content").isEqualTo("xxxxx");
		}
	}

	/**
	 * Verifies that a hard-link entry is refused, even when it names a file inside the archive, because a hard link
	 * can share a file that lies outside the target.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void hardLinkRefused() throws IOException
	{
		TarArchiveEntry hardLink = new TarArchiveEntry(TOP + "/hard", TarConstants.LF_LINK);
		hardLink.setLinkName(TOP + "/ok.txt");
		requireRefused("tar-hard-link-", TOP + "/hard", hardLink);
	}

	/**
	 * Returns entry types that the extractor does not support: character and block devices, a FIFO, a contiguous
	 * file, and a vendor-specific type.
	 *
	 * @return each type flag
	 */
	@DataProvider
	public static Object[][] unsupportedTypes()
	{
		return new Object[][]
		{
			{TarConstants.LF_CHR},
			{TarConstants.LF_BLK},
			{TarConstants.LF_FIFO},
			{TarConstants.LF_CONTIG},
			{(byte) 'A'}
		};
	}

	/**
	 * Verifies that an entry of an unsupported type is refused.
	 *
	 * @param type the entry's type flag
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "unsupportedTypes")
	public void unsupportedTypeRefused(byte type) throws IOException
	{
		requireRefused("tar-type-", TOP + "/special", new TarArchiveEntry(TOP + "/special", type));
	}

	/**
	 * Returns modes that hold the setuid, setgid or sticky bit, for a regular file and for a directory.
	 *
	 * @return each mode and whether the entry is a directory
	 */
	@DataProvider
	public static Object[][] specialModes()
	{
		return new Object[][]
		{
			{04755, false},
			{02755, false},
			{01755, false},
			{04755, true},
			{02755, true},
			{01777, true}
		};
	}

	/**
	 * Verifies that a regular file or directory whose mode holds the setuid, setgid or sticky bit is refused.
	 *
	 * @param mode      the entry's mode
	 * @param directory {@code true} for a directory entry, {@code false} for a regular file
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "specialModes")
	public void specialModeRefused(int mode, boolean directory) throws IOException
	{
		TarArchiveEntry entry;
		if (directory)
			entry = directory(TOP + "/special");
		else
			entry = file(TOP + "/special", 3);
		entry.setMode(mode);
		requireRefused("tar-mode-", TOP + "/special", entry);
	}

	/**
	 * Verifies that a regular file that would take the total of regular-file bytes past the caller's limit is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void totalBytesLimitRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path target = target(cleanup, "tar-bytes-");
			byte[] archive = archive(directory(TOP), file(TOP + "/first", 600), file(TOP + "/second", 600));

			IOException failure = refusal(() -> TarGzExtractor.extract(new ByteArrayInputStream(archive), target, TOP,
				1000, 100));

			requireThat(failure.getMessage(), "message").contains(TOP + "/second");
			requireNothingLeft(target);
		}
	}

	/**
	 * Verifies that, with the default limits, a regular file that declares more than 1 GiB is refused before its
	 * content is written: the archive ends right after the entry's header, so a refusal that names the size, rather
	 * than the archive's early end, shows that the declared size was checked first.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void declaredSizeRefusedBeforeWriting() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path target = target(cleanup, "tar-declared-size-");
			TarArchiveEntry huge = new TarArchiveEntry(TOP + "/huge");
			huge.setSize((1L << 30) + 1);
			byte[] header = new byte[512];
			huge.writeEntryHeader(header);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (GZIPOutputStream out = new GZIPOutputStream(bytes))
			{
				out.write(header);
			}

			IOException failure = refusal(() -> TarGzExtractor.extract(new ByteArrayInputStream(bytes.toByteArray()),
				target, TOP));

			requireThat(failure.getMessage(), "message").contains(TOP + "/huge").contains("1073741824");
			requireNothingLeft(target);
		}
	}

	/**
	 * Verifies that an archive with more entries than the caller's limit is refused at the first entry past it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void entryLimitRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path target = target(cleanup, "tar-entries-");
			byte[] archive = archive(directory(TOP), file(TOP + "/first", 1), file(TOP + "/second", 1));

			IOException failure = refusal(() -> TarGzExtractor.extract(new ByteArrayInputStream(archive), target, TOP,
				1000, 2));

			requireThat(failure.getMessage(), "message").contains(TOP + "/second");
			requireNothingLeft(target);
		}
	}

	/**
	 * Verifies that an entry with an absolute path is refused and that nothing is written at that path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void absolutePathRefused() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path target = target(cleanup, "tar-absolute-");
			Path escaped = target.getParent().resolve("escaped.txt").toAbsolutePath();
			String name = escaped.toString().replace('\\', '/');
			TarArchiveEntry absolute = new TarArchiveEntry(name, true);
			absolute.setSize(3);
			byte[] archive = archive(directory(TOP), file(TOP + "/ok.txt", 2), absolute);

			IOException failure = refusal(() -> extract(archive, target));

			requireThat(failure.getMessage(), "message").contains(name);
			requireNothingLeft(target);
		}
	}

	/**
	 * Verifies that an archive that holds the same file twice is refused, so that a later entry cannot replace an
	 * earlier one.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void duplicateEntryRefused() throws IOException
	{
		requireRefused("tar-duplicate-", TOP + "/ok.txt", file(TOP + "/ok.txt", 4));
	}

	/**
	 * Verifies that an entry written through a symbolic link that the archive created is refused, even when the link
	 * points inside the top-level directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void entryThroughLinkRefused() throws IOException
	{
		requireRefused("tar-through-link-", TOP + "/link/sub/file.txt", directory(TOP + "/sub"),
			link(TOP + "/link", "sub"), file(TOP + "/link/sub/file.txt", 3));
	}

	/**
	 * Verifies that an entry whose path passes through a chain of symbolic links that resolves outside the target is
	 * refused and writes nothing there. Each link's target, read as text, stays inside the top-level directory, but
	 * {@code d/l} names {@code d} itself, so following {@code ..} through it climbs one directory more than the text
	 * suggests: {@code e} reaches the target directory and {@code f} the directory above it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void entryThroughLinkChainRefused() throws IOException
	{
		requireRefused("tar-link-chain-", TOP + "/", directory(TOP + "/d"), link(TOP + "/d/l", "."),
			link(TOP + "/e", "d/l/../.."), link(TOP + "/f", "e/" + TOP + "/d/l/../../.."),
			file(TOP + "/f/sub/escaped.txt", 3));
	}

	/**
	 * Verifies that a symbolic link whose target, read as text, stays inside the top-level directory but which
	 * resolves outside it through another link is refused.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void linkChainOutsideRefused() throws IOException
	{
		requireRefused("tar-link-chain-outside-", TOP + "/e", directory(TOP + "/d"), link(TOP + "/d/l", "."),
			link(TOP + "/e", "d/l/../.."));
	}

	/**
	 * Extracts an archive that starts with a valid directory and file followed by hostile entries, and requires the
	 * extraction to be refused with a message that names an entry, leaving the target empty.
	 *
	 * @param prefix  the prefix of the test's directory
	 * @param named   the entry that the refusal must name
	 * @param hostile the entries that follow the valid ones
	 * @throws IOException if the fixture cannot be written
	 */
	private static void requireRefused(String prefix, String named, TarArchiveEntry... hostile) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path target = target(cleanup, prefix);
			TarArchiveEntry[] entries = new TarArchiveEntry[hostile.length + 2];
			entries[0] = directory(TOP);
			entries[1] = file(TOP + "/ok.txt", 2);
			System.arraycopy(hostile, 0, entries, 2, hostile.length);
			byte[] archive = archive(entries);

			IOException failure = refusal(() -> extract(archive, target));

			requireThat(failure.getMessage(), "message").contains(named);
			requireNothingLeft(target);
		}
	}

	/**
	 * Creates an empty target directory inside a new directory of the test's own, below the build's directory.
	 *
	 * @param cleanup the scope that deletes the test's directory
	 * @param prefix  the prefix of the test's directory
	 * @return the target directory
	 * @throws IOException if the directories cannot be created
	 */
	private static Path target(TemporaryDirectories cleanup, String prefix) throws IOException
	{
		Path build = Files.createDirectories(Path.of("target/extraction-tests"));
		Path root = cleanup.add(Files.createTempDirectory(build, prefix));
		return Files.createDirectory(root.resolve("target"));
	}

	/**
	 * Extracts an archive with the default limits.
	 *
	 * @param archive the compressed archive
	 * @param target  the empty target directory
	 * @throws IOException if the extraction is refused or fails
	 */
	private static void extract(byte[] archive, Path target) throws IOException
	{
		try (InputStream in = new ByteArrayInputStream(archive))
		{
			TarGzExtractor.extract(in, target, TOP);
		}
	}

	/**
	 * Runs an extraction that must be refused.
	 *
	 * @param extraction the extraction
	 * @return the refusal
	 */
	private static IOException refusal(Extraction extraction)
	{
		try
		{
			extraction.run();
		}
		catch (IOException e)
		{
			return e;
		}
		throw new AssertionError("The extraction succeeded");
	}

	/**
	 * Requires that a refused extraction left its target empty and wrote nothing beside it.
	 *
	 * @param target the target directory
	 * @throws IOException if a directory cannot be listed
	 */
	private static void requireNothingLeft(Path target) throws IOException
	{
		requireThat(names(target.getParent()), "testDirectory").isEqualTo(List.of("target"));
		requireThat(names(target), "target").isEmpty();
	}

	/**
	 * Returns the names in a directory, sorted.
	 *
	 * @param directory the directory
	 * @return the names
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<String> names(Path directory) throws IOException
	{
		try (Stream<Path> paths = Files.list(directory))
		{
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Returns a directory entry.
	 *
	 * @param name the entry's name
	 * @return the entry
	 */
	private static TarArchiveEntry directory(String name)
	{
		return new TarArchiveEntry(name + "/");
	}

	/**
	 * Returns a regular-file entry whose content is {@code size} repetitions of {@code x}.
	 *
	 * @param name the entry's name
	 * @param size the content's size in bytes
	 * @return the entry
	 */
	private static TarArchiveEntry file(String name, int size)
	{
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(size);
		return entry;
	}

	/**
	 * Returns a symbolic-link entry.
	 *
	 * @param name   the entry's name
	 * @param target the link's target
	 * @return the entry
	 */
	private static TarArchiveEntry link(String name, String target)
	{
		TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
		entry.setLinkName(target);
		return entry;
	}

	/**
	 * Returns a gzip-compressed tar archive of entries. A regular file's content is its size in repetitions of
	 * {@code x}; every other entry has no content.
	 *
	 * @param entries the entries
	 * @return the archive
	 * @throws IOException if the archive cannot be written
	 */
	private static byte[] archive(TarArchiveEntry... entries) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream out = new TarArchiveOutputStream(new GZIPOutputStream(bytes)))
		{
			out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			for (TarArchiveEntry entry : entries)
			{
				out.putArchiveEntry(entry);
				if (entry.isFile())
					out.write("x".repeat((int) entry.getSize()).getBytes(UTF_8));
				out.closeArchiveEntry();
			}
		}
		return bytes.toByteArray();
	}

	/**
	 * An extraction whose refusal a test expects.
	 */
	@FunctionalInterface
	private interface Extraction
	{
		/**
		 * Runs the extraction.
		 *
		 * @throws IOException if the extraction is refused or fails
		 */
		void run() throws IOException;
	}
}
