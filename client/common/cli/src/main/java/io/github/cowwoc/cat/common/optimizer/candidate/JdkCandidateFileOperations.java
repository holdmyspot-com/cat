package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * JDK implementation of {@link CandidateFileOperations}.
 */
final class JdkCandidateFileOperations implements CandidateFileOperations
{
	@Override
	public void writeAndForceNew(Path privateStage, byte[] bytes) throws IOException
	{
		try (FileChannel channel = FileChannel.open(privateStage, StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE))
		{
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining())
				channel.write(buffer);
			channel.force(true);
		}
	}

	@Override
	public PathInspection captureAtomically(Path observedTarget, Path absentPrivateSibling) throws IOException
	{
		if (inspect(observedTarget).presence() != DeliveryPresence.REGULAR)
			throw new NoSuchFileException(observedTarget.toString());
		try
		{
			Files.readAttributes(absentPrivateSibling, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			throw new FileAlreadyExistsException(absentPrivateSibling.toString());
		}
		catch (NoSuchFileException _)
		{
			// Only a confirmed missing private sibling permits the atomic move.
		}
		Files.move(observedTarget, absentPrivateSibling, StandardCopyOption.ATOMIC_MOVE);
		return inspect(absentPrivateSibling);
	}

	@Override
	public LinkPublication linkNew(Path privateSource, Path absentTarget, String expectedSha256) throws IOException
	{
		LinkPublication publication = new LinkPublication(expectedSha256);
		PathInspection source = inspect(privateSource);
		if (source.presence() != DeliveryPresence.REGULAR ||
			!source.contentSha256().orElseThrow().equals(publication.expectedSha256()))
		{
			throw new IOException("Private source has unexpected SHA-256: " + privateSource);
		}
		Files.createLink(absentTarget, privateSource);
		return publication;
	}

	@Override
	public boolean sameFile(Path first, Path second) throws IOException
	{
		PathInspection firstInspection = inspect(first);
		PathInspection secondInspection = inspect(second);
		if (firstInspection.presence() != DeliveryPresence.REGULAR ||
			secondInspection.presence() != DeliveryPresence.REGULAR ||
			first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize()))
		{
			return false;
		}
		return Files.isSameFile(first, second);
	}

	@Override
	public PathInspection inspect(Path path) throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return new PathInspection(DeliveryPresence.ABSENT, Optional.empty(), Optional.empty());
		}
		if (!attributes.isRegularFile())
			throw new IOException("Unsupported file kind: " + path);

		MessageDigest digest = newDigest();
		try (FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
		{
			ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
			while (channel.read(buffer) != -1)
			{
				buffer.flip();
				digest.update(buffer);
				buffer.clear();
			}
		}
		return new PathInspection(DeliveryPresence.REGULAR, Optional.of(PromptSnapshot.FileKind.REGULAR),
			Optional.of(HexFormat.of().formatHex(digest.digest())));
	}

	@Override
	public void unlinkPrivate(Path privatePath) throws IOException
	{
		Files.delete(privatePath);
	}

	@Override
	public void deletePrivate(Path privatePath) throws IOException
	{
		Files.delete(privatePath);
	}

	@Override
	public void createDirectoryNew(Path absentDirectory) throws IOException
	{
		Files.createDirectory(absentDirectory);
	}

	@Override
	public void deleteEmptyOwnedDirectory(Path directory) throws IOException
	{
		Files.delete(directory);
	}

	@Override
	public void forceDirectory(Path directory) throws IOException
	{
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
		{
			channel.force(true);
		}
	}

	/**
	 * Creates the mandatory SHA-256 digest.
	 *
	 * @return a new digest
	 */
	private static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("SHA-256 is unavailable", e);
		}
	}
}
