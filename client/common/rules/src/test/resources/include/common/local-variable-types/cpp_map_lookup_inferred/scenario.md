# C++ map lookup inferred

## Variables

- **missing_message**: The text the function prints when the player has no recorded scores, such as `no scores yet`

## Scenario

Our C++17 game server keeps each player's round scores in a `std::map<std::string, std::vector<int>>` named
`scoresByPlayer`. Write a function that takes that map by const reference and a player name, looks the player up once,
and prints the player's best score, or prints `{{missing_message}}` when the player is not in the map. Show only the
function and the headers it needs.

## Assertions

### lookup_declared_by_inference

- **kind**: effect
- **description**: The function holds the result of `scoresByPlayer.find(player)` in a local variable declared with
	`auto`, such as `auto entry = scoresByPlayer.find(player);`, and does not declare it with an explicit iterator type
	such as `std::map<std::string, std::vector<int>>::const_iterator`. It prints the largest score of a player who is in
	the map and `{{missing_message}}` otherwise.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/local-variable-types.md` before its first governed action.
