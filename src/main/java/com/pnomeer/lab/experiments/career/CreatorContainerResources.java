package com.pnomeer.lab.experiments.career;
import com.pnomeer.lab.runtime.DockerContainerEngine;
import java.util.Set;
public final class CreatorContainerResources extends CareerContainerResources {
    public CreatorContainerResources(String image,boolean redis){this(image,redis,DockerContainerEngine::command);}
    CreatorContainerResources(String image,boolean redis,Commands docker){
        super(image,redis,docker,redis?"creator-studio-redis":"creator-studio-mysql",
                redis?Set.of("career.creator-studio.session-rotation"):Set.of("career.creator-studio.mapping-integrity","career.creator-studio.post-commit"),
                "creator_lab","creator-db","LAB_CREATOR_");
    }
}
