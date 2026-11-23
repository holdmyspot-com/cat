package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A launch's choice of the credential that its nested session uses: the {@link SourceHome} whose harness folders hold
 * it, and the class of login that a gateway bills when the credential sends its requests to a base URL. Both harnesses'
 * runners take the same two options, and the runner of every session that a nested session requests, of either
 * harness, receives its parent runner's choice.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param home              the absolute folder that the launch names with {@value #HOME_OPTION}, or empty for the
 *                          runner's own home folder
 * @param gatewayLoginClass the class that the launch declares with {@value #LOGIN_CLASS_OPTION}, {@code subscription}
 *                          or {@code api_key}, or empty if it declares none
 * @param inherited         {@code true} if a nested session's runner passed on its own launch's choice to this
 *                          launch's runner, {@code false} if the launch made the choice itself
 */
public record AuthenticationOptions(Optional<Path> home, Optional<HostLogin> gatewayLoginClass, boolean inherited)
{
	/**
	 * The option that names the source home.
	 */
	public static final String HOME_OPTION = "--home";
	/**
	 * The option that declares the class of login that a gateway bills.
	 */
	public static final String LOGIN_CLASS_OPTION = "--gateway-login-class";
	/**
	 * The choice of a launch that names neither option: the runner's own home folder and no declared class.
	 */
	public static final AuthenticationOptions DEFAULT = new AuthenticationOptions(Optional.empty(), Optional.empty(),
		false);

	/**
	 * Creates a choice.
	 *
	 * @param home              the absolute folder that the launch names with {@value #HOME_OPTION}, or empty for the
	 *                          runner's own home folder
	 * @param gatewayLoginClass the class that the launch declares with {@value #LOGIN_CLASS_OPTION}, or empty if it
	 *                          declares none
	 * @param inherited         {@code true} if a nested session's runner passed on its own launch's choice, {@code false}
	 *                          if the launch made the choice itself
	 * @throws NullPointerException     if {@code home} or {@code gatewayLoginClass} are null
	 * @throws IllegalArgumentException if {@code home} is not absolute, or {@code gatewayLoginClass} is
	 *                                  {@link HostLogin#UNKNOWN}
	 */
	public AuthenticationOptions
	{
		requireThat(home, "home").isNotNull();
		requireThat(gatewayLoginClass, "gatewayLoginClass").isNotNull();
		if (home.isPresent() && !home.get().isAbsolute())
			throw new IllegalArgumentException("home must be absolute: " + home.get());
		if (gatewayLoginClass.isPresent() && gatewayLoginClass.get() == HostLogin.UNKNOWN)
			throw new IllegalArgumentException("gatewayLoginClass may not be UNKNOWN");
	}

	/**
	 * Reads the choice that a runner's options make: {@value #HOME_OPTION}, {@value #LOGIN_CLASS_OPTION} and
	 * {@value RunnerArguments#INHERITED_SOURCE_HOME_OPTION}. Every other option is left to the runner's own parser.
	 *
	 * @param args the runner's options
	 * @return the choice
	 * @throws NullPointerException      if {@code args} is null
	 * @throws InvalidArgumentsException if an option is repeated or lacks its value, if {@value #HOME_OPTION} is not an
	 *                                   absolute path, or if {@value #LOGIN_CLASS_OPTION} is neither
	 *                                   {@code subscription} nor {@code api_key}
	 */
	public static AuthenticationOptions parse(String[] args)
	{
		requireThat(args, "args").isNotNull();
		Path home = null;
		HostLogin loginClass = null;
		boolean inherited = false;
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			switch (option)
			{
				case HOME_OPTION ->
				{
					if (home != null)
						throw new InvalidArgumentsException("Repeated option: " + option);
					home = parseHome(value(args, index));
					index += 1;
				}
				case LOGIN_CLASS_OPTION ->
				{
					if (loginClass != null)
						throw new InvalidArgumentsException("Repeated option: " + option);
					loginClass = parseLoginClass(value(args, index));
					index += 1;
				}
				case RunnerArguments.INHERITED_SOURCE_HOME_OPTION -> inherited = true;
				default ->
				{
				}
			}
		}
		return new AuthenticationOptions(Optional.ofNullable(home), Optional.ofNullable(loginClass), inherited);
	}

	/**
	 * Reads options that make the choice and nothing else, such as those that follow the file of a request for a
	 * child's needs.
	 *
	 * @param args the options
	 * @return the choice
	 * @throws NullPointerException      if {@code args} is null
	 * @throws InvalidArgumentsException if an option other than {@value #HOME_OPTION} and {@value #LOGIN_CLASS_OPTION}
	 *                                   appears, or {@link #parse(String[])} refuses the options
	 */
	public static AuthenticationOptions parseAlone(String[] args)
	{
		requireThat(args, "args").isNotNull();
		for (int index = 0; index < args.length; index += 2)
		{
			if (!args[index].equals(HOME_OPTION) && !args[index].equals(LOGIN_CLASS_OPTION))
			{
				throw new InvalidArgumentsException("Unsupported option: " + args[index] + ". Only " + HOME_OPTION +
					" and " + LOGIN_CLASS_OPTION + " may follow the file.");
			}
		}
		return parse(args);
	}

	/**
	 * Returns the value of the option at an index.
	 *
	 * @param args  the options
	 * @param index the option's index
	 * @return the value
	 * @throws InvalidArgumentsException if the option has no value
	 */
	private static String value(String[] args, int index)
	{
		if (index + 1 >= args.length || args[index + 1].startsWith("--"))
			throw new InvalidArgumentsException("Missing value for " + args[index]);
		return args[index + 1];
	}

	/**
	 * Reads the value of {@value #HOME_OPTION}.
	 *
	 * @param value the option's value
	 * @return the folder
	 * @throws NullPointerException      if {@code value} is null
	 * @throws InvalidArgumentsException if {@code value} is not an absolute path
	 */
	public static Path parseHome(String value)
	{
		requireThat(value, "value").isNotNull();
		Path home = Path.of(value);
		if (!home.isAbsolute())
		{
			throw new InvalidArgumentsException(HOME_OPTION + " must name an absolute folder, but it names " + value +
				". Name the home folder by its absolute path.");
		}
		return home;
	}

	/**
	 * Reads the value of {@value #LOGIN_CLASS_OPTION}.
	 *
	 * @param value the option's value
	 * @return the class
	 * @throws NullPointerException      if {@code value} is null
	 * @throws InvalidArgumentsException if {@code value} is neither {@code subscription} nor {@code api_key}
	 */
	public static HostLogin parseLoginClass(String value)
	{
		requireThat(value, "value").isNotNull();
		for (HostLogin login : List.of(HostLogin.SUBSCRIPTION, HostLogin.API_KEY))
		{
			if (classValue(login).equals(value))
				return login;
		}
		throw new InvalidArgumentsException(LOGIN_CLASS_OPTION + " must be subscription or api_key, but it is " +
			value + ". Name the kind of login that the gateway bills.");
	}

	/**
	 * Returns the text that names a class of login in an option and in records.
	 *
	 * @param login the class
	 * @return the lower-case name of the class, such as {@code api_key}
	 * @throws NullPointerException if {@code login} is null
	 */
	public static String classValue(HostLogin login)
	{
		requireThat(login, "login").isNotNull();
		return login.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Resolves the source home that this choice selects.
	 *
	 * @param environment the runner's environment
	 * @param platform    the platform whose home variable names the runner's home folder
	 * @return the source home
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the named folder does not exist, or no folder is named and the environment
	 *                                 names no home folder
	 */
	public SourceHome sourceHome(Map<String, String> environment, Platform platform) throws SessionRefusedException
	{
		return SourceHome.resolve(home.orElse(null), environment, platform);
	}

	/**
	 * Returns the options that state this choice to another runner, such as the runner that a request server starts.
	 *
	 * @return {@value #HOME_OPTION} and {@value #LOGIN_CLASS_OPTION} with their values, each only if the choice names
	 *   it
	 */
	public List<String> arguments()
	{
		List<String> arguments = new ArrayList<>();
		home.ifPresent(folder -> arguments.addAll(List.of(HOME_OPTION, folder.toString())));
		gatewayLoginClass.ifPresent(login -> arguments.addAll(List.of(LOGIN_CLASS_OPTION, classValue(login))));
		return arguments;
	}

	/**
	 * Returns the options that pass this choice on to the runner of a session that the nested session requests.
	 *
	 * @return {@link #arguments()} followed by {@value RunnerArguments#INHERITED_SOURCE_HOME_OPTION}
	 */
	public List<String> inheritedArguments()
	{
		List<String> arguments = arguments();
		arguments.add(RunnerArguments.INHERITED_SOURCE_HOME_OPTION);
		return arguments;
	}
}
