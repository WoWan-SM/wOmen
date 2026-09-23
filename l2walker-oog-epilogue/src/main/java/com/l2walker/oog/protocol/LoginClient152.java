package com.l2walker.oog.protocol;

import com.l2walker.oog.config.SetIniConfig;
import com.l2walker.oog.crypt.GameXorCrypt;
import com.l2walker.oog.crypt.RsaLoginKey;
import com.l2walker.oog.net.LoginSocketFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PublicKey;
import java.util.List;
import java.util.Locale;

/**
 * OOG login client. Outgoing packets follow the Gracia Epilogue client field order.
 */
public final class LoginClient152 {

	private static final int TIMEOUT_MS = 5000;

	private final LoginSocketFactory sockets = new LoginSocketFactory();

	public LoginResult login(SetIniConfig config) throws Exception {
		if (config.account().isBlank() || config.password().isBlank()) {
			throw new IllegalArgumentException("Account and Password must be set");
		}

		PlaySession session;
		EpiloguePackets.ServerEntry world;
		try (Socket loginSocket = open(config.loginServerIp(), config.loginServerPort())) {
			InputStream in = loginSocket.getInputStream();
			OutputStream out = loginSocket.getOutputStream();
			byte[] initPlain = LoginCrypt.decryptInitial(TcpFrames.recv(in));
			EpiloguePackets.InitFrame init = EpiloguePackets.parseInit(initPlain);
			PublicKey serverKey = RsaLoginKey.publicKeyFromScrambled(init.scrambledModulus());
			byte[] rsa = RsaLoginKey.sealCredentials(serverKey, config.account(), config.password());

			LoginCrypt crypt = new LoginCrypt();
			crypt.setKey(init.blowfishKey());
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.authGameGuard(init.sessionId())));
			int guardResponse = EpiloguePackets.parseGgAuth(crypt.decrypt(TcpFrames.recv(in)));
			if (guardResponse != init.sessionId()) {
				throw new IllegalStateException("GGAuth did not echo the login session id");
			}
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.requestAuthLogin(rsa, init.sessionId())));

			EpiloguePackets.SessionPair loginOk = EpiloguePackets.parseLoginOk(crypt.decrypt(TcpFrames.recv(in)));
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.requestServerList(loginOk.first(), loginOk.second())));
			List<EpiloguePackets.ServerEntry> servers = EpiloguePackets.parseServerList(crypt.decrypt(TcpFrames.recv(in)));
			world = chooseServer(config, servers);
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.requestServerLogin(loginOk.first(), loginOk.second(), world.id())));
			EpiloguePackets.SessionPair playOk = EpiloguePackets.parsePlayOk(crypt.decrypt(TcpFrames.recv(in)));
			session = new PlaySession(
					config.account().toLowerCase(Locale.ROOT),
					loginOk.first(),
					loginOk.second(),
					playOk.first(),
					playOk.second());
		}

		String gameHost = config.gameServerIp().isBlank() ? world.host() : config.gameServerIp();
		int gamePort = config.gameServerIp().isBlank() ? world.port() : config.gameServerPort();
		try (Socket gameSocket = open(gameHost, gamePort)) {
			InputStream in = gameSocket.getInputStream();
			OutputStream out = gameSocket.getOutputStream();
			GameXorCrypt gameCrypt = new GameXorCrypt(GameXorCrypt.Role.CLIENT);
			byte[] keyBody = TcpFrames.recv(in);
			gameCrypt.decrypt(keyBody);
			byte[] xorKey = EpiloguePackets.parseKeyPacket(keyBody);
			gameCrypt.setKey(xorKey);

			byte[] protocol = EpiloguePackets.protocolVersion(config.protocolVersion());
			gameCrypt.encrypt(protocol);
			TcpFrames.send(out, protocol);

			byte[] auth = EpiloguePackets.gameAuth(config.account(), session);
			gameCrypt.encrypt(auth);
			TcpFrames.send(out, auth);

			byte[] characters = TcpFrames.recv(in);
			gameCrypt.decrypt(characters);
			EpiloguePackets.CharacterList list = EpiloguePackets.parseCharSelection(characters);
			return new LoginResult(
					session.account(),
					world.id(),
					gameHost,
					gamePort,
					list.count(),
					list.maxCharacters(),
					List.copyOf(list.names()));
		}
	}

	private Socket open(String host, int port) throws Exception {
		Socket socket = sockets.open(new InetSocketAddress(host, port), TIMEOUT_MS);
		socket.setSoTimeout(TIMEOUT_MS);
		return socket;
	}

	private static EpiloguePackets.ServerEntry chooseServer(SetIniConfig config, List<EpiloguePackets.ServerEntry> servers) {
		if (servers.isEmpty()) {
			throw new IllegalStateException("login server returned an empty server list");
		}
		for (EpiloguePackets.ServerEntry entry : servers) {
			if (entry.id() == config.serverId()) {
				return entry;
			}
		}
		return servers.get(0);
	}
}
