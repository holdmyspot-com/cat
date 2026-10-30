package com.holdmyspot.cat.common.optimizer.inventory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Tells whether a file is a character device, which CAT treats as absent from a project.
 * <p>
 * Inside a sandboxed Claude Code session, Claude Code lays a {@code /dev/null} character device over each path that it
 * protects, such as {@code .bashrc} or {@code .vscode}. Git lists each one as untracked, and the session cannot remove
 * them, so a project would otherwise never be clean and never fork. An inventory therefore leaves character devices
 * out, and the clean-base check does not count them.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
@FunctionalInterface
public interface CharacterDevices
{
	/**
	 * Indicates whether a file is a character device.
	 *
	 * @param file       the file's absolute path
	 * @param attributes the file's attributes, read without following symbolic links
	 * @return {@code true} if {@code file} is a character device
	 * @throws IOException if the file's type cannot be read
	 */
	boolean isCharacterDevice(Path file, BasicFileAttributes attributes) throws IOException;
}
