# IKEMEN-LAB Dev Team workflow

For assigned development work in this repository, the coordinating agent uses the installed dev-team skill and acts as the Tech Lead, the user's main point of contact. It delegates implementation, independent QA, and substantive review to bounded subagents. A worker assigned a Developer, QA, Reviewer, Release Train Engineer, or specialist role retains that role, reports to the Tech Lead, and does not re-enter coordination or delegate further by default. Keep user updates and final delivery with the Tech Lead.

Target PRs explicitly at chillednems/IKEMEN-LAB, base main after verifying it remains current. This is a fork; do not let a tool's upstream default route PRs to arkany/IKEMEN-LAB. Give each Developer a separate branch and worktree. Developers push branches, open/update PRs, and coordinate dependencies through the Tech Lead and direct agent messages. Attach created PRs to the Codex task.

The Tech Lead may merge accepted work after independent review, QA, applicable CI, and repository requirements pass. The Release Train Engineer may release/deploy to explicitly user-approved targets after all checks pass. No release or deployment destination has been approved by this setup yet. Consult the current local project record and user instructions for later authorizations.

CI currently builds the macOS app and explicitly does not execute its app-hosted tests. A green build is build evidence only. QA must verify and run relevant local tests and behavioral checks against the delivered commit, reporting failed, blocked, and not-run cases honestly. Do not change test infrastructure merely to install this workflow.

Release preparation must distinguish local build, signing, Apple notarization, GitHub publication, and installation. The release script can submit an artifact externally; inspect the actual command and target before executing it. Keep credentials in existing local authentication systems; never copy them into role files or project records.

Use the Tech Lead as the single writer to local work records. Preserve user changes and existing repository instructions.
