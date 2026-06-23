package io.github.zzz8688.diagagent.dto;

public class DiagnosisConclusion {

    private double overallConfidence;
    private boolean manualInterventionNeeded;
    private String finalConclusion;

    public DiagnosisConclusion() {
    }

    public DiagnosisConclusion(double overallConfidence, boolean manualInterventionNeeded, String finalConclusion) {
        this.overallConfidence = overallConfidence;
        this.manualInterventionNeeded = manualInterventionNeeded;
        this.finalConclusion = finalConclusion;
    }

    public double getOverallConfidence() {
        return overallConfidence;
    }

    public void setOverallConfidence(double overallConfidence) {
        this.overallConfidence = overallConfidence;
    }

    public boolean isManualInterventionNeeded() {
        return manualInterventionNeeded;
    }

    public void setManualInterventionNeeded(boolean manualInterventionNeeded) {
        this.manualInterventionNeeded = manualInterventionNeeded;
    }

    public String getFinalConclusion() {
        return finalConclusion;
    }

    public void setFinalConclusion(String finalConclusion) {
        this.finalConclusion = finalConclusion;
    }
}
