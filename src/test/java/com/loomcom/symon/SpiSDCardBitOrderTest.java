package com.loomcom.symon;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit test to verify SPI bit order calculations for SD Card responses
 */
public class SpiSDCardBitOrderTest {

    @Test
    public void testMsbFirstBitOrder() {
        // Test MSB first transmission of 0x01 = 00000001
        // Expected output: 0,0,0,0,0,0,0,1 (MSB to LSB)
        int responseData = 0x01;
        int[] expectedBits = {0, 0, 0, 0, 0, 0, 0, 1};

        System.out.println("Testing MSB first transmission of 0x01 (00000001)");
        System.out.println("Expected: 0,0,0,0,0,0,0,1");

        // Simulate the bit transmission logic from SpiSDCard.java
        for (int bitCount = 1; bitCount <= 8; bitCount++) {
            int currentBitIndex = (bitCount - 1) % 8;

            // Test original logic (should be MSB first)
            int bitPositionOriginal = 7 - currentBitIndex;
            int misoOriginal = (responseData >> bitPositionOriginal) & 1;

            // Test my attempted fix (was actually LSB first)
            int bitPositionFixed = currentBitIndex;
            int misoFixed = (responseData >> (7 - bitPositionFixed)) & 1;

            System.out.printf("BitCount=%d, currentBitIndex=%d:\n", bitCount, currentBitIndex);
            System.out.printf("  Original: bitPos=%d, miso=%d\n", bitPositionOriginal, misoOriginal);
            System.out.printf("  My Fix:   bitPos=%d, miso=%d\n", 7-bitPositionFixed, misoFixed);
            System.out.printf("  Expected: %d\n", expectedBits[currentBitIndex]);
            System.out.println();

            // The original logic should be correct for MSB first
            assertEquals("Original logic should produce MSB first",
                        expectedBits[currentBitIndex], misoOriginal);
        }
    }

    @Test
    public void testMsbFirstBitOrderFor0x80() {
        // Test MSB first transmission of 0x80 = 10000000
        // Expected output: 1,0,0,0,0,0,0,0 (MSB to LSB)
        int responseData = 0x80;
        int[] expectedBits = {1, 0, 0, 0, 0, 0, 0, 0};

        System.out.println("Testing MSB first transmission of 0x80 (10000000)");
        System.out.println("Expected: 1,0,0,0,0,0,0,0");

        for (int bitCount = 1; bitCount <= 8; bitCount++) {
            int currentBitIndex = (bitCount - 1) % 8;
            int bitPosition = 7 - currentBitIndex;
            int miso = (responseData >> bitPosition) & 1;

            System.out.printf("BitCount=%d, bitPos=%d, miso=%d, expected=%d\n",
                             bitCount, bitPosition, miso, expectedBits[currentBitIndex]);

            assertEquals("Should produce MSB first", expectedBits[currentBitIndex], miso);
        }
    }

    @Test
    public void testCurrentImplementationBehavior() {
        // This test will help us understand what the current real implementation
        // is actually doing by simulating the exact logic
        int responseData = 0x01;

        System.out.println("Testing current implementation behavior:");
        System.out.println("Response data: 0x01 = 00000001");

        StringBuilder actualSequence = new StringBuilder();

        for (int bitCount = 1; bitCount <= 8; bitCount++) {
            int currentBitIndex = (bitCount - 1) % 8;
            int bitPosition = 7 - currentBitIndex;  // Original logic
            int miso = (responseData >> bitPosition) & 1;

            actualSequence.append(miso);
            if (bitCount < 8) actualSequence.append(",");

            System.out.printf("Step %d: currentBitIndex=%d, bitPosition=%d, miso=%d\n",
                             bitCount, currentBitIndex, bitPosition, miso);
        }

        String result = actualSequence.toString();
        System.out.println("Actual sequence: " + result);

        // For 0x01, MSB first should be: 0,0,0,0,0,0,0,1
        assertEquals("MSB first of 0x01", "0,0,0,0,0,0,0,1", result);
    }
}