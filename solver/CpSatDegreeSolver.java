import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import java.util.*;

/**
 * Certified upper bound on Keccak-f algebraic degree via an actual CP-SAT
 * solve of the division-property trail model — not the hand-written greedy
 * in KeccakDegreeUpperBound.java. This is the real thing the published
 * papers (Xiang-Zhang-Bao-Lin ASIACRYPT 2016 style MILP encoding) build,
 * just solved with OR-Tools' CP-SAT instead of Gurobi.
 *
 * Model (forward direction — this is the standard division-property MILP
 * layout, not the backward search the greedy used):
 *   x[0][i]      : free Boolean variables — the monomial we're testing.
 *                  Objective: maximize sum(x[0][i]).
 *   theta        : per round, COPY (fan-out) and XOR-merge (fan-in)
 *                  constraints, built directly from thetaSources().
 *   rho+pi       : pure index relabeling, no new variables/constraints.
 *   chi          : term-A / term-C selector per output bit (term B is
 *                  provably dominated by term C — see KeccakDegreeUpperBound
 *                  class doc — and is correctly omitted, not approximated).
 *   iota         : unmodeled (constants carry no variable).
 *   terminal     : x[r][j] fixed to 1, every other round-r bit fixed to 0.
 *
 * ------------------------------------------------------------------------
 * CHANGE LOG (this version): added an optional, provably-safe lower-bound
 * hint. The true tight degree is monotonically non-decreasing round over
 * round (see KeccakDegreeUpperBound's class doc: "the true tight degree is
 * provably non-decreasing in rounds" -- a surviving degree-d monomial trail
 * can always be extended one more round). So before solving round r, if we
 * already know round r-1's best-found degree (certified OR not -- even an
 * uncertified FEASIBLE value is a valid lower bound on the true optimum,
 * per solve()'s own doc), we can tell the solver "don't waste time on
 * branches below this" via addGreaterOrEqual. This NEVER changes the
 * answer (it only prunes provably-suboptimal search space) -- it can only
 * help the solver reach OPTIMAL faster, never cause it to miss the true
 * answer. Passing knownLowerBound = 0 (the default) reproduces the exact
 * original behavior.
 * ------------------------------------------------------------------------
 */
public class CpSatDegreeSolver {

    final KeccakDegreeUpperBound geo; // reuse idx/decode/thetaSources/rhoPi maps
    final int b;

    CpSatDegreeSolver(int w) {
        this.geo = new KeccakDegreeUpperBound(w);
        this.b = geo.b;
    }

    /** Builds the CP-SAT model for a given round count (shared by solve() and solveTimed()). */
    CpModel buildModel(int rounds) {
        return buildModel(rounds, 0);
    }

    /**
     * Builds the CP-SAT model for a given round count, optionally seeded with
     * a known lower bound on the objective (see class-doc CHANGE LOG above).
     *
     * @param knownLowerBound the largest degree already proven/found at an
     *                        earlier (necessarily <= rounds) round; pass 0
     *                        if none is known yet (e.g. round 1, or when
     *                        calling this fresh with no prior history).
     */
    CpModel buildModel(int rounds, int knownLowerBound) {
        CpModel model = new CpModel();

        // x[t][i] for t = 0..rounds
        BoolVar[][] x = new BoolVar[rounds + 1][b];
        for (int t = 0; t <= rounds; t++)
            for (int i = 0; i < b; i++)
                x[t][i] = model.newBoolVar("x_" + t + "_" + i);

        for (int t = 0; t < rounds; t++) {
            // ---- theta: y[o] = XOR of thetaSources(o), via COPY + merge ----
            BoolVar[] y = new BoolVar[b];
            // collect, per source bit s, every (o, branch) usage for the COPY constraint
            Map<Integer, List<BoolVar>> usagesOfSource = new HashMap<>();

            for (int o = 0; o < b; o++) {
                int[] xyz = geo.decode(o);
                int[] sources = geo.thetaSources(xyz[0], xyz[1], xyz[2]);
                BoolVar[] branches = new BoolVar[sources.length];
                for (int k = 0; k < sources.length; k++) {
                    BoolVar br = model.newBoolVar("theta_t" + t + "_o" + o + "_k" + k);
                    branches[k] = br;
                    model.addLessOrEqual(br, x[t][sources[k]]); // can't fire without its source
                    usagesOfSource.computeIfAbsent(sources[k], key -> new ArrayList<>()).add(br);
                }
                y[o] = model.newBoolVar("y_t" + t + "_o" + o);
                model.addEquality(LinearExpr.sum(branches), y[o]); // XOR-merge: exactly one branch if active
            }
            // COPY: each source bit's total usage across ALL outputs equals itself
            // (idempotency x*x=x -- a bit can only "carry" the trail through one branch)
            for (int s = 0; s < b; s++) {
                List<BoolVar> usages = usagesOfSource.getOrDefault(s, List.of());
                if (!usages.isEmpty()) {
                    model.addEquality(LinearExpr.sum(usages.toArray(new BoolVar[0])), x[t][s]);
                }
            }

            // ---- rho+pi: pure relabel; z[o'] is just y[o] under the forward map ----
            BoolVar[] z = new BoolVar[b];
            for (int o = 0; o < b; o++) {
                int[] xyz = geo.decode(o);
                int[] dest = geo.rhoPiForward(xyz[0], xyz[1], xyz[2]);
                z[geo.idx(dest[0], dest[1], dest[2])] = y[o];
            }

            // ---- chi: term A / term C selector (term B dropped -- dominated) ----
            for (int yy = 0; yy < 5; yy++) {
                for (int zz = 0; zz < geo.w; zz++) {
                    BoolVar[] selA = new BoolVar[5];
                    BoolVar[] selC = new BoolVar[5];
                    for (int xx = 0; xx < 5; xx++) {
                        int outIdx = geo.idx(xx, yy, zz);
                        selA[xx] = model.newBoolVar("chiA_t" + t + "_o" + outIdx);
                        selC[xx] = model.newBoolVar("chiC_t" + t + "_o" + outIdx);
                        model.addEquality(LinearExpr.sum(new BoolVar[]{selA[xx], selC[xx]}), x[t + 1][outIdx]);
                        model.addLessOrEqual(selA[xx], z[geo.idx(xx, yy, zz)]);           // term A needs b_x
                        model.addLessOrEqual(selC[xx], z[geo.idx(xx + 1, yy, zz)]);       // term C needs b_{x+1}
                        model.addLessOrEqual(selC[xx], z[geo.idx(xx + 2, yy, zz)]);       //      and  b_{x+2}
                    }
                    // "demand" side: a post-theta bit z[x] can only be active if
                    // something downstream actually consumes it -- term A of the
                    // SAME output x, term C's first slot of output x-1, or term C's
                    // second slot of output x-2. Without this, z[x] could sit active
                    // with no justification, and the solver would pad the objective
                    // with "dead" activity that never reaches the target (this was
                    // exactly the bug that gave round-1 an impossible bound of 25).
                    for (int xx = 0; xx < 5; xx++) {
                        BoolVar zBit = z[geo.idx(xx, yy, zz)];
                        model.addEquality(
                            LinearExpr.sum(new BoolVar[]{ selA[xx], selC[Math.floorMod(xx - 1, 5)], selC[Math.floorMod(xx - 2, 5)] }),
                            zBit);
                    }
                }
            }
        }

        // ---- terminal condition: isolate a single target output bit ----
        int target = geo.idx(0, 0, 0);
        for (int i = 0; i < b; i++) {
            model.addEquality(x[rounds][i], i == target ? 1 : 0);
        }

        // ---- NEW: monotonic lower-bound pruning ----
        // Safe because degree is provably non-decreasing round over round
        // (see class-doc CHANGE LOG). This can only shrink the search space
        // by ruling out provably-impossible-to-be-worse branches -- it can
        // never hide a better answer, since knownLowerBound is itself a
        // value the solver (or a previous round) already proved reachable.
        if (knownLowerBound > 0) {
            model.addGreaterOrEqual(LinearExpr.sum(x[0]), knownLowerBound);
        }

        // ---- objective ----
        model.maximize(LinearExpr.sum(x[0]));
        return model;
    }

    /** Certified upper bound on the degree of output bit (0,0,0) after `rounds` rounds. */
    int solve(int rounds, double timeLimitSeconds) {
        return solve(rounds, timeLimitSeconds, 0);
    }

    /** Same as solve(), but seeded with a known lower bound (see buildModel doc). */
    int solve(int rounds, double timeLimitSeconds, int knownLowerBound) {
        CpModel model = buildModel(rounds, knownLowerBound);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        solver.getParameters().setNumWorkers(8);
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            int val = (int) Math.round(solver.objectiveValue());
            if (status == CpSolverStatus.FEASIBLE) {
                System.out.println("  (time limit hit -- " + val + " is a valid lower bound on the true optimum, not certified tight)");
            }
            return val;
        } else {
            System.out.println("  solver status: " + status);
            return -1;
        }
    }

    /**
     * Entry point for being spawned as a subprocess (e.g. from Express).
     * Usage: java CpSatDegreeSolver <w> <rounds> [timeLimitSecondsPerRound]
     *
     * Prints ONLY a JSON array to stdout:
     *   [{"round":1,"degree":2,"certified":true}, {"round":2,"degree":4,"certified":true}, ...]
     * "certified":false means the per-round time limit was hit before the
     * solver could prove optimality -- the value is a valid lower bound on
     * the true upper bound, not a certified-tight one (see solve()'s doc).
     * All progress/diagnostic output goes to stderr, never stdout, so the
     * caller can safely JSON.parse(stdout) without stray text breaking it.
     *
     * NOTE ON RUNTIME: solve time grows sharply with rounds (round 7 at
     * b=50 took ~200s in testing). The per-round time limit below is kept
     * short for web responsiveness; rounds that hit it come back marked
     * "certified":false rather than blocking indefinitely. For b=100
     * (w=4) expect this to bite earlier than it did for b=50.
     *
     * NOTE ON THE LOWER BOUND: each round now passes the previous round's
     * best-found degree (certified or not) into the next round's model as
     * a floor. This is sound (see CHANGE LOG at the top of this file) and
     * is purely a speed optimization -- it does not change what "certified"
     * means, and a round can still come back uncertified if even the
     * pruned search space is too large for the time budget.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java CpSatDegreeSolver <w> <rounds> [timeLimitSecondsPerRound]");
            System.exit(1);
        }
        Loader.loadNativeLibraries();
        int w = Integer.parseInt(args[0]);
        int maxRounds = Integer.parseInt(args[1]);
        double timeLimit = args.length > 2 ? Double.parseDouble(args[2]) : 15.0;

        CpSatDegreeSolver solver = new CpSatDegreeSolver(w);
        System.err.printf("CP-SAT certified upper bound, Keccak-f[%d] (w=%d)%n", solver.b, w);

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        int previousDegree = 0; // NEW: tracks the running lower bound across rounds

        for (int r = 1; r <= maxRounds; r++) {
            long start = System.currentTimeMillis();
            SolveResult result = solver.solveTimed(r, timeLimit, previousDegree);
            long ms = System.currentTimeMillis() - start;
            System.err.printf("round %2d  CP-SAT=%3d  certified=%s  (%d ms)%n",
                    r, result.degree, result.certified, ms);

            if (!first) json.append(",");
            first = false;
            json.append(String.format("{\"round\":%d,\"degree\":%d,\"certified\":%s}",
                    r, result.degree, result.certified));

            if (result.degree > previousDegree) previousDegree = result.degree; // NEW: carry forward for next round

            if (result.degree >= solver.b - 1) break; // saturated, stop early
        }
        json.append("]");
        System.out.println(json); // ONLY line on stdout
    }

    static class SolveResult {
        final int degree;
        final boolean certified;
        SolveResult(int degree, boolean certified) { this.degree = degree; this.certified = certified; }
    }

    /** Same as solve(), but also reports whether the result was certified optimal. */
    SolveResult solveTimed(int rounds, double timeLimitSeconds) {
        return solveTimed(rounds, timeLimitSeconds, 0);
    }

    /** Same as solveTimed(), but seeded with a known lower bound (see buildModel doc). */
    SolveResult solveTimed(int rounds, double timeLimitSeconds, int knownLowerBound) {
        // solve() already prints a warning line for the uncertified case;
        // re-run its logic here so we can capture the certified flag too.
        CpModel model = buildModel(rounds, knownLowerBound);
        CpSolver cpSolver = new CpSolver();
        cpSolver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
        cpSolver.getParameters().setNumWorkers(8);
        CpSolverStatus status = cpSolver.solve(model);

        if (status == CpSolverStatus.OPTIMAL) {
            return new SolveResult((int) Math.round(cpSolver.objectiveValue()), true);
        } else if (status == CpSolverStatus.FEASIBLE) {
            return new SolveResult((int) Math.round(cpSolver.objectiveValue()), false);
        } else {
            System.err.println("  solver status: " + status);
            return new SolveResult(-1, false);
        }
    }
}