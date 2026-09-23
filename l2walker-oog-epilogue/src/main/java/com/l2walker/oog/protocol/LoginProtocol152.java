package com.l2walker.oog.protocol;

import com.l2walker.oog.config.SetIniConfig;
import com.l2walker.oog.net.LoginSocketFactory;

import java.net.InetSocketAddress;

/**
 * Stub for Gracia Epilogue (152) OOG login sequence.
 * <p>
 * Typical l2off / extended-server flow (simplified):
 * <ol>
 *   <li>TCP connect to login server ({@code LoginServerIP:LoginServerPort})</li>
 *   <li>Receive init packet; derive session Blowfish key</li>
 *   <li>Send {@code RequestAuthLogin} with RSA-encrypted credentials (OOG client version block)</li>
 *   <li>Receive login ok / fail; on success receive server list</li>
 *   <li>{@code RequestServerLogin} with selected server id</li>
 *   <li>Receive game server IP/port; connect game server and run XOR handshake</li>
 *   <li>{@code RequestCharacterList} / {@code CharacterSelect} (CT 2.3+ packet fixes apply for OOG)</li>
 * </ol>
 * Implement packet builders and crypto in {@code com.l2walker.oog.net} for your server fork.
 */
public final class LoginProtocol152 {

	private final SetIniConfig config;

	public LoginProtocol152(SetIniConfig config) {
		this.config = config;
	}

	public void runLoginSequenceStub() {
		InetSocketAddress loginAddress = new InetSocketAddress(config.loginServerIp(), config.loginServerPort());
		System.out.println("[stub] Would open login connection to " + loginAddress);
		System.out.println("[stub] ProtocolVersion=" + config.protocolVersion() + ", account=" + mask(config.account()));
		System.out.println("[stub] Next steps: implement Blowfish init, auth, server list, game XOR — see docs in Project store");

		// Ensures factory compiles and/documents intended socket usage without connecting in stub mode.
		LoginSocketFactory unused = new LoginSocketFactory();
		if (unused == null) {
			throw new IllegalStateException("unreachable");
		}
	}

	private static String mask(String account) {
		if (account == null || account.length() < 2) {
			return "***";
		}
		return account.charAt(0) + "***" + account.charAt(account.length() - 1);
	}
}
