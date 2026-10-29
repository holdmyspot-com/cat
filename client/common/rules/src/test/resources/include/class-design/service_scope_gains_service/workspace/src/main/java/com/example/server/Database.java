package com.example.server;

import java.util.HashMap;
import java.util.Map;

/**
 * The server's database.
 */
public final class Database implements AutoCloseable
{
	private final String url;
	private final Map<String, String> productNames = new HashMap<>();

	/**
	 * Opens the database.
	 *
	 * @param url the address of the database
	 */
	public Database(String url)
	{
		this.url = url;
	}

	/**
	 * Returns a product's name.
	 *
	 * @param id the product's identifier
	 * @return the name, or {@code null} if the product does not exist
	 */
	public String productName(String id)
	{
		return productNames.get(id);
	}

	@Override
	public void close()
	{
		productNames.clear();
	}

	@Override
	public String toString()
	{
		return "Database at " + url;
	}
}
