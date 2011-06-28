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

package com.turn.bittorrent.common;

import com.turn.bittorrent.bcodec.BDecoder;
import com.turn.bittorrent.bcodec.BEValue;
import com.turn.bittorrent.bcodec.BEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Logger;

/** A torrent file tracked by the controller's BitTorrent tracker.
 *
 * <p>
 * This class represents an active torrent on the tracker. The torrent
 * information is kept in-memory, and is created from the byte blob one would
 * usually find in a <tt>.torrent</tt> file.
 * </p>
 *
 * <p>
 * Each torrent also keeps a repository of the peers seeding and leeching this
 * torrent from the tracker.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Metainfo_File_Structure">Torrent meta-info file structure specification</a>
 */
public class Torrent {

	private static final Logger logger = Logger.getLogger(Torrent.class);

	/** Torrent file piece length (in bytes), we use 512 kB. */
	private static final int PIECE_LENGTH = 512 * 1024;

	public static final int PIECE_HASH_SIZE = 20;

	/** The query parameters encoding when parsing byte strings. */
	public static final String BYTE_ENCODING = "ISO-8859-1";

	protected byte[] encoded;
	protected Map<String, BEValue> decoded;

	protected byte[] encoded_info;
	protected Map<String, BEValue> decoded_info;

	private String announceUrl;
	private String name;
	private byte[] info_hash;
	private String hex_info_hash;

	/** Create a new torrent from metainfo binary data.
	 *
	 * Parses the metainfo data (which should be B-encoded as described in the
	 * BitTorrent specification) and create a Torrent object from it.
	 *
	 * @param torrent The metainfo byte data.
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 */
	public Torrent(byte[] torrent) throws IllegalArgumentException {
		this.encoded = torrent;

		try {
			this.decoded = BDecoder.bdecode(
					new ByteArrayInputStream(this.encoded)).getMap();

			this.announceUrl = this.decoded.get("announce").getString();

			this.decoded_info = this.decoded.get("info").getMap();
			this.name = this.decoded_info.get("name").getString();

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			BEncoder.bencode(this.decoded_info, baos);
			this.encoded_info = baos.toByteArray();
			this.info_hash = Torrent.hash(this.encoded_info);
			this.hex_info_hash = Torrent.byteArrayToHexString(this.info_hash);
		} catch (Exception e) {
			throw new IllegalArgumentException("Can't parse torrent information!", e);
		}
	}

	/** Get this torrent's name.
	 *
	 * For a single-file torrent, this is usually the name of the file. For a
	 * multi-file torrent, this is usually the name of a top-level directory
	 * containing those files.
	 */
	public String getName() {
		return this.name;
	}

	public byte[] getInfoHash() {
		return this.info_hash;
	}

	/** Get this torrent's info hash (as an hexadecimal-coded string).
	 */
	public String getHexInfoHash() {
		return this.hex_info_hash;
	}

	/** Return a human-readable representation of this torrent object.
	 *
	 * The torrent's name is used.
	 */
	public String toString() {
		return this.getName();
	}

	/** Return the B-encoded meta-info of this torrent.
	 */
	public byte[] getEncoded() {
		return this.encoded;
	}

	public String getAnnounceUrl() {
		return this.announceUrl;
	}

	public static byte[] hash(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		md.update(data);
		return md.digest();
	}

	/** Convert a byte string to a string containing an hexadecimal
	 * representation of the original data.
	 *
	 * @param bytes The byte array to convert.
	 */
	public static String byteArrayToHexString(byte[] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return String.format("%0" + (bytes.length << 1) + "X", bi);
	}

	/** Return an hexadecimal representation of the bytes contained in the
	 * given string, following the default, expected byte encoding.
	 *
	 * @param input The input string.
	 */
	public static String toHexString(String input) {
		try {
			byte[] bytes = input.getBytes(Torrent.BYTE_ENCODING);
			return Torrent.byteArrayToHexString(bytes);
		} catch (UnsupportedEncodingException uee) {
			return null;
		}
	}

	public static Torrent create(File source, String announce, String createdBy)
		throws NoSuchAlgorithmException, IOException {
		logger.info("Creating torrent for " + source.getName() + "...");

		Map<String, BEValue> torrent = new HashMap<String, BEValue>();
		torrent.put("announce", new BEValue(announce));
		torrent.put("creation date", new BEValue(new Date().getTime()));
		torrent.put("created by", new BEValue(createdBy));

		Map<String, BEValue> info = new TreeMap<String, BEValue>();
		info.put("length", new BEValue(source.length()));
		info.put("name", new BEValue(source.getName()));
		info.put("piece length", new BEValue(Torrent.PIECE_LENGTH));
		info.put("pieces", new BEValue(Torrent.hashPieces(source),
					Torrent.BYTE_ENCODING));
		torrent.put("info", new BEValue(info));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(new BEValue(torrent), baos);
		return new Torrent(baos.toByteArray());
	}

	private static String hashPieces(File source)
		throws NoSuchAlgorithmException, IOException {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		FileInputStream fis = new FileInputStream(source);
		StringBuffer pieces = new StringBuffer();
		byte[] data = new byte[Torrent.PIECE_LENGTH];
		int read;

		while ((read = fis.read(data)) > 0) {
			md.reset();
			md.update(data, 0, read);
			pieces.append(new String(md.digest(), Torrent.BYTE_ENCODING));
		}
		fis.close();

		int n_pieces = new Double(Math.ceil((double)source.length() /
					Torrent.PIECE_LENGTH)).intValue();
		logger.debug("Hashed " + source.getName() + " (" +
				source.length() + " bytes) in " + n_pieces + " pieces.");

		return pieces.toString();
	}
}
