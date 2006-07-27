// Copyright (c) 2006  Dustin Sallings <dustin@spy.net>
// arch-tag: 30573332-B549-4E6F-AD59-04C6D0928419

package net.spy.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import net.spy.SpyObject;
import net.spy.memcached.ops.Operation;

/**
 * Connection to a cluster of memcached 
 */
public class MemcachedConnection extends SpyObject {
	// The number of empty selects we'll allow before taking action.  It's too
	// easy to write a bug that causes it to loop uncontrollably.  This helps
	// find those bugs and often works around them.
	private static final int EXCESSIVE_EMPTY = 100;
	// maximum amount of time to wait between reconnect attempts
	private static final long MAX_DELAY = 30000;
	private static final int MAX_OPS_QUEUE_LEN = 8192;

	private Selector selector=null;
	private QueueAttachment[] connections=null;
	private int emptySelects=0;
	private SortedMap<Long, QueueAttachment> reconnectQueue=null;

	public MemcachedConnection(InetSocketAddress[] a) throws IOException {
		reconnectQueue=new TreeMap<Long, QueueAttachment>();
		selector=Selector.open();
		connections=new QueueAttachment[a.length];
		int cons=0;
		for(SocketAddress sa : a) {
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			QueueAttachment qa=new QueueAttachment(sa, ch);
			qa.which=cons;
			int ops=0;
			if(ch.connect(sa)) {
				getLogger().info("Connected to %s immediately", qa);
				qa.reconnectAttempt=0;
			} else {
				getLogger().debug("Added %s to connect queue", qa);
				ops=SelectionKey.OP_CONNECT;
			}
			qa.sk=ch.register(selector, ops, qa);
			connections[cons++]=qa;
		}
	}

	@SuppressWarnings("unchecked")
	public void handleIO() throws IOException {
		long delay=0;
		if(!reconnectQueue.isEmpty()) {
			long now=System.currentTimeMillis();
			long then=reconnectQueue.firstKey();
			delay=Math.max(then-now, 1);
		}
		getLogger().debug("Selecting with delay of %sms", delay);
		int selected=selector.select(delay);
		if(selected > 0) {
			Set<SelectionKey> selectedKeys=selector.selectedKeys();
			assert selected == selectedKeys.size();
			getLogger().debug("Selected %d, selected %d keys",
					selected, selectedKeys.size());
			emptySelects=0;
			for(SelectionKey sk : selectedKeys) {
				getLogger().debug(
						"Got selection key:  %s (r=%s, w=%s, c=%s, op=%s)",
						sk, sk.isReadable(), sk.isWritable(),
						sk.isConnectable(), sk.attachment());
				handleIO(sk);
			} // for each selector
			selectedKeys.clear();
		} else {
			// It's very easy in NIO to write a bug such that your selector
			// spins madly.  This will catch that and let it break.
			getLogger().info("No selectors ready, interrupted: "
				+ Thread.interrupted());
			if(++emptySelects > EXCESSIVE_EMPTY) {
				for(SelectionKey sk : selector.keys()) {
					getLogger().info("%s has %s, interested in %s",
							sk, sk.readyOps(), sk.interestOps());
					if(sk.readyOps() != 0) {
						getLogger().info("%s has a ready op, handling IO", sk);
						handleIO(sk);
					} else {
						queueReconnect((QueueAttachment)sk.attachment());
					}
				}
				assert emptySelects < EXCESSIVE_EMPTY + 10
					: "Too many empty selects";
			}
		}
		if(!reconnectQueue.isEmpty()) {
			attemptReconnects();
		}
	}

	// Handle IO for a specific selector.
	private void handleIO(SelectionKey sk) throws IOException {
		QueueAttachment qa=(QueueAttachment)sk.attachment();
		if(sk.isConnectable()) {
			getLogger().info("Connection state changed for %s", sk);
			try {
				if(qa.channel.finishConnect()) {
					synchronized(qa) {
						qa.reconnectAttempt=0;
						if(hasPendingOperations(qa)) {
							sk.interestOps(SelectionKey.OP_WRITE);
						} else {
							sk.interestOps(0);
						}
					}
				}
			} catch(IOException e) {
				getLogger().warn("Problem handling connect", e);
				queueReconnect(qa);
			}
		} else {
			Operation currentOp=qa.ops.peek();
			if(currentOp != null) {
				handleOperation(currentOp, sk, qa);
			} else {
				if(sk.isReadable()) {
					ByteBuffer b=ByteBuffer.allocate(1);
					int read=qa.channel.read(b);
					assert read == -1
						: "expected to read -1 bytes, read " + read;
					queueReconnect(qa);
				} else {
					assert false : "No current operations, but selectors ready";
				}
			}
		}
	}

	// Handle IO for an operation.
	private void handleOperation(Operation currentOp, SelectionKey sk,
			QueueAttachment qa) throws IOException {
		getLogger().debug("Current operation: %s", currentOp);
		// First switch is for IO.
		switch(currentOp.getState()) {
			case READING:
				assert !sk.isWritable() : "While reading, came up writable";
				if(sk.isReadable()) {
					int read=qa.channel.read(qa.buf);
					if(read < 0) {
						queueReconnect(qa);
					} else {
						qa.buf.flip();
						currentOp.readFromBuffer(qa.buf);
						qa.buf.clear();
					}
				} else {
					assert false : "While reading, came up not readable.";
				}
				break;
			case WRITING:
				if(sk.isReadable()) {
					ByteBuffer b=ByteBuffer.allocate(1);
					int read=qa.channel.read(b);
					assert read == -1
						: "expected to read -1 bytes, read " + read;
					queueReconnect(qa);
				} else if(sk.isWritable()) {
					ByteBuffer b=currentOp.getBuffer();
					int written=qa.channel.write(b);
					getLogger().debug("Wrote %d bytes for %s",
							written, currentOp);
					if(b.remaining() == 0) {
						currentOp.writeComplete();
					}
				} else {
					assert false : "Not readable or writable.";
				}
				break;
			case COMPLETE:
				assert false : "Current op is in complete state";
				break;
			default:
				assert false;
		}
		// Second switch is for post-IO examination and state transition
		switch(currentOp.getState()) {
			case READING:
				if(sk.isValid()) {
					sk.interestOps(SelectionKey.OP_READ);
				}
				break;
			case WRITING:
				getLogger().debug("Operation is still writing.");
				if(sk.isValid()) {
					sk.interestOps(SelectionKey.OP_WRITE);
				}
				break;
			case COMPLETE:
				qa.ops.remove();
				// If there are more operations in the queue, tell
				// it we want to write
				synchronized(qa) {
					// After removing the cancelled operations, if there's
					// another operation waiting to go, wait for write
					if(hasPendingOperations(qa) && sk.isValid()) {
						sk.interestOps(SelectionKey.OP_WRITE);
					}
				}
				break;
			default:
				assert false;
		}
	}

	private boolean hasPendingOperations(QueueAttachment qa) {
		assert Thread.holdsLock(qa) : "Not locking qa";
		Operation nextOp=qa.ops.peek();
		while(nextOp != null && nextOp.isCancelled()) {
			getLogger().info("Removing cancelled operation: %s",
					nextOp);
			qa.ops.remove();
			nextOp=qa.ops.peek();
		}
		return nextOp != null;
	}

	private void queueReconnect(QueueAttachment qa) throws IOException {
		synchronized(qa) {
			getLogger().warn("Closing, and reopening %s, attempt %d.",
					qa, qa.reconnectAttempt);
			qa.sk.cancel();
			qa.reconnectAttempt++;
			qa.channel.socket().close();

			long delay=Math.min((100*qa.reconnectAttempt) ^ 2, MAX_DELAY);

			reconnectQueue.put(System.currentTimeMillis() + delay, qa);

			// Need to do a little queue management.
			setupResend(qa);
		}
	}

	private void attemptReconnects() throws IOException {
		long now=System.currentTimeMillis();
		for(Iterator<QueueAttachment> i=
				reconnectQueue.headMap(now).values().iterator(); i.hasNext();) {
			QueueAttachment qa=i.next();
			i.remove();
			getLogger().info("Reconnecting %s", qa);
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			ch.connect(qa.socketAddress);
			qa.channel=ch;
			qa.sk=ch.register(selector, 0, qa);
			qa.sk.interestOps(SelectionKey.OP_CONNECT);
		}
	}

	private void setupResend(QueueAttachment qa) {
		Operation op=qa.ops.peek();
		if(op != null) {
			if(op.getState() == Operation.State.WRITING) {
				getLogger().warn("Resetting write state of op: %s", op);
				op.getBuffer().reset();
			} else {
				getLogger().warn(
						"Discarding partially completed operation: %s", op);
				qa.ops.remove();
			}
		}
	}

	/**
	 * Get the number of connections currently handled.
	 */
	public int getNumConnections() {
		return connections.length;
	}

	/**
	 * Get the remote address of the socket with the given ID.
	 * 
	 * @param which which id
	 * @return the rmeote address
	 */
	public SocketAddress getAddressOf(int which) {
		return connections[which].socketAddress;
	}

	/**
	 * Add an operation to the given connection.
	 * 
	 * @param which the connection offset
	 * @param o the operation
	 */
	@SuppressWarnings("unchecked")
	public void addOperation(int which, Operation o) {
		QueueAttachment qa=connections[which];
		o.initialize();
		synchronized(qa) {
			qa.ops.add(o);
			// If this is the only operation in the queue, tell the selector
			// we want to write
			if(qa.reconnectAttempt == 0 && qa.ops.size() == 1) {
				qa.sk.interestOps(SelectionKey.OP_WRITE);
			}
		}
		selector.wakeup();
		getLogger().debug("Added %s to %d", o, which);
	}

	/**
	 * Shut down all of the connections.
	 */
	public void shutdown() throws IOException {
		for(QueueAttachment qa : connections) {
			qa.channel.close();
			qa.sk=null;
			getLogger().debug("Shut down channel %s", qa.channel);
		}
		selector.close();
		getLogger().debug("Shut down selector %s", selector);
	}

	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("{MemcachedConnection to");
		for(QueueAttachment qa : connections) {
			sb.append(" ");
			sb.append(qa.socketAddress);
		}
		sb.append("}");
		return sb.toString();
	}

	private static class QueueAttachment {
		public int which=0;
		public int reconnectAttempt=1;
		public SocketAddress socketAddress=null;
		public SocketChannel channel=null;
		public ByteBuffer buf=null;
		public BlockingQueue<Operation> ops=null;
		public SelectionKey sk=null;
		public QueueAttachment(SocketAddress sa, SocketChannel c) {
			super();
			socketAddress=sa;
			channel=c;
			buf=ByteBuffer.allocate(4096);
			ops=new ArrayBlockingQueue<Operation>(MAX_OPS_QUEUE_LEN);
		}

		public String toString() {
			return "{QA sa=" + socketAddress + ", #ops=" + ops.size()
				+ ", topop=" + ops.peek() + "}";
		}
	}
}