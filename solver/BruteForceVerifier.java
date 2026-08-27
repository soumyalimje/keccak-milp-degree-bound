import java.util.*;

/**
 * Brute-force EXACT algebraic degree for Keccak-f[25] (w=1), by tracking
 * the actual ANF (Zhegalkin polynomial) of every state bit symbolically —
 * no bounding, no trails, the literal ground-truth polynomial.
 *
 * Representation: a Boolean function's ANF is a set of monomials, each
 * monomial a bitmask over the 25 input variables (bit i set = variable i
 * appears in that monomial). Two facts make this tractable:
 *   - XOR of two functions  = symmetric difference of their monomial sets
 *     (this is exactly what GF(2) addition means in the ANF ring).
 *   - AND of two functions  = for every pair (m1 in P, m2 in Q), the mask
 *     (m1 | m2) is toggled into the result (OR because x_i*x_i = x_i is
 *     idempotent over GF(2); toggled because repeated masks cancel mod 2).
 *
 * w=1 makes rho a no-op (rotating a 1-bit lane by anything mod 1 is
 * nothing), so only pi's row/column relabeling survives — one less thing
 * that can hide a bug.
 *
 * This is EXACT, not a bound — but it only stays computationally tractable
 * for a few rounds, since polynomial size can grow explosively through chi
 * (each chi is a multiplication, i.e. |P|*|Q| candidate monomials before
 * cancellation). A hard cap aborts honestly rather than hanging.
 */
public class BruteForceVerifier {

    static final int MONOMIAL_CAP = 400_000; // abort a round rather than hang

    final KeccakDegreeUpperBound model; // reuse its idx/decode/theta/rho-pi maps
    final int b;

    BruteForceVerifier(int w) {
        this.model = new KeccakDegreeUpperBound(w);
        this.b = model.b;
    }

    // ---- ANF polynomial ops: monomials as bitmasks packed in a Set<Long> ----

    static Set<Long> xor(Set<Long> p, Set<Long> q) {
        Set<Long> result = new HashSet<>(p);
        for (long m : q) {
            if (!result.remove(m)) result.add(m);
        }
        return result;
    }

    static Set<Long> and(Set<Long> p, Set<Long> q) throws TooBigException {
        if ((long) p.size() * q.size() > MONOMIAL_CAP) throw new TooBigException();
        Set<Long> result = new HashSet<>();
        for (long m1 : p) {
            for (long m2 : q) {
                long combined = m1 | m2;
                if (!result.remove(combined)) result.add(combined);
                if (result.size() > MONOMIAL_CAP) throw new TooBigException();
            }
        }
        return result;
    }

    static int degreeOf(Set<Long> poly) {
        int max = 0;
        for (long m : poly) max = Math.max(max, Long.bitCount(m));
        return max;
    }

    static class TooBigException extends Exception {}

    // ---- one full round, symbolic ----

    Set<Long>[] runRound(Set<Long>[] state) throws TooBigException {
        int b = state.length;

        // theta
        @SuppressWarnings("unchecked")
        Set<Long>[] afterTheta = new Set[b];
        for (int i = 0; i < b; i++) {
            int[] xyz = model.decode(i);
            int[] sources = model.thetaSources(xyz[0], xyz[1], xyz[2]);
            Set<Long> acc = new HashSet<>();
            for (int s : sources) acc = xor(acc, state[s]);
            afterTheta[i] = acc;
        }

        // rho + pi (pure relabel, no polynomial work)
        @SuppressWarnings("unchecked")
        Set<Long>[] afterRhoPi = new Set[b];
        for (int i = 0; i < b; i++) {
            int[] xyz = model.decode(i);
            int[] dest = model.rhoPiForward(xyz[0], xyz[1], xyz[2]);
            afterRhoPi[model.idx(dest[0], dest[1], dest[2])] = afterTheta[i];
        }

        // chi: y_x = b_x XOR b_{x+2} XOR (b_{x+1} AND b_{x+2})
        @SuppressWarnings("unchecked")
        Set<Long>[] afterChi = new Set[b];
        for (int y = 0; y < 5; y++) {
            for (int z = 0; z < model.w; z++) {
                for (int x = 0; x < 5; x++) {
                    Set<Long> bx  = afterRhoPi[model.idx(x, y, z)];
                    Set<Long> bx1 = afterRhoPi[model.idx(x + 1, y, z)];
                    Set<Long> bx2 = afterRhoPi[model.idx(x + 2, y, z)];
                    Set<Long> and = and(bx1, bx2);
                    Set<Long> out = xor(xor(bx, bx2), and);
                    afterChi[model.idx(x, y, z)] = out;
                }
            }
        }
        // iota skipped: XORing a fixed constant only toggles the degree-0
        // (empty-mask) monomial, which cannot change the max degree.
        return afterChi;
    }

    void run(int maxRounds) {
        @SuppressWarnings("unchecked")
        Set<Long>[] state = new Set[b];
        for (int i = 0; i < b; i++) {
            Set<Long> mono = new HashSet<>();
            mono.add(1L << i);
            state[i] = mono;
        }

        System.out.printf("Brute-force EXACT degree, Keccak-f[%d] (w=%d)%n", b, model.w);
        System.out.printf("%-6s %-14s %-14s %-10s%n", "Round", "Exact degree", "Our bound", "Max poly size");

        for (int r = 1; r <= maxRounds; r++) {
            try {
                state = runRound(state);
            } catch (TooBigException e) {
                System.out.printf("%-6d %-14s (polynomial exceeded %,d monomials — aborting further rounds)%n",
                        r, "N/A", MONOMIAL_CAP);
                break;
            }
            int exactDegree = 0;
            int maxPolySize = 0;
            for (int i = 0; i < b; i++) {
                exactDegree = Math.max(exactDegree, degreeOf(state[i]));
                maxPolySize = Math.max(maxPolySize, state[i].size());
            }
            int ourBound = model.upperBoundDegree(0, 0, 0, r);
            String verdict = ourBound >= exactDegree ? "OK (sound)" : "!! BOUND TOO LOW — BUG";
            System.out.printf("%-6d %-14d %-14d %-10d %s%n", r, exactDegree, ourBound, maxPolySize, verdict);
        }
    }

    public static void main(String[] args) {
        new BruteForceVerifier(1).run(6); // w=1, b=25
    }
}
