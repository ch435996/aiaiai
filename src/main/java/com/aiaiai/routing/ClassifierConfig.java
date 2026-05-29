package com.aiaiai.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Static classification rules loaded from application.yml.
 * Business data (method names, paper titles) lives in KnownPapersRegistry, not here.
 */
@Component
@ConfigurationProperties(prefix = "aiaiai.classifier")
public class ClassifierConfig {

    private List<String> boundaryKeywords = List.of();
    private List<String> mixedIntentTriggers = List.of();
    private List<String> broadKeywords = List.of();
    private List<String> detailSeekingKeywords = List.of();
    private List<String> comparisonKeywords = List.of();
    private List<String> vagueRefTriggers = List.of();
    private Map<String, String> typoCorrections = Map.of();
    private List<String> oralHighPrecision = List.of();
    private List<String> oralSoftSignals = List.of();

    public List<String> getBoundaryKeywords() { return boundaryKeywords; }
    public void setBoundaryKeywords(List<String> boundaryKeywords) { this.boundaryKeywords = boundaryKeywords; }

    public List<String> getMixedIntentTriggers() { return mixedIntentTriggers; }
    public void setMixedIntentTriggers(List<String> mixedIntentTriggers) { this.mixedIntentTriggers = mixedIntentTriggers; }

    public List<String> getBroadKeywords() { return broadKeywords; }
    public void setBroadKeywords(List<String> broadKeywords) { this.broadKeywords = broadKeywords; }

    public List<String> getDetailSeekingKeywords() { return detailSeekingKeywords; }
    public void setDetailSeekingKeywords(List<String> detailSeekingKeywords) { this.detailSeekingKeywords = detailSeekingKeywords; }

    public List<String> getComparisonKeywords() { return comparisonKeywords; }
    public void setComparisonKeywords(List<String> comparisonKeywords) { this.comparisonKeywords = comparisonKeywords; }

    public List<String> getVagueRefTriggers() { return vagueRefTriggers; }
    public void setVagueRefTriggers(List<String> vagueRefTriggers) { this.vagueRefTriggers = vagueRefTriggers; }

    public Map<String, String> getTypoCorrections() { return typoCorrections; }
    public void setTypoCorrections(Map<String, String> typoCorrections) { this.typoCorrections = typoCorrections; }

    public List<String> getOralHighPrecision() { return oralHighPrecision; }
    public void setOralHighPrecision(List<String> oralHighPrecision) { this.oralHighPrecision = oralHighPrecision; }

    public List<String> getOralSoftSignals() { return oralSoftSignals; }
    public void setOralSoftSignals(List<String> oralSoftSignals) { this.oralSoftSignals = oralSoftSignals; }
}
