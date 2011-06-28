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

import com.turn.bittorrent.bcodec.BEValue;
import com.turn.bittorrent.common.Peer;
import com.turn.bittorrent.common.Torrent;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

/** A BitTorrent tracker peer.
 *
 * Represents a peer exchanging on a given torrent. In this implementation,
 * we don't really care about the status of the peers and how much they
 * have downloaded / exchanged because we are not a torrent exchange and
 * don't need to keep track of what peers are doing while they're
 * downloading. We only care about when they start, and when they are done.
 *
 * We also never expire peers automatically. Unless peers send a STOPPED
 * announce request, they remain as long as the torrent object they are a
 * part of.
 */
public class TrackedPeer extends Peer {

	private static final Logger logger = Logger.getLogger(TrackedPeer.class);
	private static final int FRESH_TIME_SECONDS = 30;

	private Torrent torrent;

	/** Represents the state of a peer exchanging on this torrent.
	 *
	 * Peers can be in the STARTED state, meaning they have announced
	 * themselves to us and are eventually exchanging data with other peers.
	 * Note that a peer starting with a completed file will also be in the
	 * started state and will never notify as being in the completed state.
	 * This information can be inferred from the fact that the peer reports 0
	 * bytes left to download.
	 *
	 * Peers enter the COMPLETED state when they announce they have entirely
	 * downloaded the file. As stated above, we may also elect them for this
	 * state if they report 0 bytes left to download.
	 *
	 * Peers enter the STOPPED state very briefly before being removed. We
	 * still pass them to the STOPPED state in case someone else kept a
	 * reference on them.
	 */
	public enum PeerState {
		UNKNOWN,
		STARTED,
		COMPLETED,
		STOPPED;
	};

	private PeerState state;
	private Date lastAnnounce;

	/** Instanciate a new tracked peer for the given torrent.
	 *
	 * @param torrent The torrent this peer exchanges on.
	 * @param peerId The byte-encoded peer ID.
	 * @param hexPeerId The hexadecimal encoded string representation of
	 * the peer ID.
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 */
	public TrackedPeer(Torrent torrent, ByteBuffer peerId, String hexPeerId,
			String ip, int port) {
		super(peerId, hexPeerId, ip, port);
		this.torrent = torrent;

		// Instanciated peers start in the UNKNOWN state.
		this.state = PeerState.UNKNOWN;
		this.lastAnnounce = null;
	}

	/** Update this peer's state and information.
	 *
	 * Note: if the peer reports 0 bytes left to download, its state will
	 * be automatically be set to COMPLETED.
	 *
	 * @param state The peer's state.
	 * @param uploaded Uploaded byte count, as reported by the peer.
	 * @param downloaded Downloaded byte count, as reported by the peer.
	 * @param left Left-to-download byte count, as reported by the peer.
	 */
	public void update(PeerState state, long uploaded, long downloaded,
			long left) {
		if (PeerState.STARTED.equals(state) && left == 0) {
			state = PeerState.COMPLETED;
		}

		if (!state.equals(this.state)) {
			logger.info("Peer " + this + " " + state.name().toLowerCase() +
					" download of " + this.torrent + ".");
		}

		this.state = state;
		this.lastAnnounce = new Date();
	}

	/** Tells whether this peer has completed its download and can thus be
	 * considered a seeder.
	 */
	public boolean isCompleted() {
		return PeerState.COMPLETED.equals(this.state);
	}

	public boolean isFresh() {
		return (this.lastAnnounce != null &&
				(this.lastAnnounce.getTime() + FRESH_TIME_SECONDS * 1000 >
				 new Date().getTime()));
	}

	/** Returns a BEValue representing this peer for inclusion in an
	 * announce reply from the tracker.
	 *
	 * The returned BEValue is a dictionnary containing the peer ID (in its
	 * original byte-encoded form), the peer's IP and the peer's port.
	 */
	public BEValue toBEValue() throws UnsupportedEncodingException {
		Map<String, BEValue> peer = new HashMap<String, BEValue>();
		peer.put("peer id", new BEValue(this.getPeerId().array()));
		peer.put("ip", new BEValue(this.getIp(), Torrent.BYTE_ENCODING));
		peer.put("port", new BEValue(this.getPort()));
		return new BEValue(peer);
	}
}
