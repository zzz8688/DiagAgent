package io.github.zzz8688.diagagent.agent;

public class AgentDiagnosisResponse {

    private String agent;
    private String analysis;
    private double confidence;
    private String conclusion;

    public AgentDiagnosisResponse() {
    }

    public AgentDiagnosisResponse(String agent, String analysis, double confidence, String conclusion) {
        this.agent = agent;
        this.analysis = analysis;
        this.confidence = confidence;
        this.conclusion = conclusion;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }
}
