package com.example.shop;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;

public final class Basket
{
	private final String owner;
	private final int {{limit_field}};
	private final List<String> items;
	private int remaining;

	public Basket(String owner, int {{limit_field}})
	{
		requireThat(owner, "owner").isNotBlank();
		this.owner = owner;
		this.{{limit_field}} = {{limit_field}};
		items = new ArrayList<>({{limit_field}});
		remaining = {{limit_field}};
	}

	public void clear()
	{
		items.clear();
		remaining = {{limit_field}};
	}

	public void add(String item)
	{
		if (remaining == 0)
			throw new IllegalStateException("The basket of " + owner + " is full");
		items.add(item);
		--remaining;
	}
}
