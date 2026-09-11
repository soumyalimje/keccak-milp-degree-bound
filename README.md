# Keccak MILP Degree Bound

A tool to estimate degree bounds for the Keccak permutation (core of SHA-3) using Mixed Integer Linear Programming (MILP).

## 📄 Report

### Summary

This project investigates **degree bound estimation for the Keccak permutation** 
(the core building block of SHA-3) using **Mixed Integer Linear Programming (MILP)**. 
Keccak's algebraic degree over successive rounds directly impacts the security margin 
against higher-order differential and cube attacks — tighter degree bounds give 
stronger, more precise security guarantees.

The tool models Keccak's round function (θ, ρ, π, χ, ι steps) as a system of linear 
and boolean constraints, then uses an MILP solver to search for the **tightest 
provable upper bound** on the algebraic degree after *n* rounds. This automated 
approach improves on manual/heuristic degree estimation by exhaustively exploring 
propagation paths within the modeled constraint system.

**Key components:**
- `solver/` — Java-based MILP model of the Keccak round function and constraint solving logic
- `app.js` / `public/` — Web frontend for configuring parameters (rounds, state size) and visualizing results
- Outputs: computed degree bounds per round, compared against theoretical/published bounds where available

**Findings:** *(fill in your actual results — e.g. "Degree bounds matched/improved upon 
existing bounds by X for Y rounds of Keccak-f[1600]")*

**Context:** Developed during a cryptography internship at IIT Bhilai, focused on 
automated cryptanalysis tooling for symmetric primitives.

---
*For full methodology, MILP formulation details, and complete results, see 
[`docs/report.pdf`](./docs/report.pdf).*
