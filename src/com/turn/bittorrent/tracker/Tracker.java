/** Copyright (C) 2011 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.bittorrent.tracker;

import com.turn.bittorrent.common.Torrent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.log4j.Logger;

import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

/** BitTorrent tracker.
 *
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker
 * port). Torrents must be registered directly to this tracker with the
 * <code>announced()</code> method.
 *
 * @author mpetazzoni
 */
public class Tracker {

	private static final Logger logger = Logger.getLogger(Tracker.class);

	public static final String ANNOUNCE_URL = "/announce";
	public static final int DEFAULT_TRACKER_PORT = 6969;

	private Connection connection;
	private InetSocketAddress address;
	private Thread tracker;
	private Thread collector;
	private boolean stop;

	/** The in-memory repository of torrents tracked. */
	private ConcurrentMap<String, TrackedTorrent> torrents;

	/** Create a new BitTorrent tracket on the default port.
	 *
	 * @param address The address to bind to.
	 * @param version A version string served in the HTTP headers
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(InetAddress address, String version) throws IOException {
		this(address, version, Tracker.DEFAULT_TRACKER_PORT);
	}

	/** Create a new BitTorrent tracker listening on the given port.
	 *
	 * @param address The address to bind to.
	 * @param version A version string served in the HTTP headers
	 * @param port The port to listen on.
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(InetAddress address, String version, int port)
		throws IOException {
		this.torrents = new ConcurrentHashMap<String, TrackedTorrent>();
		this.connection = new SocketConnection(
				new TrackerService(version, this.torrents));
		this.address = new InetSocketAddress(address, port);
	}

	/** Returns the full announce URL served by this tracker.
	 *
	 * This has the form http://host:port/announce.
	 */
	public String getAnnounceUrl() {
		return new StringBuilder("http://")
			.append(this.address.getAddress().getCanonicalHostName())
			.append(":")
			.append(this.address.getPort())
			.append(Tracker.ANNOUNCE_URL)
			.toString();
	}

	/** Start the tracker thread.
	 */
	public void start() {
		if (this.tracker == null || !this.tracker.isAlive()) {
			this.tracker = new TrackerThread();
			this.tracker.setName("tracker:" + this.address.getPort());
			this.tracker.start();
		}

		if (this.collector == null || !this.collector.isAlive()) {
			this.collector = new PeerCollectorThread();
			this.collector.setName("peer-collector:" + this.address.getPort());
			this.collector.start();
		}
	}

	/** Stop the tracker.
	 *
	 * This effectively closes the listening HTTP connection to terminate
	 * the service, and interrupts the peer collector thread as well.
	 */
	public void stop() {
		this.stop = true;

		try {
			this.connection.close();
			logger.info("BitTorrent tracker closed.");
		} catch (IOException ioe) {
			logger.error("Could not stop the tracker: " +
				ioe.getMessage() + "!");
		}

		if (this.collector != null && this.collector.isAlive()) {
			this.collector.interrupt();
			logger.info("Peer collection terminated.");
		}
	}

	/** Announce a new torrent on this tracker.
	 *
	 * The fact that torrents must be announced here first makes this tracker a
	 * closed BitTorrent tracker: it will only accept clients for torrents it
	 * knows about, and this list of torrents is managed by the program
	 * instrumenting this Tracker class.
	 *
	 * @param newTorrent The Torrent object to start tracking.
	 * @return The torrent object for this torrent on this tracker. This may be
	 * different from the supplied Torrent object if the tracker already
	 * contained a torrent with the same hash.
	 */
	public synchronized TrackedTorrent announce(Torrent newTorrent) {
		TrackedTorrent torrent = this.torrents.get(newTorrent.getHexInfoHash());

		if (torrent != null) {
			logger.warn("Tracker already announced torrent for '" +
					torrent.getName() + "' with hash " +
					torrent.getHexInfoHash() + ".");
			return torrent;
		} else {
			torrent = new TrackedTorrent(newTorrent);
		}

		this.torrents.put(torrent.getHexInfoHash(), torrent);
		logger.info("Registered new torrent for '" + torrent.getName() + "' " +
			   "with hash " + torrent.getHexInfoHash());
		return torrent;
	}

	/** Stop announcing the given torrent.
	 *
	 * @param torrent The Torrent object to stop tracking.
	 */
	public synchronized void remove(Torrent torrent) {
		if (torrent == null) {
			return;
		}

		this.torrents.remove(torrent.getHexInfoHash());
	}

	/** Stop announcing the given torrent after a delay.
	 *
	 * @param torrent The Torrent object to stop tracking.
	 * @param delay The delay, in milliseconds, before removing the torrent.
	 */
	public synchronized void remove(Torrent torrent, long delay) {
		if (torrent == null) {
			return;
		}

		new Timer().schedule(new TorrentRemoveTimer(this, torrent), delay);
	}

	/** Timer task for removing a torrent from a tracker.
	 *
	 * This task can be used to stop announcing a torrent after a certain delay
	 * through a Timer.
	 */
	private class TorrentRemoveTimer extends TimerTask {

		private Tracker tracker;
		private Torrent torrent;

		TorrentRemoveTimer(Tracker tracker, Torrent torrent) {
			this.tracker = tracker;
			this.torrent = torrent;
		}

		@Override
		public void run() {
			this.tracker.remove(torrent);
		}
	}

	/** The main tracker thread.
	 *
	 * The core of the BitTorrent tracker run by the controller is the
	 * Simpleframework HTTP service listening on the configured address. It can
	 * be stopped with the <em>stop()</em> method, which closes the listening
	 * socket.
	 */
	private class TrackerThread extends Thread {

		@Override
		public void run() {
			logger.info("Starting BitTorrent tracker on " + getAnnounceUrl() +
					"...");

			try {
				connection.connect(address);
			} catch (IOException ioe) {
				logger.error("Could not start the tracker: " +
					ioe.getMessage() + "!");
			}
		}
	}

	/** The unfresh peer collector thread.
	 *
	 * Every PEER_COLLECTION_FREQUENCY_SECONDS, this thread will collect
	 * unfresh peers from all announced torrents.
	 */
	private class PeerCollectorThread extends Thread {

		private static final int PEER_COLLECTION_FREQUENCY_SECONDS = 15;

		@Override
		public void run() {
			logger.info("Starting tracker peer collection for tracker at " +
					getAnnounceUrl() + "...");

			while (!stop) {
				for (TrackedTorrent torrent : torrents.values()) {
					torrent.collectUnfreshPeers();
				}

				try {
					Thread.sleep(PeerCollectorThread
							.PEER_COLLECTION_FREQUENCY_SECONDS * 1000);
				} catch (InterruptedException ie) {
					// Ignore
				}
			}
		}
	}
}
