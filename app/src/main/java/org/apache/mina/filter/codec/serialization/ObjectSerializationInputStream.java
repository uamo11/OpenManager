/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package org.apache.mina.filter.codec.serialization;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectStreamConstants;

/**
 * Hardened neutralizer for CVE-2024-52046 (Apache MINA Unsafe Java Deserialization RCE).
 *
 * This class replaces the vulnerable MINA 2.1.3 ObjectSerializationInputStream on the classpath.
 * It strictly refuses to deserialize untrusted byte streams into arbitrary Java objects.
 */
public class ObjectSerializationInputStream extends InputStream implements ObjectInput, ObjectStreamConstants {
    private final DataInputStream in;
    private final ClassLoader classLoader;
    private int maxObjectSize = 1048576;

    public ObjectSerializationInputStream(InputStream in) {
        this(in, Thread.currentThread().getContextClassLoader());
    }

    public ObjectSerializationInputStream(InputStream in, ClassLoader classLoader) {
        this.in = (in instanceof DataInputStream) ? (DataInputStream) in : new DataInputStream(in);
        this.classLoader = classLoader;
    }

    public int getMaxObjectSize() {
        return maxObjectSize;
    }

    public void setMaxObjectSize(int maxObjectSize) {
        this.maxObjectSize = maxObjectSize;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public Object readObject() throws ClassNotFoundException, IOException {
        throw new SecurityException(
            "CVE-2024-52046 Neutralized: Unsafe Java object deserialization is permanently disabled in OpenManager."
        );
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        in.readFully(b);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        in.readFully(b, off, len);
    }

    @Override
    public int skipBytes(int n) throws IOException {
        return in.skipBytes(n);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return in.readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return in.readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return in.readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return in.readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return in.readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return in.readChar();
    }

    @Override
    public int readInt() throws IOException {
        return in.readInt();
    }

    @Override
    public long readLong() throws IOException {
        return in.readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return in.readFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return in.readDouble();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String readLine() throws IOException {
        return in.readLine();
    }

    @Override
    public String readUTF() throws IOException {
        return in.readUTF();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
