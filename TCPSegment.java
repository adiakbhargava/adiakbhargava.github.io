public class TCPSegment {

    // -------------------------------------------------------------------------
    // HEADER SIZE CONSTANT
    // Derived from Figure 4:
    // 4 (seqNum) + 4 (ackNum) + 8 (timestamp) + 4 (length+flags) + 4 (zeros+checksum)
    // = 24 bytes total header
    // -------------------------------------------------------------------------
    public static final int HEADER_SIZE = 24;

    // -------------------------------------------------------------------------
    // HEADER FIELDS — mapped directly from Figure 4 and Section 2.1.1
    // -------------------------------------------------------------------------

    // Byte Sequence Number (4 bytes)
    // "indicates the position of the first byte of the data in this segment"
    // Section 2.1.1. Starts at 0 for a new connection (Section 2.1.4).
    public int seqNum;

    // Acknowledgment (4 bytes)
    // "indicates the next byte expected in the reverse direction"
    // Section 2.1.1
    public int ackNum;

    // Timestamp (8 bytes)
    // "derived from System.nanoTime()... 64 bits (or 8 bytes) long"
    // Section 2.1.1. Sender sets this. Receiver copies it back into ACK
    // so sender can compute RTT. Section 2.2.
    public long timestamp;

    // Length of DATA portion only, in bytes. NOT including header.
    // "Length is the length of the data portion (in bytes)"
    // Section 2.1.1. Only 29 bits are valid — lower 3 bits are flags.
    // We store length and flags separately and pack them during serialization.
    public int length;

    // Checksum — 16 bits, one's complement over entire packet.
    // "computed over the entire packet with the checksum assumed zero"
    // Section 2.1.1 and Appendix A.
    // Java short is signed but we treat it as raw bits.
    public short checksum;

    // Three flags — packed into the 3 LSBs of the length+flags word.
    // S = bit 2, F = bit 1, A = bit 0. Figure 4 and Section 2.1.1.
    public boolean synFlag; // SYN — connection open
    public boolean finFlag; // FIN — connection close
    public boolean ackFlag; // ACK — acknowledgment valid

    // -------------------------------------------------------------------------
    // PAYLOAD
    // Raw data bytes. Max size = MTU (command line arg). Section 2.1.3.
    // null or empty for ACK-only packets (length will be 0).
    // "If no data is sent along with the acknowledgment, the length of
    //  this segment will be zero." Section 2.1.4.
    // -------------------------------------------------------------------------
    public byte[] data;

    // -------------------------------------------------------------------------
    // CONSTRUCTORS
    // -------------------------------------------------------------------------

    // Empty constructor — used when deserializing a received packet.
    // fromBytes() will fill in all fields after construction.
    public TCPSegment() {}

    // Convenience constructor — used when building a packet to send.
    // Checksum is intentionally left at 0 here. Caller must call
    // computeChecksum() after construction and before sending.
    // Appendix A: "checksum assumed zero in the input"
    public TCPSegment(int seqNum, int ackNum, long timestamp,
                      boolean synFlag, boolean finFlag, boolean ackFlag,
                      byte[] data) {
        this.seqNum    = seqNum;
        this.ackNum    = ackNum;
        this.timestamp = timestamp;
        this.synFlag   = synFlag;
        this.finFlag   = finFlag;
        this.ackFlag   = ackFlag;
        this.data      = data;
        // length = number of data bytes only (not header)
        // if data is null this segment carries no payload (e.g. pure ACK)
        this.length    = (data != null) ? data.length : 0;
        this.checksum  = 0; // set by computeChecksum() before sending
    }

    // -------------------------------------------------------------------------
    // SERIALIZATION — toBytes()
    // Converts all fields into a flat byte[] for a UDP payload.
    // Layout matches Figure 4 exactly.
    // Checksum field is written as whatever this.checksum currently holds.
    // Caller must call computeChecksum() before toBytes() if integrity needed.
    // -------------------------------------------------------------------------
    public byte[] toBytes() {

        int dataLen = (data != null) ? data.length : 0;

        // Allocate: 24 byte header + however many data bytes we have
        byte[] buf = new byte[HEADER_SIZE + dataLen];

        // --- Bytes 0-3: seqNum ---
        // Big-endian: most significant byte written first (network byte order).
        // >> shifts right to isolate each byte.
        // & 0xFF masks off all but lowest 8 bits to kill sign extension.
        // (byte) cast takes only the lowest 8 bits.
        buf[0] = (byte)((seqNum >> 24) & 0xFF);
        buf[1] = (byte)((seqNum >> 16) & 0xFF);
        buf[2] = (byte)((seqNum >> 8)  & 0xFF);
        buf[3] = (byte)( seqNum        & 0xFF);

        // --- Bytes 4-7: ackNum ---
        // Same big-endian pattern as seqNum.
        buf[4] = (byte)((ackNum >> 24) & 0xFF);
        buf[5] = (byte)((ackNum >> 16) & 0xFF);
        buf[6] = (byte)((ackNum >> 8)  & 0xFF);
        buf[7] = (byte)( ackNum        & 0xFF);

        // --- Bytes 8-15: timestamp (64-bit long, 8 bytes) ---
        // Same pattern but 8 shifts instead of 4.
        // >> on a long shifts within 64 bits.
        // For shifts > 31, we are safe because timestamp is already a long.
        buf[8]  = (byte)((timestamp >> 56) & 0xFF);
        buf[9]  = (byte)((timestamp >> 48) & 0xFF);
        buf[10] = (byte)((timestamp >> 40) & 0xFF);
        buf[11] = (byte)((timestamp >> 32) & 0xFF);
        buf[12] = (byte)((timestamp >> 24) & 0xFF);
        buf[13] = (byte)((timestamp >> 16) & 0xFF);
        buf[14] = (byte)((timestamp >> 8)  & 0xFF);
        buf[15] = (byte)( timestamp        & 0xFF);

        // --- Bytes 16-19: length + flags packed into one 32-bit word ---
        // Section 2.1.1: "least three significant bits are used by flags"
        // S=bit2, F=bit1, A=bit0
        // Shift length LEFT by 3 to vacate the 3 LSBs for flags.
        // OR in each flag at its assigned bit position.
        int lengthAndFlags = (length  << 3)
                           | (synFlag ? (1 << 2) : 0)  // bit 2 = 0b100
                           | (finFlag ? (1 << 1) : 0)  // bit 1 = 0b010
                           | (ackFlag ? (1 << 0) : 0); // bit 0 = 0b001

        buf[16] = (byte)((lengthAndFlags >> 24) & 0xFF);
        buf[17] = (byte)((lengthAndFlags >> 16) & 0xFF);
        buf[18] = (byte)((lengthAndFlags >> 8)  & 0xFF);
        buf[19] = (byte)( lengthAndFlags        & 0xFF);

        // --- Bytes 20-21: all zeros ---
        // Figure 4: upper 16 bits of the checksum row are "All Zeros".
        // Java initializes all array elements to 0 by default.
        // Not setting these explicitly is intentional, not an omission.
        // buf[20] = 0; (already 0)
        // buf[21] = 0; (already 0)

        // --- Bytes 22-23: checksum (16-bit value) ---
        // checksum is a Java short (signed 16-bit).
        // & 0xFFFF gets a clean int with no sign extension before writing.
        // If computeChecksum() has not been called yet, this writes 0,
        // which is correct per Appendix A for checksum computation input.
        buf[22] = (byte)((checksum >> 8) & 0xFF);
        buf[23] = (byte)( checksum       & 0xFF);

        // --- Bytes 24 onwards: data payload ---
        // Copy each data byte one at a time.
        for (int i = 0; i < dataLen; i++) {
            buf[HEADER_SIZE + i] = data[i];
        }

        return buf;
    }

    // -------------------------------------------------------------------------
    // DESERIALIZATION — fromBytes()
    // Static factory method. Takes a raw byte[] from a received UDP packet
    // and reconstructs a TCPSegment with all fields populated.
    // Mirror image of toBytes() — every operation is the reverse.
    // -------------------------------------------------------------------------
    public static TCPSegment fromBytes(byte[] buf) {

        TCPSegment seg = new TCPSegment();

        // --- Bytes 0-3: seqNum ---
        // & 0xFF on each byte kills sign extension before shifting.
        // Shift each byte to its correct position and OR together.
        seg.seqNum = ((buf[0] & 0xFF) << 24)
                   | ((buf[1] & 0xFF) << 16)
                   | ((buf[2] & 0xFF) << 8)
                   |  (buf[3] & 0xFF);

        // --- Bytes 4-7: ackNum ---
        seg.ackNum = ((buf[4] & 0xFF) << 24)
                   | ((buf[5] & 0xFF) << 16)
                   | ((buf[6] & 0xFF) << 8)
                   |  (buf[7] & 0xFF);

        // --- Bytes 8-15: timestamp (64-bit long) ---
        // Must cast to long BEFORE shifting.
        // Without (long) cast, Java does the shift as 32-bit int
        // and any shift > 31 destroys the data entirely.
        seg.timestamp = ((long)(buf[8]  & 0xFF) << 56)
                      | ((long)(buf[9]  & 0xFF) << 48)
                      | ((long)(buf[10] & 0xFF) << 40)
                      | ((long)(buf[11] & 0xFF) << 32)
                      | ((long)(buf[12] & 0xFF) << 24)
                      | ((long)(buf[13] & 0xFF) << 16)
                      | ((long)(buf[14] & 0xFF) << 8)
                      |  (long)(buf[15] & 0xFF);

        // --- Bytes 16-19: length + flags word ---
        int lengthAndFlags = ((buf[16] & 0xFF) << 24)
                           | ((buf[17] & 0xFF) << 16)
                           | ((buf[18] & 0xFF) << 8)
                           |  (buf[19] & 0xFF);

        // Unpack length: shift RIGHT by 3 to remove the 3 flag bits.
        // Use >>> (unsigned right shift) so Java does not sign-extend.
        // If MSB is 1 and we used >>, the top bits fill with 1s
        // and length becomes a huge wrong value.
        seg.length = (lengthAndFlags >>> 3);

        // Unpack flags: isolate each bit position and check if set.
        // S=bit2: mask with 1 after shifting right 2
        // F=bit1: mask with 1 after shifting right 1
        // A=bit0: mask with 1 directly
        seg.synFlag = ((lengthAndFlags >> 2) & 1) == 1;
        seg.finFlag = ((lengthAndFlags >> 1) & 1) == 1;
        seg.ackFlag = ((lengthAndFlags >> 0) & 1) == 1;

        // --- Bytes 20-21: all zeros — nothing to read ---

        // --- Bytes 22-23: checksum ---
        // Reconstruct 16-bit value and cast to short.
        seg.checksum = (short)(((buf[22] & 0xFF) << 8)
                              |  (buf[23] & 0xFF));

        // --- Bytes 24 onwards: data payload ---
        int dataLen = buf.length - HEADER_SIZE;

        if (dataLen > 0) {
            seg.data = new byte[dataLen];
            for (int i = 0; i < dataLen; i++) {
                seg.data[i] = buf[HEADER_SIZE + i];
            }
        } else {
            // Pure ACK or control segment — no payload
            seg.data = null;
        }

        return seg;
    }

    // -------------------------------------------------------------------------
    // CHECKSUM COMPUTATION — computeChecksum()
    // Implements one's complement checksum per Appendix A.
    // Must be called AFTER all other fields are set, BEFORE sending.
    // Sets this.checksum to the computed value.
    // -------------------------------------------------------------------------
    public void computeChecksum() {

        // Zero out checksum field before computing — Appendix A:
        // "checksum assumed zero in the input"
        this.checksum = 0;

        // Get full packet bytes with checksum field zeroed
        byte[] buf = this.toBytes();

        int sum = 0;

        // Loop through bytes two at a time — each pair is one 16-bit chunk
        for (int i = 0; i < buf.length; i += 2) {

            if (i == buf.length - 1) {
                // Odd length: one byte left over
                // Pad lower byte with 0x00 implicitly by only shifting upper byte
                // Appendix A: "pad it with 0x00"
                sum += ((buf[i] & 0xFF) << 8);
            } else {
                // Normal case: combine two bytes into one 16-bit chunk
                // & 0xFF on both bytes prevents sign extension
                sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
            }

            // Handle carry after each addition.
            // If bit 16 or higher is set, a carry has occurred.
            // Add it back into bit 0 — this is one's complement carry rule.
            while ((sum >> 16) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
        }

        // One's complement: bitwise NOT the sum.
        // & 0xFFFF masks to 16 bits before casting to short.
        // (short) cast is safe — 0xFFFF as short is -1, treated as raw bits.
        this.checksum = (short)(~sum & 0xFFFF);
    }

    // -------------------------------------------------------------------------
    // CHECKSUM VERIFICATION — verifyChecksum()
    // Runs the same one's complement algorithm over the ENTIRE received packet
    // including the checksum field.
    // Returns true if result is 0xFFFF — mathematical proof the packet
    // is intact. Any corruption changes the result away from 0xFFFF.
    // -------------------------------------------------------------------------
    public static boolean verifyChecksum(byte[] buf) {

        int sum = 0;

        for (int i = 0; i < buf.length; i += 2) {

            if (i == buf.length - 1) {
                sum += ((buf[i] & 0xFF) << 8);
            } else {
                sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
            }

            while ((sum >> 16) != 0) {
                sum = (sum & 0xFFFF) + (sum >> 16);
            }
        }

        // A valid packet produces 0xFFFF because:
        // S + ~S = 0xFFFF in one's complement arithmetic
        // Any bit corruption changes S, breaking the equality
        return (sum & 0xFFFF) == 0xFFFF;
    }

    // -------------------------------------------------------------------------
    // SANITY TEST — main()
    // Verifies round-trip serialization and checksum correctness.
    // Remove or comment out before final submission.
    // -------------------------------------------------------------------------
    public static void main(String[] args) {

        byte[] testData = {0x48, 0x65, 0x6C, 0x6C, 0x6F}; // "Hello"

        TCPSegment original = new TCPSegment(
            12345,
            67890,
            System.nanoTime(),
            true,   // synFlag
            false,  // finFlag
            true,   // ackFlag
            testData
        );

        // Test round-trip serialization (checksum still 0 at this point)
        byte[] raw = original.toBytes();
        TCPSegment parsed = TCPSegment.fromBytes(raw);

        boolean pass = true;

        if (parsed.seqNum != original.seqNum) {
            System.out.println("FAIL seqNum: got " + parsed.seqNum);
            pass = false;
        }
        if (parsed.ackNum != original.ackNum) {
            System.out.println("FAIL ackNum: got " + parsed.ackNum);
            pass = false;
        }
        if (parsed.timestamp != original.timestamp) {
            System.out.println("FAIL timestamp: got " + parsed.timestamp);
            pass = false;
        }
        if (parsed.length != original.length) {
            System.out.println("FAIL length: got " + parsed.length);
            pass = false;
        }
        if (parsed.synFlag != original.synFlag) {
            System.out.println("FAIL synFlag: got " + parsed.synFlag);
            pass = false;
        }
        if (parsed.finFlag != original.finFlag) {
            System.out.println("FAIL finFlag: got " + parsed.finFlag);
            pass = false;
        }
        if (parsed.ackFlag != original.ackFlag) {
            System.out.println("FAIL ackFlag: got " + parsed.ackFlag);
            pass = false;
        }
        if (parsed.data == null || parsed.data.length != original.data.length) {
            System.out.println("FAIL data length");
            pass = false;
        } else {
            for (int i = 0; i < original.data.length; i++) {
                if (parsed.data[i] != original.data[i]) {
                    System.out.println("FAIL data byte " + i);
                    pass = false;
                }
            }
        }

        if (pass) {
            System.out.println("PASS — all fields survived serialization round trip");
        }

        // Test checksum on valid packet
        original.computeChecksum();
        byte[] rawWithChecksum = original.toBytes();
        System.out.println("Checksum valid: "
            + TCPSegment.verifyChecksum(rawWithChecksum));

        // Test corruption detection
        // Flip all bits in byte 5 — simulates a corrupted packet
        rawWithChecksum[5] ^= 0xFF;
        System.out.println("Corruption detected: "
            + !TCPSegment.verifyChecksum(rawWithChecksum));
    }
}