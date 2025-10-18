/*
 * Copyright (c) 2016 Seth J. Morabito <web@loomcom.com>
 *                    Maik Merten <maikmerten@googlemail.com>
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
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.loomcom.symon.devices;

/**
 * Interface for SPI devices that can be connected to the PeripheralController
 */
public interface SpiDevice {
    /**
     * Select this SPI device for communication
     */
    void select();

    /**
     * Deselect this SPI device
     */
    void deselect();

    /**
     * Transfer one bit via SPI
     * @param data The bit to send (0 or 1)
     * @return The bit received (0 or 1)
     */
    int transfer(int data);

    /**
     * Handle SCK falling edge - advance to next bit
     * Called when SCK transitions from high to low
     */
    default void onSckFallingEdge() {
        // Default implementation does nothing
    }

    /**
     * Reset the device to initial state
     */
    void reset();

    /**
     * Check if device is currently selected
     * @return true if selected, false otherwise
     */
    boolean isSelected();

    /**
     * Get device name for debugging
     * @return Device name string
     */
    String getName();
}