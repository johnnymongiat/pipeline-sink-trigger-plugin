Trigger primarily used for periodically scheduling a build of a configured sink job if and only if the corresponding build pipeline graph is inactive, stable, and stale:

*   **Inactive:** None of the jobs that make up the nodes of the build pipeline graph are currently running, or scheduled in the build queue.
*   **Stable:** The last build (if present) for each job that make up the nodes of the build pipeline graph were successful. This rule can be relaxed by selecting the <b>Ignore non-successful upstream dependency builds</b> option (not recommended).
*   **Stale:** The last build of the sink job was prior to any of the build jobs that make up the nodes of the build pipeline graph.
  
All rules must comply in order for a build of the sink job to be scheduled.
