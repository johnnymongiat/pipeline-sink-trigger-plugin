package hudson.plugins.pipelinesinktrigger;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.TopLevelItem;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Hudson;
import hudson.model.Project;
import hudson.model.Run;
import hudson.model.listeners.ItemListener;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.triggers.TimerTrigger;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.antlr.runtime.RecognitionException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.DirectedGraph;
import org.jgrapht.EdgeFactory;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.traverse.DepthFirstIterator;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

/**
 * {@link Trigger} primarily used for periodically scheduling a build of a configured sink job if and only if the corresponding build pipeline graph
 * is inactive, stable, and stale:
 * <ul>
 *   <li><b>Inactive:</b> None of the jobs that make up the nodes of the build pipeline graph are currently running, or scheduled in the build queue.</li>
 *   <li><b>Stable:</b> The last build (if present) for each job that make up the nodes of the build pipeline graph were successful. This rule can be relaxed
 *   by selecting the <b>Ignore non-successful upstream dependency builds</b> option (not recommended).</li>
 *   <li><b>Stale:</b> The last build of the sink job was prior to any of the build jobs that make up the nodes of the build pipeline graph.</li>
 * </ul>
 * 
 * <p>All rules must comply in order for a build of the sink job to be scheduled.</p>
 */
public class BuildGraphPipelineSinkTrigger extends Trigger<AbstractProject<?,?>> {

    private static final Logger LOGGER = Logger.getLogger(BuildGraphPipelineSinkTrigger.class.getName());

    private static final String MARKER = Strings.repeat("=", 100);

    private static final String CONTEXT_FINGERPRINT_FILE_NM = "pipeline-context.fingerprint";

    private String rootProjectName;
    private String sinkProjectName;
    private String excludedProjectNames;
    private final boolean ignoreNonSuccessfulUpstreamDependencyBuilds;
    private final boolean verbose;

    @DataBoundConstructor
    public BuildGraphPipelineSinkTrigger(String spec, String rootProjectName, String sinkProjectName, String excludedProjectNames,
            boolean ignoreNonSuccessfulUpstreamDependencyBuilds, boolean verbose) throws RecognitionException {
        super(spec);
        this.rootProjectName = rootProjectName;
        this.sinkProjectName = sinkProjectName;
        this.excludedProjectNames = excludedProjectNames;
        this.ignoreNonSuccessfulUpstreamDependencyBuilds = ignoreNonSuccessfulUpstreamDependencyBuilds;
        this.verbose = verbose;
    }

    public String getRootProjectName() {
        return rootProjectName;
    }

    public String getSinkProjectName() {
        return sinkProjectName;
    }

    public String getExcludedProjectNames() {
        return excludedProjectNames;
    }

    public boolean isIgnoreNonSuccessfulUpstreamDependencyBuilds() {
        return ignoreNonSuccessfulUpstreamDependencyBuilds;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public void run() {
        if (!Hudson.getInstance().isQuietingDown()) {
            LOGGER.log(Level.INFO, MARKER);
            LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_DecidingIfBuildShouldBeTriggered(this.job.getName(), sinkProjectName));
            try {
                final TopLevelItem rootProjectItem = Hudson.getInstance().getItem(rootProjectName);
                if (rootProjectItem == null) {
                    LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_RootProjectDoesNotExist(rootProjectName));
                    return;
                }
                final AbstractProject<?,?> rootProject = (AbstractProject<?,?>) rootProjectItem;
                if (rootProject.isDisabled()) {
                    LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_RootProjectDisabled(rootProjectName));
                    return;
                }

                final TopLevelItem sinkProjectItem = Hudson.getInstance().getItem(sinkProjectName);
                if (sinkProjectItem == null) {
                    LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_SinkProjectDoesNotExist(sinkProjectName));
                    return;
                }
                final AbstractProject<?,?> sinkProject = (AbstractProject<?,?>) sinkProjectItem;
                if (sinkProject.isDisabled()) {
                    LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_SinkProjectDisabled(sinkProjectName));
                    return;
                }

                final Set<String> exclusions = new HashSet<String>();
                for (String excludedPojectName : StringUtils.split(excludedProjectNames, ',')) {
                    final TopLevelItem excludedProjectItem = Hudson.getInstance().getItem(excludedPojectName.trim());
                    if (excludedProjectItem == null) {
                        LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_ExcludedProjectDoesNotExist(excludedPojectName.trim()));
                        return;
                    }
                    exclusions.add(excludedProjectItem.getName());
                }

                if (sinkProject.isBuilding()) {
                    LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_SkippingTriggerSinceSinkProjectIsBuilding(sinkProjectName));
                    return;
                }

                final DirectedGraph<AbstractProject<?,?>, String> graph = constructDirectedGraph(rootProject, exclusions);
                final CycleDetector<AbstractProject<?,?>, String> cycleDetector = new CycleDetector<AbstractProject<?,?>, String>(graph);
                if (cycleDetector.detectCycles()) {
                    LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_PipelineGraphContainsCycles(sinkProjectName));
                    return;
                }
                triggerBuildOfSinkIfNecessary(graph, rootProject, sinkProject);
            }
            catch (Exception e) {
                // Swallow the exception and log.
                LOGGER.log(Level.SEVERE, "Encountered an error during trigger execution.", e);
            }
            finally {
                LOGGER.log(Level.INFO, MARKER);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private DirectedGraph<AbstractProject<?,?>, String> constructDirectedGraph(AbstractProject<?,?> root, Set<String> exclusions) {
        final DirectedGraph<AbstractProject<?,?>, String> graph = new DefaultDirectedGraph<AbstractProject<?,?>, String>(
                new EdgeFactory<AbstractProject<?,?>, String>() {
                    public String createEdge(AbstractProject<?,?> source, AbstractProject<?,?> target) {
                        return String.format("'%s' --> '%s'", source.getName(), target.getName());
                    }
                });

        final StringBuilder prettyPrinter = new StringBuilder(); // Used for printing the pipeline graph as an adjacency list matrix.
        final Stack<AbstractProject<?,?>> stack = new Stack<AbstractProject<?,?>>();
        graph.addVertex(root);
        stack.push(root);
        while (!stack.isEmpty()) {
            final AbstractProject<?,?> p = stack.pop();
            prettyPrinter.append(p.getName());
            prettyPrinter.append(": {");
            int index = 0;
            final List<AbstractProject> children = p.getDownstreamProjects();
            for (AbstractProject<?,?> child : children) {
                if (!child.isDisabled() && !exclusions.contains(child.getName())) {
                    graph.addVertex(child);
                    graph.addEdge(p, child);
                    stack.push(child);
                    if (index > 0) {
                        prettyPrinter.append(", ");
                    }
                    prettyPrinter.append(child.getName());
                    index++;
                }
            }
            prettyPrinter.append(String.format("}%n"));
        }

        if (verbose) {
            LOGGER.log(Level.INFO, String.format("The build pipeline graph rooted at '%s':%n%s", root.getName(), prettyPrinter.toString()));
        }

        return graph;
    }

    private void triggerBuildOfSinkIfNecessary(DirectedGraph<AbstractProject<?,?>, String> graph, AbstractProject<?,?> root, AbstractProject<?,?> sink)
            throws IOException {
        final List<String> lastBuildFingerprints = Lists.newArrayList();
        final List<String> listOfNonSuccessfulUpstreamProjectBuilds = new ArrayList<String>();
        final DepthFirstIterator<AbstractProject<?,?>, String> itr = new DepthFirstIterator<AbstractProject<?,?>, String>(graph, root);
        while (itr.hasNext()) {
            final AbstractProject<?,?> project = itr.next();
            if (project.isBuilding() || project.isInQueue()) {
                LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_PipelineActive(sinkProjectName));
                return;
            }

            final Run<?,?> lastBuild = project.getLastBuild();
            if (lastBuild != null && lastBuild.getResult().isWorseThan(Result.UNSTABLE)) {
                listOfNonSuccessfulUpstreamProjectBuilds.add(project.getName());
            }

            // Capture a contextual "fingerprint" (note: the fingerprint is composed of the project's full name, and last build id (if present), so
            // if a project is renamed during its existence, then it can impact the detection of changes between consecutive polls of this trigger).
            lastBuildFingerprints.add(String.format("%s(%s)", project.getFullName(), (lastBuild == null ? "" : lastBuild.getId())));
        }

        if (!listOfNonSuccessfulUpstreamProjectBuilds.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < listOfNonSuccessfulUpstreamProjectBuilds.size(); i++) {
                sb.append(listOfNonSuccessfulUpstreamProjectBuilds.get(i));
                if (i < listOfNonSuccessfulUpstreamProjectBuilds.size() - 1) {
                    sb.append(", ");
                }
            }
            if (!ignoreNonSuccessfulUpstreamDependencyBuilds) {
                LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_DetectedNonSuccessfulUpstreamDependencyBuilds(sinkProjectName, sb.toString()));
                return;
            }
            LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_IgnoringNonSuccessfulUpstreamDependencyBuilds(sb.toString()));
        }

        // Determine if a build of the sink has already been triggered due to upstream dependency build changes.
        final String currentFingerprint = calculateFingerprint(lastBuildFingerprints);
        final String prevFingerprint = readFingerprint();

        // Prevent a build of the sink project from being triggered upon initial setup of the trigger job itself (i.e. the previous fingerprint
        // information will not exist when the first poll has been issued). Persist the initial fingerprint, and from this point onwards, any
        // changes in the build pipeline graph will be detected.
        if (prevFingerprint == null) {
            persistFingerprint(currentFingerprint);
            LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_NoPreviousFingerprintToCompareAgainst(sinkProjectName));
            return;
        }
        if (currentFingerprint.equals(prevFingerprint)) {
            LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_NoUpstreamDependencyBuildChanges(sinkProjectName));
            return;
        }

        // A change has been detected, so update the context, and schedule a build of the sink project.
        persistFingerprint(currentFingerprint);
        LOGGER.log(Level.INFO, Messages.BuildGraphPipelineSinkTrigger_DetectedUpstreamDependencyBuildChanges(sinkProjectName));
        final boolean isBuildScheduled = sink.scheduleBuild(new BuildGraphPipelineSinkTriggerCause());
        LOGGER.log(Level.INFO, isBuildScheduled ? hudson.tasks.Messages.BuildTrigger_Triggering(sinkProjectName) :
            hudson.tasks.Messages.BuildTrigger_InQueue(sinkProjectName));
    }

    private String calculateFingerprint(List<String> lastBuildFingerprints) {
        Collections.sort(lastBuildFingerprints);
        return DigestUtils.shaHex(Joiner.on(';').join(lastBuildFingerprints));
    }

    private String readFingerprint() throws IOException {
        final File file = new File(this.job.getRootDir(), CONTEXT_FINGERPRINT_FILE_NM);
        return file.exists() ? Files.readFirstLine(file, Charsets.UTF_8) : null;
    }

    private void persistFingerprint(String fingerprint) throws IOException {
        Files.write(fingerprint, new File(this.job.getRootDir(), CONTEXT_FINGERPRINT_FILE_NM), Charsets.UTF_8);
    }

    @Extension
    public static final class BuildGraphPipelineSinkTriggerDescriptor extends TriggerDescriptor {

        private final TimerTrigger.DescriptorImpl timerTriggerDescriptorDelegate = new TimerTrigger.DescriptorImpl();

        public FormValidation doCheckRootProjectName(@QueryParameter String rootProjectName) throws IOException, ServletException {
            return validateProjectParemeter(rootProjectName);
        }

        public FormValidation doCheckSinkProjectName(@QueryParameter String sinkProjectName) throws IOException, ServletException {
            return validateProjectParemeter(sinkProjectName);
        }

        public FormValidation doCheckExcludedProjectNames(@QueryParameter String excludedProjectNames) throws IOException, ServletException {
            if (excludedProjectNames.trim().length() > 0) {
                for (String excludedPojectName : StringUtils.split(excludedProjectNames, ',')) {
                    final FormValidation val = validateProjectParemeter(excludedPojectName.trim());
                    if (FormValidation.Kind.ERROR.equals(val.getKind())) {
                        return val;
                    }
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSpec(@QueryParameter String spec) throws IOException, ServletException {
            return timerTriggerDescriptorDelegate.doCheckSpec(spec);
        }

        private FormValidation validateProjectParemeter(String projectName) throws IOException, ServletException {
            if (projectName.trim().length() == 0) {
                return FormValidation.error(Messages.BuildGraphPipelineSinkTrigger_NoProjectSpecified());
            }
            final Item item = Hudson.getInstance().getItem(projectName);
            if (item == null) {
                return FormValidation.error(Messages.BuildGraphPipelineSinkTrigger_NoSuchProject(projectName));
            }
            if (!AbstractProject.class.isAssignableFrom(item.getClass())) {
                return FormValidation.error(hudson.tasks.Messages.BuildTrigger_NotBuildable(projectName));
            }
            return FormValidation.ok();
        }

        @Override
        public String getDisplayName() {
            return Messages.BuildGraphPipelineSinkTrigger_DisplayName();
        }

        @Override
        public boolean isApplicable(Item item) {
            return item instanceof TopLevelItem;
        }

    }

    private static final class BuildGraphPipelineSinkTriggerCause extends Cause {

        public BuildGraphPipelineSinkTriggerCause() {
            super();
        }

        @Override
        public String getShortDescription() {
            return Messages.BuildGraphPipelineSinkTrigger_CauseShortDescription();
        }

    }

    /**
     * Called from {@link BuildGraphPipelineSinkTrigger.DefaultItemListener} when a job is renamed.
     *
     * @return {@code true} if this {@link BuildGraphPipelineSinkTrigger} is changed and needs to be saved, otherwise {@code false}.
     */
    public boolean onJobRenamed(String oldName, String newName) {
        final boolean excludedProjectNamesChanged = handleRenameForExcludedProjectNames(oldName, newName);
        final boolean rootProjectNameChanged = handleRenameForRootProjectName(oldName, newName);
        final boolean sinkProjectNameChanged = handleRenameForSinkProjectName(oldName, newName);
        return (excludedProjectNamesChanged || rootProjectNameChanged || sinkProjectNameChanged);
    }

    private boolean handleRenameForExcludedProjectNames(String oldName, String newName) {
        boolean changed = false;
        if (StringUtils.stripToEmpty(excludedProjectNames).length() > 0) {
            final StringBuilder sb = new StringBuilder();
            final String[] exclusions = StringUtils.split(excludedProjectNames, ',');
            for (int i = 0; i < exclusions.length; i++) {
                if (exclusions[i].trim().equals(oldName)) {
                    sb.append(newName);
                    changed = true;
                }
                else {
                    sb.append(exclusions[i].trim());
                }
                if (i < exclusions.length - 1) {
                    sb.append(',');
                }
            }
            excludedProjectNames = sb.toString();
        }
        return changed;
    }

    private boolean handleRenameForRootProjectName(String oldName, String newName) {
        if (rootProjectName.equals(oldName)) {
            rootProjectName = newName;
            return true;
        }
        return false;
    }

    private boolean handleRenameForSinkProjectName(String oldName, String newName) {
        if (sinkProjectName.equals(oldName)) {
            sinkProjectName = newName;
            return true;
        }
        return false;
    }

    /**
     * Called from {@link BuildGraphPipelineSinkTrigger.DefaultItemListener} when a job is deleted. Any changes due to the
     * deletion of the specified job are only limited to the excluded project names field.
     *
     * @return {@code true} if this {@link BuildGraphPipelineSinkTrigger} is changed and needs to be saved, otherwise {@code false}.
     */
    public boolean onJobDeleted(String nameOfDeletedJob) {
        boolean changed = false;
        if (StringUtils.stripToEmpty(excludedProjectNames).length() > 0) {
            final Set<String> setOfExclusions = Sets.newLinkedHashSet();
            setOfExclusions.addAll(Arrays.asList(StringUtils.split(excludedProjectNames, ',')));
            final Iterator<String> itr = setOfExclusions.iterator();
            while (itr.hasNext()) {
                final String exclusion = itr.next();
                if (exclusion.trim().equals(nameOfDeletedJob)) {
                    itr.remove();
                    changed = true;
                }
            }
            if (changed) {
                final StringBuilder sb = new StringBuilder();
                int i = 0;
                for (String exclusion : setOfExclusions) {
                    sb.append(exclusion.trim());
                    if (i < setOfExclusions.size() - 1) {
                        sb.append(',');
                    }
                    i++;
                }
                excludedProjectNames = sb.toString();
            }
        }
        return changed;
    }

    @Extension
    public static final class DefaultItemListener extends ItemListener {

        @Override
        public void onDeleted(Item item) {
            for (Project<?, ?> p : Hudson.getInstance().getProjects()) {
                final BuildGraphPipelineSinkTrigger trigger = p.getTrigger(BuildGraphPipelineSinkTrigger.class);
                if (trigger != null) {
                    if (trigger.onJobDeleted(item.getName())) {
                        try {
                            p.save();
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, String.format("Failed to persist project setting during deletion of %s", item.getName()), e);
                        }
                    }
                }
            }
        }

        @Override
        public void onRenamed(Item item, String oldName, String newName) {
            for (Project<?, ?> p : Hudson.getInstance().getProjects()) {
                final BuildGraphPipelineSinkTrigger trigger = p.getTrigger(BuildGraphPipelineSinkTrigger.class);
                if (trigger != null) {
                    if (trigger.onJobRenamed(oldName, newName)) {
                        try {
                            p.save();
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, String.format("Failed to persist project setting during rename from %s to %s", oldName, newName), e);
                        }
                    }
                }
            }
        }
    }

}
