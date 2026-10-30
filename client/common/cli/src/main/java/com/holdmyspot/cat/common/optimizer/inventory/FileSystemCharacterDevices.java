package com.holdmyspot.cat.common.optimizer.inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The {@link CharacterDevices} that read a file's type from its file system.
 * <p>
 * A file system without the {@code unix} attribute view, such as Windows', holds no character devices.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class FileSystemCharacterDevices implements CharacterDevices
{
	/**
	 * The bits of a Unix file mode that hold the file's type.
	 */
	private static final int FILE_TYPE_MASK = 0170000;
	/**
	 * The file type of a character device.
	 */
	private static final int CHARACTER_DEVICE = 0020000;

	/**
	 * Creates the classifier.
	 */
	public FileSystemCharacterDevices()
	{
	}

	@Override
	public boolean isCharacterDevice(Path file, BasicFileAttributes attributes) throws IOException
	{
		// A regular file, directory or link is never a device, so only other files cost a second read
		if (!attributes.isOther())
			return false;
		if (!file.getFileSystem().supportedFileAttributeViews().contains("unix"))
			return false;
		int mode = (int) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS);
		return (mode & FILE_TYPE_MASK) == CHARACTER_DEVICE;
	}
}
