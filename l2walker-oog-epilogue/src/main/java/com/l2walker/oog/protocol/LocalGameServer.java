package com.l2walker.oog.protocol;

import com.l2walker.oog.crypt.GameXorCrypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Loopback game server for protocol 152. Accepts one client after {@link LocalLoginServer}
 * has published a {@link PlaySession}.
 */
public final class LocalGameServer implements AutoCloseable {

	private final SessionTable sessions;
	private final String characterName;
	private final byte[] xorKey = new byte[8];
	private final ServerSocket socket;
	private final Thread thread;
	private volatile Exception failure;
	private volatile int seenProtocol;

	public LocalGameServer(SessionTable sessions, String characterName) throws IOException {
		this.sessions = sessions;
		this.characterName = characterName;
		new SecureRandom().nextBytes(xorKey);
		this.socket = new ServerSocket();
		this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
		this.thread = new Thread(this::serve, "local-game-152");
		this.thread.setDaemon(true);
	}

	public void start() {
		thread.start();
	}

	public int port() {
		return socket.getLocalPort();
	}

	public int seenProtocol() {
		return seenProtocol;
	}

	public Exception failure() {
		return failure;
	}

	private void serve() {
		try (Socket client = socket.accept()) {
			client.setTcpNoDelay(true);
			client.setSoTimeout(5000);
			handle(client);
		} catch (IOException ex) {
			if (!socket.isClosed()) {
				failure = ex;
			}
		}
	}

	private void handle(Socket client) {
		try {
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			GameXorCrypt crypt = new GameXorCrypt(GameXorCrypt.Role.SERVER);
			crypt.setKey(xorKey);
			byte[] keyPacket = EpiloguePackets.keyPacket(xorKey);
			crypt.encrypt(keyPacket);
			TcpFrames.send(out, keyPacket);

			byte[] protocolBody = TcpFrames.recv(in);
			crypt.decrypt(protocolBody);
			seenProtocol = EpiloguePackets.parseProtocolVersion(protocolBody);
			if (seenProtocol != 152) {
				throw new IllegalStateException("client protocol " + seenProtocol + " is not 152");
			}

			byte[] authBody = TcpFrames.recv(in);
			crypt.decrypt(authBody);
			EpiloguePackets.GameAuth auth = EpiloguePackets.parseGameAuth(authBody);
			PlaySession session = sessions.get(auth.account().toLowerCase(Locale.ROOT));
			if (session == null
					|| session.playOk1() != auth.playOk1()
					|| session.playOk2() != auth.playOk2()
					|| session.loginOk1() != auth.loginOk1()
					|| session.loginOk2() != auth.loginOk2()) {
				throw new IllegalStateException("game auth session key mismatch");
			}

			byte[] characters = EpiloguePackets.charSelection(characterName);
			crypt.encrypt(characters);
			TcpFrames.send(out, characters);
		} catch (Exception ex) {
			failure = ex;
		}
	}

	@Override
	public void close() throws IOException {
		socket.close();
		try {
			thread.join(2000);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
