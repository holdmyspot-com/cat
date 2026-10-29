package com.example.licensing;

import java.util.Optional;

/**
 * The licenses stored on this machine.
 */
public interface LicenseStore
{
	/**
	 * Returns the license stored for a customer.
	 *
	 * @param email the customer's email address
	 * @return the license, or an empty value if none is stored
	 */
	Optional<License> load(String email);
}
