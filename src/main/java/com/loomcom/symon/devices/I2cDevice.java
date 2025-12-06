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

/**
 * Interface for I2C devices that can be connected to the PeripheralController.
 *
 * I2C Protocol Overview:
 * - START condition: SDA falls while SCL is high
 * - STOP condition: SDA rises while SCL is high
 * - Data: SDA sampled on SCL rising edge, MSB first
 * - ACK/NACK: 9th bit after each byte (low = ACK, high = NACK)
 * - First byte after START is address (7 bits) + R/W bit (0=write, 1=read)
 */
public interface I2cDevice {

    /**
     * Get the 7-bit I2C address of this device
     * @return I2C address (0x00-0x7F)
     */
    int getAddress();

    /**
     * Called when a START condition is detected and this device's address matches.
     * @param isRead true if read operation (R/W bit = 1), false if write
     * @return true if device acknowledges (ACK), false for NACK
     */
    boolean start(boolean isRead);

    /**
     * Called when a STOP condition is detected.
     * Device should complete any pending operations.
     */
    void stop();

    /**
     * Write a byte to the device (master -> slave).
     * Called after address byte with W bit, for each data byte.
     * @param data The byte written by master
     * @return true if device acknowledges (ACK), false for NACK
     */
    boolean writeByte(int data);

    /**
     * Read a byte from the device (slave -> master).
     * Called after address byte with R bit, for each data byte.
     * @param ack true if master will ACK (more bytes wanted), false if NACK (last byte)
     * @return The byte to send to master
     */
    int readByte(boolean ack);

    /**
     * Reset the device to initial state
     */
    void reset();

    /**
     * Get device name for debugging
     * @return Device name string
     */
    String getName();
}
