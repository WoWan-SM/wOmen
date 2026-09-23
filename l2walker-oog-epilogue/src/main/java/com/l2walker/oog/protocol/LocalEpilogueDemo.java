package com.l2walker.oog.protocol;

import com.l2walker.oog.config.SetIniConfig;

/**
 * Runs the OOG client against the loopback login and game servers.
 */
public final class LocalEpilogueDemo {

	private LocalEpilogueDemo() {
	}

	public static LoginResult run(String account, String password, String characterName) throws Exception {
		SessionTable sessions = new SessionTable();
		try (LocalLoginServer login = new LocalLoginServer(account, password, sessions);
				LocalGameServer game = new LocalGameServer(sessions, characterName)) {
			login.start();
			game.start();
			login.setGameEndpoint("127.0.0.1", game.port());
			SetIniConfig config = new SetIniConfig(
					152,
					"127.0.0.1",
					login.port(),
					"",
					game.port(),
					true,
					false,
					account,
					password,
					0,
					"INFO",
					1);
			LoginResult result = new LoginClient152().login(config);
			if (login.failure() != null) {
				throw new IllegalStateException("login harness failed", login.failure());
			}
			if (game.failure() != null) {
				throw new IllegalStateException("game harness failed", game.failure());
			}
			return result;
		}
	}
}
