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

import java.time.LocalDateTime;

/**
 * DS3231 Real-Time Clock simulation.
 *
 * The DS3231 is a temperature-compensated I2C RTC with the following features:
 * - I2C address: 0x68
 * - Registers 0x00-0x06: Time/date in BCD format
 * - Registers 0x07-0x0D: Alarms (not fully simulated)
 * - Register 0x0E: Control
 * - Register 0x0F: Status
 * - Registers 0x10-0x12: Aging offset and temperature (not simulated)
 *
 * This simulation reads the host system's current time and returns it
 * in DS3231 BCD format. Time can also be "set" but this just establishes
 * an offset from the system time.
 */
public class DS3231 implements I2cDevice {

    private final static Logger logger = LoggerFactory.getLogger(DS3231.class.getName());

    // I2C address (7-bit)
    public static final int I2C_ADDRESS = 0x68;

    // Register addresses
    private static final int REG_SECONDS = 0x00;
    private static final int REG_MINUTES = 0x01;
    private static final int REG_HOURS = 0x02;
    private static final int REG_DAY = 0x03;      // Day of week (1-7)
    private static final int REG_DATE = 0x04;     // Day of month (1-31)
    private static final int REG_MONTH = 0x05;    // Month (1-12, bit 7 = century)
    private static final int REG_YEAR = 0x06;     // Year (00-99)
    private static final int REG_ALARM1_SECONDS = 0x07;
    private static final int REG_ALARM1_MINUTES = 0x08;
    private static final int REG_ALARM1_HOURS = 0x09;
    private static final int REG_ALARM1_DAY_DATE = 0x0A;
    private static final int REG_ALARM2_MINUTES = 0x0B;
    private static final int REG_ALARM2_HOURS = 0x0C;
    private static final int REG_ALARM2_DAY_DATE = 0x0D;
    private static final int REG_CONTROL = 0x0E;
    private static final int REG_STATUS = 0x0F;
    private static final int REG_AGING = 0x10;
    private static final int REG_TEMP_MSB = 0x11;
    private static final int REG_TEMP_LSB = 0x12;

    private static final int NUM_REGISTERS = 0x13;

    // Internal state
    private int registerPointer = 0;
    private boolean inTransaction = false;
    private boolean isReadMode = false;

    // Register storage (for writable registers like alarms, control, status)
    private int[] registers = new int[NUM_REGISTERS];

    // Time offset: difference between "set" time and system time
    // Allows BASIC programs to set the clock without affecting system time
    private long timeOffsetMillis = 0;

    public DS3231() {
        reset();
        logger.info("DS3231 RTC initialized at I2C address 0x{}", String.format("%02X", I2C_ADDRESS));
    }

    @Override
    public int getAddress() {
        return I2C_ADDRESS;
    }

    @Override
    public boolean start(boolean isRead) {
        inTransaction = true;
        isReadMode = isRead;
        logger.debug("DS3231 START: {} mode, register pointer = 0x{}",
                    isRead ? "READ" : "WRITE", String.format("%02X", registerPointer));
        return true;  // Always ACK our address
    }

    @Override
    public void stop() {
        inTransaction = false;
        logger.debug("DS3231 STOP");
    }

    @Override
    public boolean writeByte(int data) {
        data &= 0xFF;

        if (!inTransaction) {
            logger.warn("DS3231 writeByte called outside transaction");
            return false;
        }

        // First byte after address (in write mode) sets register pointer
        // Subsequent bytes write to registers with auto-increment
        logger.debug("DS3231 WRITE: 0x{} to register 0x{}",
                    String.format("%02X", data), String.format("%02X", registerPointer));

        // Check if this is setting the register pointer or writing data
        // The DS3231 protocol: first write byte = register address, subsequent = data
        // We track this by checking if we've already received a register address
        if (registerPointer < 0) {
            // First byte sets register pointer
            registerPointer = data % NUM_REGISTERS;
            logger.debug("DS3231 register pointer set to 0x{}", String.format("%02X", registerPointer));
        } else {
            // Write to current register
            writeRegister(registerPointer, data);
            registerPointer = (registerPointer + 1) % NUM_REGISTERS;
        }

        return true;  // ACK
    }

    @Override
    public int readByte(boolean ack) {
        if (!inTransaction) {
            logger.warn("DS3231 readByte called outside transaction");
            return 0xFF;
        }

        int value = readRegister(registerPointer);
        logger.debug("DS3231 READ: 0x{} from register 0x{}, master will {}",
                    String.format("%02X", value), String.format("%02X", registerPointer),
                    ack ? "ACK" : "NACK");

        // Auto-increment register pointer
        registerPointer = (registerPointer + 1) % NUM_REGISTERS;

        return value;
    }

    @Override
    public void reset() {
        registerPointer = 0;
        inTransaction = false;
        isReadMode = false;
        timeOffsetMillis = 0;

        // Initialize registers to defaults
        for (int i = 0; i < NUM_REGISTERS; i++) {
            registers[i] = 0;
        }

        // Control register defaults: oscillator enabled, no alarms
        registers[REG_CONTROL] = 0x00;
        // Status register: oscillator running, no alarm flags
        registers[REG_STATUS] = 0x00;
        // Temperature (simulated ~25°C = 0x19 in upper byte)
        registers[REG_TEMP_MSB] = 0x19;
        registers[REG_TEMP_LSB] = 0x00;

        logger.debug("DS3231 reset");
    }

    @Override
    public String getName() {
        return "DS3231 RTC";
    }

    /**
     * Reset the register pointer for next transaction.
     * Called by I2C controller to indicate register pointer should be set by next write.
     */
    public void resetRegisterPointer() {
        registerPointer = -1;  // Indicates next write sets the pointer
    }

    /**
     * Read a register value.
     * Time registers (0x00-0x06) read current system time with offset.
     */
    private int readRegister(int reg) {
        switch (reg) {
            case REG_SECONDS:
            case REG_MINUTES:
            case REG_HOURS:
            case REG_DAY:
            case REG_DATE:
            case REG_MONTH:
            case REG_YEAR:
                return readTimeRegister(reg);

            default:
                return registers[reg];
        }
    }

    /**
     * Write a register value.
     * Time registers (0x00-0x06) adjust the time offset.
     */
    private void writeRegister(int reg, int value) {
        value &= 0xFF;

        switch (reg) {
            case REG_SECONDS:
            case REG_MINUTES:
            case REG_HOURS:
            case REG_DAY:
            case REG_DATE:
            case REG_MONTH:
            case REG_YEAR:
                writeTimeRegister(reg, value);
                break;

            case REG_CONTROL:
                registers[REG_CONTROL] = value;
                logger.debug("DS3231 control register set to 0x{}", String.format("%02X", value));
                break;

            case REG_STATUS:
                // Writing to status clears alarm flags (bits 0-1) if written as 0
                registers[REG_STATUS] = (registers[REG_STATUS] & 0xFC) | (value & 0x03);
                break;

            default:
                registers[reg] = value;
                break;
        }
    }

    /**
     * Read a time register, returning current time (with offset) in BCD.
     */
    private int readTimeRegister(int reg) {
        LocalDateTime now = LocalDateTime.now().plusNanos(timeOffsetMillis * 1_000_000);

        switch (reg) {
            case REG_SECONDS:
                return binaryToBcd(now.getSecond());
            case REG_MINUTES:
                return binaryToBcd(now.getMinute());
            case REG_HOURS:
                // 24-hour mode (bit 6 = 0)
                return binaryToBcd(now.getHour());
            case REG_DAY:
                // Day of week: 1 = Sunday, 7 = Saturday
                // Java: 1 = Monday, 7 = Sunday
                int dow = now.getDayOfWeek().getValue();  // 1=Mon, 7=Sun
                dow = (dow % 7) + 1;  // Convert to 1=Sun, 7=Sat
                return dow;
            case REG_DATE:
                return binaryToBcd(now.getDayOfMonth());
            case REG_MONTH:
                // Bit 7 is century bit (set if year >= 2100)
                int month = binaryToBcd(now.getMonthValue());
                if (now.getYear() >= 2100) {
                    month |= 0x80;
                }
                return month;
            case REG_YEAR:
                // Year is 00-99 (relative to 2000 or 2100 based on century bit)
                int year = now.getYear() % 100;
                return binaryToBcd(year);
            default:
                return 0;
        }
    }

    /**
     * Write a time register, adjusting the time offset.
     * This is a simplified implementation - a full implementation would
     * reconstruct the complete datetime and calculate a new offset.
     */
    private void writeTimeRegister(int reg, int value) {
        // For now, just store the value - a complete implementation would
        // calculate a time offset. This allows the 6502 to "set" the clock
        // even though it doesn't affect the actual system time.
        logger.debug("DS3231 time register 0x{} written with 0x{} (time set not fully implemented)",
                   String.format("%02X", reg), String.format("%02X", value));
        registers[reg] = value;
    }

    /**
     * Convert binary value (0-99) to BCD.
     */
    private int binaryToBcd(int binary) {
        if (binary < 0 || binary > 99) {
            return 0;
        }
        int tens = binary / 10;
        int ones = binary % 10;
        return (tens << 4) | ones;
    }

    /**
     * Convert BCD value to binary.
     */
    private int bcdToBinary(int bcd) {
        int tens = (bcd >> 4) & 0x0F;
        int ones = bcd & 0x0F;
        return (tens * 10) + ones;
    }
}
