/*
 * Copyright (c) 2025 Waffle2e Computer Project
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 */

package com.loomcom.symon.devices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Simulated SD Card that can mount disk image files
 * Implements SPI protocol for SD card communication
 *
 * Clean, simple implementation based on java-periphery principles:
 * - Atomic byte-level transfers
 * - Pre-calculated responses
 * - Internal timing management
 */
public class SpiSDCard implements SpiDevice {

    private final static Logger logger = LoggerFactory.getLogger(SpiSDCard.class.getName());

    // SD card states
    private enum CardState {
        IDLE,
        READY,
        READING,
        WRITING,
        ERROR
    }

    // SD card commands
    private static final int CMD0 = 0x40;   // GO_IDLE_STATE
    private static final int CMD8 = 0x48;   // SEND_IF_COND
    private static final int CMD17 = 0x51;  // READ_SINGLE_BLOCK
    private static final int CMD24 = 0x58;  // WRITE_SINGLE_BLOCK
    private static final int CMD55 = 0x77;  // APP_CMD
    private static final int ACMD41 = 0x69; // SD_SEND_OP_COND

    // SD card responses
    private static final int R1_IDLE = 0x01;
    private static final int R1_READY = 0x00;
    private static final int R1_ILLEGAL_CMD = 0x04;

    // Data tokens
    private static final int DATA_TOKEN = 0xFE;
    private static final int DATA_ACCEPTED = 0x05;

    // Card properties
    private static final int SECTOR_SIZE = 512;
    private static final long DEFAULT_CARD_SIZE = 64 * 1024 * 1024; // 64MB default

    // Current state
    private CardState state = CardState.IDLE;
    private boolean selected = false;
    private RandomAccessFile imageFile = null;
    private String imagePath = null;
    private long cardSizeBytes = DEFAULT_CARD_SIZE;

    // Simple bit-level SPI state
    private int bitBuffer = 0;
    private int bitCount = 0;

    // Command processing
    private int[] commandBuffer = new int[6];
    private int commandIndex = 0;
    private boolean inCommand = false;

    // Response system - KEY IMPROVEMENT: Pre-calculated response bits
    private int[] preCalculatedResponseBits = new int[8];  // MSB-first bits ready to send
    private int responseBitIndex = 0;
    private boolean responseReady = false;
    private boolean responseStarted = false;

    // Pending response to be activated on SCK falling edge
    private int pendingResponseValue = 0;
    private boolean hasPendingResponse = false;

    // Multi-byte response queue for R7 responses
    private int[] responseQueue = new int[5];  // Up to 5 bytes for R7 (R1 + 4 data bytes)
    private int responseQueueIndex = 0;
    private int responseQueueLength = 0;
    private boolean usingResponseQueue = false;

    // Data transfer state
    private int[] dataBuffer = new int[SECTOR_SIZE];
    private int dataIndex = 0;
    private boolean inDataTransfer = false;
    private int dataTransferIndex = 0;      // Index into data transfer sequence
    private boolean dataSent = false;       // Track if data token has been sent
    private boolean writingData = false;
    private boolean awaitingWriteToken = false;  // Waiting for 0xFE data token after CMD24
    private int writeDataIndex = 0;              // Index for receiving write data
    private long currentSector = 0;

    public SpiSDCard() {
        reset();
        logger.info("SPI SD Card initialized (no image mounted)");
    }

    public SpiSDCard(String imagePath) {
        this();
        mountImage(imagePath);
    }

    @Override
    public void select() {
        selected = true;
        logger.debug("SD card selected");
    }

    @Override
    public void deselect() {
        selected = false;
        // Clear transient response state so re-select starts clean
        responseReady = false;
        responseBitIndex = 0;
        hasPendingResponse = false;
        usingResponseQueue = false;
        responseQueueIndex = 0;
        responseQueueLength = 0;
        Arrays.fill(preCalculatedResponseBits, 1);
        logger.debug("SD card deselected and transient response state cleared");
    }

    @Override
    public boolean isSelected() {
        return selected;
    }

    @Override
    public String getName() {
        return imagePath != null ? ("SD Card (" + imagePath + ")") : "SD Card (no image)";
    }

    @Override
    public void reset() {
        state = CardState.IDLE;
        selected = false;
        bitBuffer = 0;
        bitCount = 0;
        commandIndex = 0;
        inCommand = false;
        responseReady = false;
        responseStarted = false;
        responseBitIndex = 0;
        hasPendingResponse = false;
        inDataTransfer = false;
        dataTransferIndex = 0;
        dataSent = false;
        writingData = false;
        awaitingWriteToken = false;
        writeDataIndex = 0;
        Arrays.fill(preCalculatedResponseBits, 1); // Default high
        responseQueueIndex = 0;
        responseQueueLength = 0;
        usingResponseQueue = false;
        Arrays.fill(responseQueue, 0xFF);
        logger.debug("SD card reset");
    }

    /**
     * Core SPI transfer method - handles one bit at a time
     * This is where all the timing happens atomically
     */
    @Override
    public int transfer(int dataOut) {
        if (!selected) {
            return 1; // MISO high when not selected
        }

        // Default MISO response is high (idle state)
        int misoResponse = 1;

        // If we have a pre-calculated response ready, send current bit
        if (responseReady && responseBitIndex < 8) {
            misoResponse = preCalculatedResponseBits[responseBitIndex];

            // Reduced logging - only log start of response
            if (responseBitIndex == 0) {
                /* logger.info("SD Card starting to send response 0x{}",
                           String.format("%02X", (preCalculatedResponseBits[0] << 7) |
                                                (preCalculatedResponseBits[1] << 6) |
                                                (preCalculatedResponseBits[2] << 5) |
                                                (preCalculatedResponseBits[3] << 4) |
                                                (preCalculatedResponseBits[4] << 3) |
                                                (preCalculatedResponseBits[5] << 2) |
                                                (preCalculatedResponseBits[6] << 1) |
                                                preCalculatedResponseBits[7])); */
            }

            // Advance to next bit immediately after reading
            responseBitIndex++;
            if (responseBitIndex >= 8) {
                // logger.info("SD Card completed sending pre-calculated response");
                responseReady = false;
                responseBitIndex = 0;

                // Check if we have more bytes in the response queue
                if (usingResponseQueue && responseQueueIndex < responseQueueLength - 1) {
                    responseQueueIndex++;
                    prepareResponse(responseQueue[responseQueueIndex]);
                    // logger.info("SD Card R7: preparing next byte {}/{} = 0x{}",
                    //            responseQueueIndex + 1, responseQueueLength,
                    //            String.format("%02X", responseQueue[responseQueueIndex]));
                } else if (usingResponseQueue) {
                    // All bytes in queue have been sent
                    usingResponseQueue = false;
                    responseQueueIndex = 0;
                    responseQueueLength = 0;
                    // logger.info("SD Card R7 response complete - all {} bytes sent", responseQueueLength);
                }
            }
        }

        // Accumulate incoming bits and process complete bytes immediately
        bitBuffer = (bitBuffer << 1) | (dataOut & 1);
        bitCount++;

        // Process complete bytes immediately
        if (bitCount >= 8) {
            processByte(bitBuffer & 0xFF);
            bitBuffer = 0;
            bitCount = 0;
        }

        return misoResponse;
    }

    @Override
    public void onSckFallingEdge() {
        // Check if we have a pending response to activate
        if (hasPendingResponse) {
            // logger.debug("SD Card SCK falling edge: activating pending response 0x{}",
            //             String.format("%02X", pendingResponseValue));
            prepareResponse(pendingResponseValue);
            hasPendingResponse = false;
        }
    }

    /**
     * Process a complete received byte
     */
    private void processByte(int byteReceived) {
        // logger.debug("SD Card processByte: received=0x{}, state={}, inCommand={}, cmdIndex={}, responseReady={}, inDataTransfer={}",
        //            String.format("%02X", byteReceived), state, inCommand, commandIndex, responseReady, inDataTransfer);

        // Handle ongoing data transfer (read mode)
        if (inDataTransfer && !writingData) {
            handleDataTransfer(byteReceived);
            return;
        }

        // Handle write data reception
        if (awaitingWriteToken) {
            if (byteReceived == DATA_TOKEN) {
                // Got data token - now receive 512 bytes + 2 CRC bytes
                awaitingWriteToken = false;
                writingData = true;
                writeDataIndex = 0;
                logger.debug("SD Card received DATA_TOKEN, starting data reception for sector {}", currentSector);
            }
            // Ignore other bytes while waiting for token (0xFF dummy bytes)
            return;
        }

        // Handle write data bytes
        if (writingData) {
            handleWriteData(byteReceived);
            return;
        }

        // Don't process dummy bytes (0xFF) as commands - they're for reading responses
        if (byteReceived == 0xFF && !inCommand) {
            // logger.debug("SD Card received dummy byte 0xFF - not processing as command");
            return;
        }

        // Accumulate command bytes (SD commands start with 0x40-0x7F)
        // IMPORTANT: Don't treat 0xFF as command start even though it has bit 6 set
        if (((byteReceived & 0x40) != 0 && byteReceived != 0xFF) || inCommand) {
            // Starting a new command - clear any previous response
            if ((byteReceived & 0x40) != 0 && byteReceived != 0xFF && !inCommand) {
                responseReady = false;
                responseBitIndex = 0;
                hasPendingResponse = false;
            }

            // Start of command or continuation
            commandBuffer[commandIndex] = byteReceived;
            commandIndex++;
            inCommand = true;
            // logger.debug("SD Card command byte {}: 0x{}", commandIndex-1, String.format("%02X", byteReceived));

            // Complete command processing - but don't prepare response yet
            if (commandIndex >= 6) {
                // Complete command received - process it
                // logger.debug("SD Card complete command received: [{}, {}, {}, {}, {}]",
                //            String.format("%02X", commandBuffer[0]), String.format("%02X", commandBuffer[1]),
                //            String.format("%02X", commandBuffer[2]), String.format("%02X", commandBuffer[3]),
                //            String.format("%02X", commandBuffer[4]), String.format("%02X", commandBuffer[5]));

                // Store response value but don't prepare it yet (wait for SCK falling edge)
                pendingResponseValue = processCommandAndGetResponse();
                hasPendingResponse = (pendingResponseValue != 0xFF);

                commandIndex = 0;
                inCommand = false;
            }
            // NOTE: While inCommand=true (bytes 1-5), responseReady remains false,
            // so transfer() will return 1 (0xFF dummy bytes) until command is complete
        }
    }

    /**
     * Process a complete 6-byte SD command and return response value
     */
    private int processCommandAndGetResponse() {
        int cmd = commandBuffer[0];
        long arg = ((long)(commandBuffer[1] & 0xFF) << 24) |
                   ((long)(commandBuffer[2] & 0xFF) << 16) |
                   ((long)(commandBuffer[3] & 0xFF) << 8) |
                   (commandBuffer[4] & 0xFF);

        // logger.debug("SD command: 0x{}, arg: 0x{}", String.format("%02X", cmd), String.format("%08X", arg));

        int responseValue = 0xFF; // Default no response

        switch (cmd) {
            case CMD0: // GO_IDLE_STATE
                state = CardState.IDLE;
                responseValue = R1_IDLE;
                // logger.info("SD Card processed CMD0, will queue response=0x{}", String.format("%02X", responseValue));
                break;

            case CMD8: // SEND_IF_COND
                if (state == CardState.IDLE) {
                    // CMD8 uses R7 response format: R1 + 4 data bytes
                    // Set up multi-byte response queue
                    responseQueue[0] = R1_IDLE;        // R1 response
                    responseQueue[1] = 0x00;           // Reserved byte 1
                    responseQueue[2] = 0x00;           // Reserved byte 2
                    responseQueue[3] = 0x01;           // Voltage accepted (2.7-3.6V)
                    responseQueue[4] = 0xAA;           // Check pattern echo

                    responseQueueLength = 5;
                    responseQueueIndex = 0;
                    usingResponseQueue = true;

                    responseValue = responseQueue[0];  // Start with R1
                    // logger.info("CMD8 R7 response queue: [0x{}, 0x{}, 0x{}, 0x{}, 0x{}]",
                    //            String.format("%02X", responseQueue[0]),
                    //            String.format("%02X", responseQueue[1]),
                    //            String.format("%02X", responseQueue[2]),
                    //            String.format("%02X", responseQueue[3]),
                    //            String.format("%02X", responseQueue[4]));
                } else {
                    responseValue = R1_ILLEGAL_CMD;
                }
                break;

            case CMD55: // APP_CMD
                responseValue = (state == CardState.IDLE) ? R1_IDLE : R1_READY;
                break;

            case ACMD41: // SD_SEND_OP_COND
                state = CardState.READY;
                responseValue = R1_READY;
                break;

            case CMD17: // READ_SINGLE_BLOCK
                if (state == CardState.READY) {
                    currentSector = arg;
                    startReadOperation();
                    responseValue = R1_READY;
                } else {
                    responseValue = R1_ILLEGAL_CMD;
                }
                break;

            case CMD24: // WRITE_SINGLE_BLOCK
                if (state == CardState.READY) {
                    currentSector = arg;
                    responseValue = R1_READY;
                    // After response is sent, wait for data token (0xFE)
                    awaitingWriteToken = true;
                    writeDataIndex = 0;
                    logger.debug("SD Card CMD24: will write to sector {}", currentSector);
                } else {
                    responseValue = R1_ILLEGAL_CMD;
                }
                break;

            default:
                logger.warn("Unknown SD command: 0x{}", String.format("%02X", cmd));
                responseValue = R1_ILLEGAL_CMD;
                break;
        }

        return responseValue;
    }

    /**
     * KEY METHOD: Pre-calculate response bits for atomic, fast retrieval
     * This eliminates timing issues by doing calculation upfront
     */
    private void prepareResponse(int responseData) {
        // Pre-calculate all 8 response bits in MSB-first order
        for (int i = 0; i < 8; i++) {
            int bitPosition = 7 - i;  // MSB first: 7,6,5,4,3,2,1,0
            preCalculatedResponseBits[i] = (responseData >> bitPosition) & 1;
        }

        responseReady = true;
        responseBitIndex = 0;

        // logger.info("SD Card pre-calculated response 0x{} -> bits: {},{},{},{},{},{},{},{}",
        //            String.format("%02X", responseData),
        //            preCalculatedResponseBits[0], preCalculatedResponseBits[1], preCalculatedResponseBits[2], preCalculatedResponseBits[3],
        //            preCalculatedResponseBits[4], preCalculatedResponseBits[5], preCalculatedResponseBits[6], preCalculatedResponseBits[7]);
    }

    /**
     * Handle data transfer operations
     */
    private void handleDataTransfer(int byteReceived) {
        // The 6502 sends 0xFF bytes when reading data
        // We need to return: DATA_TOKEN (0xFE) + 512 bytes + 2 CRC bytes

        int responseValue = 0xFF; // Default idle

        if (dataTransferIndex == 0) {
            // First byte: send DATA_TOKEN
            responseValue = 0xFE; // DATA_TOKEN
            // logger.info("SD Card sending DATA_TOKEN (0xFE) for sector {}", currentSector);
            dataTransferIndex++;
        } else if (dataTransferIndex <= SECTOR_SIZE) {
            // Send 512 bytes of sector data
            int dataIndex = dataTransferIndex - 1; // Convert to 0-based index
            if (dataIndex < SECTOR_SIZE) {
                responseValue = dataBuffer[dataIndex] & 0xFF;
                // if (dataIndex < 16) { // Log first 16 bytes for debugging
                //     logger.debug("SD Card sending data byte {}: 0x{}", dataIndex, String.format("%02X", responseValue));
                // }
                dataTransferIndex++;
            }
        } else if (dataTransferIndex == SECTOR_SIZE + 1) {
            // Calculate CRC-16-CCITT for the sector
            int crc = calculateCRC16(dataBuffer, SECTOR_SIZE);
            // Send first CRC byte (high byte)
            responseValue = (crc >> 8) & 0xFF;
            // logger.debug("SD Card sending CRC byte 1 (high): 0x{}", String.format("%02X", responseValue));
            dataTransferIndex++;
        } else if (dataTransferIndex == SECTOR_SIZE + 2) {
            // Calculate CRC-16-CCITT for the sector
            int crc = calculateCRC16(dataBuffer, SECTOR_SIZE);
            // Send second CRC byte (low byte) and end transfer
            responseValue = crc & 0xFF;
            // logger.info("SD Card sending CRC byte 2 (low): 0x{} - transfer complete", String.format("%02X", responseValue));
            inDataTransfer = false;
            dataTransferIndex = 0;
            dataSent = false;
        } else {
            // Should not reach here
            logger.error("SD Card data transfer: unexpected state, index={}", dataTransferIndex);
            inDataTransfer = false;
            dataTransferIndex = 0;
            dataSent = false;
            return;
        }

        // Prepare the response byte for bit-by-bit transfer
        prepareResponse(responseValue);
    }

    /**
     * Start read operation from mounted image
     */
    private void startReadOperation() {
        try {
            if (imageFile == null) {
                logger.error("No disk image mounted for read operation");
                return;
            }

            long byteOffset = currentSector * SECTOR_SIZE;
            // logger.info("DEBUG: Reading sector {}, byteOffset={}, cardSizeBytes={}", currentSector, byteOffset, cardSizeBytes);
            if (byteOffset >= cardSizeBytes) {
                logger.error("Read beyond end of disk: sector {}", currentSector);
                return;
            }

            imageFile.seek(byteOffset);
            Arrays.fill(dataBuffer, 0);

            // Read sector data using bulk read (much faster than byte-by-byte)
            byte[] tempBuffer = new byte[SECTOR_SIZE];
            int bytesRead = imageFile.read(tempBuffer, 0, SECTOR_SIZE);

            // Convert byte array to int array (dataBuffer is int[] for unsigned byte handling)
            for (int i = 0; i < SECTOR_SIZE; i++) {
                if (i < bytesRead && bytesRead != -1) {
                    dataBuffer[i] = tempBuffer[i] & 0xFF; // Convert to unsigned
                } else {
                    dataBuffer[i] = 0xFF; // Fill with 0xFF if beyond file
                }
            }

            // Debug: Log first 16 bytes of sector data that will be sent
            // StringBuilder hexBytes = new StringBuilder();
            // for (int i = 0; i < 16; i++) {
            //     hexBytes.append(String.format("%02X ", dataBuffer[i] & 0xFF));
            // }
            // logger.info("SD Card sector {} first 16 bytes: {}", currentSector, hexBytes.toString());

            // Prepare for data transfer
            inDataTransfer = true;
            dataTransferIndex = 0;
            dataSent = false;
            dataIndex = 0;
            writingData = false;

            // logger.info("SD Card prepared sector {} for reading", currentSector);

        } catch (IOException e) {
            logger.error("Error reading from disk image: {}", e.getMessage());
        }
    }

    /**
     * Handle incoming write data bytes (512 data + 2 CRC)
     * After receiving all bytes, write to disk and send DATA_ACCEPTED response
     */
    private void handleWriteData(int byteReceived) {
        if (writeDataIndex < SECTOR_SIZE) {
            // Receiving data bytes
            dataBuffer[writeDataIndex] = byteReceived & 0xFF;
            writeDataIndex++;

            if (writeDataIndex == SECTOR_SIZE) {
                logger.debug("SD Card received all 512 data bytes for sector {}", currentSector);
            }
        } else if (writeDataIndex == SECTOR_SIZE) {
            // First CRC byte - ignore (we don't validate CRC)
            writeDataIndex++;
        } else if (writeDataIndex == SECTOR_SIZE + 1) {
            // Second CRC byte - now write to disk and respond
            writeDataIndex++;

            // Write data to image file
            writeToImage();

            // Send DATA_ACCEPTED response (0x05)
            // Format: xxx0 0101 where xxx are don't care bits
            prepareResponse(DATA_ACCEPTED);

            // After DATA_ACCEPTED, card goes busy (MISO low), then ready (MISO high)
            // For simplicity, we'll just send the response and be done
            writingData = false;
            logger.debug("SD Card write complete for sector {}, sent DATA_ACCEPTED", currentSector);
        }
    }

    /**
     * Write dataBuffer to the disk image at currentSector
     */
    private void writeToImage() {
        try {
            if (imageFile == null) {
                logger.error("No disk image mounted for write operation");
                return;
            }

            long byteOffset = currentSector * SECTOR_SIZE;
            if (byteOffset >= cardSizeBytes) {
                logger.error("Write beyond end of disk: sector {}", currentSector);
                return;
            }

            imageFile.seek(byteOffset);

            // Convert int array to byte array
            byte[] tempBuffer = new byte[SECTOR_SIZE];
            for (int i = 0; i < SECTOR_SIZE; i++) {
                tempBuffer[i] = (byte) dataBuffer[i];
            }

            imageFile.write(tempBuffer);
            logger.info("SD Card wrote sector {} to disk image", currentSector);

        } catch (IOException e) {
            logger.error("Error writing to disk image: {}", e.getMessage());
        }
    }

    /**
     * Mount a disk image file
     */
    public boolean mountImage(String imagePath) {
        try {
            if (imageFile != null) {
                imageFile.close();
            }

            File imageFileObj = new File(imagePath);
            if (!imageFileObj.exists()) {
                logger.error("Disk image file not found: {}", imagePath);
                return false;
            }

            imageFile = new RandomAccessFile(imageFileObj, "rw");
            cardSizeBytes = imageFileObj.length();
            this.imagePath = imagePath;

            logger.info("SD Card mounted image: {} ({} bytes)", imagePath, cardSizeBytes);
            return true;

        } catch (IOException e) {
            logger.error("Error mounting disk image: {}", e.getMessage());
            imageFile = null;
            imagePath = null;
            return false;
        }
    }

    /**
     * Unmount the current disk image
     */
    public void unmountImage() {
        try {
            if (imageFile != null) {
                imageFile.close();
                imageFile = null;
            }
            imagePath = null;
            cardSizeBytes = DEFAULT_CARD_SIZE;
            logger.info("SD Card image unmounted");
        } catch (IOException e) {
            logger.error("Error unmounting disk image: {}", e.getMessage());
        }
    }

    /**
     * Get the path of the currently mounted image
     * @return image path or null if no image mounted
     */
    public String getImagePath() {
        return imagePath;
    }

    /**
     * Check if a disk image is currently mounted
     * @return true if image is mounted, false otherwise
     */
    public boolean isImageMounted() {
        return imageFile != null && imagePath != null;
    }

    /**
     * Calculate CRC-16-CCITT for data buffer
     * Uses polynomial 0x1021 (x^16 + x^12 + x^5 + 1)
     * Initial value: 0x0000
     * This matches the SD card specification for data transfer CRC
     *
     * @param data buffer to calculate CRC for (int array where each element is 0-255)
     * @param length number of bytes to process
     * @return 16-bit CRC value
     */
    private int calculateCRC16(int[] data, int length) {
        int crc = 0x0000;  // CRC-16-CCITT initial value

        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;  // XOR byte into high byte of CRC

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;  // Polynomial 0x1021
                } else {
                    crc = crc << 1;
                }
            }
        }

        return crc & 0xFFFF;  // Return 16-bit result
    }
}