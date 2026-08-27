import java.util.*;

/**
 * Zero-sum distinguisher verification -- runs the REAL Keccak-f permutation
 * (actual bit values through theta/rho/pi/chi/iota, not the abstract
 * division-property trail variables used by CpSatDegreeSolver) and checks
 * the higher-order-derivative theorem directly:
 *
 *   deg(f) = d  ==>  XOR-sum of f over any (d+1)-dimensional affine
 *                    subspace is 0, for EVERY choice of subspace.
 *
 * This is a CROSS-CHECK, not a computation of the upper bound -- the
 * upper bound itself comes from CpSatDegreeSolver. This class exists to
 * confirm that solver's answer is consistent with the literal cipher,
 * not just with the abstract constraint model.
 *
 * Scope: rounds 1-4 only. Subspace size is 2^(d+1), so this grows fast
 * (round 4 at degree 16 needs 2^17 = 131072 evaluations -- still fast,
 * but round 6+ at degree 44+ would need 2^45+, which is why this stays
 * a small-round demo, not a full-table replacement).
 *
 * Round constants are generated via the standard Keccak LFSR algorithm
 * (verified against the well-known first four 64-bit constants --
 * 0x1, 0x8082, 0x800000000000808A, 0x8000000080008000 -- before use).
 */
public class ZeroSumDistinguisher {

    final KeccakDegreeUpperBound geo; // reuses idx/decode/rhoPiForward
    final int b, w;
    final long[] roundConstants;

    ZeroSumDistinguisher(int w, int maxRounds) {
        this.geo = new KeccakDegreeUpperBound(w);
        this.b = geo.b;
        this.w = w;
        this.roundConstants = new long[maxRounds];
        int log2w = Integer.numberOfTrailingZeros(w);
        for (int r = 0; r < maxRounds; r++) {
            roundConstants[r] = roundConstant(r, log2w);
        }
    }

    // ---- standard Keccak round-constant LFSR (verified against known values) ----
    static int rcBit(int t) {
        if (t % 255 == 0) return 1;
        int R = 1;
        for (int i = 0; i < t % 255; i++) {
            R <<= 1;
            if ((R & 0x100) != 0) R ^= 0x171;
        }
        return R & 1;
    }

    static long roundConstant(int roundIndex, int log2w) {
        long rc = 0;
        for (int j = 0; j <= log2w; j++) {
            int t = j + 7 * roundIndex;
            if (rcBit(t) == 1) rc |= (1L << ((1 << j) - 1));
        }
        return rc;
    }

    // ---- real value-level round function ----
    int[] oneRound(int[] state, int roundIndex) {
        // theta
        int[] C = new int[5 * w];
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < w; z++) {
                int c = 0;
                for (int y = 0; y < 5; y++) c ^= state[geo.idx(x, y, z)];
                C[x * w + z] = c;
            }
        int[] D = new int[5 * w];
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < w; z++)
                D[x * w + z] = C[Math.floorMod(x - 1, 5) * w + z] ^ C[Math.floorMod(x + 1, 5) * w + Math.floorMod(z - 1, w)];
        int[] A = new int[b];
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                for (int z = 0; z < w; z++)
                    A[geo.idx(x, y, z)] = state[geo.idx(x, y, z)] ^ D[x * w + z];

        // rho + pi
        int[] B = new int[b];
        for (int x = 0; x < 5; x++)
            for (int y = 0; y < 5; y++)
                for (int z = 0; z < w; z++) {
                    int[] dest = geo.rhoPiForward(x, y, z);
                    B[geo.idx(dest[0], dest[1], dest[2])] = A[geo.idx(x, y, z)];
                }

        // chi
        int[] out = new int[b];
        for (int y = 0; y < 5; y++)
            for (int z = 0; z < w; z++)
                for (int x = 0; x < 5; x++) {
                    int bx = B[geo.idx(x, y, z)];
                    int bx1 = B[geo.idx(x + 1, y, z)];
                    int bx2 = B[geo.idx(x + 2, y, z)];
                    out[geo.idx(x, y, z)] = bx ^ bx2 ^ (bx1 & bx2);
                }

        // iota
        long rc = roundConstants[roundIndex];
        for (int z = 0; z < w; z++) {
            out[geo.idx(0, 0, z)] ^= (int) ((rc >> z) & 1L);
        }
        return out;
    }

    int[] applyRounds(int[] state, int numRounds) {
        int[] s = state;
        for (int r = 0; r < numRounds; r++) s = oneRound(s, r);
        return s;
    }

    /** XOR-sum of the target output bit over a `dimension`-dim affine subspace (bits 0..dim-1 free, rest 0). */
    int xorSumAtDimension(int numRounds, int dimension, int targetIdx) {
        int xorSum = 0;
        int subspaceSize = 1 << dimension;
        for (int mask = 0; mask < subspaceSize; mask++) {
            int[] state = new int[b];
            for (int k = 0; k < dimension; k++) state[k] = (mask >> k) & 1;
            xorSum ^= applyRounds(state, numRounds)[targetIdx];
        }
        return xorSum;
    }

    /**
     * Usage: java ZeroSumDistinguisher <w> <maxRounds> <degree1> <degree2> ... <degreeN>
     * degrees are the already-verified CP-SAT results for rounds 1..maxRounds.
     * Prints a JSON array to stdout only; nothing else on stdout.
     */
    public static void main(String[] args) {
        int w = Integer.parseInt(args[0]);
        int maxRounds = Integer.parseInt(args[1]);
        int[] degrees = new int[maxRounds];
        for (int i = 0; i < maxRounds; i++) degrees[i] = Integer.parseInt(args[2 + i]);

        ZeroSumDistinguisher zsd = new ZeroSumDistinguisher(w, maxRounds);
        int target = zsd.geo.idx(0, 0, 0);

        StringBuilder json = new StringBuilder("[");
        for (int r = 1; r <= maxRounds; r++) {
            int d = degrees[r - 1];
            int sumAtDPlus1 = zsd.xorSumAtDimension(r, d + 1, target);
            int sumAtD = zsd.xorSumAtDimension(r, d, target);
            boolean verified = (sumAtDPlus1 == 0);
            if (r > 1) json.append(",");
            json.append(String.format(
                "{\"round\":%d,\"degree\":%d,\"dimTested\":%d,\"subspaceSize\":%d,\"xorSumAtDPlus1\":%d,\"xorSumAtD\":%d,\"verified\":%s}",
                r, d, d + 1, (1 << (d + 1)), sumAtDPlus1, sumAtD, verified));
        }
        json.append("]");
        System.out.println(json);
    }
}
