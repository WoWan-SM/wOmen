package com.l2walker.oog.protocol;

import java.util.concurrent.ConcurrentHashMap;

/** In-memory play sessions for the loopback Gracia Epilogue harness. */
public final class SessionTable {

	private final ConcurrentHashMap<String, PlaySession> sessions = new ConcurrentHashMap<>();

	public void put(PlaySession session) {
		sessions.put(session.account(), session);
	}

	public PlaySession get(String account) {
		return sessions.get(account);
	}
}
