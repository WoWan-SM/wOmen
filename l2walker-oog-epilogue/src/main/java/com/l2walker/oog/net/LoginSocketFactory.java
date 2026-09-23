package com.l2walker.oog.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Creates blocking TCP sockets for login/game servers. */
public class LoginSocketFactory {

	public Socket open(InetSocketAddress address, int connectTimeoutMs) throws IOException {
		Socket socket = new Socket();
		socket.connect(address, connectTimeoutMs);
		socket.setTcpNoDelay(true);
		return socket;
	}
}
