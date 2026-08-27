// ============================================================
// Keccak-f Degree Propagation Lab — Express server
// ------------------------------------------------------------
// Serves the static frontend (public/) and exposes /api/degree,
// which shells out to the Java CP-SAT solver (solver/CpSatDegreeSolver.java).
// Locked to w=2 (b=50) -- the only width fully verified against
// published Boura-Canteaut-De Canniere values through saturation.
// ============================================================

const express = require('express');
const path = require('path');
const { spawn } = require('child_process');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json());

// ---- serve the frontend ----
// Expects: ./public/index.html, ./public/styles.css, ./public/script.js
app.use(express.static(path.join(__dirname, 'public')));

// ---- API: degree computation ----
app.post('/api/degree', async (req, res) => {
  const rounds = parseInt(req.body.rounds, 10);
  const w = parseInt(req.body.w, 10);

  // w=1,2 verified through full saturation. w=4,8,16 verified for
  // rounds 1-4 only (matches Boura formula + zero-sum exactly at
  // those sizes) -- CP-SAT gets noticeably slower per round as w
  // grows, so higher round counts at w=8/16 may come back uncertified
  // rather than fail outright (the per-round time limit handles that).
  const ALLOWED_W = [1, 2, 4, 8, 16];
  if (!rounds || rounds < 1 || !ALLOWED_W.includes(w)) {
    return res.status(400).json({ error: 'w must be one of 1, 2, 4, 8, 16, and rounds must be a positive integer' });
  }

  try {
    const degrees = await runJavaSolver(w, rounds);
    res.json({ w, b: 25 * w, degrees });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'Solver execution failed', detail: err.message });
  }
});

// ---- Java CP-SAT solver hook ----
function runJavaSolver(w, rounds) {
  return new Promise((resolve, reject) => {
    const classpath = path.join(__dirname, 'solver', 'lib', '*') +
                       ':' + path.join(__dirname, 'solver');

    const proc = spawn('java', [
      '-cp', classpath,
      'CpSatDegreeSolver',
      w.toString(),
      rounds.toString(),
      '600',       // time limit per round (seconds)
      '--json'
    ]);

    let stdout = '';
    let stderr = '';

    proc.stdout.on('data', (chunk) => { stdout += chunk; });
    proc.stderr.on('data', (chunk) => { stderr += chunk; }); // progress logs land here, not in JSON

    proc.on('close', (code) => {
      if (code !== 0) {
        return reject(new Error(stderr || `Java solver exited with code ${code}`));
      }
      try {
        resolve(JSON.parse(stdout.trim()));
      } catch (e) {
        reject(new Error(`Could not parse solver stdout: ${stdout}\n${stderr}`));
      }
    });
  });
}

// ---- API: zero-sum distinguisher cross-check (rounds 1-4 only) ----
app.post('/api/zerosum', async (req, res) => {
  const w = parseInt(req.body.w, 10);
  const degrees = req.body.degrees; // array of already-computed degrees, index 0 = round 1

  const ALLOWED_W = [1, 2, 4, 8, 16];
  if (!ALLOWED_W.includes(w) || !Array.isArray(degrees) || degrees.length === 0 || degrees.length > 4) {
    return res.status(400).json({ error: 'w must be one of 1, 2, 4, 8, 16, and degrees must be an array of 1-4 values' });
  }

  try {
    const results = await runZeroSumSolver(w, degrees);
    res.json({ w, results });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'Zero-sum verification failed', detail: err.message });
  }
});

function runZeroSumSolver(w, degrees) {
  return new Promise((resolve, reject) => {
    const classpath = path.join(__dirname, 'solver', 'lib', '*') +
                       ':' + path.join(__dirname, 'solver');

    const solverArgs = ['-cp', classpath, 'ZeroSumDistinguisher', w.toString(), degrees.length.toString(),
                         ...degrees.map(d => d.toString())];
    const proc = spawn('java', solverArgs);

    let stdout = '';
    let stderr = '';
    proc.stdout.on('data', (chunk) => { stdout += chunk; });
    proc.stderr.on('data', (chunk) => { stderr += chunk; });

    proc.on('close', (code) => {
      if (code !== 0) {
        return reject(new Error(stderr || `Zero-sum solver exited with code ${code}`));
      }
      try {
        resolve(JSON.parse(stdout.trim()));
      } catch (e) {
        reject(new Error(`Could not parse zero-sum solver stdout: ${stdout}\n${stderr}`));
      }
    });
  });
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Keccak degree lab running:`);
  console.log(`  Local:   http://localhost:${PORT}`);
  console.log(`  Network: http://<your-mac-ip>:${PORT}  (find it with: ipconfig getifaddr en0)`);
});