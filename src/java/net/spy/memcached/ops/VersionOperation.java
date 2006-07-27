// Copyright (c) 2006  Dustin Sallings <dustin@spy.net>
// arch-tag: 52EC8A7E-E2CC-46C8-AF87-662CFD856593

package net.spy.memcached.ops;

import java.nio.ByteBuffer;

/**
 * Operation to request the version of a memcached server.
 */
public class VersionOperation extends Operation {

	private static final byte[] REQUEST="version\r\n".getBytes();

	private OperationCallback cb=null;

	public VersionOperation(OperationCallback c) {
		super();
		cb=c;
	}

	@Override
	public void handleLine(String line) {
		if(cb != null) {
			assert line.startsWith("VERSION ");
			cb.receivedStatus(line.substring("VERSION ".length()));
		}
		transitionState(State.COMPLETE);
	}

	@Override
	public void initialize() {
		setBuffer(ByteBuffer.wrap(REQUEST));
	}

}