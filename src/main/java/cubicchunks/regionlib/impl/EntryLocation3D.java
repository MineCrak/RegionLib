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
package cubicchunks.regionlib.impl;

import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.api.region.key.IKeyProvider;
import cubicchunks.regionlib.api.region.key.RegionKey;

/**
 * A 3D implementation of IEntryLocation
 */
public class EntryLocation3D implements IKey<EntryLocation3D> {
	private static final int LOC_BITS = 4;
	private static final int LOC_BITMASK = (1 << LOC_BITS) - 1;
	public static final int ENTRIES_PER_REGION = (1 << LOC_BITS)*(1 << LOC_BITS)*(1 << LOC_BITS);

	private final int entryX;
	private final int entryY;
	private final int entryZ;

	public EntryLocation3D(int entryX, int entryY, int entryZ) {
		this.entryX = entryX;
		this.entryY = entryY;
		this.entryZ = entryZ;
	}

	public int getEntryX() {
		return entryX;
	}

	public int getEntryY() {
		return entryY;
	}

	public int getEntryZ() {
		return entryZ;
	}

	@Override public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		EntryLocation3D that = (EntryLocation3D) o;

		if (entryX != that.entryX) return false;
		if (entryY != that.entryY) return false;
		return entryZ == that.entryZ;

	}

	@Override public int hashCode() {
		int result = entryX;
		result = 31*result + entryY;
		result = 31*result + entryZ;
		return result;
	}

	@Override public RegionKey getRegionKey() {
		int regX = entryX >> LOC_BITS;
		int regY = entryY >> LOC_BITS;
		int regZ = entryZ >> LOC_BITS;

		return new RegionKey(regX + "." + regY + "." + regZ + ".3dr");
	}

	@Override public int getId() {
		return ((entryX & LOC_BITMASK) << LOC_BITS*2) | ((entryY & LOC_BITMASK) << LOC_BITS) | (entryZ & LOC_BITMASK);
	}

	@Override public String toString() {
		return "EntryLocation3D{" +
			"entryX=" + entryX +
			", entryY=" + entryY +
			", entryZ=" + entryZ +
			'}';
	}

	public static class Provider implements IKeyProvider<EntryLocation3D> {

		@Override public EntryLocation3D fromRegionAndId(RegionKey regionKey, int id) throws IllegalArgumentException {
			if (!regionKey.getName().matches("-?\\d+\\.-?\\d+\\.-?\\d+\\.3dr")) {
				throw new IllegalArgumentException("Invalid name " + regionKey.getName());
			}
			String[] s = regionKey.getName().split("\\.");

			int relativeX = id >>> LOC_BITS * 2;
			int relativeY = (id >>> LOC_BITS) & LOC_BITMASK;
			int relativeZ = id & LOC_BITMASK;
			return new EntryLocation3D(
					Integer.parseInt(s[0]) << LOC_BITS | relativeX,
					Integer.parseInt(s[1]) << LOC_BITS | relativeY,
					Integer.parseInt(s[2]) << LOC_BITS | relativeZ);
		}

		@Override public int getKeyCount(RegionKey key) {
			return ENTRIES_PER_REGION;
		}
	}
}
