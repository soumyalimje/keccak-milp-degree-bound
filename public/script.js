/* ============================================================
   Keccak-f Degree Propagation Lab — client-side controller
   ------------------------------------------------------------
   IMPORTANT: this tool reports an UPPER BOUND on the algebraic
   degree (via bit-level division-property MILP), not an exact
   value. "MILP Upper Bound" in the table and every derived stat
   (complexity, saturation) should be read as worst-case-for-the-
   attacker, not a certified exact degree.
   ============================================================ */

const laneSizeSelect = document.getElementById('lane-size');
const roundsInput      = document.getElementById('rounds');
const runBtn           = document.getElementById('run-btn');

const statB       = document.getElementById('stat-b');
const statMax     = document.getElementById('stat-max');

const resultsBody      = document.getElementById('results-body');
const saturationAlert  = document.getElementById('saturation-alert');
const alertBMinus1     = document.getElementById('alert-b-minus-1');

let currentResults = null; // { w, b, bMinus1, rows: [{round, degree, bound, complexity, saturated}] }

/* ---------- helpers ---------- */

function widthFor(w){ return 25 * w; }

// Boura et al. (2011) style forward bound: doubles each round, capped at
// saturation. Used purely as a SANITY CEILING for display -- the Java
// CP-SAT solver's "degree" value should never exceed this. The API
// doesn't return this value (it only returns round/degree/certified),
// so it's computed client-side from the round number alone.
function referenceBound(round, bMinus1){
  return Math.min(Math.pow(2, round), bMinus1);
}

// The ACTUAL Boura-Canteaut-De Canniere closed-form bound (not the naive
// doubling ceiling above). gamma=3 was derived directly from chi's 5-bit
// S-box (delta_1=2, delta_2=delta_3=delta_4=4, per the theorem
// gamma = max_i (n0-i)/(n0-delta_i)) and cross-checked against a real
// CP-SAT solve earlier -- both independently reached the same numbers
// for b=25 and b=50 through saturation. Recomputed here client-side
// (not copied from any published table) so it's independently verifiable.
function bouraFormulaBound(round, b, bMinus1){
  const gamma = 3.0;
  if (round <= 1) return 2; // round 1: chi's own algebraic degree
  let deg = 2;
  for (let r = 2; r <= round; r++){
    const trivial = Math.min(Math.pow(2, r), bMinus1);
    const formula = Math.min(b - (b - deg) / gamma, bMinus1);
    deg = Math.min(trivial, formula);
  }
  return Math.round(deg);
}

/* ---------- verification panel ---------- */

function renderVerificationTable(result){
  const body = document.getElementById('verification-body');
  if (!body) return; // panel not present on this page yet
  body.innerHTML = '';

  result.rows.forEach(row => {
    const bouraVal = bouraFormulaBound(row.round, result.b, result.bMinus1);
    const isCertified = row.certified !== false; // undefined counts as certified (older API responses)

    let matchLabel;
    if (row.degree === bouraVal){
      matchLabel = '<span class="status-match">exact match</span>';
    } else if (row.degree < bouraVal){
      // "Tighter" is only a meaningful claim if the CP-SAT value itself was
      // proven optimal. An uncertified value below the formula's bound might
      // just mean the search ran out of time before reaching the formula's
      // value -- not that CP-SAT genuinely beats it. Label these differently
      // so this ambiguity is visible instead of silently overstated.
      matchLabel = isCertified
        ? '<span class="status-match">consistent (tighter)</span>'
        : '<span class="status-diverge">tighter, but UNCERTIFIED — may not be the true value</span>';
    } else {
      matchLabel = '<span class="status-diverge">⚠ exceeds formula — check model</span>';
    }

    const degreeCell = isCertified
      ? row.degree
      : `${row.degree} <span style="color:var(--text-dim); font-size:11px;">(uncertified)</span>`;

    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${row.round}</td>
      <td>${degreeCell}</td>
      <td>${bouraVal}</td>
      <td>${matchLabel}</td>
    `;
    body.appendChild(tr);
  });
}

function formatComplexity(exponent){
  if (exponent > 60){
    return `2^${exponent}`;
  }
  const value = Math.pow(2, exponent);
  if (value >= 1e6){
    return `2^${exponent} ≈ ${value.toExponential(2)}`;
  }
  return `2^${exponent} = ${value.toLocaleString()}`;
}

/* ---------- state parameter panel ---------- */

function updateStateReadout(){
  const w = parseInt(laneSizeSelect.value, 10);
  const b = widthFor(w);
  statB.textContent = b;
  statMax.textContent = b - 1;

  // w=4/8/16 only have verified numbers for rounds 1-4 (matched exactly
  // against the formula + zero-sum) -- CP-SAT gets slower per round as
  // w grows, so nudge toward a safe round count rather than let someone
  // request round 10 at w=16 and wonder why it's taking forever.
  const hint = document.getElementById('rounds-hint');
  if (hint){
    if (w >= 4){
      hint.textContent = `Verified for rounds 1\u20134 at this width \u2014 higher round counts may be slow or come back uncertified.`;
      hint.classList.remove('hidden');
    } else {
      hint.classList.add('hidden');
    }
  }
}

laneSizeSelect.addEventListener('change', updateStateReadout);

/* ---------- results table ---------- */

function renderTable(result){
  resultsBody.innerHTML = '';
  let firstSaturatedRound = null;

  result.rows.forEach(row => {
    const tr = document.createElement('tr');
    if (row.saturated){
      tr.classList.add('row-saturated');
      if (firstSaturatedRound === null) firstSaturatedRound = row.round;
    }

    const matchTag = row.degree === row.bound
      ? '<span class="status-match">= ref. bound</span>'
      : `<span class="status-diverge">−${row.bound - row.degree} tighter than ref.</span>`;

    const certifiedTag = row.certified === false
      ? ' <span class="status-diverge" title="Time limit hit before the solver could prove optimality -- this is a valid lower bound on the true upper bound, not a certified-tight one.">(uncertified)</span>'
      : '';

    const statusPill = row.saturated
      ? '<span class="status-pill status-saturated">Saturated</span>'
      : '<span class="status-pill status-growing">Growing</span>';

    tr.innerHTML = `
      <td>${row.round}</td>
      <td>${row.degree}${certifiedTag} <br><span style="color:var(--text-dim); font-size:11px;">${matchTag}</span></td>
      <td>${row.bound}</td>
      <td>${formatComplexity(row.complexity)}</td>
      <td>${statusPill}</td>
    `;
    resultsBody.appendChild(tr);
  });

  if (firstSaturatedRound !== null){
    alertBMinus1.textContent = result.bMinus1;
    saturationAlert.classList.remove('hidden');
  } else {
    saturationAlert.classList.add('hidden');
  }
}

/* ---------- zero-sum distinguisher (rounds 1-4 only) ---------- */

async function runZeroSumVerification(result){
  const body = document.getElementById('zerosum-body');
  if (!body) return; // panel not present on this page yet

  const roundsToTest = result.rows.slice(0, 4); // scope: rounds 1-4 only
  if (roundsToTest.length === 0) return;

  body.innerHTML = '<tr><td colspan="5" class="loading-cell">Running the real cipher over each test subspace...</td></tr>';

  try {
    const res = await fetch('/api/zerosum', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ w: result.w, degrees: roundsToTest.map(r => r.degree) })
    });

    if (!res.ok){
      const errorData = await res.json();
      throw new Error(errorData.error || 'Zero-sum check failed');
    }

    const data = await res.json();
    body.innerHTML = '';

    data.results.forEach(row => {
      const tr = document.createElement('tr');
      const verifiedLabel = row.verified
        ? '<span class="status-match">✓ zero-sum confirmed</span>'
        : '<span class="status-diverge">⚠ nonzero — check model</span>';
      const boundaryNote = row.xorSumAtD !== 0
        ? `<span style="color:var(--text-dim); font-size:11px;">dim ${row.degree}: nonzero (${row.xorSumAtD}) — confirms d+1 was needed</span>`
        : `<span style="color:var(--text-dim); font-size:11px;">dim ${row.degree}: also 0 here (not guaranteed either way at dim=d)</span>`;

      tr.innerHTML = `
        <td>${row.round}</td>
        <td>${row.degree}</td>
        <td>${row.dimTested} <br>${boundaryNote}</td>
        <td>${row.subspaceSize.toLocaleString()} pts</td>
        <td>${verifiedLabel}</td>
      `;
      body.appendChild(tr);
    });
  } catch (err) {
    console.error(err);
    body.innerHTML = `<tr><td colspan="5" style="color:var(--accent); text-align:center; padding:1.5rem;">Error: ${err.message}</td></tr>`;
  }
}

/* ---------- live asynchronous execution bridge ---------- */

async function runSolver() {
  const laneSize = parseInt(laneSizeSelect.value, 10);
  const rounds = parseInt(roundsInput.value, 10);
  const b = widthFor(laneSize);
  const bMinus1 = b - 1;

  // Visual UI feedback changes
  runBtn.disabled = true;
  runBtn.classList.add('running');
  resultsBody.innerHTML = '<tr><td colspan="5" class="loading-cell">MILP solver processing matrix branches. Please hold...</td></tr>';

  try {
    const res = await fetch('/api/degree', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ w: laneSize, rounds: rounds })
    });

    if (!res.ok) {
      const errorData = await res.json();
      throw new Error(errorData.error || 'Server processing error encountered');
    }

    const data = await res.json();

    // The API returns {round, degree, certified} per row -- it does NOT
    // include a reference bound, so that's computed here from the round
    // number alone (same Boura ceiling formula used everywhere else in
    // this file).
    const formattedRows = data.degrees.map(item => ({
      round: item.round,
      degree: item.degree,
      certified: item.certified,
      bound: referenceBound(item.round, bMinus1),
      complexity: item.degree + 1, // exponent d+1, mapped to 2^(d+1)
      saturated: item.degree >= bMinus1
    }));

    currentResults = {
      w: laneSize,
      b: b,
      bMinus1: bMinus1,
      rows: formattedRows
    };

    renderTable(currentResults);
    renderVerificationTable(currentResults);
    runZeroSumVerification(currentResults);

  } catch (err) {
    console.error(err);
    resultsBody.innerHTML = `<tr><td colspan="5" style="color:var(--accent); text-align:center; padding:2rem;">Error: ${err.message}</td></tr>`;
  } finally {
    runBtn.disabled = false;
    runBtn.classList.remove('running');
  }
}

// Attach event triggers and instantiate structural layers
runBtn.addEventListener('click', runSolver);
document.addEventListener('DOMContentLoaded', () => {
  updateStateReadout();
});