package jmh;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates key indices following either a uniform or Zipfian (power-law) distribution.
 *
 * <p>Zipfian modelling: rank r gets weight proportional to 1/r^exponent. Weights are normalised
 * into a CDF and a lookup table maps uniform [0,1) samples to key indices. This gives hot-key
 * behaviour that closely resembles real-world cache access patterns.
 */
public final class KeyDistribution {

    private final int keySpaceSize;
    private final int[] zipfTable; // CDF lookup — maps uniform sample → Zipf-ranked key index
    private final boolean zipfian;

    /**
     * @param keySpaceSize number of distinct keys
     * @param zipfian if true, use Zipfian; if false, use uniform
     */
    public KeyDistribution(int keySpaceSize, boolean zipfian) {
        this.keySpaceSize = keySpaceSize;
        this.zipfian = zipfian;

        if (zipfian) {
            double exponent = 1.0;
            double[] weights = new double[keySpaceSize];
            double total = 0.0;
            for (int i = 0; i < keySpaceSize; i++) {
                weights[i] = 1.0 / Math.pow(i + 1, exponent);
                total += weights[i];
            }
            // Build a CDF table with LOOKUP_SIZE entries for fast O(1) sampling.
            int lookupSize = 1 << 16; // 65536 buckets
            zipfTable = new int[lookupSize];
            double cumulative = 0.0;
            int tablePos = 0;
            for (int i = 0; i < keySpaceSize; i++) {
                cumulative += weights[i] / total;
                int upTo = (int) (cumulative * lookupSize);
                while (tablePos < upTo && tablePos < lookupSize) {
                    zipfTable[tablePos++] = i;
                }
            }
            // Fill any remainder with the last key.
            while (tablePos < lookupSize) {
                zipfTable[tablePos++] = keySpaceSize - 1;
            }
        } else {
            zipfTable = null;
        }
    }

    /** Returns a key index according to the configured distribution. */
    public int nextKey() {
        if (zipfian) {
            int bucket = ThreadLocalRandom.current().nextInt(zipfTable.length);
            return zipfTable[bucket];
        } else {
            return ThreadLocalRandom.current().nextInt(keySpaceSize);
        }
    }

    /** Returns the key space size. */
    public int keySpaceSize() {
        return keySpaceSize;
    }
}
