"""Account storage for the cat-accounts command."""


class Accounts:
    def __init__(self):
        self.users = {}       # user -> profile dict
        self.documents = {}   # document id -> {"owner": user, "editors": set of users, "text": str}

    def delete(self, user: str) -> None:
        raise NotImplementedError
