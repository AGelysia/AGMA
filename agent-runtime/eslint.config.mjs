import eslint from "@eslint/js";
import prettier from "eslint-config-prettier";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: ["dist/**", "dist-standalone/**", "node_modules/**", "coverage/**"],
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.ts", "**/*.mjs"],
    languageOptions: {
      globals: globals.node,
    },
  },
  prettier,
);
