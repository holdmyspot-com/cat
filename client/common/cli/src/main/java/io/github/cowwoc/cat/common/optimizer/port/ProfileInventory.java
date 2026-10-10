package io.github.cowwoc.cat.common.optimizer.port;

import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lists the current harness's trial profiles with their source, and reads the profile that a prompt file binds.
 * <p>
 * A file binds a profile only through a field that its harness applies whenever it runs the file: {@code model} and
 * {@code effort} in Claude skill and subagent frontmatter, where {@code inherit} binds nothing, and {@code model} and
 * {@code model_reasoning_effort} in a Codex custom agent's TOML. Every other file binds nothing.
 */
public interface ProfileInventory
{
	/**
	 * The installed agent definition whose binding supplies the role profile: CAT's highest implementer tier.
	 */
	String ROLE_AGENT = "optimize-xhigh";

	/**
	 * Returns the harness's profile inventory: every model that it offers the user, each with every reasoning effort
	 * that the model accepts.
	 *
	 * @return the inventory and its source
	 * @throws IOException if the inventory cannot be read
	 */
	ProfileCatalog inventory() throws IOException;

	/**
	 * Returns the capability ranking of the harness's model families that CAT ships, taken from the vendor's
	 * published model documentation.
	 *
	 * @return the ranking, whose source names the documentation's address and the date it was read
	 * @throws IOException if the ranking cannot be read
	 */
	CapabilityRanking ranking() throws IOException;

	/**
	 * Indicates whether the harness reads a profile binding from a file at a path.
	 *
	 * @param path the file's path, relative to the project or absolute
	 * @return {@code true} if the file is a Claude skill or subagent, or a Codex custom agent
	 * @throws NullPointerException if {@code path} is null
	 */
	boolean canBind(Path path);

	/**
	 * Reads the profile that a binding file binds.
	 *
	 * @param content the content of a file for which {@link #canBind(Path)} returns {@code true}
	 * @return the bound dimensions; an empty dimension is left open, and {@link TrialProfile#HARNESS_DEFAULT} if the
	 *   file binds nothing
	 * @throws NullPointerException if {@code content} is null
	 */
	TrialProfile binding(String content);

	/**
	 * Indicates whether the harness accepts a model in a trial profile.
	 *
	 * @param model the model's name
	 * @return {@code true} if the harness runs trials and sessions under the model
	 * @throws NullPointerException if {@code model} is null
	 */
	boolean supportsModel(String model);

	/**
	 * Returns the harness's role profile, which the contract author, judge and prompt author sessions run under: the
	 * profile that the installed definition of CAT's highest implementer tier, {@code optimize-xhigh}, binds.
	 *
	 * @return the role profile
	 * @throws IOException if the installed agent definition is missing or binds no profile
	 */
	TrialProfile roleProfile() throws IOException;
}
