/*
 * * Copyright (C) 2013-2014 Matt Baxter http://kitteh.org
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.kitteh.irc;

import org.kitteh.irc.exception.KittehOutputException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread handling outgoing messages from the bot.
 */
final class IRCBotOutput extends Thread {
    private final IRCBot bot;
    private final Object wait = new Object();
    private final BufferedWriter bufferedWriter;
    private volatile int delay;
    private volatile String quitReason;
    private volatile boolean handleLowPriority = false;
    private final Queue<String> highPriorityQueue = new ConcurrentLinkedQueue<>();
    private final Queue<String> lowPriorityQueue = new ConcurrentLinkedQueue<>();

    IRCBotOutput(IRCBot bot, BufferedWriter bufferedWriter, int messageDelay) {
        this.bot = bot;
        this.setName("Kitteh IRCBot Output (" + bot.getName() + ")");
        this.bufferedWriter = bufferedWriter;
        this.delay = messageDelay;
    }

    @Override
    public void run() {
        while (!this.isInterrupted()) {
            synchronized (this.wait) {
                try {
                    while ((!this.handleLowPriority || this.lowPriorityQueue.isEmpty()) && this.highPriorityQueue.isEmpty()) {
                        this.wait.wait();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            String message = this.highPriorityQueue.poll();
            if (message == null && this.handleLowPriority) {
                message = this.lowPriorityQueue.poll();
            }
            if (message == null) {
                continue;
            }
            try {
                this.bufferedWriter.write(message + "\r\n");
                this.bufferedWriter.flush();
            } catch (final IOException e) {
                this.bot.getExceptionListener().queue(new KittehOutputException("Exception sending queued message", e, message));
            }
            this.bot.getOutputListener().queue(message);

            try {
                Thread.sleep(this.delay);
            } catch (final InterruptedException e) {
                break;
            }
        }
        final StringBuilder quitBuilder = new StringBuilder();
        quitBuilder.append("QUIT");
        if (this.quitReason != null) {
            quitBuilder.append(" :").append(this.quitReason);
        }
        final String quitMessage = quitBuilder.toString();
        try {
            quitBuilder.append("\r\n");
            this.bufferedWriter.write(quitMessage);
            this.bufferedWriter.flush();
            this.bufferedWriter.close();
        } catch (final IOException e) {
            this.bot.getExceptionListener().queue(new KittehOutputException("Exception sending queued message", e, quitMessage));
        }
    }

    /**
     * Queues up a message to be output. If high priority, or {@link
     * #setLowPriorityEnabled()} has been called, the message will be sent as
     * soon as possible.
     *
     * @param message message to send
     * @param highPriority true if the message must be sent ASAP out of order
     */
    void queueMessage(String message, boolean highPriority) {
        (highPriority ? this.highPriorityQueue : this.lowPriorityQueue).add(message);
        if (highPriority || this.handleLowPriority) {
            synchronized (this.wait) {
                this.wait.notify();
            }
        }
    }

    /**
     * Enables low priority sending. Until this is set, low priority messages
     * are not sent. Called after successful connection.
     */
    void setLowPriorityEnabled() {
        this.handleLowPriority = true;
    }

    /**
     * Sets the delay between messages.
     *
     * @param delay number of milliseconds to wait between messages
     */
    void setMessageDelay(int delay) {
        this.delay = delay;
    }

    /**
     * Interrupts the thread and sets a quit message to be used (assuming the
     * bot is still connected).
     *
     * @param message quit message to be sent prior to disconnection
     */
    void shutdown(String message) {
        this.quitReason = message;
        this.interrupt();
    }
}
