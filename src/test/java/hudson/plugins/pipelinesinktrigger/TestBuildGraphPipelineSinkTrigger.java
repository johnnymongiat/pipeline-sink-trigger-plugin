package hudson.plugins.pipelinesinktrigger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.antlr.runtime.RecognitionException;
import org.junit.Test;

public class TestBuildGraphPipelineSinkTrigger {

    private static final String DEFAULT_SPEC = "* * * * *";
    private static final String DEFAULT_ROOT_PROJECT_NAME = "Mock-Root";
    private static final String DEFAULT_SINK_PROJECT_NAME = "Mock-Sink";

    private BuildGraphPipelineSinkTrigger newBuildGraphPipelineSinkTrigger(String excludedProjectNames) throws RecognitionException {
        return new BuildGraphPipelineSinkTrigger(DEFAULT_SPEC, DEFAULT_ROOT_PROJECT_NAME, DEFAULT_SINK_PROJECT_NAME, excludedProjectNames, false, false);
    }

    @Test
    public void onJobRenamedShouldCauseNoChangeWhenRenamedJobIsNotRootProjectNameOrSinkProjectOrPartOfTheExcludedProjectNames() throws RecognitionException {
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger("");
        final boolean changed = trigger.onJobRenamed("Job-1", "Job-1-1");
        assertFalse(changed);
        assertEquals(DEFAULT_ROOT_PROJECT_NAME, trigger.getRootProjectName());
        assertEquals(DEFAULT_SINK_PROJECT_NAME, trigger.getSinkProjectName());
        assertTrue(trigger.getExcludedProjectNames().isEmpty());
    }

    @Test
    public void onJobRenamedShouldCauseChangeWhenRenamedJobIsTheRootProjectName() throws RecognitionException {
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger("");
        final String newRootProjectName = DEFAULT_ROOT_PROJECT_NAME.concat("-1");
        final boolean changed = trigger.onJobRenamed(DEFAULT_ROOT_PROJECT_NAME, newRootProjectName);
        assertTrue(changed);
        assertEquals(newRootProjectName, trigger.getRootProjectName());
        assertEquals(DEFAULT_SINK_PROJECT_NAME, trigger.getSinkProjectName());
        assertTrue(trigger.getExcludedProjectNames().isEmpty());
    }

    @Test
    public void onJobRenamedShouldCauseChangeWhenRenamedJobIsTheSinkProjectName() throws RecognitionException {
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger("");
        final String newSinkProjectName = DEFAULT_SINK_PROJECT_NAME.concat("-1");
        final boolean changed = trigger.onJobRenamed(DEFAULT_SINK_PROJECT_NAME, newSinkProjectName);
        assertTrue(changed);
        assertEquals(DEFAULT_ROOT_PROJECT_NAME, trigger.getRootProjectName());
        assertEquals(newSinkProjectName, trigger.getSinkProjectName());
        assertTrue(trigger.getExcludedProjectNames().isEmpty());
    }

    @Test
    public void onJobRenamedShouldCauseChangeWhenRenamedJobIsPartOfTheExcludedProjectNames() throws RecognitionException {
        final String excludedProjectNames = "Job-1, Job-2, Job-3";
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger(excludedProjectNames);
        final boolean changed = trigger.onJobRenamed("Job-2", "Job-2-2");
        assertTrue(changed);
        assertEquals(DEFAULT_ROOT_PROJECT_NAME, trigger.getRootProjectName());
        assertEquals(DEFAULT_SINK_PROJECT_NAME, trigger.getSinkProjectName());
        assertEquals("Job-1,Job-2-2,Job-3", trigger.getExcludedProjectNames());
    }

    @Test
    public void onJobDeletedShouldCauseNoChangesWhenTriggerHasNoEmptyExcludedProjectNames() throws RecognitionException {
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger("");
        final boolean changed = trigger.onJobDeleted("Job-1");
        assertFalse(changed);
        assertTrue(trigger.getExcludedProjectNames().isEmpty());
    }

    @Test
    public void onJobDeletedShouldCauseChangeWhenDeletedJobMatchesAnExcludedProjectName() throws RecognitionException {
        final String excludedProjectNames = "Job-1, Job-2, Job-3";
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger(excludedProjectNames);
        boolean changed = trigger.onJobDeleted("Job-1");
        assertTrue(changed);
        assertEquals("Job-2,Job-3", trigger.getExcludedProjectNames());
        changed = trigger.onJobDeleted("Job-2");
        assertTrue(changed);
        assertEquals("Job-3", trigger.getExcludedProjectNames());
        changed = trigger.onJobDeleted("Job-3");
        assertTrue(changed);
        assertTrue(trigger.getExcludedProjectNames().isEmpty());
    }

    @Test
    public void onJobDeletedShouldCauseNoChangeWhenDeletedJobIsNotPartOfTheExcludedProjectNames() throws RecognitionException {
        final String excludedProjectNames = "Job-1, Job-2, Job-3";
        final BuildGraphPipelineSinkTrigger trigger = newBuildGraphPipelineSinkTrigger(excludedProjectNames);
        boolean changed = trigger.onJobDeleted("Job-4");
        assertFalse(changed);
        assertEquals(excludedProjectNames, trigger.getExcludedProjectNames());
    }

}