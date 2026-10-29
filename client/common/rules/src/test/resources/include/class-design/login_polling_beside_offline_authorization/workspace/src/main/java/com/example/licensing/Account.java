package com.example.licensing;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * A customer's account on this machine.
 */
public final class Account
{
	/**
	 * How long after its expiry a license still allows offline use.
	 */
	private static final Duration GRACE_PERIOD = Duration.ofDays(7);

	private final String email;
	private final LicenseStore licenses;
	private final Clock clock;

	/**
	 * Creates an account.
	 *
	 * @param email    the customer's email address
	 * @param licenses the licenses stored on this machine
	 * @param clock    the clock that tells the current time
	 */
	public Account(String email, LicenseStore licenses, Clock clock)
	{
		this.email = email;
		this.licenses = licenses;
		this.clock = clock;
	}

	/**
	 * @return the customer's email address
	 */
	public String email()
	{
		return email;
	}

	/**
	 * Returns whether the customer may use a product while this machine is offline.
	 *
	 * @param product the product's identifier
	 * @return {@code true} if the stored license covers the product and has not expired more than the grace period ago
	 */
	public boolean mayUseOffline(String product)
	{
		Optional<License> stored = licenses.load(email);
		if (stored.isEmpty())
			return false;
		License license = stored.get();
		if (!license.products().contains(product))
			return false;
		return clock.instant().isBefore(license.expires().plus(GRACE_PERIOD));
	}
}
