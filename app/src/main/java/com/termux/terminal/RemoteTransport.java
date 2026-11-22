package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A network-backed byte stream (SSH shell channel, Telnet connection...) that a {@link TerminalSession}
 * drives instead of a local pseudo-terminal subprocess.
 * <p>
 * Every method except {@link #getDisplayName()} is invoked from a background thread, so
 * implementations may block on network I/O.
 */
public interface RemoteTransport {

    /** Lets the transport print local status lines (connecting, fingerprints, errors) on the terminal. */
    interface StatusSink {
        void print(String text);
    }

    /** Connects, authenticates and opens an interactive shell sized to the terminal. */
    void connect(int columns, int rows, int cellWidthPixels, int cellHeightPixels, StatusSink sink) throws Exception;

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    /** Bytes typed on behalf of the user once the shell is ready (startup commands), or null. */
    byte[] getStartupInput();

    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Exit status of the remote shell once its output stream ended, 0 when unknown. */
    int getExitStatus();

    /** Closes the connection; must be safe to call several times and before connect() finished. */
    void close();

    String getDisplayName();
}
