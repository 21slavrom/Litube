/*
 * sidx parsing logic adapted from media3's FragmentedMp4Extractor.parseSidx
 * (Apache 2.0 licensed). Parses the ISO 14496-12 SegmentIndex (sidx) box to
 * obtain per-segment byte ranges and durations, which are used to build a
 * DASH SegmentList so the Chromecast receiver fetches individual segments
 * instead of downloading the entire stream as a single byte-range request.
 */

package com.hhst.youtubelite.cast;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ChunkIndex;

/**
 * Parses an ISO 14496-12 SegmentIndex (sidx) box into a {@link ChunkIndex}.
 * <p>
 * The sidx box is located at {@code indexRange} in the YouTube fmp4 stream
 * (available from {@link org.schabi.newpipe.extractor.services.youtube.ItagItem#getIndexStart()}
 * / {@link org.schabi.newpipe.extractor.services.youtube.ItagItem#getIndexEnd()}).
 * Parsing it server-side (on the phone) lets us emit a DASH {@code SegmentList}
 * with explicit per-segment byte ranges, so the Chromecast default receiver
 * issues targeted Range GETs for individual fmp4 segments instead of
 * downloading the entire stream with {@code Range: bytes=0-}.
 */
@UnstableApi
final class SidxParser {

    private static final String TAG = "YTLCastProxy";
    private static final int HEADER_SIZE = 8;

    private SidxParser() {
    }

    /**
     * Parses a raw sidx box into a {@link ChunkIndex}.
     *
     * @param sidxData         the raw bytes fetched from the stream's index range; may
     *                         contain other boxes (e.g. moov tail) before the sidx box —
     *                         this method scans for the "sidx" box type at any 4-byte
     *                         boundary
     * @param dataStartOffset  the absolute byte offset in the stream where
     *                         {@code sidxData[0]} begins (i.e. the itag's indexStart)
     * @return the parsed chunk index, or {@code null} if no sidx box could be located
     *         or the data is malformed
     */
    @Nullable
    static ChunkIndex parse(@NonNull byte[] sidxData, long dataStartOffset) {
     try {
        int sidxOffset = findSidxBoxOffset(sidxData);
        if (sidxOffset < 0) {
            Log.w(TAG, "SidxParser: no sidx box found in " + sidxData.length + " bytes");
            return null;
        }
        // Box header: [size(4)] [type(4)]. Size includes the header itself.
        long sidxSize = ((long)(sidxData[sidxOffset] & 0xFF) << 24)
                      | ((long)(sidxData[sidxOffset + 1] & 0xFF) << 16)
                      | ((long)(sidxData[sidxOffset + 2] & 0xFF) << 8)
                      |  (long)(sidxData[sidxOffset + 3] & 0xFF);
        long sidxAbsoluteEnd = dataStartOffset + sidxOffset + sidxSize;
        long inputPosition = sidxAbsoluteEnd; // first segment starts right after sidx

        ParsableByteArray atom = new ParsableByteArray(sidxData, sidxData.length);
        atom.setPosition(sidxOffset + HEADER_SIZE);
        int fullAtom = atom.readInt();
        int version = fullAtom & 0xFF;

        atom.skipBytes(4); // referenceID
        long timescale = atom.readUnsignedInt();
        long earliestPresentationTime;
        long offset = inputPosition;
        if (version == 0) {
            earliestPresentationTime = atom.readUnsignedInt();
            offset += atom.readUnsignedInt();
        } else {
            // version 1: 64-bit fields. readUnsignedLongToLong throws if the top
            // bit is set (it can't represent unsigned > Long.MAX_VALUE in a long).
            // For sidx this is extremely unlikely in practice (the values are times
            // and byte offsets), but some VP9/AV1 streams have large values. Mask
            // off the sign bit and interpret as unsigned via BigInteger-free math.
            earliestPresentationTime = readUnsignedLongBitsAsLong(atom);
            offset += readUnsignedLongBitsAsLong(atom);
        }
        long earliestPresentationTimeUs = (earliestPresentationTime * C.MICROS_PER_SECOND) / timescale;

        atom.skipBytes(2); // reserved

        int referenceCount = atom.readUnsignedShort();
        if (referenceCount == 0) {
            Log.w(TAG, "SidxParser: referenceCount=0 (empty sidx)");
            return null;
        }

        int[] sizes = new int[referenceCount];
        long[] offsets = new long[referenceCount];
        long[] durationsUs = new long[referenceCount];
        long[] timesUs = new long[referenceCount];

        long time = earliestPresentationTime;
        long timeUs = earliestPresentationTimeUs;
        for (int i = 0; i < referenceCount; i++) {
            int firstInt = atom.readInt();
            // Bit 31 is the reference type (1 = indirect, 0 = media). We only handle media.
            if ((firstInt & 0x80000000) != 0) {
                Log.w(TAG, "SidxParser: indirect reference at index " + i + ", aborting");
                return null;
            }
            long referenceDuration = atom.readUnsignedInt();

            sizes[i] = 0x7FFFFFFF & firstInt;
            offsets[i] = offset;

            timesUs[i] = timeUs;
            time += referenceDuration;
            timeUs = (time * C.MICROS_PER_SECOND) / timescale;
            durationsUs[i] = timeUs - timesUs[i];

            atom.skipBytes(4); // startsWithSAP / SAPType / SAPDeltaTime
            offset += sizes[i];
        }

        return new ChunkIndex(sizes, offsets, durationsUs, timesUs);
     } catch (Exception e) {
        Log.w(TAG, "SidxParser: parse failed", e);
        return null;
     }
    }

    /**
     * Scans {@code data} for the ISO 14496-12 sidx box header (type field = "sidx").
     * Returns the offset of the box (i.e. the position of the size field), or
     * {@code -1} if not found. The sidx box type is 0x73696478 ("sidx" in ASCII).
     * Boxes are 4-byte aligned in fmp4, so we step in 4-byte increments.
     */
    private static int findSidxBoxOffset(@NonNull byte[] data) {
        if (data.length < HEADER_SIZE) return -1;
        // Walk the box sequence: at each position, read size (4) + type (4).
        // If type == "sidx", return that position. Otherwise advance by `size`.
        int pos = 0;
        while (pos + HEADER_SIZE <= data.length) {
            long size = ((long)(data[pos] & 0xFF) << 24)
                      | ((long)(data[pos + 1] & 0xFF) << 16)
                      | ((long)(data[pos + 2] & 0xFF) << 8)
                      |  (long)(data[pos + 3] & 0xFF);
            int type = ((data[pos + 4] & 0xFF) << 24)
                     | ((data[pos + 5] & 0xFF) << 16)
                     | ((data[pos + 6] & 0xFF) << 8)
                     |  (data[pos + 7] & 0xFF);
            if (type == 0x73696478) {
                return pos;
            }
            // size == 0 means "to end of file"; size == 1 means 64-bit size in
            // a real fmp4, but neither should appear inside a sidx range. Bail.
            if (size <= 0 || pos + size > data.length) {
                // Fall back to a brute-force scan in case the bytes aren't a
                // clean box sequence (e.g. the indexRange starts mid-box).
                break;
            }
            pos += (int) size;
        }
        // Brute-force scan for "sidx" at any 4-byte boundary as a last resort.
        // Validate the candidate by checking that the 4 bytes preceding "sidx"
        // form a plausible box size (> header, fits in the buffer) to avoid
        // matching the "sidx" pattern inside random binary data.
        for (int i = 4; i + 4 <= data.length; i += 4) {
            if (data[i] == 0x73 && data[i + 1] == 0x69
                    && data[i + 2] == 0x64 && data[i + 3] == 0x78) {
                int sizeOffset = i - 4;
                long size = ((long)(data[sizeOffset] & 0xFF) << 24)
                          | ((long)(data[sizeOffset + 1] & 0xFF) << 16)
                          | ((long)(data[sizeOffset + 2] & 0xFF) << 8)
                          |  (long)(data[sizeOffset + 3] & 0xFF);
                if (size >= HEADER_SIZE && sizeOffset + size <= data.length) {
                    return sizeOffset;
                }
            }
        }
        return -1;
    }

    /**
     * Reads 8 bytes as an unsigned 64-bit integer, returning it as a {@code long}.
     * Unlike {@link ParsableByteArray#readUnsignedLongToLong()}, this does not
     * throw if the top bit is set — it simply masks it off. For sidx fields
     * (times and offsets) the loss of the top bit is irrelevant in practice.
     */
    private static long readUnsignedLongBitsAsLong(@NonNull ParsableByteArray atom) {
        long high = atom.readUnsignedInt();
        long low = atom.readUnsignedInt();
        return (high << 32) | low;
    }
}
