package com.hellblazer.luciferase.lucien.tetree;

import java.math.BigInteger;

/**
 * @author hal.hildebrand
 **/
public class TmIndex extends BigInteger {
    private final byte level;

    public TmIndex(byte[] val, int off, int len, byte level) {
        super(val, off, len);
        this.level = level;
    }

    public TmIndex(byte[] val, byte level) {
        super(val);
        this.level = level;
    }

    public TmIndex(int signum, byte[] magnitude, int off, int len, byte level) {
        super(signum, magnitude, off, len);
        this.level = level;
    }

    public TmIndex(int signum, byte[] magnitude, byte level) {
        super(signum, magnitude);
        this.level = level;
    }

    public TmIndex(String val, int radix, byte level) {
        super(val, radix);
        this.level = level;
    }

    public TmIndex(String val, byte level) {
        super(val);
        this.level = level;
    }
}
