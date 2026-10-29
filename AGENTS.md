# Core Directives

- **Token Efficiency:** Be extremely concise. Use the absolute minimum number of tokens required. Provide only the bottom line; omit filler and non-essential explanations.
- **Simplicity:** Always deliver the simplest possible solution to a problem, regardless of the programming language or ecosystem.
- **No Guessing:** Base all solutions on factual code in the repository. Look at existing files to verify context. Never assume or guess.
- **No Intent Assumptions:** Implement *only* what is explicitly requested. If intent is unclear or ambiguous, stop completely and ask clarifying questions before proceeding.
- **No throwing the responsibility to the user:** NEVER supply more than 1 command to run, always wait for the user to tell you to continue or to supply an error.

# Global coding preferences

- Use ES modules for Node.js code. Prefer `import`/`export`; do not use `require`/`module.exports`.
- Use Yarn for install, run, build, and package management commands unless the project clearly cannot.
- When working with git, use `main` as the default branch. Never target `master`. If there's no default branch because it's a new repo, create `main` as the default.
- You never NEVER EVER, commit to main, if the user is on main, you MUST always create a branch and commit to the branch!
- You must NEVER commit and push changes even to a branch, unless the user tells you to do it
- ALWAYS!!!!!!!! run the entire suite of tests after every code change, ALWAYS!!!!!
- Never EVER touch anything out of the scope of fix, feature or refactor code
- If I ask you to use a skill, you can find them in the `D:\D backup\My Documents\projects\skills` directory. Always refer to that path to read skill instructions.
- Always increase the version number when writing new code
- When committing the code and pushing to GitHub, ALWAYS write a comprehensive PR note containing:
  - **Summary**: A high-level overview of the PR purpose.
  - **What Was Changed**: Bulleted breakdown of all new, extracted, or refactored components and logic.
  - **Dependencies & Version Control**: Bullet points detailing `versionCode`/`versionName` bumps, dependency changes, and Gradle/AGP updates.
  - **Testing & Verification**: Summary of unit/integration test suites added, test execution results, and build status.