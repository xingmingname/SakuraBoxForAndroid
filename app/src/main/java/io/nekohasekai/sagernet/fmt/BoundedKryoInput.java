package io.nekohasekai.sagernet.fmt;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.ByteBufferInput;

import java.nio.ByteBuffer;

/** Allocation limits for the existing Kryo wire format, including nested bean decodes. */
public final class BoundedKryoInput extends ByteBufferInput {
    public static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    public static final int MAX_STRING_CHARS = 4 * 1024 * 1024;
    public static final int MAX_COLLECTION_ELEMENTS = 200000;
    public static final long MAX_ALLOCATION_BYTES = 32L * 1024 * 1024;
    private static final int MAX_NESTING = 16;
    private static final ThreadLocal<Budget> ACTIVE_BUDGET = new ThreadLocal<>();

    private static final class Budget {
        long remaining = MAX_ALLOCATION_BYTES;
        int depth;
        void reserve(long bytes) {
            if (bytes < 0 || bytes > remaining) throw invalid("allocation budget exceeded");
            remaining -= bytes;
        }
    }

    private final Budget budget;
    private boolean closed;

    public BoundedKryoInput(byte[] bytes) {
        super(checkedBuffer(bytes));
        Budget active = ACTIVE_BUDGET.get();
        if (active == null) active = new Budget();
        if (active.depth >= MAX_NESTING) throw invalid("nesting limit exceeded");
        active.reserve(64);
        active.depth++;
        budget = active;
        ACTIVE_BUDGET.set(active);
    }

    private static ByteBuffer checkedBuffer(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_INPUT_BYTES) throw invalid("input size limit exceeded");
        // Wrap the existing bytes instead of allocating an additional direct buffer.
        return ByteBuffer.wrap(bytes);
    }

    private static KryoException invalid(String reason) {
        return new KryoException("Invalid binary configuration: " + reason);
    }

    private long remainingBytes() {
        return (long) limit() - position();
    }

    private void reserveArray(int count, int elementBytes, int minimumInputBytes) {
        if (count < 0 || (long) count * minimumInputBytes > remainingBytes())
            throw invalid("invalid or truncated array length");
        budget.reserve(32L + (long) count * elementBytes);
    }

    private void reserveString() {
        int start = position();
        int count;
        if (readVarIntFlag()) {
            int encodedCount = super.readVarIntFlag(true);
            if (encodedCount < 0) throw invalid("invalid string length");
            count = Math.max(0, encodedCount - 1);
            if (count > MAX_STRING_CHARS || count > remainingBytes())
                throw invalid("invalid or truncated string length");
        } else {
            count = 0;
            int end = limit();
            while (true) {
                if (start + count >= end) throw invalid("unterminated string");
                int value = getByteBuffer().get(start + count) & 0xff;
                if (++count > MAX_STRING_CHARS) throw invalid("string length limit exceeded");
                if ((value & 0x80) != 0) break;
            }
        }
        // Includes String/builder, decoder scratch space and ASCII buffer growth.
        budget.reserve(64L + 8L * count);
        setPosition(start);
    }

    @Override public String readString() {
        reserveString();
        return super.readString();
    }

    @Override public StringBuilder readStringBuilder() {
        reserveString();
        return super.readStringBuilder();
    }

    /** Collection nodes and boxed elements are charged before the caller starts its loop. */
    public static int readCollectionSize(ByteBufferInput input, int minimumInputBytes) {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_ELEMENTS || minimumInputBytes <= 0
                || (long) count * minimumInputBytes > (long) input.limit() - input.position())
            throw invalid("invalid or truncated collection length");
        if (input instanceof BoundedKryoInput)
            ((BoundedKryoInput) input).budget.reserve(64L + 64L * count);
        return count;
    }

    @Override public byte[] readBytes(int length) {
        reserveArray(length, 1, 1);
        return super.readBytes(length);
    }

    @Override public int[] readInts(int length) {
        reserveArray(length, 4, 4);
        return super.readInts(length);
    }

    @Override public long[] readLongs(int length) {
        reserveArray(length, 8, 8);
        return super.readLongs(length);
    }

    @Override public float[] readFloats(int length) {
        reserveArray(length, 4, 4);
        return super.readFloats(length);
    }

    @Override public double[] readDoubles(int length) {
        reserveArray(length, 8, 8);
        return super.readDoubles(length);
    }

    @Override public short[] readShorts(int length) {
        reserveArray(length, 2, 2);
        return super.readShorts(length);
    }

    @Override public char[] readChars(int length) {
        reserveArray(length, 2, 2);
        return super.readChars(length);
    }

    @Override public boolean[] readBooleans(int length) {
        reserveArray(length, 1, 1);
        return super.readBooleans(length);
    }

    @Override public int[] readInts(int length, boolean optimizePositive) {
        reserveArray(length, 4, 1);
        return super.readInts(length, optimizePositive);
    }

    @Override public long[] readLongs(int length, boolean optimizePositive) {
        reserveArray(length, 8, 1);
        return super.readLongs(length, optimizePositive);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (--budget.depth == 0) ACTIVE_BUDGET.remove();
        super.close();
    }
}
