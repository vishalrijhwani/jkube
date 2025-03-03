/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.build.service.docker.access.hc.unix;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

final class UnixSocket extends Socket {

    private final Object connectLock = new Object();
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    private final UnixSocketChannel channel;

    UnixSocket() throws IOException {
        channel = UnixSocketChannel.open();
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout may not be negative: " + timeout);
        }

        if (!(endpoint instanceof UnixSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type: " + endpoint.getClass().getName());
        }

        synchronized (connectLock) {
            channel.connect((UnixSocketAddress) endpoint);
        }
    }

    @Override
    public void bind(SocketAddress bindpoint) throws IOException {
        throw new SocketException("Bind is not supported");
    }

    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public int getLocalPort() {
        return -1;
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        synchronized (connectLock) {
            return channel.getRemoteSocketAddress();
        }
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        synchronized (connectLock) {
            return channel.getLocalSocketAddress();
        }
    }

    @Override
    public SocketChannel getChannel() {
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        if (!channel.isConnected()) {
            throw new SocketException("Socket is not connected");
        }

        if (inputShutdown) {
            throw new SocketException("Socket input is shutdown");
        }

        return new FilterInputStream(Channels.newInputStream(channel)) {
            @Override
            public void close() throws IOException {
                shutdownInput();
            }
        };
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        if (!channel.isConnected()) {
            throw new SocketException("Socket is not connected");
        }

        if (outputShutdown) {
            throw new SocketException("Socket output is shutdown");
        }

        return new FilterOutputStream(Channels.newOutputStream(channel)) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                shutdownOutput();
            }
        };
    }

    @Override
    public void sendUrgentData(int data) throws IOException {
        throw new SocketException("Urgent data not supported");
    }

    @Override
    public synchronized void setSoTimeout(int timeout) throws SocketException {
        channel.socket().setSoTimeout(timeout);
    }

    @Override
    public synchronized int getSoTimeout() throws SocketException {
        return channel.socket().getSoTimeout();
    }

    @Override
    public synchronized void setSendBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("Send buffer size must be positive: " + size);
        }

        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        // just ignore
    }

    @Override
    public synchronized int getSendBufferSize() throws SocketException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        throw new UnsupportedOperationException("Getting the send buffer size is not supported");
    }

    @Override
    public synchronized void setReceiveBufferSize(int size) throws SocketException {
        if (size <= 0) {
            throw new IllegalArgumentException("Receive buffer size must be positive: " + size);
        }

        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        // just ignore
    }

    @Override
    public synchronized int getReceiveBufferSize() throws SocketException {
        if (!channel.isOpen()) {
            throw new SocketException("Socket is closed");
        }

        throw new UnsupportedOperationException("Getting the receive buffer size is not supported");
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {
        channel.socket().setKeepAlive(on);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        return channel.socket().getKeepAlive();
    }

    @Override
    public void setTrafficClass(int tc) throws SocketException {
        if (tc < 0 || tc > 255) {
            throw new IllegalArgumentException("Traffic class is not in range 0 -- 255: " + tc);
        }

        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }

        // just ignore
    }

    @Override
    public int getTrafficClass() {
        throw new UnsupportedOperationException("Getting the traffic class is not supported");
    }

    @Override
    public void setReuseAddress(boolean on) throws SocketException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }

        // just ignore
    }

    @Override
    public boolean getReuseAddress() {
        throw new UnsupportedOperationException("Getting the SO_REUSEADDR option is not supported");
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
        inputShutdown = true;
        outputShutdown = true;
    }

    @Override
    public void shutdownInput() throws IOException {
        channel.shutdownInput();
        inputShutdown = true;
    }

    @Override
    public void shutdownOutput() throws IOException {
        channel.shutdownOutput();
        outputShutdown = true;
    }

    @Override
    public String toString() {
        if (isConnected()) {
            return "UnixSocket[addr=" + channel.getRemoteSocketAddress() + ']';
        }

        return "UnixSocket[unconnected]";
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public boolean isBound() {
        return false;
    }

    @Override
    public boolean isClosed() {
        return !channel.isOpen();
    }

    @Override
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    @Override
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    @Override
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
        // no-op
    }
}
