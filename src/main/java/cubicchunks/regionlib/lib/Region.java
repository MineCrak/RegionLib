/*
 *  This file is part of RegionLib, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2016 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.regionlib.lib;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import cubicchunks.regionlib.api.region.IRegionProvider;
import cubicchunks.regionlib.api.region.key.IKeyProvider;
import cubicchunks.regionlib.api.region.key.RegionKey;
import cubicchunks.regionlib.util.CorruptedDataException;
import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.api.region.header.IHeaderDataEntryProvider;
import cubicchunks.regionlib.lib.header.IKeyIdToSectorMap;
import cubicchunks.regionlib.api.region.IRegion;
import cubicchunks.regionlib.lib.header.IntPackedSectorMap;
import cubicchunks.regionlib.util.CheckedConsumer;
import cubicchunks.regionlib.util.WrappedException;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * A basic region implementation
 *
 * @param <K> The key type
 */
public class Region<K extends IKey<K>> implements IRegion<K> {

	private final IKeyIdToSectorMap<?, ?, K> sectorMap;
	private final RegionSectorTracker<K> regionSectorTracker;

	private final SeekableByteChannel file;
	private final List<IHeaderDataEntryProvider<?, K>> headerEntryProviders;
	private final int sectorSize;
	private final RegionKey regionKey;
	private final IKeyProvider<K> keyProvider;
	private final int keyCount;

	private Region(SeekableByteChannel file,
			IntPackedSectorMap<K> sectorMap,
			RegionSectorTracker<K> sectorTracker,
			List<IHeaderDataEntryProvider<?, K>> headerEntryProviders,
			RegionKey regionKey,
			IKeyProvider<K> keyProvider,
			int sectorSize) throws IOException {
		this.regionKey = regionKey;
		this.keyProvider = keyProvider;
		this.keyCount = keyProvider.getKeyCount(regionKey);
		this.file = file;
		this.headerEntryProviders = headerEntryProviders;
		this.sectorSize = sectorSize;
		this.sectorMap = sectorMap;
		this.regionSectorTracker = sectorTracker;
	}

	@Override public synchronized void writeValue(K key, ByteBuffer value) throws IOException {
		int size = value.remaining();
		int sizeWithSizeInfo = size + Integer.BYTES;
		int numSectors = getSectorNumber(sizeWithSizeInfo);
		RegionEntryLocation location = this.regionSectorTracker.reserveForKey(key, numSectors);

		int bytesOffset = location.getOffset()*sectorSize;

		file.position(bytesOffset).write(ByteBuffer.allocate(Integer.BYTES).putInt(0, size));
		file.write(value);

		final int id = key.getId();
		int currentHeaderBytes = 0;
		for (IHeaderDataEntryProvider<?, K> prov : headerEntryProviders) {
			ByteBuffer buf = ByteBuffer.allocate(prov.getEntryByteCount());
			prov.apply(key).write(buf);
			buf.flip();
			file.position(currentHeaderBytes*keyCount + id*prov.getEntryByteCount()).write(buf);
			currentHeaderBytes += prov.getEntryByteCount();
		}
	}

	@Override public synchronized Optional<ByteBuffer> readValue(K key) throws IOException {
		// a hack because Optional can't throw checked exceptions
		try {
			return sectorMap.getEntryLocation(key).flatMap(loc -> {
				try {
					int sectorOffset = loc.getOffset();
					int sectorCount = loc.getSize();

					ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES);

					file.position(sectorOffset*sectorSize).read(buf);

					int dataLength = buf.getInt(0);
					if (dataLength > sectorCount*sectorSize) {
						throw new CorruptedDataException("Expected data size max" + sectorCount*sectorSize + " but found " + dataLength);
					}

					ByteBuffer bytes = ByteBuffer.allocate(dataLength);
					file.read(bytes);
					bytes.flip();
					return Optional.of(bytes);
				} catch (IOException e) {
					throw new WrappedException(e);
				}
			});
		} catch (WrappedException e) {
			throw (IOException) e.get();
		}
	}

	/**
	 * Returns true if something was stored there before within this region.
	 */
	@Override public synchronized boolean hasValue(K key) {
		return sectorMap.getEntryLocation(key).isPresent();
	}

	@Override public void forEachKey(CheckedConsumer<? super K, IOException> cons) throws IOException {
		for (int id = 0; id < this.keyCount; id++) {
			int idFinal = id; // because java is stupid
			K key = sectorMap.getEntryLocation(id).map(loc -> keyProvider.fromRegionAndId(this.regionKey, idFinal)).orElse(null);
			if (key != null) {
				cons.accept(key);
			}
		}
	}


	private int getSectorNumber(int bytes) {
		return ceilDiv(bytes, sectorSize);
	}

	@Override public void close() throws IOException {
		this.file.close();
	}

	private static int ceilDiv(int x, int y) {
		return -Math.floorDiv(-x, y);
	}

	public static <L extends IKey<L>> Builder<L> builder() {
		return new Builder<>();
	}

	/**
	 * Internal Region builder. Using it is very unsafe, there are no safeguards against using it improperly. Should only be used by
	 * {@link IRegionProvider} implementations.
	 */
	// TODO: make a safer to use builder
	public static class Builder<K extends IKey<K>> {

		private Path directory;
		private int sectorSize = 512;
		private List<IHeaderDataEntryProvider> headerEntryProviders = new ArrayList<>();
		private RegionKey regionKey;
		private IKeyProvider<K> keyProvider;

		public Builder<K> setDirectory(Path path) {
			this.directory = path;
			return this;
		}

		public Builder<K> setRegionKey(RegionKey key) {
			this.regionKey = key;
			return this;
		}

		public Builder<K> setKeyProvider(IKeyProvider<K> keyProvider) {
			this.keyProvider = keyProvider;
			return this;
		}

		public Builder<K> setSectorSize(int sectorSize) {
			this.sectorSize = sectorSize;
			return this;
		}

		public Builder<K> addHeaderEntry(IHeaderDataEntryProvider<?, K> headerEntry) {
			headerEntryProviders.add(headerEntry);
			return this;
		}

		public Region<K> build() throws IOException {
			SeekableByteChannel file = Files.newByteChannel(directory.resolve(regionKey.getName()), CREATE, READ, WRITE);

			int entryMapBytes = Integer.BYTES;
			for (IHeaderDataEntryProvider<?, ?> prov : headerEntryProviders) {
				entryMapBytes += prov.getEntryByteCount();
			}
			int entryMapSectors = ceilDiv(keyProvider.getKeyCount(regionKey) * entryMapBytes, sectorSize);

			IntPackedSectorMap<K> sectorMap = IntPackedSectorMap.readOrCreate(file, keyProvider.getKeyCount(regionKey));
			RegionSectorTracker<K> regionSectorTracker = RegionSectorTracker.fromFile(file, sectorMap, entryMapSectors, sectorSize);
			this.headerEntryProviders.add(0, sectorMap.headerEntryProvider());
			return new Region(file, sectorMap, regionSectorTracker,
					this.headerEntryProviders, this.regionKey, keyProvider, this.sectorSize);
		}
	}
}
