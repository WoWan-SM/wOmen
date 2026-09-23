package com.l2walker.oog.protocol;

import com.l2walker.oog.config.SetIniConfig;

/**
 * Gracia Epilogue OOG entry point: validates {@code set.ini} and runs {@link LoginClient152}.
 */
public final class LoginProtocol152 {

	private final SetIniConfig config;

	public LoginProtocol152(SetIniConfig config) {
		this.config = config;
	}

	public void describe() {
		System.out.println("Dry run — config is valid, no socket opened.");
		System.out.println("ProtocolVersion=" + config.protocolVersion());
		System.out.println("LoginServer=" + config.loginServerIp() + ":" + config.loginServerPort());
		System.out.println("ServerId=" + config.serverId() + ", account=" + mask(config.account()));
		System.out.println("Local handshake: ./gradlew run --args=--demo");
		System.out.println("Private server:  ./gradlew run --args=config/set.ini");
	}

	public LoginResult login() throws Exception {
		return new LoginClient152().login(config);
	}

	private static String mask(String account) {
		if (account == null || account.length() < 2) {
			return "***";
		}
		return account.charAt(0) + "***" + account.charAt(account.length() - 1);
	}
}
