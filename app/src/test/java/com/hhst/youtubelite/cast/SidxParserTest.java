package com.hhst.youtubelite.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.media3.extractor.ChunkIndex;

import org.junit.Test;

import java.nio.ByteBuffer;

/**
 * Regression tests for {@link SidxParser}. Cast streaming depends on this
 * parser producing accurate per-segment byte ranges and durations, since the
 * DASH SegmentList emitted by {@link DashManifestBuilder} is consumed verbatim
 * by the Chromecast default receiver. A regression that drops a segment,
 * miscalculates an offset, or silently returns null would manifest as a stall
 * or full-stream download on the receiver — these tests pin the contract.
 */
public class SidxParserTest {

	private static final int TIMESCALE_US = 1_000_000;

	/**
	 * Builds a minimal ISO 14496-12 sidx box (version 0) with the given
	 * segment sizes and durations. timescale=1_000_000 (microseconds),
	 * earliestPresentationTime=0, firstOffset=0.
	 */
	private static byte[] buildSidxV0(int[] segSizes, int[] segDurations) {
		int referenceCount = segSizes.length;
		// size(4) + type(4) + fullAtom(4) + referenceID(4) + timescale(4)
		// + earliestPts(4) + firstOffset(4) + reserved(2) + refCount(2)
		// + references(12 each)
		int sidxSize = 8 + 4 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + referenceCount * 12;
		ByteBuffer buf = ByteBuffer.allocate(sidxSize);
		buf.putInt(sidxSize);
		buf.put("sidx".getBytes());
		buf.putInt(0);            // version=0, flags=0
		buf.putInt(1);            // referenceID
		buf.putInt(TIMESCALE_US); // timescale
		buf.putInt(0);            // earliestPresentationTime
		buf.putInt(0);            // firstOffset
		buf.putShort((short) 0);  // reserved
		buf.putShort((short) referenceCount);
		for (int i = 0; i < referenceCount; i++) {
			// bit 31 = 0 (media reference), bits 30..0 = size
			buf.putInt(segSizes[i] & 0x7FFFFFFF);
			buf.putInt(segDurations[i]);          // subsegmentDuration (timescale ticks)
			buf.putInt(0x80000000);               // startsWithSAP=1, sapType=1, sapDeltaTime=0
		}
		return buf.array();
	}

	/** Builds a sidx box with a leading prefix that is not a clean box sequence. */
	private static byte[] withPrefix(byte[] prefix, byte[] sidx) {
		byte[] out = new byte[prefix.length + sidx.length];
		System.arraycopy(prefix, 0, out, 0, prefix.length);
		System.arraycopy(sidx, 0, out, prefix.length, sidx.length);
		return out;
	}

	@Test
	public void parsesValidSidxV0() {
		int[] sizes = {100, 200, 300};
		int[] durations = {5_000_000, 5_000_000, 5_000_000};
		byte[] sidx = buildSidxV0(sizes, durations);

		ChunkIndex idx = SidxParser.parse(sidx, 0L);

		assertNotNull(idx);
		assertEquals(3, idx.length);
		assertEquals(100, idx.sizes[0]);
		assertEquals(200, idx.sizes[1]);
		assertEquals(300, idx.sizes[2]);
		// First segment starts immediately after the sidx box.
		assertEquals(sidx.length, idx.offsets[0]);
		assertEquals(sidx.length + 100, idx.offsets[1]);
		assertEquals(sidx.length + 100 + 200, idx.offsets[2]);
		assertEquals(5_000_000L, idx.durationsUs[0]);
		assertEquals(5_000_000L, idx.durationsUs[1]);
		assertEquals(5_000_000L, idx.durationsUs[2]);
		assertEquals(0L, idx.timesUs[0]);
		assertEquals(5_000_000L, idx.timesUs[1]);
		assertEquals(10_000_000L, idx.timesUs[2]);
	}

	@Test
	public void dataStartOffsetShiftsAbsoluteOffsets() {
		// The sidx box lives at byte 100 in the upstream stream; the buffer
		// passed to parse() starts at that offset, so all returned offsets
		// must be absolute (i.e. shifted by dataStartOffset).
		int[] sizes = {50, 60};
		int[] durations = {1_000_000, 1_000_000};
		byte[] sidx = buildSidxV0(sizes, durations);
		long dataStartOffset = 100L;

		ChunkIndex idx = SidxParser.parse(sidx, dataStartOffset);

		assertNotNull(idx);
		assertEquals(dataStartOffset + sidx.length, idx.offsets[0]);
		assertEquals(dataStartOffset + sidx.length + 50, idx.offsets[1]);
	}

	@Test
	public void handlesVariableDurations() {
		int[] sizes = {100, 200, 300};
		int[] durations = {1_000_000, 2_500_000, 500_000};
		byte[] sidx = buildSidxV0(sizes, durations);

		ChunkIndex idx = SidxParser.parse(sidx, 0L);

		assertNotNull(idx);
		assertEquals(1_000_000L, idx.durationsUs[0]);
		assertEquals(2_500_000L, idx.durationsUs[1]);
		assertEquals(500_000L, idx.durationsUs[2]);
		assertEquals(0L, idx.timesUs[0]);
		assertEquals(1_000_000L, idx.timesUs[1]);
		assertEquals(3_500_000L, idx.timesUs[2]);
	}

	@Test
	public void returnsNullWhenNoSidxBoxFound() {
		byte[] junk = {0, 0, 0, 16, 'f', 'r', 'e', 'e',
				0, 0, 0, 0, 0, 0, 0, 0};
		assertNull(SidxParser.parse(junk, 0L));
	}

	@Test
	public void returnsNullWhenBufferTooSmallForHeader() {
		assertNull(SidxParser.parse(new byte[]{1, 2, 3}, 0L));
	}

	@Test
	public void returnsNullWhenReferenceCountIsZero() {
		// Build a sidx box with referenceCount=0.
		ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 4 + 4 + 4 + 4 + 2 + 2);
		buf.putInt(buf.capacity());
		buf.put("sidx".getBytes());
		buf.putInt(0);
		buf.putInt(1);
		buf.putInt(TIMESCALE_US);
		buf.putInt(0);
		buf.putInt(0);
		buf.putShort((short) 0);
		buf.putShort((short) 0); // referenceCount=0

		assertNull(SidxParser.parse(buf.array(), 0L));
	}

	@Test
	public void returnsNullForIndirectReference() {
		// Single reference with bit 31 set (indirect). The parser must reject
		// the entire sidx rather than silently emitting a bogus entry.
		ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + 12);
		buf.putInt(buf.capacity());
		buf.put("sidx".getBytes());
		buf.putInt(0);
		buf.putInt(1);
		buf.putInt(TIMESCALE_US);
		buf.putInt(0);
		buf.putInt(0);
		buf.putShort((short) 0);
		buf.putShort((short) 1);      // referenceCount=1
		buf.putInt(0x80000000);       // bit 31 set = indirect reference
		buf.putInt(5_000_000);
		buf.putInt(0);

		assertNull(SidxParser.parse(buf.array(), 0L));
	}

	@Test
	public void bruteForceScanFindsSidxAfterNonBoxPrefix() {
		// Prefix with bytes that do not form a clean box sequence (size too
		// large to walk past). The brute-force scanner must still locate the
		// "sidx" pattern at a 4-byte boundary and validate the preceding size.
		int[] sizes = {100, 200};
		int[] durations = {5_000_000, 5_000_000};
		byte[] sidx = buildSidxV0(sizes, durations);
		// 8 bytes of 0xFF — not a valid box (size would be 4 GiB).
		byte[] prefixed = withPrefix(new byte[]{(byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, (byte) 0xFF}, sidx);

		ChunkIndex idx = SidxParser.parse(prefixed, 0L);

		assertNotNull("brute-force scan should locate sidx after non-box prefix",
				idx);
		assertEquals(2, idx.length);
		assertEquals(100, idx.sizes[0]);
		// First segment starts after the 8-byte prefix + sidx box.
		assertEquals(8 + sidx.length, idx.offsets[0]);
	}

	@Test
	public void bruteForceScanFindsSidxAfterValidFreeBox() {
		// A valid "free" box prefix of size 12. The box-sequence walker should
		// skip past it and land on the sidx box.
		int[] sizes = {100};
		int[] durations = {5_000_000};
		byte[] sidx = buildSidxV0(sizes, durations);
		ByteBuffer freeBox = ByteBuffer.allocate(12);
		freeBox.putInt(12);
		freeBox.put("free".getBytes());
		freeBox.putInt(0);
		byte[] prefixed = withPrefix(freeBox.array(), sidx);

		ChunkIndex idx = SidxParser.parse(prefixed, 0L);

		assertNotNull(idx);
		assertEquals(1, idx.length);
		// First segment starts after free box (12) + sidx box.
		assertEquals(12 + sidx.length, idx.offsets[0]);
	}

	@Test
	public void parsesSingleSegmentSidx() {
		byte[] sidx = buildSidxV0(new int[]{42}, new int[]{5_000_000});

		ChunkIndex idx = SidxParser.parse(sidx, 0L);

		assertNotNull(idx);
		assertEquals(1, idx.length);
		assertEquals(42, idx.sizes[0]);
		assertEquals(sidx.length, idx.offsets[0]);
		assertEquals(5_000_000L, idx.durationsUs[0]);
		assertEquals(0L, idx.timesUs[0]);
	}
}
