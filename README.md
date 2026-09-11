# Keccak MILP Degree Bound

A tool to estimate degree bounds for the Keccak permutation (core of SHA-3) using Mixed Integer Linear Programming (MILP).

## 📄 Report

### Summary

This project was completed during a cryptanalysis internship at **IIT Bhilai**, under 
the guidance of **Dr. Dhiman Saha**. The task was to design a **Mixed-Integer Linear 
Programming (MILP)** model to compute upper bounds on the **algebraic degree** of the 
Keccak-f permutation (the core of SHA-3) for reduced state widths, and to verify the 
results independently rather than trust a single method.

The round function (θ, ρ, π, χ, ι) was modeled as a **division-property trail search**, 
expressed as a Boolean MILP model, and solved with **Google OR-Tools' CP-SAT** engine. 
Since χ is the only nonlinear step in each round, it's the sole place degree can grow — 
the model tracks that growth round by round.

**Key components:**
- `solver/` — Java-based MILP model of the Keccak round function and constraint solving logic
- `app.js` / `public/` — Web frontend for configuring parameters (rounds, state size) and visualizing results

**Verification:** every computed bound was cross-checked against three independent 
sources — the closed-form Boura–Canteaut–De Cannière bound (FSE 2011), brute-force 
exact ANF computation for early rounds, and an empirical zero-sum distinguisher run 
against the real cipher. All three agreed exactly at every round certified.

**Results:** degree bounds were computed for state widths of 25, 50, 100, 200, and 
400 bits. Rounds 1–4 gave identical bounds (2, 4, 8, 16) regardless of state width — 
a direct confirmation of Keccak's "Matryoshka" scaling property. Saturation (degree 
reaching its maximum possible value, b−1) was observed for b=25 by round 6.

The report also documents three implementation bugs found during debugging (including 
one that initially produced a mathematically impossible round-1 result) and how each 
was diagnosed and fixed.

**Context:** Developed during a cryptanalysis internship at IIT Bhilai, focused on 
automated cryptanalysis tooling for symmetric primitives.

---
📥 **Full report:** [`docs/report.docx`](./docs/report.docx)
