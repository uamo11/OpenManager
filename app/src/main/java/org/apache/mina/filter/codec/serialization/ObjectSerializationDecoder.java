/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package org.apache.mina.filter.codec.serialization;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

/**
 * Hardened neutralizer for CVE-2024-52046 (Apache MINA Unsafe Java Deserialization RCE).
 *
 * This class replaces the vulnerable MINA 2.1.3 ObjectSerializationDecoder on the classpath.
 * It strictly refuses to deserialize untrusted byte streams into arbitrary Java objects,
 * eliminating the deserialization gadget chain attack surface.
 */
public class ObjectSerializationDecoder extends CumulativeProtocolDecoder {
    private final ClassLoader classLoader;
    private int maxObjectSize = 1048576; // 1MB

    public ObjectSerializationDecoder() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ObjectSerializationDecoder(ClassLoader classLoader) {
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
    protected boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        throw new SecurityException(
            "CVE-2024-52046 Neutralized: Unsafe Java object deserialization is permanently disabled in OpenManager."
        );
    }
}
