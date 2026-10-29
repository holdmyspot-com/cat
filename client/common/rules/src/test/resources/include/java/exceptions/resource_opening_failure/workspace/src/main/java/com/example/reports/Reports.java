package com.example.reports;

final class Reports
{
	static Session open(Account account) throws {{exception}}
	{
		return account.session();
	}

	static Report summarize(Session session)
	{
		return session.summary();
	}

	static Report export(Session session) throws {{exception}}
	{
		return session.export();
	}

	static Report denied(Account account, {{exception}} failure)
	{
		return Report.denied(account, failure.getMessage());
	}

	static Report summary(Account account)
	{
		Session session;
		try
		{
			session = open(account);
		}
		catch ({{exception}} failure)
		{
			return denied(account, failure);
		}
		try (session)
		{
			return summarize(session);
		}
	}

	static Report exported(Account account) throws {{exception}}
	{
		Session session;
		try
		{
			session = open(account);
		}
		catch ({{exception}} failure)
		{
			return denied(account, failure);
		}
		try (session)
		{
			return export(session);
		}
	}
}
