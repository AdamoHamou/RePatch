package edu.unlv.cs.evol.integration;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/*
 * SPEC-11: the dataset selector maps repatch.dataSet to the resource
 * directory, defaulting to (and falling back on) sample_data.
 */
public class DataSetSelectorTest {

    @After
    public void clearProperty() {
        System.clearProperty("repatch.dataSet");
    }

    @Test
    public void defaultsToSampleData() {
        System.clearProperty("repatch.dataSet");
        assertEquals("/sample_data", RePatchIntegration.dataSetDir());
    }

    @Test
    public void completeSelectsCompleteData() {
        System.setProperty("repatch.dataSet", "complete");
        assertEquals("/complete_data", RePatchIntegration.dataSetDir());
    }

    @Test
    public void sampleSelectsSampleData() {
        System.setProperty("repatch.dataSet", "sample");
        assertEquals("/sample_data", RePatchIntegration.dataSetDir());
    }

    @Test
    public void unknownValueFallsBackToSample() {
        System.setProperty("repatch.dataSet", "everything");
        assertEquals("/sample_data", RePatchIntegration.dataSetDir());
    }
}
