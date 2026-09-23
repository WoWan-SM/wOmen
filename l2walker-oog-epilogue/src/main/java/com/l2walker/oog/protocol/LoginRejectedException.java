package com.l2walker.oog.protocol;

import java.io.IOException;

public final class LoginRejectedException extends IOException {

	private final int reason;

	public LoginRejectedException(int reason) {
		super(message(reason));
		this.reason = reason;
	}

	public int reason() {
		return reason;
	}

	private static String message(int reason) {
		return switch (reason) {
			case 0x02 -> "login rejected: account or password is wrong (" + reason + ")";
			case 0x07 -> "login rejected: account already in use (" + reason + ")";
			default -> "login rejected, reason " + reason;
		};
	}
}
