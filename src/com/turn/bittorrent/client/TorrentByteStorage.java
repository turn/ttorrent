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

package com.turn.bittorrent.client;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.apache.log4j.Logger;

/** Torrent data storage.
 *
 * A torrent, regardless of whether it contains multiple files or not, is
 * considered as one linear, contiguous byte array. As such, pieces can spread
 * across multiple files.
 *
 * Although this BitTorrent client currently only supports single-torrent
 * files, this TorrentByteStorage class provides an abstraction for the Piece
 * class to read and write to the torrent's data without having to care about
 * which file(s) a piece is on.
 *
 * The current implementation uses a RandomAccessFile FileChannel to expose
 * thread-safe read/write methods.
 *
 * @author mpetazzoni
 */
public class TorrentByteStorage {

	private static final Logger logger =
		Logger.getLogger(TorrentByteStorage.class);

	private FileChannel channel;

	public TorrentByteStorage(File file, int size) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "rw");

		// Set the file length to the appropriate size, eventually truncating
		// or extending the file if it already exists with a different size.
		raf.setLength(size);

		this.channel = raf.getChannel();
		logger.debug("Initialized torrent byte storage at " +
				file.getAbsolutePath() + ".");
	}

	public ByteBuffer read(int offset, int length) throws IOException {
		ByteBuffer data = ByteBuffer.allocate(length);
		int bytes = channel.read(data, offset);
		data.clear();
		data.limit(bytes >= 0 ? bytes : 0);
		return data;
	}

	public void write(ByteBuffer block, int offset) throws IOException {
		channel.write(block, offset);
	}
}
