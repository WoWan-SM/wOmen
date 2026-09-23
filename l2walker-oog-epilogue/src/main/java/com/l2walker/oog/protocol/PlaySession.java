package com.l2walker.oog.protocol;

/** Session keys shared by the local login and game harness. */
public record PlaySession(
		String account,
		int loginOk1,
		int loginOk2,
		int playOk1,
		int playOk2
) {
}
