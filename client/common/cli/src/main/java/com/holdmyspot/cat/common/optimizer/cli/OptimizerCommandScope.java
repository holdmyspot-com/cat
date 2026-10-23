package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Supplies public services shared by one prompt-optimizer command.
 */
public interface OptimizerCommandScope extends JvmScope
{
	/**
	 * Returns the port that starts nested sessions through the harness's runner.
	 *
	 * @return the port
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the port's run roots cannot be resolved
	 */
	SessionPort sessions() throws IOException;

	/**
	 * Returns the port that lists the harness's profile inventory, reads prompt files' profile bindings and reads
	 * the role profile.
	 *
	 * @return the port
	 * @throws IllegalStateException if this scope is closed
	 */
	ProfileInventory profiles();

	/**
	 * Returns the signal that reports whether the host user interrupted the host session.
	 *
	 * @return the signal
	 * @throws IllegalStateException if this scope is closed
	 */
	HostInterruptSignal interrupts();

	/**
	 * Returns the replacer that atomically replaces the files that this command writes.
	 *
	 * @return the replacer
	 * @throws IllegalStateException if this scope is closed
	 */
	FileReplacer fileReplacer();

	/**
	 * Returns the launcher's working directory, below the project.
	 *
	 * @return the directory
	 * @throws IllegalStateException if this scope is closed
	 */
	Path directory();

	/**
	 * Returns the user's home directory supplied by the harness environment boundary.
	 *
	 * @return the directory whose {@code .cat/tests} tree holds scenarios for user rules
	 * @throws IllegalStateException if this scope is closed
	 */
	Path home();

	/**
	 * Returns the {@code prompt-optimizer} launcher that runs this command, which a contract author session runs for
	 * the contract tools.
	 *
	 * @return the launcher's absolute path
	 * @throws IllegalStateException if this scope is closed
	 */
	Path launcher();
}
