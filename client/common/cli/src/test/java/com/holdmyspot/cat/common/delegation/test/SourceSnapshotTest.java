package com.holdmyspot.cat.common.delegation.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.delegation.SourceSnapshot;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests immutable source capture and materialization.
 */
public final class SourceSnapshotTest
{
	/**
	 * Creates the test fixture.
	 */
	public SourceSnapshotTest()
	{
	}

	/**
	 * Verifies captured bytes survive later source edits and materialization.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void rawBytesSurviveAttributes() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-cohort-"));
			Path source = project.resolve("line endings.txt");
			byte[] expected = {'a', '\r', '\n', 'b', '\n'};
			Files.write(source, expected);
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort, List.of("line endings.txt"));
			Files.writeString(source, "changed");
			Path materialized = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-copy-"));

			snapshot.materialize(materialized);

			requireThat(Files.readAllBytes(materialized.resolve("line endings.txt")), "bytes").isEqualTo(expected);
		}
	}

	/**
	 * Verifies metadata-only permission changes alter the captured identity.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void metadataIdentityTracksPermissions() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path source = project.resolve("metadata.txt");
			Files.writeString(source, "metadata");
			if (Files.getFileStore(source).supportsFileAttributeView("posix"))
			{
				String before = SourceSnapshot.metadataIdentity(source);
				Files.setPosixFilePermissions(source, EnumSet.of(PosixFilePermission.OWNER_READ));
				requireThat(SourceSnapshot.metadataIdentity(source), "metadataChangesDetected").isNotEqualTo(before);
			}
		}
	}

	/**
	 * Verifies selected tracked deletions remain absent in the materialized snapshot.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void deletedSourceStaysAbsent() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-cohort-"));
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort, List.of("deleted.java"));
			Path materialized = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-copy-"));

			snapshot.materialize(materialized);

			requireThat(Files.notExists(materialized.resolve("deleted.java")), "deletedPathIsAbsent").isTrue();
		}
	}

	/**
	 * Verifies unselected project files do not enter the snapshot.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void unrelatedFilesAreExcluded() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-cohort-"));
			Files.writeString(project.resolve("source.java"), "selected");
			Files.writeString(project.resolve(".env"), "not source");
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort, List.of("source.java"));
			Path materialized = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-copy-"));

			snapshot.materialize(materialized);

			requireThat(Files.exists(materialized.resolve("source.java")), "selectedPathExists").isTrue();
			requireThat(Files.notExists(materialized.resolve(".env")), "unselectedPathIsAbsent").isTrue();
		}
	}

	/**
	 * Verifies a safe leaf symlink and an international filename survive snapshot materialization.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void safeLeafLinkAndUnicodePath() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-cohort-"));
			Path source = project.resolve("space and café.txt");
			Files.writeString(source, "safe bytes");
			FileTime targetTime = FileTime.fromMillis(1_600_000_000_000L);
			Files.setLastModifiedTime(source, targetTime);
			Path link = project.resolve("safe link.txt");
			Files.createSymbolicLink(link, source.getFileName());
			FileTime linkTime = FileTime.fromMillis(1_500_000_000_000L);
			BasicFileAttributeView sourceLinkAttributes = Files.getFileAttributeView(link,
				BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			sourceLinkAttributes.setTimes(linkTime, null, null);
			String metadataIdentity = SourceSnapshot.metadataIdentity(link);
			SourceSnapshot snapshot = SourceSnapshot.capture(project, cohort,
				List.of("space and café.txt", "safe link.txt"));
			Path materialized = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-copy-"));

			snapshot.materialize(materialized);

			requireThat(Files.readString(materialized.resolve("space and café.txt")), "unicodeSource").
				isEqualTo("safe bytes");
			Path materializedLink = materialized.resolve("safe link.txt");
			requireThat(Files.isSymbolicLink(materializedLink), "leafLinkPreserved").isTrue();
			requireThat(Files.readSymbolicLink(materializedLink), "leafLinkText").isEqualTo(source.getFileName());
			requireThat(Files.getLastModifiedTime(materializedLink, LinkOption.NOFOLLOW_LINKS), "leafLinkTime").
				isEqualTo(linkTime);
			requireThat(SourceSnapshot.metadataIdentity(materializedLink), "leafLinkMetadata").
				isEqualTo(metadataIdentity);
			requireThat(Files.readString(source), "sourceTargetBytes").isEqualTo("safe bytes");
			requireThat(Files.getLastModifiedTime(source), "sourceTargetTime").isEqualTo(targetTime);
			requireThat(Files.getLastModifiedTime(materialized.resolve("space and café.txt")), "copyTargetTime").
				isEqualTo(targetTime);
		}
	}

	/**
	 * Verifies traversal, Git administrative paths, and symlink ancestors are rejected.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void unsafePathsAreRejected() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-cohort-"));
			Path outside = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-outside-"));
			Files.writeString(outside.resolve("secret.txt"), "secret");
			Files.createSymbolicLink(project.resolve("alias"), outside);
			for (String path : List.of("../secret.txt", ".git/config", "alias/secret.txt"))
			{
				IOException failure = null;
				try
				{
					SourceSnapshot.capture(project, cohort.resolve(path.replace('/', '_')), List.of(path));
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "unsafePathRejected").isNotNull();
			}
		}
	}

	/**
	 * Verifies filesystem socket entries are rejected as special files.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void specialFileIsRejected() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(Path.of("/tmp"), "p-"));
			Path cohort = cleanup.add(Files.createTempDirectory(Path.of("/tmp"), "c-"));
			Path socket = project.resolve("special.sock");
			try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX))
			{
				server.bind(UnixDomainSocketAddress.of(socket));
				IOException failure = null;
				try
				{
					SourceSnapshot.capture(project, cohort, List.of("special.sock"));
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "specialFileRejected").isNotNull();
			}
		}
	}

	/**
	 * Verifies a selected source that shares an inode with another path is rejected.
	 *
	 * @throws IOException if the temporary fixture cannot be created
	 */
	@Test
	public void hardLinkedSourceIsRejected() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-project-"));
			Path cohort = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "snapshot-cohort-"));
			Path source = project.resolve("source.txt");
			Files.writeString(source, "bytes");
			Files.createLink(project.resolve("alias.txt"), source);
			IOException failure = null;
			try
			{
				SourceSnapshot.capture(project, cohort, List.of("source.txt"));
			}
			catch (IOException e)
			{
				failure = e;
			}
			requireThat(failure, "hardLinkRejected").isNotNull();
		}
	}
}
