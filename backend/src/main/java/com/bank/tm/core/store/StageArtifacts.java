package com.bank.tm.core.store;

import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.Stage;

import java.util.EnumSet;
import java.util.Set;

/** Hangi adımın hangi ara çıktıyı ürettiği; akış bir adımdan yeniden başlatıldığında temizlik için kullanılır. */
public final class StageArtifacts {

    private StageArtifacts() {
    }

    public static Stage producer(ArtifactKind kind) {
        return switch (kind) {
            case MODEL_EXTRACTION_OUTPUT -> Stage.MODEL_EXTRACTION;
            case THREAT_CANDIDATES -> Stage.THREAT_CANDIDATES;
            case KNOWLEDGE_RETRIEVAL -> Stage.KNOWLEDGE_RETRIEVAL;
            case THREAT_GENERATION_OUTPUT -> Stage.THREAT_GENERATION;
            case THREAT_VALIDATION_REPORT -> Stage.THREAT_VALIDATION;
            case RISK_SUMMARY -> Stage.RISK_ASSESSMENT;
            case REPORT_SNAPSHOT -> Stage.REPORT;
        };
    }

    public static Set<ArtifactKind> producedFrom(Stage stage) {
        Set<ArtifactKind> out = EnumSet.noneOf(ArtifactKind.class);
        for (ArtifactKind k : ArtifactKind.values()) {
            if (producer(k).isAfterOrEqual(stage)) out.add(k);
        }
        return out;
    }
}
