package io.nayuki.flac.decode;

import java.io.IOException;
import java.io.InputStream;

public class StreamFlacInput extends AbstractFlacLowLevelInput {
    private final InputStream stream;


    public StreamFlacInput(InputStream stream) {
        this.stream = stream;
    }


    @Override
    public long getLength() {
        throw new UnsupportedOperationException("The length is not available");
    }


    @Override
    public void seekTo(long pos) throws IOException {
        throw new UnsupportedOperationException("Seeking not supported");
    }


    @Override
    protected int readUnderlying(byte[] buf, int off, int len) throws IOException {
        return this.stream.read(buf, off, len);
    }


    @Override
    public void close() throws IOException {
        if (this.stream != null) {
            this.stream.close();
            super.close();
        }
    }

}
