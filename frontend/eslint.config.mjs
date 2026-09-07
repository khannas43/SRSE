// ESLint 9 flat config. The `lint` script has been dead since the ESLint 9
// upgrade — v9 stopped reading `.eslintrc.*` and this file did not exist, so
// `npm run lint` failed to start rather than reporting anything. `tsc` and
// `next build` were carrying the checks on their own.
//
// eslint-config-next 16 ships flat-config arrays directly, so they spread in
// with no FlatCompat shim: `core-web-vitals` is the Next rule set plus its
// stricter performance rules, `typescript` layers the TS parser and rules on
// top.
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

export default [
  {
    // Build output and generated declarations: not ours to lint, and .next in
    // particular is large enough to dominate the run.
    ignores: [".next/**", "out/**", "node_modules/**", "next-env.d.ts"],
  },
  ...nextCoreWebVitals,
  ...nextTypescript,
];
