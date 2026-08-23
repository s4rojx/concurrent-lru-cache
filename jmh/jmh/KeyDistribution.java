package jmh;

import java.util.concurrent.ThreadLocalRandom;

public final class KeyDistribution {

    private final int keySpaceSize;
    private final int[] zipfTable;
    private final boolean zipfian;

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
            int lookupSize = 1 << 16;
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
            while (tablePos < lookupSize) {
                zipfTable[tablePos++] = keySpaceSize - 1;
            }
        } else {
            zipfTable = null;
        }
    }

    public int nextKey() {
        if (zipfian) {
            int bucket = ThreadLocalRandom.current().nextInt(zipfTable.length);
            return zipfTable[bucket];
        } else {
            return ThreadLocalRandom.current().nextInt(keySpaceSize);
        }
    }

    public int keySpaceSize() {
        return keySpaceSize;
    }
}
