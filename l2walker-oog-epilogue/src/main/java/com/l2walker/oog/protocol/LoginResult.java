package com.l2walker.oog.protocol;

import java.util.List;

/** Outcome of an OOG login plus the game-server auth step. */
public record LoginResult(
		String account,
		int serverId,
		String gameHost,
		int gamePort,
		int characterCount,
		int maxCharacters,
		List<String> characterNames
) {
}
