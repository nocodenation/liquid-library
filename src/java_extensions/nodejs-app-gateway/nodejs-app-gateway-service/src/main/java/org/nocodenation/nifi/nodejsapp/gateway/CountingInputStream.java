/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nocodenation.nifi.nodejsapp.gateway;

import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream wrapper that counts bytes read and enforces a size limit.
 * 
 * <p>This class wraps an underlying InputStream and tracks the total number
 * of bytes read. If the number of bytes exceeds the configured maximum,
 * an IOException is thrown to prevent memory exhaustion attacks.</p>
 * 
 * <p>Usage example:</p>
 * <pre>
 * try (CountingInputStream cis = new CountingInputStream(inputStream, maxBytes)) {
 *     // read from cis - will throw IOException if limit exceeded
 * }
 * </pre>
 * 
 * @since 1.0.0
 */
public class CountingInputStream extends InputStream {
    
    private final InputStream delegate;
    private final long maxBytes;
    private long bytesRead = 0;

    /**
     * Creates a new CountingInputStream.
     * 
     * @param delegate the underlying input stream to wrap
     * @param maxBytes the maximum number of bytes allowed to be read
     */
    public CountingInputStream(InputStream delegate, long maxBytes) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = delegate.read();
        if (b != -1) {
            bytesRead++;
            checkLimit();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = delegate.read(b, off, len);
        if (n > 0) {
            bytesRead += n;
            checkLimit();
        }
        return n;
    }

    private void checkLimit() throws IOException {
        if (bytesRead > maxBytes) {
            throw new IOException("Request body size exceeds maximum allowed size of " + maxBytes + " bytes");
        }
    }

    /**
     * Returns the number of bytes read so far.
     * 
     * @return the total bytes read from the underlying stream
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * Returns the maximum bytes allowed.
     * 
     * @return the configured maximum byte limit
     */
    public long getMaxBytes() {
        return maxBytes;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
