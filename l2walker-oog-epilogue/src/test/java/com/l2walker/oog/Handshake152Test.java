package com.l2walker.oog;

import com.l2walker.oog.config.SetIniConfig;
import com.l2walker.oog.protocol.LocalEpilogueDemo;
import com.l2walker.oog.protocol.LocalLoginServer;
import com.l2walker.oog.protocol.LoginClient152;
import com.l2walker.oog.protocol.LoginRejectedException;
import com.l2walker.oog.protocol.LoginResult;
import com.l2walker.oog.protocol.SessionTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Handshake152Test {

	@Test
	void localEpilogueHandshake() throws Exception {
		LoginResult result = LocalEpilogueDemo.run("Epilogue", "secret", "HumanFighter");
		assertEquals("epilogue", result.account());
		assertEquals(1, result.serverId());
		assertEquals("127.0.0.1", result.gameHost());
		assertEquals(1, result.characterCount());
		assertEquals(7, result.maxCharacters());
		assertEquals("HumanFighter", result.characterNames().get(0));
	}

	@Test
	void wrongPasswordIsRejected() throws Exception {
		SessionTable sessions = new SessionTable();
		try (LocalLoginServer login = new LocalLoginServer("epilogue", "secret", sessions)) {
			login.start();
			SetIniConfig config = new SetIniConfig(
					152, "127.0.0.1", login.port(), "", 7777, true, false, "epilogue", "nope", 0, "INFO", 1);
			LoginRejectedException rejected = assertThrows(LoginRejectedException.class, () -> new LoginClient152().login(config));
			assertEquals(0x02, rejected.reason());
			assertTrue(login.acceptedAccount() == null);
		}
	}
}
